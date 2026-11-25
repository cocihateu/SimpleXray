package com.simplexray.re.service

import android.os.SystemClock
import com.simplexray.re.common.config.BUILTIN_GROUP_TARGET_TAGS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class FallbackRelaySpec(
    val groupTag: String,
    val port: Int,
    val username: String,
    val password: String,
)

internal data class FailoverPlan(
    val primaryLeaf: String?,
    val candidates: List<String>,
    val failoverTimeoutMs: Int,
    val watchResponse: Boolean = true,
    val generation: Long = 0L,
    val stallTimeoutMs: Int = failoverTimeoutMs,
    val groupPaths: Map<String, Set<String>> = emptyMap(),
    val stallTimeoutsMs: Map<String, Int> = emptyMap(),
    val owner: Any? = null,
)

/** Routes initial TCP/UDP exchanges through ordered group candidates. */
internal class FallbackRelayServer(
    private val specs: List<FallbackRelaySpec>,
    private val nodeEntryPort: Int,
    private val nodeUsers: Map<String, String>,
    private val nodePassword: String,
    private val nodeUotVersions: Map<String, Int>,
    private val resolvePlan: (String) -> FailoverPlan,
    private val onCandidateFailed: suspend (String, String, Any?, String, Boolean) -> Unit,
    private val onCandidateConnected: (String, String, Any?) -> Unit,
    private val onStalledUpload: suspend (String, String, Any?, String, Boolean, (() -> Unit) -> Boolean) -> Boolean,
    private val awaitReady: suspend () -> Unit = {},
    private val nowElapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
) : Closeable {
    private val listeners = CopyOnWriteArrayList<ServerSocket>()
    private val jobs = CopyOnWriteArrayList<Job>()
    private val resources = ConcurrentHashMap.newKeySet<Closeable>()
    private val entries = ConcurrentHashMap.newKeySet<RelayConnectionEntry>()
    @Volatile private var cleanupScope: CoroutineScope? = null
    private val relayIo = Dispatchers.IO.limitedParallelism(1024)
    @Volatile private var closed = false

    fun start(scope: CoroutineScope) {
        check(closed == false && listeners.isEmpty())
        try {
            val cleanupJob = Job(scope.coroutineContext[Job])
            jobs += cleanupJob
            cleanupScope = CoroutineScope(cleanupJob + Dispatchers.Default)
            specs.groupBy { it.port }.forEach { (port, targets) ->
                val server = ServerSocket()
                listeners += server
                server.reuseAddress = true
                server.bind(InetSocketAddress("127.0.0.1", port))
                jobs += scope.launch(relayIo) {
                    while (isActive && closed == false) {
                        val client = try { server.accept() } catch (_: IOException) { break }
                        track(client)
                        launch {
                            try {
                                handleClient(targets, client)
                            } catch (_: IOException) {
                                // Malformed or interrupted sessions do not stop the listener.
                            } finally {
                                release(client)
                            }
                        }
                    }
                }
            }
            // Blocking socket I/O does not observe coroutine cancellation on its own.
            jobs += scope.launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { close() }
            }
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    override fun close() {
        closed = true
        cleanupScope = null
        listeners.forEach { runCatching { it.close() } }
        listeners.clear()
        resources.toList().forEach(::release)
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    fun closeStalledConnections(groupTag: String, leaf: String) {
        val samples = entries.mapNotNull { entry -> entry.sample(groupTag, leaf)?.let { entry to it } }
        if (samples.isEmpty()) return
        cleanupScope?.launch {
            delay(FallbackPolicy.CLOSE_SAMPLE_DELAY_MS)
            samples.forEach { (entry, bytes) -> entry.closeIfStalled(groupTag, leaf, bytes) }
        }
    }

    private fun register(entry: RelayConnectionEntry) {
        entries += entry
        RelayConnectionRegistry.register(entry)
    }

    private fun unregister(entry: RelayConnectionEntry) {
        entries -= entry
        RelayConnectionRegistry.unregister(entry.id)
    }

    private fun release(resource: Closeable) {
        runCatching { resource.close() }
        resources.remove(resource)
    }

    private fun track(resource: Closeable) {
        resources += resource
        if (closed) release(resource)
    }

    private suspend fun handleClient(targets: List<FallbackRelaySpec>, client: Socket) {
        client.tcpNoDelay = true
        client.soTimeout = HANDSHAKE_TIMEOUT_MS
        val input = DataInputStream(client.getInputStream())
        val output = client.getOutputStream()
        val (spec, ruleId) = authenticateServer(input, output, targets) ?: return
        val version = input.readUnsignedByte()
        val command = input.readUnsignedByte()
        val reserved = input.readUnsignedByte()
        if (version != 5 || reserved != 0) {
            reply(output, 1)
            throw IOException("SOCKS request header")
        }
        val address = try { readAddress(input) } catch (e: IOException) {
            reply(output, 8)
            throw e
        }
        when (command) {
            1 -> if (address.port == 0) reply(output, 1) else handleTcp(spec, client, address, ruleId)
            3 -> handleUdp(spec, client, address, ruleId)
            else -> reply(output, 7)
        }
    }

    private suspend fun nextPlan(group: String, attempted: Set<String>, ruleId: String): FailoverPlan {
        val plan = resolvePlan(group)
        if (plan.remaining(attempted).isNotEmpty() || ruleId == "dialer") return plan
        if (withTimeoutOrNull(plan.failoverTimeoutMs.toLong().coerceAtLeast(1L)) { awaitReady(); true } != true) return plan
        return resolvePlan(group)
    }

    private fun FailoverPlan.remaining(attempted: Set<String>) = candidates.distinct()
        .take(FallbackPolicy.MAX_DIAL_ATTEMPTS).filter { it !in attempted }
        .take(FallbackPolicy.MAX_DIAL_ATTEMPTS - attempted.size)

    private suspend fun handleTcp(spec: FallbackRelaySpec, client: Socket, target: SocksAddress, ruleId: String) = coroutineScope {
        client.soTimeout = 0
        val currentUpstream = AtomicReference<Socket?>(null)
        val entry = RelayConnectionEntry("tcp", client.inetAddress.hostAddress.orEmpty(), client.port, spec.groupTag) {
            currentUpstream.get()?.let(::release)
            runCatching { client.close() }
        }
        target.describe(entry)
        entry.ruleId = ruleId
        register(entry)
        val flight = FirstFlight()
        val localEnded = AtomicBoolean(false)
        val reader = launch(relayIo) {
            val buffer = ByteArray(32 * 1024)
            try {
                while (isActive) {
                    val count = client.getInputStream().read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    val chunk = buffer.copyOf(count)
                    entry.uploadBytes.addAndGet(count.toLong())
                    while (isActive && flight.pending.offer(chunk, 100, TimeUnit.MILLISECONDS) == false) {
                        // Bounded buffering follows upstream consumption.
                    }
                }
            } catch (_: IOException) {
                entry.close()
            } finally {
                // EOF is a half-close; its request may still need a response or a retry.
                localEnded.set(true)
            }
        }
        var accepted = false
        try {
            val attempted = mutableSetOf<String>()
            while (isActive && closed == false && entry.closed.get() == false && flight.canReplay) {
                val plan = nextPlan(spec.groupTag, attempted, ruleId)
                val leaf = plan.remaining(attempted).firstOrNull() ?: break
                attempted += leaf
                val socket = Socket()
                track(socket)
                currentUpstream.set(socket)
                if (entry.closed.get()) release(socket)
                val timeout = plan.failoverTimeoutMs.coerceAtLeast(1)
                val deadline = deadlineJob(socket, nowElapsedRealtimeMs() + timeout)
                var setupComplete = false
                var sender: Job? = null
                val retryRequested = AtomicBoolean(false)
                val ioFailure = AtomicReference<IOException?>()
                val responseWait = UploadResponseWait(
                    scope = this@coroutineScope,
                    timeoutMs = plan.stallTimeoutsMs[leaf] ?: plan.stallTimeoutMs,
                    enabled = plan.watchResponse && leaf !in BUILTIN_GROUP_TARGET_TAGS,
                    isOpen = { closed == false && entry.closed.get() == false && socket.isClosed == false },
                    retry = {
                        retryRequested.set(true)
                        socket.close()
                    },
                ) { applyIfWaiting ->
                    onStalledUpload(spec.groupTag, leaf, plan.owner, entry.targetHost, true, applyIfWaiting)
                }
                try {
                    connectNode(socket, leaf, 1, target, timeout)
                    deadline.cancelAndJoin()
                    if (entry.closed.get() || closed || isActive == false) break
                    if (socket.isClosed) throw IOException("Local SOCKS setup timed out")
                    socket.soTimeout = 0
                    setupComplete = true
                    // SOCKS acceptance selects the local route; payload I/O supplies node evidence.
                    entry.select(leaf, plan.groupPaths[leaf].orEmpty())
                    if (accepted == false) {
                        try { reply(client.getOutputStream(), 0) } catch (e: IOException) {
                            entry.close()
                            throw e
                        }
                        accepted = true
                    }
                    // Snapshot before reading server-first bytes can clear retained data.
                    val replay = flight.replay().toMutableList()
                    sender = launch(relayIo) {
                        try {
                            val output = socket.getOutputStream()
                            fun send(chunk: ByteArray) {
                                output.write(chunk)
                                if (chunk.isNotEmpty() && flight.established == false) responseWait.wrote()
                            }
                            replay.forEach(::send)
                            replay.clear()
                            while (isActive) {
                                val chunk = flight.pending.poll(100, TimeUnit.MILLISECONDS)
                                if (chunk == null) {
                                    if (localEnded.get()) {
                                        socket.shutdownOutput()
                                        break
                                    }
                                    continue
                                }
                                // No suspension between dequeue and retention. Teardown joins this
                                // sender before another attempt snapshots the complete initial flight.
                                flight.retain(chunk)
                                send(chunk)
                            }
                        } catch (e: IOException) {
                            if (isActive && socket.isClosed == false) ioFailure.compareAndSet(null, e)
                            runCatching { socket.close() }
                        }
                    }
                    val buffer = ByteArray(32 * 1024)
                    while (isActive && entry.closed.get() == false) {
                        val count = socket.getInputStream().read(buffer)
                        if (count < 0) {
                            if (flight.established == false) throw EOFException("Upstream closed before response")
                            break
                        }
                        if (count == 0) continue
                        // Pin on observation, BEFORE writing to the client (which may partly fail).
                        if (flight.established == false) {
                            if (responseWait.responded() == false) throw IOException("Initial exchange selected for retry")
                            flight.establish()
                            onCandidateConnected(spec.groupTag, leaf, plan.owner)
                        }
                        entry.recordDownload(count)
                        try { client.getOutputStream().write(buffer, 0, count) } catch (e: IOException) {
                            entry.close()
                            throw e
                        }
                    }
                    break
                } catch (e: IOException) {
                    if (setupComplete && socket.isClosed == false) ioFailure.compareAndSet(null, e)
                } finally {
                    deadline.cancel()
                    responseWait.finish()
                    release(socket)
                    withContext(NonCancellable) { sender?.cancelAndJoin() }
                    currentUpstream.compareAndSet(socket, null)
                    entry.select("", emptySet())
                }
                if (isActive && flight.established == false && retryRequested.get() == false &&
                    closed == false && entry.closed.get() == false
                ) ioFailure.get()?.let { error ->
                    onCandidateFailed(spec.groupTag, leaf, plan.owner, entry.targetHost, FallbackPolicy.isStrongFailure(error))
                }
            }
            if (accepted == false && entry.closed.get() == false) reply(client.getOutputStream(), 1)
        } finally {
            entry.close()
            unregister(entry)
            withContext(NonCancellable) { reader.cancelAndJoin() }
        }
    }

    private suspend fun handleUdp(spec: FallbackRelaySpec, client: Socket, requested: SocksAddress, ruleId: String) = coroutineScope {
        val local = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        track(local)
        val currentControl = AtomicReference<Socket?>(null)
        val currentUdp = AtomicReference<RelayDatagramTransport?>(null)
        val endpoint = AtomicReference<InetSocketAddress?>(
            if (requested.port == 0) null else InetSocketAddress(client.inetAddress, requested.port),
        )
        val entry = RelayConnectionEntry("udp", client.inetAddress.hostAddress.orEmpty(), client.port, spec.groupTag) {
            runCatching { local.close() }
            runCatching { currentUdp.get()?.close() }
            runCatching { currentControl.get()?.close() }
            runCatching { client.close() }
        }
        register(entry)
        entry.ruleId = ruleId
        val flight = FirstFlight()
        client.soTimeout = 0
        try {
            reply(client.getOutputStream(), 0, local.localPort)
        } catch (e: IOException) {
            entry.close()
            release(local)
            unregister(entry)
            throw e
        }
        val controlReader = launch(relayIo) {
            try { while (client.getInputStream().read() >= 0) { /* Association lifetime. */ } }
            catch (_: IOException) { /* Control socket ended. */ }
            finally { entry.close() }
        }
        val reader = launch(relayIo) {
            val buffer = ByteArray(65_535)
            val packet = DatagramPacket(buffer, buffer.size)
            var targetDescribed = false
            try {
                while (isActive) {
                    packet.length = buffer.size
                    local.receive(packet)
                    if (packet.address.isLoopbackAddress == false || packet.address != client.inetAddress) continue
                    val source = InetSocketAddress(packet.address, packet.port)
                    if (endpoint.get()?.let { it != source } == true) continue
                    val data = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                    try { parseSocksDatagram(data) } catch (_: IOException) { continue }
                    endpoint.compareAndSet(null, source)
                    if (endpoint.get() != source) continue
                    if (targetDescribed == false) {
                        datagramTarget(data).describe(entry)
                        targetDescribed = true
                    }
                    entry.uploadBytes.addAndGet(data.size.toLong())
                    while (isActive && flight.pending.offer(data, 100, TimeUnit.MILLISECONDS) == false) {
                        // Datagram buffering follows the selected upstream.
                    }
                }
            } catch (_: IOException) {
                entry.close()
            }
        }
        try {
            val attempted = mutableSetOf<String>()
            while (isActive && closed == false && entry.closed.get() == false && flight.canReplay) {
                val plan = nextPlan(spec.groupTag, attempted, ruleId)
                val leaf = plan.remaining(attempted).firstOrNull() ?: break
                attempted += leaf
                val control = Socket()
                track(control)
                currentControl.set(control)
                if (entry.closed.get()) release(control)
                val timeout = plan.failoverTimeoutMs.coerceAtLeast(1)
                val deadline = deadlineJob(control, nowElapsedRealtimeMs() + timeout)
                var remote: RelayDatagramTransport? = null
                var sender: Job? = null
                var remoteControlReader: Job? = null
                val retryRequested = AtomicBoolean(false)
                val outstandingHost = AtomicReference<String?>(null)
                val ioFailure = AtomicReference<Pair<IOException, String>?>()
                fun exchangeHost() = outstandingHost.get() ?: entry.targetHost
                val responseWait = UploadResponseWait(
                    scope = this@coroutineScope,
                    timeoutMs = plan.stallTimeoutsMs[leaf] ?: plan.stallTimeoutMs,
                    enabled = plan.watchResponse && leaf !in BUILTIN_GROUP_TARGET_TAGS,
                    isOpen = { closed == false && entry.closed.get() == false && control.isClosed == false },
                    retry = {
                        retryRequested.set(true)
                        remote?.close()
                    },
                ) { applyIfWaiting ->
                    // Datagram silence supplies weak evidence while preserving route-change retries.
                    onStalledUpload(spec.groupTag, leaf, plan.owner, exchangeHost(), false, applyIfWaiting)
                }
                try {
                    val uotVersion = nodeUotVersions[leaf] ?: 0
                    val upstream: RelayDatagramTransport = if (uotVersion != 0) {
                        val host = UotDatagramTransport.host(uotVersion).toByteArray(Charsets.US_ASCII)
                        connectNode(control, leaf, 1, SocksAddress(3, byteArrayOf(host.size.toByte()) + host, 0), timeout)
                        UotDatagramTransport(control, uotVersion)
                    } else {
                        val bound = connectNode(control, leaf, 3, SocksAddress(1, ByteArray(4), 0), timeout)
                        if (bound.port == 0) throw IOException("SOCKS UDP relay port")
                        val socket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
                        try {
                            socket.connect(InetSocketAddress("127.0.0.1", bound.port))
                            SocksDatagramTransport(socket)
                        } catch (e: Exception) {
                            socket.close()
                            throw e
                        }
                    }
                    track(upstream)
                    currentUdp.set(upstream)
                    deadline.cancelAndJoin()
                    if (entry.closed.get() || closed || isActive == false) break
                    if (control.isClosed) throw IOException("SOCKS association timed out")
                    control.soTimeout = 0
                    remote = upstream
                    entry.select(leaf, plan.groupPaths[leaf].orEmpty())
                    if (uotVersion == 0) remoteControlReader = launch(relayIo) {
                        try {
                            while (control.getInputStream().read() >= 0) { /* Upstream association lifetime. */ }
                            throw EOFException("Upstream association ended")
                        } catch (e: IOException) {
                            if (isActive && control.isClosed == false) ioFailure.compareAndSet(null, e to exchangeHost())
                        } finally { upstream.close() }
                    }
                    val replay = flight.replay().toMutableList()
                    sender = launch(relayIo) {
                        fun send(data: ByteArray): Boolean {
                            try {
                                upstream.send(data)
                                if (flight.established == false && data.size > parseSocksDatagram(data).payloadOffset) {
                                    responseWait.wrote { outstandingHost.set(datagramTarget(data).host) }
                                }
                                return true
                            } catch (e: IOException) {
                                if (isActive && control.isClosed == false) ioFailure.compareAndSet(null, e to datagramTarget(data).host)
                                upstream.close()
                                return false
                            }
                        }
                        for (data in replay) if (send(data) == false) return@launch
                        replay.clear()
                        while (isActive) {
                            val data = flight.pending.poll(100, TimeUnit.MILLISECONDS) ?: continue
                            flight.retain(data)
                            if (send(data) == false) break
                        }
                    }
                    while (isActive && entry.closed.get() == false) {
                        val data = upstream.receive()
                        val clientEndpoint = endpoint.get() ?: continue
                        if (flight.established == false) {
                            if (responseWait.responded() == false) throw IOException("Initial exchange selected for retry")
                            flight.establish()
                            onCandidateConnected(spec.groupTag, leaf, plan.owner)
                        }
                        entry.recordDownload(data.size)
                        try { local.send(DatagramPacket(data, data.size, clientEndpoint)) } catch (e: IOException) {
                            entry.close()
                            throw e
                        }
                    }
                    break
                } catch (e: IOException) {
                    if (remote != null && control.isClosed == false) ioFailure.compareAndSet(null, e to exchangeHost())
                } finally {
                    deadline.cancel()
                    responseWait.finish()
                    currentUdp.getAndSet(null)?.let(::release)
                    release(control)
                    withContext(NonCancellable) {
                        sender?.cancelAndJoin()
                        remoteControlReader?.cancelAndJoin()
                    }
                    currentControl.compareAndSet(control, null)
                    entry.select("", emptySet())
                }
                if (isActive && flight.established == false && retryRequested.get() == false &&
                    closed == false && entry.closed.get() == false
                ) ioFailure.get()?.let { (error, host) ->
                    if (host.isNotEmpty()) onCandidateFailed(spec.groupTag, leaf, plan.owner, host, FallbackPolicy.isStrongFailure(error))
                }
            }
        } finally {
            entry.close()
            release(local)
            unregister(entry)
            withContext(NonCancellable) {
                reader.cancelAndJoin()
                controlReader.cancelAndJoin()
            }
        }
    }

    private fun CoroutineScope.deadlineJob(socket: Socket, deadlineAt: Long) = launch(Dispatchers.Default) {
        delay((deadlineAt - nowElapsedRealtimeMs()).coerceAtLeast(0L))
        socket.close()
    }

    private fun connectNode(socket: Socket, leaf: String, command: Int, target: SocksAddress, timeout: Int): SocksAddress {
        val user = nodeUsers[leaf] ?: throw IOException("Physical node entry missing")
        val userBytes = user.toByteArray(Charsets.UTF_8)
        val passwordBytes = nodePassword.toByteArray(Charsets.UTF_8)
        if (userBytes.size !in 1..255 || passwordBytes.size !in 1..255) throw IOException("SOCKS credential length")
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress("127.0.0.1", nodeEntryPort), timeout)
        socket.soTimeout = timeout
        val input = DataInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        output.write(byteArrayOf(5, 1, 2))
        if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != 2) throw IOException("SOCKS auth method")
        output.write(byteArrayOf(1, userBytes.size.toByte()) + userBytes + byteArrayOf(passwordBytes.size.toByte()) + passwordBytes)
        if (input.readUnsignedByte() != 1 || input.readUnsignedByte() != 0) throw IOException("SOCKS authentication")
        // Preserve the SOCKS destination verbatim. Never infer a target or SNI from payload.
        output.write(byteArrayOf(5, command.toByte(), 0, target.type.toByte()) + target.raw +
            byteArrayOf((target.port shr 8).toByte(), target.port.toByte()))
        val version = input.readUnsignedByte()
        val result = input.readUnsignedByte()
        val reserved = input.readUnsignedByte()
        if (version != 5 || reserved != 0 || result != 0) throw IOException("SOCKS target connection $result")
        return readAddress(input)
    }

    private fun authenticateServer(
        input: DataInputStream,
        output: OutputStream,
        targets: List<FallbackRelaySpec>,
    ): Pair<FallbackRelaySpec, String>? {
        if (input.readUnsignedByte() != 5) throw IOException("SOCKS greeting version")
        val methods = ByteArray(input.readUnsignedByte()).also(input::readFully)
        if (methods.any { it.toInt() == 2 } == false) {
            output.write(byteArrayOf(5, 0xff.toByte()))
            return null
        }
        output.write(byteArrayOf(5, 2))
        val version = input.readUnsignedByte()
        val user = ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.UTF_8)
        val password = ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.UTF_8)
        val spec = targets.firstOrNull { it.username == user.substringBefore("/") }
        val accepted = version == 1 && user.isNotEmpty() && password.isNotEmpty() && spec != null && password == spec.password
        output.write(byteArrayOf(1, if (accepted) 0 else 1))
        return if (accepted) requireNotNull(spec) to user.substringAfter("/", "default") else null
    }

    private fun readAddress(input: DataInputStream): SocksAddress {
        val type = input.readUnsignedByte()
        val raw = when (type) {
            1 -> ByteArray(4).also(input::readFully)
            4 -> ByteArray(16).also(input::readFully)
            3 -> {
                val length = input.readUnsignedByte()
                if (length == 0) throw IOException("SOCKS empty domain")
                byteArrayOf(length.toByte()) + ByteArray(length).also(input::readFully)
            }
            else -> throw IOException("SOCKS address type $type")
        }
        return SocksAddress(type, raw, input.readUnsignedShort())
    }

    private fun reply(output: OutputStream, result: Int, port: Int = 0) {
        output.write(byteArrayOf(5, result.toByte(), 0, 1, 127, 0, 0, 1, (port shr 8).toByte(), port.toByte()))
    }

    private fun datagramTarget(packet: ByteArray) =
        readAddress(DataInputStream(ByteArrayInputStream(packet, 3, packet.size - 3)))

    private data class SocksAddress(val type: Int, val raw: ByteArray, val port: Int) {
        val host: String get() = if (type == 3) raw.copyOfRange(1, raw.size).toString(Charsets.UTF_8).lowercase()
            else InetAddress.getByAddress(raw).hostAddress.orEmpty()

        fun describe(entry: RelayConnectionEntry) {
            if (type == 3) entry.destinationHost = raw.copyOfRange(1, raw.size).toString(Charsets.UTF_8)
            else entry.destinationIp = InetAddress.getByAddress(raw).hostAddress.orEmpty()
            entry.destinationPort = port
        }
    }

    /** Retains all dequeued bytes until observation of a response or exceeding the cap. */
    internal class FirstFlight {
        val pending = LinkedBlockingQueue<ByteArray>(8)
        private val initial = mutableListOf<ByteArray>()
        private var retainedBytes = 0
        @Volatile var established = false
            private set
        @Volatile private var withinCap = true
        val canReplay: Boolean get() = withinCap && established == false

        @Synchronized fun replay(): List<ByteArray> = if (canReplay) initial.toList() else emptyList()

        @Synchronized fun retain(bytes: ByteArray) {
            if (canReplay == false) return
            if (bytes.size > 256 * 1024 - retainedBytes) {
                withinCap = false
                initial.clear()
            } else {
                initial += bytes
                retainedBytes += bytes.size
            }
        }

        @Synchronized fun establish(): Boolean {
            if (established) return false
            established = true
            initial.clear()
            return true
        }
    }

    /** Reports one initial-response stall while policy decides whether to retry the exchange. */
    internal class UploadResponseWait(
        private val scope: CoroutineScope,
        private val timeoutMs: Int,
        private val enabled: Boolean,
        private val isOpen: () -> Boolean,
        private val retry: () -> Unit,
        private val waitFor: suspend (Long) -> Unit = { delay(it) },
        private val confirm: suspend ((() -> Unit) -> Boolean) -> Boolean,
    ) {
        // Retain the observation job so later writes share the initial stall report.
        private var waiting: Job? = null
        private var open = true

        @Synchronized fun wrote(onOutstanding: () -> Unit = {}) {
            if (enabled == false || open == false || waiting != null || isOpen() == false) return
            onOutstanding()
            waiting = scope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                val current = coroutineContext[Job]
                waitFor(timeoutMs.toLong().coerceAtLeast(1L))
                val shouldRetry = confirm { signal ->
                    synchronized(this@UploadResponseWait) {
                        if (open && waiting === current && isOpen()) {
                            signal()
                            true
                        } else {
                            false
                        }
                    }
                }
                if (shouldRetry) synchronized(this@UploadResponseWait) {
                    if (open && waiting === current && isOpen()) {
                        open = false
                        retry()
                    }
                }
            }
            waiting?.start()
        }

        @Synchronized fun responded(): Boolean {
            if (open == false) return false
            close()
            return true
        }

        @Synchronized fun close() {
            open = false
            waiting?.cancel()
        }

        suspend fun finish() {
            val observation = synchronized(this) {
                close()
                waiting
            }
            if (observation != null) withContext(NonCancellable) { observation.join() }
        }
    }

    private companion object {
        const val HANDSHAKE_TIMEOUT_MS = 5_000
    }
}
