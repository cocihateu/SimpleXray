package com.simplexray.re.service

import android.os.SystemClock
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
)

/** Routes initial TCP/UDP exchanges through ordered group candidates. */
internal class FallbackRelayServer(
    private val specs: List<FallbackRelaySpec>,
    private val nodeEntryPort: Int,
    private val nodeUsers: Map<String, String>,
    private val nodePassword: String,
    private val nodeUotVersions: Map<String, Int>,
    private val resolvePlan: (String) -> FailoverPlan,
    private val onCandidateFailed: suspend (String, String, Long) -> Unit,
    private val onCandidateConnected: (String, String, Long) -> Unit,
    private val onStalledUpload: suspend (String, String, Long, (() -> Unit) -> Boolean) -> Unit,
    private val awaitReady: suspend () -> Unit = {},
    private val nowElapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
) : Closeable {
    private val listeners = CopyOnWriteArrayList<ServerSocket>()
    private val jobs = CopyOnWriteArrayList<Job>()
    private val resources = ConcurrentHashMap.newKeySet<Closeable>()
    private val relayIo = Dispatchers.IO.limitedParallelism(1024)
    @Volatile private var closed = false

    fun start(scope: CoroutineScope) {
        check(closed == false && listeners.isEmpty())
        try {
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
        listeners.forEach { runCatching { it.close() } }
        listeners.clear()
        resources.toList().forEach(::release)
        jobs.forEach { it.cancel() }
        jobs.clear()
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
        if (plan.candidates.any { it !in attempted } || ruleId == "dialer") return plan
        if (withTimeoutOrNull(plan.failoverTimeoutMs.toLong().coerceAtLeast(1L)) { awaitReady(); true } != true) return plan
        return resolvePlan(group)
    }

    private suspend fun handleTcp(spec: FallbackRelaySpec, client: Socket, target: SocksAddress, ruleId: String) = coroutineScope {
        client.soTimeout = 0
        val currentUpstream = AtomicReference<Socket?>(null)
        val entry = RelayConnectionEntry("tcp", client.inetAddress.hostAddress.orEmpty(), client.port, spec.groupTag) {
            runCatching { currentUpstream.get()?.close() }
            runCatching { client.close() }
        }
        target.describe(entry)
        entry.ruleId = ruleId
        RelayConnectionRegistry.register(entry)
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
                val leaf = plan.candidates.firstOrNull { it !in attempted } ?: break
                attempted += leaf
                val socket = Socket()
                track(socket)
                currentUpstream.set(socket)
                entry.leafTag = leaf
                val timeout = plan.failoverTimeoutMs.coerceAtLeast(1)
                val deadline = deadlineJob(socket, nowElapsedRealtimeMs() + timeout)
                var sender: Job? = null
                val failureConfirmed = AtomicBoolean(false)
                val responseWait = UploadResponseWait(
                    scope = this@coroutineScope,
                    timeoutMs = timeout,
                    enabled = plan.watchResponse,
                    isOpen = { closed == false && entry.closed.get() == false && socket.isClosed == false },
                ) { applyIfWaiting ->
                    onStalledUpload(spec.groupTag, leaf, plan.generation) { failure ->
                        applyIfWaiting {
                            failureConfirmed.set(true)
                            failure()
                            if (flight.established) entry.close() else socket.close()
                        }
                    }
                }
                try {
                    connectNode(socket, leaf, 1, target, timeout)
                    deadline.cancel()
                    socket.soTimeout = 0
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
                            replay.forEach { chunk -> responseWait.write { output.write(chunk) } }
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
                                responseWait.write { output.write(chunk) }
                            }
                        } catch (_: IOException) {
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
                        flight.establish()
                        responseWait.responded()
                        onCandidateConnected(spec.groupTag, leaf, plan.generation)
                        try { client.getOutputStream().write(buffer, 0, count) } catch (e: IOException) {
                            entry.close()
                            throw e
                        }
                        entry.downloadBytes.addAndGet(count.toLong())
                    }
                    break
                } catch (_: IOException) {
                    // Close and join first: neither a late sender nor retained suffix may escape
                    // into the next socket. A stream with any response is never replayed.
                } finally {
                    deadline.cancel()
                    responseWait.close()
                    release(socket)
                    withContext(NonCancellable) { sender?.cancelAndJoin() }
                    currentUpstream.compareAndSet(socket, null)
                }
                if (isActive && flight.established == false && failureConfirmed.get() == false &&
                    closed == false && entry.closed.get() == false
                ) onCandidateFailed(spec.groupTag, leaf, plan.generation)
            }
            if (accepted == false && entry.closed.get() == false) reply(client.getOutputStream(), 1)
        } finally {
            entry.close()
            RelayConnectionRegistry.unregister(entry.id)
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
        RelayConnectionRegistry.register(entry)
        entry.ruleId = ruleId
        val flight = FirstFlight()
        client.soTimeout = 0
        try {
            reply(client.getOutputStream(), 0, local.localPort)
        } catch (e: IOException) {
            entry.close()
            release(local)
            RelayConnectionRegistry.unregister(entry.id)
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
                    if (entry.destinationPort == 0) {
                        readAddress(DataInputStream(ByteArrayInputStream(data, 3, data.size - 3))).describe(entry)
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
                val leaf = plan.candidates.firstOrNull { it !in attempted } ?: break
                attempted += leaf
                val control = Socket()
                track(control)
                currentControl.set(control)
                entry.leafTag = leaf
                val timeout = plan.failoverTimeoutMs.coerceAtLeast(1)
                val deadline = deadlineJob(control, nowElapsedRealtimeMs() + timeout)
                var remote: RelayDatagramTransport? = null
                var sender: Job? = null
                var remoteControlReader: Job? = null
                val failureConfirmed = AtomicBoolean(false)
                val responseWait = UploadResponseWait(
                    scope = this@coroutineScope,
                    timeoutMs = timeout,
                    enabled = plan.watchResponse,
                    isOpen = { closed == false && entry.closed.get() == false && control.isClosed == false },
                ) { applyIfWaiting ->
                    onStalledUpload(spec.groupTag, leaf, plan.generation) { failure ->
                        applyIfWaiting {
                            failureConfirmed.set(true)
                            failure()
                            if (flight.established) entry.close() else remote?.close()
                        }
                    }
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
                    remote = upstream
                    track(upstream)
                    currentUdp.set(upstream)
                    control.soTimeout = 0
                    deadline.cancel()
                    if (uotVersion == 0) remoteControlReader = launch(relayIo) {
                        try { while (control.getInputStream().read() >= 0) { /* Upstream association lifetime. */ } }
                        catch (_: IOException) { /* Upstream control socket ended. */ }
                        finally { upstream.close() }
                    }
                    val replay = flight.replay().toMutableList()
                    sender = launch(relayIo) {
                        try {
                            replay.forEach { data -> responseWait.write { upstream.send(data) } }
                            replay.clear()
                            while (isActive) {
                                val data = flight.pending.poll(100, TimeUnit.MILLISECONDS) ?: continue
                                flight.retain(data)
                                responseWait.write { upstream.send(data) }
                            }
                        } catch (_: IOException) {
                            upstream.close()
                        }
                    }
                    while (isActive && entry.closed.get() == false) {
                        val data = upstream.receive()
                        val clientEndpoint = endpoint.get() ?: continue
                        flight.establish()
                        responseWait.responded()
                        onCandidateConnected(spec.groupTag, leaf, plan.generation)
                        try { local.send(DatagramPacket(data, data.size, clientEndpoint)) } catch (e: IOException) {
                            entry.close()
                            throw e
                        }
                        entry.downloadBytes.addAndGet(data.size.toLong())
                    }
                    break
                } catch (_: IOException) {
                    // The association remains pinned after its first response, like TCP.
                } finally {
                    deadline.cancel()
                    responseWait.close()
                    remote?.let(::release)
                    release(control)
                    withContext(NonCancellable) {
                        sender?.cancelAndJoin()
                        remoteControlReader?.cancelAndJoin()
                    }
                    currentUdp.set(null)
                    currentControl.compareAndSet(control, null)
                }
                if (isActive && flight.established == false && failureConfirmed.get() == false &&
                    closed == false && entry.closed.get() == false
                ) onCandidateFailed(spec.groupTag, leaf, plan.generation)
            }
        } finally {
            entry.close()
            release(local)
            RelayConnectionRegistry.unregister(entry.id)
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

    private data class SocksAddress(val type: Int, val raw: ByteArray, val port: Int) {
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

        @Synchronized fun establish() {
            established = true
            initial.clear()
        }
    }

    /** Only outstanding writes trigger probes. Healthy/stale results back off, not disconnect. */
    internal class UploadResponseWait(
        private val scope: CoroutineScope,
        private val timeoutMs: Int,
        private val enabled: Boolean,
        private val isOpen: () -> Boolean,
        private val waitFor: suspend (Long) -> Unit = { delay(it) },
        private val confirm: suspend ((() -> Unit) -> Boolean) -> Unit,
    ) {
        private var waiting: Job? = null
        private var open = true
        private var outstanding = false

        fun write(block: () -> Unit) {
            synchronized(this) {
                if (open && isOpen()) {
                    outstanding = true
                    arm()
                }
            }
            // Do not rearm on write completion: a response may have arrived mid-write.
            block()
        }

        private fun arm() {
            if (enabled == false || open == false || outstanding == false || waiting != null || isOpen() == false) return
            waiting = scope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                val current = coroutineContext[Job]
                var interval = timeoutMs.toLong().coerceAtLeast(1L)
                try {
                    while (isActive) {
                        waitFor(interval)
                        val shouldCheck = synchronized(this@UploadResponseWait) {
                            open && outstanding && waiting === current && isOpen()
                        }
                        if (shouldCheck == false) break
                        confirm { failure ->
                            synchronized(this@UploadResponseWait) {
                                if (open == false || outstanding == false || waiting !== current || isOpen() == false) false else {
                                    outstanding = false
                                    failure()
                                    true
                                }
                            }
                        }
                        val stillWaiting = synchronized(this@UploadResponseWait) {
                            open && outstanding && waiting === current && isOpen()
                        }
                        if (stillWaiting == false) break
                        interval = (interval * 2).coerceAtMost(maxOf(30_000L, timeoutMs.toLong()))
                    }
                } finally {
                    synchronized(this@UploadResponseWait) {
                        if (waiting === current) waiting = null
                    }
                }
            }
            waiting?.start()
        }

        @Synchronized fun responded() {
            outstanding = false
            waiting?.cancel()
            waiting = null
        }

        @Synchronized fun close() {
            open = false
            outstanding = false
            waiting?.cancel()
            waiting = null
        }
    }

    private companion object {
        const val HANDSHAKE_TIMEOUT_MS = 5_000
    }
}
