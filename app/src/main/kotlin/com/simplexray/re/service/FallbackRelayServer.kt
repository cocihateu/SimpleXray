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
import kotlinx.coroutines.channels.Channel
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
    val hostRetryCandidates: List<String>? = null,
) {
    internal interface ReplyEvidence {
        fun creditReply(leaf: String)
    }
}

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

    private suspend fun nextPlan(group: String, host: String, attempted: Set<String>, ruleId: String, hostRetry: Boolean): FailoverPlan {
        val plan = resolvePlan(group, host)
        if (plan.remaining(attempted, hostRetry).isNotEmpty() || ruleId == "dialer") return plan
        if (withTimeoutOrNull(plan.failoverTimeoutMs.toLong().coerceAtLeast(1L)) { awaitReady(); true } != true) return plan
        return resolvePlan(group, host)
    }

    private fun FailoverPlan.remaining(attempted: Set<String>, hostRetry: Boolean): List<String> {
        val ordered = if (hostRetry) (hostRetryCandidates ?: candidates).filter { it !in BUILTIN_GROUP_TARGET_TAGS }
            else candidates
        return ordered.distinct().filter { it !in attempted }.take(FallbackPolicy.MAX_DIAL_ATTEMPTS - attempted.size)
    }

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
            var hostRetry = false
            while (isActive && closed == false && entry.closed.get() == false && flight.canReplay) {
                val plan = nextPlan(spec.groupTag, destination.host, attempted, ruleId, hostRetry)
                val leaf = plan.remaining(attempted, hostRetry).firstOrNull() ?: break
                attempted += leaf
                val socket = Socket()
                track(socket)
                currentUpstream.set(socket)
                if (entry.closed.get()) release(socket)
                val timeout = plan.failoverTimeoutMs.coerceAtLeast(1)
                val deadline = deadlineJob(socket, nowElapsedRealtimeMs() + timeout)
                var setupComplete = false
                var sender: Job? = null
                val retryForHost = AtomicReference<Boolean?>(null)
                val ioFailure = AtomicReference<TransferFailure?>()
                val observe = plan.watchResponse && leaf !in BUILTIN_GROUP_TARGET_TAGS
                val responseWait = UploadResponseWait(
                    scope = this@coroutineScope,
                    entry = entry,
                    timeoutMs = plan.stallTimeoutsMs[leaf] ?: plan.stallTimeoutMs,
                    enabled = observe,
                    creditReply = { (plan.owner as? FailoverPlan.ReplyEvidence)?.creditReply(leaf) },
                    isOpen = { isActive && closed == false && entry.closed.get() == false },
                    retry = { forHost ->
                        retryForHost.set(forHost)
                        if (flight.canReplay) socket.close() else entry.close()
                    },
                    closeBlockedWrite = { host ->
                        ioFailure.set(TransferFailure(SocketTimeoutException("Upstream write timed out"), listOfNotNull(host)))
                        socket.close()
                    },
                    nowElapsedRealtimeMs = nowElapsedRealtimeMs,
                ) { host, strong, pending, applyIfWaiting ->
                    onStalledUpload(spec.groupTag, leaf, plan.owner, host, strong, pending, applyIfWaiting)
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
                            responseWait.finishAtEof(EOFException("Upstream stream ended"), flight.established, ioFailure)
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
                    responseWait.finish()
                    release(socket)
                    withContext(NonCancellable) { sender?.cancelAndJoin() }
                    currentUpstream.compareAndSet(socket, null)
                    entry.select("", emptySet())
                }
                val requestedRetry = retryForHost.get()
                if (requestedRetry != null) hostRetry = requestedRetry
                else if (isActive && !closed && !entry.closed.get()) ioFailure.get()?.let { (error, hosts) ->
                    val strong = FallbackPolicy.isStrongFailure(error)
                    val affected = strong || (hosts.isNotEmpty() && error is EOFException)
                    if (!flight.established || affected) {
                        if (observe && affected) hostRetry = true
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

    private class UdpLane(client: Socket, groupTag: String, onClose: () -> Unit) {
        val incoming = Channel<ByteArray>(8)
        val currentControl = AtomicReference<Socket?>(null)
        val currentUdp = AtomicReference<RelayDatagramTransport?>(null)
        val entry = RelayConnectionEntry("udp", client.inetAddress.hostAddress.orEmpty(), client.port, groupTag) {
            incoming.cancel()
            runCatching { currentUdp.get()?.close() }
            runCatching { currentControl.get()?.close() }
            onClose()
        }
    }

    private suspend fun handleUdp(spec: FallbackRelaySpec, client: Socket, requested: SocksAddress, ruleId: String) = coroutineScope {
        val local = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        track(local)
        val lanes = ConcurrentHashMap<Destination, UdpLane>()
        val associationKey = requested.destination()
        var isolated: Boolean? = null
        var endpoint = if (requested.port == 0) null else InetSocketAddress(client.inetAddress, requested.port)
        client.soTimeout = 0
        try {
            reply(client.getOutputStream(), 0, local.localPort)
            val association = coroutineContext[Job]
            launch(relayIo) {
                try { while (client.getInputStream().read() >= 0) { /* Association lifetime. */ } }
                catch (_: IOException) { /* Control socket ended. */ }
                finally {
                    association?.cancel()
                    local.close()
                }
            }
            val buffer = ByteArray(65_535)
            val packet = DatagramPacket(buffer, buffer.size)
            while (isActive && !closed) {
                packet.length = buffer.size
                local.receive(packet)
                if (!packet.address.isLoopbackAddress || packet.address != client.inetAddress) continue
                val source = InetSocketAddress(packet.address, packet.port)
                if (endpoint != null && endpoint != source) continue
                val data = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                try { parseSocksDatagram(data) } catch (_: IOException) { continue }
                endpoint = source
                val address = datagramTarget(data)
                val target = address.destination()
                val separate = isolated ?: resolvePlan(spec.groupTag, target.host).watchResponse.also { isolated = it }
                val key = if (separate) target else associationKey
                val active = lanes[key]?.takeUnless { it.entry.closed.get() }
                val lane = active ?: UdpLane(client, spec.groupTag) {
                    if (!separate) local.close()
                }.also { created ->
                    address.describe(created.entry)
                    created.entry.ruleId = ruleId
                    register(created.entry)
                    lanes[key] = created
                    launch(relayIo) {
                        relayUdpLane(created, target.host, separate) { reply ->
                            local.send(DatagramPacket(reply, reply.size, source))
                        }
                    }.invokeOnCompletion {
                        created.entry.close()
                        unregister(created.entry)
                        lanes.remove(key, created)
                    }
                }
                lane.entry.recordUpload(data.size)
                // Isolate backpressure between destinations; a shared upstream retains ordinary queue ordering.
                if (separate) lane.incoming.trySend(data) else lane.incoming.send(data)
            }
        } finally {
            release(local)
            runCatching { client.close() }
            lanes.values.forEach { it.entry.close() }
        }
    }

    private suspend fun relayUdpLane(lane: UdpLane, planHost: String, isolated: Boolean, deliver: (ByteArray) -> Unit) = coroutineScope {
        val entry = lane.entry
        val attempted = mutableSetOf<String>()
        var hostRetry = false
        while (isActive && !closed && !entry.closed.get()) {
            val first = lane.incoming.receiveCatching().getOrNull() ?: break
            val flight = FirstFlight()
            flight.retain(first)
            while (isActive && !closed && !entry.closed.get() && flight.canReplay) {
                val plan = nextPlan(entry.groupTag, planHost, attempted, entry.ruleId, hostRetry)
                val leaf = plan.remaining(attempted, hostRetry).firstOrNull() ?: break
                attempted += leaf
                val control = Socket()
                track(control)
                lane.currentControl.set(control)
                if (entry.closed.get()) release(control)
                val timeout = plan.failoverTimeoutMs.coerceAtLeast(1)
                val deadline = deadlineJob(control, nowElapsedRealtimeMs() + timeout)
                var remote: RelayDatagramTransport? = null
                var sender: Job? = null
                var remoteControlReader: Job? = null
                val retryForHost = AtomicReference<Boolean?>(null)
                val ioFailure = AtomicReference<TransferFailure?>()
                val observe = plan.watchResponse && leaf !in BUILTIN_GROUP_TARGET_TAGS
                val responseWait = UploadResponseWait(
                    scope = this@coroutineScope,
                    entry = entry,
                    timeoutMs = plan.stallTimeoutsMs[leaf] ?: plan.stallTimeoutMs,
                    enabled = observe,
                    creditReply = { (plan.owner as? FailoverPlan.ReplyEvidence)?.creditReply(leaf) },
                    isOpen = { isActive && closed == false && entry.closed.get() == false },
                    retry = { forHost ->
                        retryForHost.set(forHost)
                        sender?.cancel()
                        remote?.close()
                    },
                    closeBlockedWrite = { host ->
                        ioFailure.set(TransferFailure(SocketTimeoutException("Upstream write timed out"), listOfNotNull(host)))
                        remote?.close()
                    },
                    nowElapsedRealtimeMs = nowElapsedRealtimeMs,
                ) { host, strong, pending, applyIfWaiting ->
                    onStalledUpload(entry.groupTag, leaf, plan.owner, host, strong, pending, applyIfWaiting)
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
                    lane.currentUdp.set(upstream)
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
                            if (isActive && control.isClosed == false) {
                                if (e is EOFException) responseWait.finishAtEof(e, flight.established, ioFailure)
                                else ioFailure.compareAndSet(null, TransferFailure(e, responseWait.requestHosts()))
                            }
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
                                if (isActive && !closed && !entry.closed.get() && retryForHost.get() == null && !control.isClosed) {
                                    // A native datagram send can fail independently of other destinations.
                                    if (uotVersion == 0 && ioFailure.get() == null && responseWait.hasOtherPending(target)) {
                                        val strong = FallbackPolicy.isStrongFailure(e)
                                        onCandidateFailed(entry.groupTag, leaf, plan.owner, target.host, strong, payload && strong)
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
                        for (data in lane.incoming) {
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
                        responseWait.responded(datagramTarget(data).destination(), data.size, flight)?.let { host ->
                            onCandidateConnected(entry.groupTag, leaf, plan.owner, host)
                        }
                        try { deliver(data) } catch (e: IOException) {
                            entry.close()
                            throw e
                        }
                    }
                } catch (e: IOException) {
                    if (remote != null && control.isClosed == false) {
                        if (e is EOFException) responseWait.finishAtEof(e, flight.established, ioFailure)
                        else ioFailure.compareAndSet(null, TransferFailure(e, responseWait.requestHosts()))
                    }
                } finally {
                    deadline.cancel()
                    responseWait.finish()
                    lane.currentUdp.getAndSet(null)?.let(::release)
                    release(control)
                    withContext(NonCancellable) {
                        sender?.cancelAndJoin()
                        remoteControlReader?.cancelAndJoin()
                    }
                    lane.currentControl.compareAndSet(control, null)
                    entry.select("", emptySet())
                }
                val requestedRetry = retryForHost.get()
                if (requestedRetry != null) hostRetry = requestedRetry
                else if (isActive && !closed && !entry.closed.get()) {
                    if (flight.established) hostRetry = false
                    ioFailure.get()?.let { (error, hosts) ->
                        val strong = FallbackPolicy.isStrongFailure(error)
                        val affected = (isolated && strong) || (hosts.isNotEmpty() && (strong || error is EOFException))
                        if (strong || !flight.established || affected) {
                            val attributed = when {
                                isolated && strong -> listOf(planHost)
                                strong && hosts.size > 1 -> emptyList()
                                else -> hosts
                            }
                            if (observe && isolated && affected) hostRetry = true
                            for (host in attributed.ifEmpty { listOf("") }) {
                                onCandidateFailed(entry.groupTag, leaf, plan.owner, host, strong, affected && host.isNotEmpty())
                            }
                        }
                    }
                }
            }
            if (!isolated || !flight.established) break
            // Begin the next flight with fresh client datagrams. The answered flight stays on its original leaf.
            val previous = attempted.last()
            attempted.clear()
            if (hostRetry) attempted += previous
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
        private val creditReply: () -> Unit,
        private val isOpen: () -> Boolean,
        private val retry: (Boolean) -> Unit,
        private val closeBlockedWrite: (String?) -> Unit,
        private val nowElapsedRealtimeMs: () -> Long,
        private val confirm: suspend (String, Boolean, Boolean, (() -> Unit) -> Boolean) -> Boolean,
    ) : RelayConnectionEntry.Attempt {
        private class Observation(
            val target: Destination, var due: Long, val latestDue: Long = due, val possibleReply: Boolean = false,
        ) {
            var check: Job? = null
            var expired = false
            var sent = false

            fun restartIdle(deadline: Long) {
                check?.cancel()
                check = null
                expired = false
                due = deadline
            }

            fun advanceResponse(deadline: Long) {
                if (!expired) due = minOf(deadline, latestDue)
            }
        }

        private val window = timeoutMs.toLong().coerceAtLeast(1L)
        private val datagrams = entry.network == "udp"
        private val pending = mutableMapOf<Destination, Observation>()
        // A port shared by several domains stays ambiguous, including for delayed duplicate replies.
        private val domainByPort = mutableMapOf<Int, Destination?>()
        private val numericTargets = mutableSetOf<Destination>()
        private var idle: Observation? = null
        private var idleReplyUntil = 0L
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
                // A prompt answer to an idle peer still opens a destination observation.
                val answersPush = !datagrams && now < idleReplyUntil
                idleReplyUntil = 0L
                writing = target
                writingPayload = expectsReply
                writeDue = now + window
                idle?.check?.cancel()
                idle = null
                val exchange = if (enabled && expectsReply) {
                    if (pending[target]?.possibleReply == true && !answersPush) pending.remove(target)?.check?.cancel()
                    pending.getOrPut(target) { Observation(target, writeDue, writeDue + window, possibleReply = answersPush) }
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
                        if (!datagrams || (expectsReply && exchange?.sent != true)) markMeaningfulProgress()
                    }
                    if (open && exchange != null && pending[target] === exchange) {
                        if (sent) {
                            exchange.sent = true
                            if (!datagrams) {
                                exchange.advanceResponse(lastProgress + window)
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

        fun responded(reply: Destination, size: Int, flight: FirstFlight): String? {
            synchronized(entry) {
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
                if (!datagrams && !first && answered == null && idle?.let { lastProgress >= it.due } == true) {
                    idleReplyUntil = lastProgress + window
                } else if (answered != null) {
                    idleReplyUntil = 0L
                }
                markMeaningfulProgress()
                entry.recordDownload(size)
                val previous = lastReply
                val source = target ?: reply
                lastReply = source
                if (pending.isEmpty()) observeIdle(source)
                if (target == null) return null
                if (answered != null || first || previous != target || lastProgress - lastSuccessAt >= window) {
                    lastSuccessAt = lastProgress
                    return target.host
                }
            }
            // Credit intervening bytes outside the entry lock; the full host callback stays periodic.
            creditReply()
            return null
        }

        private fun observeIdle(target: Destination) {
            if (!enabled || drain != null) return
            val deadline = lastProgress + window
            val current = idle
            if (current?.target == target) current.restartIdle(deadline) else {
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
                if (!datagrams && responsePending && observation.due <= now && writing == observation.target) {
                    observation.advanceResponse(writeDue)
                }
                if (observation.due <= now) {
                    observation.expired = true
                    if (observation.check == null) startCheck(observation, responsePending)
                    if (!responsePending) return
                    observation.due = now + FallbackPolicy.WEAK_CHECK_GAP_MS
                }
                next = minOf(next, observation.due)
            }
            if (drain == null) {
                pending.values.forEach { visit(it, true) }
                idle?.takeUnless { it.expired }?.let { visit(it, false) }
            }
            arm(next)
        }

        private fun startCheck(observation: Observation, responsePending: Boolean) {
            val strong = !datagrams && responsePending && !observation.possibleReply && quiet()
            observation.check = scope.launch(Dispatchers.Default) {
                val self = coroutineContext[Job]
                fun current() = open && drain == null && isOpen() && observation.check === self &&
                    (if (responsePending) pending[observation.target] === observation else idle === observation)
                try {
                    var resumed = false
                    suspend fun report(asStrong: Boolean) = confirm(observation.target.host, asStrong, responsePending) { signal ->
                        synchronized(entry) {
                            if (!current()) false else if (asStrong && !quiet()) {
                                resumed = true
                                false
                            } else {
                                signal()
                                true
                            }
                        }
                    }
                    val replace = report(strong)
                    if (resumed) {
                        report(false)
                        return@launch
                    }
                    // A weak TCP exchange changes the next dial. Confirmed demotions have their own cleanup path.
                    if (replace && (strong || datagrams || !responsePending)) {
                        // Idle streams and shared associations get a full progress sample before retirement.
                        val sample = synchronized(entry) { meaningfulProgress }
                        if (datagrams || !responsePending) delay(FallbackPolicy.CLOSE_SAMPLE_DELAY_MS)
                        synchronized(entry) {
                            if (current() && sample == meaningfulProgress && quiet()) retire(responsePending)
                        }
                    }
                } finally {
                    synchronized(entry) { if (observation.check === self) observation.check = null }
                }
            }
        }

        // Both demotion samples and retirement run under the entry lock used for byte progress.
        private fun quiet() = writing == null && nowElapsedRealtimeMs() - meaningfulProgressAt >= window

        private fun isIdle(): Boolean = open && drain == null && isOpen() && quiet() &&
            idle?.let { nowElapsedRealtimeMs() >= it.due } == true

        override fun isStalled(): Boolean = isIdle() || (open && drain == null && isOpen() &&
            pending.values.any { it.expired } && quiet())

        override fun retire() = retire(false)

        private fun retire(forHost: Boolean) {
            stop()
            retry(forHost)
        }

        private fun stop() {
            open = false
            timer?.cancel()
            idle?.check?.cancel()
            pending.values.forEach { it.check?.cancel() }
            drain?.complete(Unit)
        }

        fun finish() = synchronized(entry) { stop() }

        suspend fun finishAtEof(error: EOFException, established: Boolean, failure: AtomicReference<TransferFailure?>) {
            fun finishEof() {
                // Settle the current write so an established stream attributes EOF only to forwarded requests.
                val hosts = pending.values.filter { !established || it.sent }.map { it.target.host }.distinct()
                if (hosts.isNotEmpty() || !established) failure.updateAndGet { previous ->
                    if (previous != null && FallbackPolicy.isStrongFailure(previous.error)) previous
                    else TransferFailure(error, hosts)
                }
                stop()
            }
            val completion = synchronized(entry) {
                if (open && enabled && established && writing != null) {
                    idle?.check?.cancel()
                    pending.values.forEach { it.check?.cancel() }
                    CompletableDeferred<Unit>().also { drain = it }
                } else {
                    finishEof()
                    null
                }
            }
            if (completion != null) withContext(NonCancellable) {
                completion.await()
                synchronized(entry) { finishEof() }
            }
        }
    }

    private companion object {
        const val HANDSHAKE_TIMEOUT_MS = 5_000
    }
}
