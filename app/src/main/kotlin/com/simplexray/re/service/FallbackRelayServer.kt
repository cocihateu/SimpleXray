package com.simplexray.re.service

import android.net.InetAddresses
import android.os.SystemClock
import com.simplexray.re.common.config.BUILTIN_GROUP_TARGET_TAGS
import com.simplexray.re.common.config.GroupMemberRef
import kotlinx.coroutines.CompletableDeferred
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
import java.net.SocketTimeoutException
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

/** Routes TCP/UDP exchanges through ordered group candidates and observes payload responses. */
internal class FallbackRelayServer(
    private val specs: List<FallbackRelaySpec>,
    private val nodeEntryPort: Int,
    private val nodeUsers: Map<String, String>,
    private val nodePassword: String,
    private val nodeUotVersions: Map<String, Int>,
    private val resolvePlan: (String, String) -> FailoverPlan,
    private val onCandidateFailed: suspend (String, String, Any?, String, Boolean, Boolean) -> Unit,
    private val onCandidateConnected: (String, String, Any?, String) -> Unit,
    private val onStalledUpload: suspend (String, String, Any?, String, Boolean, Boolean, (() -> Unit) -> Boolean) -> Boolean,
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

    fun closeStalledConnections(groupTag: String, member: GroupMemberRef) {
        // The controller can announce demotion while holding its group lock and a reporting entry.
        // Sample each entry independently after dispatching cleanup to the relay.
        cleanupScope?.launch {
            val samples = entries.mapNotNull { entry -> entry.sample(groupTag, member)?.let { entry to it } }
            if (samples.isEmpty()) return@launch
            delay(FallbackPolicy.CLOSE_SAMPLE_DELAY_MS)
            samples.forEach { (entry, sample) -> entry.closeIfStalled(sample) }
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

    private suspend fun nextPlan(group: String, host: String, attempted: Set<String>, ruleId: String): FailoverPlan {
        val plan = resolvePlan(group, host)
        if (plan.remaining(attempted).isNotEmpty() || ruleId == "dialer") return plan
        if (withTimeoutOrNull(plan.failoverTimeoutMs.toLong().coerceAtLeast(1L)) { awaitReady(); true } != true) return plan
        return resolvePlan(group, host)
    }

    private fun FailoverPlan.remaining(attempted: Set<String>) = candidates.distinct()
        .take(FallbackPolicy.MAX_DIAL_ATTEMPTS).filter { it !in attempted }
        .take(FallbackPolicy.MAX_DIAL_ATTEMPTS - attempted.size)

    private data class TransferFailure(val error: IOException, val requestHosts: List<String>)

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
        val destination = target.destination()
        val localEnded = AtomicBoolean(false)
        val reader = launch(relayIo) {
            val buffer = ByteArray(32 * 1024)
            try {
                while (isActive) {
                    val count = client.getInputStream().read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    val chunk = buffer.copyOf(count)
                    entry.recordUpload(count)
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
                val plan = nextPlan(spec.groupTag, destination.host, attempted, ruleId)
                val leaf = plan.remaining(attempted).firstOrNull() ?: break
                attempted += leaf
                val socket = Socket()
                track(socket)
                currentUpstream.set(socket)
                if (entry.closed.get()) release(socket)
                val timeout = plan.failoverTimeoutMs.coerceAtLeast(1)
                val deadline = deadlineJob(socket, nowElapsedRealtimeMs() + timeout)
                var setupComplete = false
                var receiveEnded = false
                var sender: Job? = null
                val retryRequested = AtomicBoolean(false)
                val ioFailure = AtomicReference<TransferFailure?>()
                val responseWait = UploadResponseWait(
                    scope = this@coroutineScope,
                    entry = entry,
                    timeoutMs = plan.stallTimeoutsMs[leaf] ?: plan.stallTimeoutMs,
                    enabled = plan.watchResponse && leaf !in BUILTIN_GROUP_TARGET_TAGS,
                    isOpen = { isActive && closed == false && entry.closed.get() == false },
                    retry = {
                        retryRequested.set(true)
                        if (flight.canReplay) socket.close() else entry.close()
                    },
                    closeBlockedWrite = { host ->
                        ioFailure.set(TransferFailure(SocketTimeoutException("Upstream write timed out"), listOfNotNull(host)))
                        socket.close()
                    },
                    nowElapsedRealtimeMs = nowElapsedRealtimeMs,
                ) { host, pending, applyIfWaiting ->
                    onStalledUpload(spec.groupTag, leaf, plan.owner, host, pending, pending, applyIfWaiting)
                }
                try {
                    connectNode(socket, leaf, 1, target, timeout)
                    deadline.cancelAndJoin()
                    if (entry.closed.get() || closed || isActive == false) break
                    if (socket.isClosed) throw IOException("Local SOCKS setup timed out")
                    socket.soTimeout = 0
                    setupComplete = true
                    // SOCKS acceptance selects the local route; payload I/O supplies node evidence.
                    entry.select(leaf, plan.groupPaths[leaf].orEmpty(), responseWait)
                    if (accepted == false) {
                        try { reply(client.getOutputStream(), 0) } catch (e: IOException) {
                            entry.close()
                            throw e
                        }
                        accepted = true
                    }
                    // Retain replay before server-first bytes can clear the initial flight.
                    val replay = flight.replay().toMutableList()
                    sender = launch(relayIo) {
                        try {
                            val output = socket.getOutputStream()
                            fun send(chunk: ByteArray) {
                                responseWait.write(destination) {
                                    try {
                                        output.write(chunk)
                                    } catch (e: IOException) {
                                        if (isActive && socket.isClosed == false) ioFailure.updateAndGet { previous ->
                                            if (previous == null || FallbackPolicy.isStrongFailure(e)) {
                                                TransferFailure(e, listOf(destination.host))
                                            } else previous
                                        }
                                        throw e
                                    }
                                }
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
                                // Retain each dequeued chunk before sending. Teardown joins this
                                // sender before another attempt reads the complete initial flight.
                                flight.retain(chunk)
                                send(chunk)
                            }
                        } catch (e: IOException) {
                            if (isActive && socket.isClosed == false) {
                                ioFailure.compareAndSet(null, TransferFailure(e, responseWait.requestHosts()))
                            }
                            runCatching { socket.close() }
                        }
                    }
                    val buffer = ByteArray(32 * 1024)
                    while (isActive && entry.closed.get() == false) {
                        val count = socket.getInputStream().read(buffer)
                        if (count < 0) {
                            if (flight.established) receiveEnded = true else {
                                val eof = EOFException("Upstream closed before response")
                                ioFailure.compareAndSet(null, TransferFailure(eof, responseWait.requestHosts()))
                            }
                            break
                        }
                        if (count == 0) continue
                        // Pin on observation, BEFORE writing to the client (which may partly fail).
                        responseWait.responded(destination, count, flight)?.let { host ->
                            onCandidateConnected(spec.groupTag, leaf, plan.owner, host)
                        }
                        try { client.getOutputStream().write(buffer, 0, count) } catch (e: IOException) {
                            entry.close()
                            throw e
                        }
                    }
                } catch (e: IOException) {
                    if (setupComplete && socket.isClosed == false) {
                        ioFailure.compareAndSet(null, TransferFailure(e, responseWait.requestHosts()))
                    }
                } finally {
                    deadline.cancel()
                    responseWait.finish(preservePendingWrite = receiveEnded)
                    release(socket)
                    withContext(NonCancellable) { sender?.cancelAndJoin() }
                    currentUpstream.compareAndSet(socket, null)
                    entry.select("", emptySet())
                }
                if (isActive && retryRequested.get() == false &&
                    closed == false && entry.closed.get() == false
                ) ioFailure.get()?.let { (error, hosts) ->
                    val strong = FallbackPolicy.isStrongFailure(error)
                    if (strong || flight.established == false) {
                        val affected = hosts.isNotEmpty() && (strong || error is EOFException)
                        onCandidateFailed(spec.groupTag, leaf, plan.owner, destination.host, strong, affected)
                    }
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
        val firstHost = CompletableDeferred<String?>()
        val currentControl = AtomicReference<Socket?>(null)
        val currentUdp = AtomicReference<RelayDatagramTransport?>(null)
        val endpoint = AtomicReference<InetSocketAddress?>(
            if (requested.port == 0) null else InetSocketAddress(client.inetAddress, requested.port),
        )
        val entry = RelayConnectionEntry("udp", client.inetAddress.hostAddress.orEmpty(), client.port, spec.groupTag) {
            firstHost.complete(null)
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
                    if (!firstHost.isCompleted) {
                        val target = datagramTarget(data)
                        target.describe(entry)
                        firstHost.complete(target.destination().host)
                    }
                    entry.recordUpload(data.size)
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
            val planHost = firstHost.await() ?: return@coroutineScope
            while (isActive && closed == false && entry.closed.get() == false && flight.canReplay) {
                val plan = nextPlan(spec.groupTag, planHost, attempted, ruleId)
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
                var receiveEnded = false
                val retryRequested = AtomicBoolean(false)
                val ioFailure = AtomicReference<TransferFailure?>()
                val responseWait = UploadResponseWait(
                    scope = this@coroutineScope,
                    entry = entry,
                    timeoutMs = plan.stallTimeoutsMs[leaf] ?: plan.stallTimeoutMs,
                    enabled = plan.watchResponse && leaf !in BUILTIN_GROUP_TARGET_TAGS,
                    isOpen = { isActive && closed == false && entry.closed.get() == false },
                    retry = {
                        retryRequested.set(true)
                        if (flight.canReplay) remote?.close() else entry.close()
                    },
                    closeBlockedWrite = { host ->
                        ioFailure.set(TransferFailure(SocketTimeoutException("Upstream write timed out"), listOfNotNull(host)))
                        remote?.close()
                    },
                    nowElapsedRealtimeMs = nowElapsedRealtimeMs,
                ) { host, pending, applyIfWaiting ->
                    onStalledUpload(spec.groupTag, leaf, plan.owner, host, false, pending, applyIfWaiting)
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
                    entry.select(leaf, plan.groupPaths[leaf].orEmpty(), responseWait)
                    if (uotVersion == 0) remoteControlReader = launch(relayIo) {
                        try {
                            while (control.getInputStream().read() >= 0) { /* Upstream association lifetime. */ }
                            throw EOFException("Upstream association ended")
                        } catch (e: IOException) {
                            if (isActive && control.isClosed == false &&
                                (flight.established == false || FallbackPolicy.isStrongFailure(e))
                            ) ioFailure.compareAndSet(null, TransferFailure(e, responseWait.requestHosts()))
                        } finally { upstream.close() }
                    }
                    val replay = flight.replay().toMutableList()
                    sender = launch(relayIo) {
                        suspend fun send(data: ByteArray): Boolean {
                            val target = datagramTarget(data).destination()
                            val payload = data.size > parseSocksDatagram(data).payloadOffset
                            try {
                                responseWait.write(target, payload) {
                                    upstream.send(data)
                                }
                                return true
                            } catch (e: IOException) {
                                if (isActive && !closed && !entry.closed.get() && !retryRequested.get() && !control.isClosed) {
                                    // A native datagram send can fail independently of other destinations.
                                    if (uotVersion == 0 && ioFailure.get() == null && responseWait.hasOtherPending(target)) {
                                        val strong = FallbackPolicy.isStrongFailure(e)
                                        onCandidateFailed(spec.groupTag, leaf, plan.owner, target.host, strong, payload && strong)
                                        return true
                                    }
                                    ioFailure.compareAndSet(null, TransferFailure(e, if (payload) listOf(target.host) else emptyList()))
                                }
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
                        val data = try {
                            upstream.receive()
                        } catch (_: SocketTimeoutException) {
                            // The observer owns response and idle decisions; a receive timeout leaves both armed.
                            continue
                        }
                        val clientEndpoint = endpoint.get() ?: continue
                        responseWait.responded(datagramTarget(data).destination(), data.size, flight)?.let { host ->
                            onCandidateConnected(spec.groupTag, leaf, plan.owner, host)
                        }
                        try { local.send(DatagramPacket(data, data.size, clientEndpoint)) } catch (e: IOException) {
                            entry.close()
                            throw e
                        }
                    }
                } catch (e: IOException) {
                    receiveEnded = flight.established && e is EOFException
                    if (remote != null && control.isClosed == false && receiveEnded == false) {
                        ioFailure.compareAndSet(null, TransferFailure(e, responseWait.requestHosts()))
                    }
                } finally {
                    deadline.cancel()
                    responseWait.finish(preservePendingWrite = receiveEnded)
                    currentUdp.getAndSet(null)?.let(::release)
                    release(control)
                    withContext(NonCancellable) {
                        sender?.cancelAndJoin()
                        remoteControlReader?.cancelAndJoin()
                    }
                    currentControl.compareAndSet(control, null)
                    entry.select("", emptySet())
                }
                if (isActive && retryRequested.get() == false &&
                    closed == false && entry.closed.get() == false
                ) ioFailure.get()?.let { (error, hosts) ->
                    val strong = FallbackPolicy.isStrongFailure(error)
                    if (strong || flight.established == false) {
                        val affected = hosts.isNotEmpty() && (strong || error is EOFException)
                        // A shared transport reset is one global event. EOF can mark each unanswered host weakly.
                        val attributed = if (strong && hosts.size > 1) emptyList() else hosts
                        for (host in attributed.ifEmpty { listOf("") }) {
                            onCandidateFailed(spec.groupTag, leaf, plan.owner, host, strong, affected && host.isNotEmpty())
                        }
                    }
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

    private data class Destination(val host: String, val port: Int, val numeric: Boolean)

    private data class SocksAddress(val type: Int, val raw: ByteArray, val port: Int) {
        val host: String get() = if (type == 3) raw.copyOfRange(1, raw.size).toString(Charsets.UTF_8).lowercase()
            else InetAddress.getByAddress(raw).hostAddress.orEmpty()

        fun destination(): Destination {
            val name = host
            if (type == 3 && InetAddresses.isNumericAddress(name)) {
                return Destination(InetAddresses.parseNumericAddress(name).hostAddress.orEmpty(), port, true)
            }
            return Destination(name, port, type != 3)
        }

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

    /** One deadline scheduler per attempt; each outstanding destination owns its health decision. */
    private class UploadResponseWait(
        private val scope: CoroutineScope,
        private val entry: RelayConnectionEntry,
        timeoutMs: Int,
        private val enabled: Boolean,
        private val isOpen: () -> Boolean,
        private val retry: () -> Unit,
        private val closeBlockedWrite: (String?) -> Unit,
        private val nowElapsedRealtimeMs: () -> Long,
        private val confirm: suspend (String, Boolean, (() -> Unit) -> Boolean) -> Boolean,
    ) : RelayConnectionEntry.Attempt {
        private class Observation(val target: Destination, var due: Long) {
            var check: Job? = null
            var expired = false
            var sent = false

            fun refresh(deadline: Long) {
                check?.cancel()
                check = null
                expired = false
                due = deadline
            }
        }

        private val window = timeoutMs.toLong().coerceAtLeast(1L)
        private val datagrams = entry.network == "udp"
        private val pending = mutableMapOf<Destination, Observation>()
        // A port shared by several domains stays ambiguous, including for delayed duplicate replies.
        private val domainByPort = mutableMapOf<Int, Destination?>()
        private val numericTargets = mutableSetOf<Destination>()
        private var idle: Observation? = null
        private var lastReply: Destination? = null
        private var lastSuccessAt = 0L
        private var writing: Destination? = null
        private var writingPayload = false
        private var writeDue = Long.MAX_VALUE
        private var lastProgress = 0L
        private var meaningfulProgress = 0L
        private var meaningfulProgressAt = 0L
        private var armedFor = Long.MAX_VALUE
        private var timer: Job? = null
        private var open = true
        private var drain: CompletableDeferred<Unit>? = null

        fun write(target: Destination, expectsReply: Boolean = true, send: () -> Unit) {
            val exchange = synchronized(entry) {
                if (!open || drain != null) throw IOException("Upstream attempt ended")
                if (datagrams && enabled) {
                    if (target.numeric) numericTargets.add(target)
                    else if (target.port !in domainByPort) domainByPort[target.port] = target
                    else if (domainByPort[target.port] != target) domainByPort[target.port] = null
                }
                val now = nowElapsedRealtimeMs()
                writing = target
                writingPayload = expectsReply
                writeDue = now + window
                idle?.check?.cancel()
                idle = null
                val exchange = if (enabled && expectsReply) {
                    pending.getOrPut(target) { Observation(target, writeDue) }.also {
                        if (!datagrams) it.refresh(writeDue)
                    }
                } else null
                arm(writeDue)
                exchange
            }
            var sent = false
            try {
                send()
                sent = true
            } finally {
                synchronized(entry) {
                    writing = null
                    writeDue = Long.MAX_VALUE
                    if (sent) {
                        lastProgress = nowElapsedRealtimeMs()
                        entry.recordForwarded()
                        // A new UDP exchange advances the association; retries of an unanswered one share its clock.
                        if (!datagrams || (expectsReply && exchange?.sent != true)) markMeaningfulProgress()
                    }
                    if (open && exchange != null && pending[target] === exchange) {
                        if (sent) {
                            exchange.sent = true
                            if (!datagrams) {
                                exchange.refresh(lastProgress + window)
                                arm(exchange.due)
                            }
                        } else if (!exchange.sent) {
                            exchange.check?.cancel()
                            pending.remove(target)
                        }
                    }
                    if (open && pending.isEmpty() && drain == null) lastReply?.let { observeIdle(it) }
                    drain?.complete(Unit)
                }
            }
        }

        fun responded(reply: Destination, size: Int, flight: FirstFlight): String? = synchronized(entry) {
            if (!open) throw IOException("Exchange selected for retry")
            val target = when {
                !datagrams || !enabled || reply in pending || reply in numericTargets -> reply
                reply.numeric -> domainByPort[reply.port]
                domainByPort[reply.port] == reply -> reply
                else -> null
            }
            val answered = target?.let(pending::remove)
            answered?.check?.cancel()
            val first = !flight.established && flight.establish()
            lastProgress = nowElapsedRealtimeMs()
            markMeaningfulProgress()
            entry.recordDownload(size)
            val previous = lastReply
            val source = target ?: reply
            lastReply = source
            if (pending.isEmpty()) observeIdle(source)
            if (target != null && (answered != null || first || previous != target || lastProgress - lastSuccessAt >= window)) {
                lastSuccessAt = lastProgress
                target.host
            } else null
        }

        private fun observeIdle(target: Destination) {
            if (!enabled || drain != null) return
            val deadline = lastProgress + window
            val current = idle
            if (current?.target == target) current.refresh(deadline) else {
                current?.check?.cancel()
                idle = Observation(target, deadline)
            }
            arm(deadline)
        }

        fun hasOtherPending(target: Destination) = synchronized(entry) { pending.keys.any { it != target } }
        fun requestHosts(): List<String> = synchronized(entry) { pending.keys.map { it.host }.distinct() }

        private fun markMeaningfulProgress() {
            meaningfulProgress++
            meaningfulProgressAt = lastProgress
        }

        // The existing wakeup reads refreshed deadlines. Bulk traffic only updates timestamps.
        private fun arm(deadline: Long) {
            if (!enabled || !open || deadline >= armedFor) return
            timer?.cancel()
            armedFor = deadline
            timer = scope.launch(Dispatchers.Default) {
                delay((deadline - nowElapsedRealtimeMs()).coerceAtLeast(0L))
                synchronized(entry) {
                    if (timer === coroutineContext[Job] && isOpen()) expire()
                }
            }
        }

        private fun expire() {
            timer = null
            armedFor = Long.MAX_VALUE
            val now = nowElapsedRealtimeMs()
            val blocked = writing
            if (blocked != null && writeDue <= now) {
                stop()
                closeBlockedWrite(blocked.host.takeIf { writingPayload })
                return
            }
            var next = writeDue
            fun visit(observation: Observation, responsePending: Boolean) {
                if (observation.due <= now) {
                    observation.expired = true
                    observation.due = now + FallbackPolicy.WEAK_CHECK_GAP_MS
                    if (observation.check == null) startCheck(observation, responsePending)
                }
                next = minOf(next, observation.due)
            }
            if (drain == null) {
                pending.values.forEach { visit(it, true) }
                idle?.let { visit(it, false) }
            }
            arm(next)
        }

        private fun startCheck(observation: Observation, responsePending: Boolean) {
            observation.check = scope.launch(Dispatchers.Default) {
                val self = coroutineContext[Job]
                fun current() = open && drain == null && isOpen() && observation.check === self &&
                    (if (responsePending) pending[observation.target] === observation else idle === observation)
                try {
                    val replace = confirm(observation.target.host, responsePending) { signal ->
                        synchronized(entry) { current().also { if (it) signal() } }
                    }
                    if (replace) {
                        // A shared association gets a full progress sample before a target requests retirement.
                        val sample = synchronized(entry) { meaningfulProgress }
                        if (datagrams && responsePending) delay(FallbackPolicy.CLOSE_SAMPLE_DELAY_MS)
                        synchronized(entry) {
                            if (current() && writing == null && sample == meaningfulProgress &&
                                nowElapsedRealtimeMs() - meaningfulProgressAt >= window
                            ) retire()
                        }
                    }
                } finally {
                    synchronized(entry) { if (observation.check === self) observation.check = null }
                }
            }
        }

        // Both demotion samples and retirement run under the entry lock used for byte progress.
        override fun isStalled(): Boolean = open && drain == null && isOpen() && writing == null &&
            pending.values.any { it.expired } && nowElapsedRealtimeMs() - meaningfulProgressAt >= window

        override fun retire() {
            stop()
            retry()
        }

        private fun stop() {
            open = false
            timer?.cancel()
            idle?.check?.cancel()
            pending.values.forEach { it.check?.cancel() }
            drain?.complete(Unit)
        }

        suspend fun finish(preservePendingWrite: Boolean) {
            val completion = synchronized(entry) {
                if (open && enabled && preservePendingWrite && writing != null) {
                    idle?.check?.cancel()
                    pending.values.forEach { it.check?.cancel() }
                    CompletableDeferred<Unit>().also { drain = it }
                } else {
                    stop()
                    null
                }
            }
            if (completion != null) withContext(NonCancellable) {
                completion.await()
                synchronized(entry) { stop() }
            }
        }
    }

    private companion object {
        const val HANDSHAKE_TIMEOUT_MS = 5_000
    }
}
