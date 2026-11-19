package com.simplexray.re.service

import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
import java.util.concurrent.atomic.AtomicLong

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
)

/** Routes initial TCP/UDP exchanges through ordered group candidates. */
internal class FallbackRelayServer(
    private val specs: List<FallbackRelaySpec>,
    private val nodeEntryPort: Int,
    private val nodeUsers: Map<String, String>,
    private val nodePassword: String,
    private val resolvePlan: (String) -> FailoverPlan,
    private val onCandidateFailed: (String, String) -> Unit,
    private val onCandidateConnected: (String, String) -> Unit,
) : Closeable {
    private val listeners = CopyOnWriteArrayList<ServerSocket>()
    private val jobs = CopyOnWriteArrayList<Job>()
    private val resources = ConcurrentHashMap.newKeySet<Closeable>()
    @Volatile private var closed = false

    fun start(scope: CoroutineScope) {
        specs.forEach { spec ->
            val server = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress("127.0.0.1", spec.port))
            }
            listeners += server
            jobs += scope.launch(Dispatchers.IO) {
                while (isActive && closed == false) {
                    val client = try { server.accept() } catch (_: IOException) { break }
                    track(client)
                    launch {
                        try {
                            handleClient(spec, client)
                        } catch (_: IOException) {
                            // Session teardown closes both local and upstream sockets.
                        } finally {
                            release(client)
                        }
                    }
                }
            }
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

    private suspend fun handleClient(spec: FallbackRelaySpec, client: Socket) {
        client.tcpNoDelay = true
        client.soTimeout = HANDSHAKE_TIMEOUT_MS
        val input = DataInputStream(client.getInputStream())
        val output = client.getOutputStream()
        if (authenticateServer(input, output, spec) == false) return
        val version = input.readUnsignedByte()
        val command = input.readUnsignedByte()
        input.readUnsignedByte()
        val address = readAddress(input)
        if (version != 5) return
        when (command) {
            1 -> handleTcp(spec, client, address)
            3 -> handleUdp(spec, client)
            else -> reply(output, 7)
        }
    }

    private suspend fun handleTcp(spec: FallbackRelaySpec, client: Socket, target: SocksAddress) = coroutineScope {
        client.soTimeout = 0
        reply(client.getOutputStream(), 0)
        val currentUpstream = AtomicReference<Socket?>(null)
        val entry = RelayConnectionEntry("tcp", client.inetAddress.hostAddress.orEmpty(), client.port, spec.groupTag) {
            runCatching { currentUpstream.get()?.close() }
            runCatching { client.close() }
        }
        target.describe(entry)
        RelayConnectionRegistry.register(entry)
        val flight = FirstFlight()
        val localEnded = AtomicBoolean(false)
        val reader = launch(Dispatchers.IO) {
            val buffer = ByteArray(32 * 1024)
            try {
                while (isActive) {
                    val count = client.getInputStream().read(buffer)
                    if (count < 0) break
                    val chunk = buffer.copyOf(count)
                    flight.firstPayload.complete(SystemClock.elapsedRealtime())
                    entry.uploadBytes.addAndGet(count.toLong())
                    while (isActive && flight.pending.offer(chunk, 100, TimeUnit.MILLISECONDS) == false) {
                        // Bounded buffering follows upstream consumption.
                    }
                }
            } catch (_: IOException) {
                entry.close()
            } finally {
                localEnded.set(true)
            }
        }
        try {
            val attempted = mutableSetOf<String>()
            while (isActive && closed == false && entry.closed.get() == false && flight.replayable) {
                val plan = resolvePlan(spec.groupTag)
                val leaf = plan.candidates.firstOrNull { it !in attempted } ?: break
                attempted += leaf
                val socket = Socket()
                track(socket)
                currentUpstream.set(socket)
                entry.leafTag = leaf
                val beganAt = SystemClock.elapsedRealtime()
                var deadline = deadlineJob(socket, beganAt + plan.failoverTimeoutMs)
                var sender: Job? = null
                var responseWatch: Job? = null
                val pendingResponseAt = AtomicLong()
                val upstreamFailed = AtomicBoolean(false)
                var responseDelivered = false
                try {
                    connectNode(socket, leaf, 1, target, plan.failoverTimeoutMs)
                    deadline.cancel()
                    deadline = launch(Dispatchers.Default) {
                        val firstPayloadAt = flight.firstPayload.await()
                        val dueAt = maxOf(beganAt, firstPayloadAt) + plan.failoverTimeoutMs
                        delay((dueAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
                        socket.close()
                    }
                    socket.soTimeout = 0
                    sender = launch(Dispatchers.IO) {
                        try {
                            val output = socket.getOutputStream()
                            flight.replay().forEach { output.write(it) }
                            while (isActive) {
                                val chunk = flight.pending.poll(100, TimeUnit.MILLISECONDS)
                                if (chunk == null) {
                                    if (localEnded.get()) {
                                        socket.shutdownOutput()
                                        break
                                    }
                                    continue
                                }
                                flight.retain(chunk)
                                if (flight.established) pendingResponseAt.compareAndSet(0L, SystemClock.elapsedRealtime())
                                output.write(chunk)
                            }
                        } catch (_: IOException) {
                            upstreamFailed.set(true)
                            runCatching { socket.close() }
                        }
                    }
                    val buffer = ByteArray(32 * 1024)
                    val count = socket.getInputStream().read(buffer)
                    if (count < 0) throw EOFException("Upstream closed before response")
                    deadline.cancel()
                    flight.establish()
                    try {
                        client.getOutputStream().write(buffer, 0, count)
                    } catch (e: IOException) {
                        entry.close()
                        throw e
                    }
                    entry.downloadBytes.addAndGet(count.toLong())
                    responseDelivered = true
                    onCandidateConnected(spec.groupTag, leaf)
                    responseWatch = responseDeadline(socket, pendingResponseAt, plan.failoverTimeoutMs)
                    while (isActive && entry.closed.get() == false) {
                        val size = socket.getInputStream().read(buffer)
                        if (size < 0) break
                        pendingResponseAt.set(0L)
                        try {
                            client.getOutputStream().write(buffer, 0, size)
                        } catch (e: IOException) {
                            entry.close()
                            throw e
                        }
                        entry.downloadBytes.addAndGet(size.toLong())
                    }
                    if (upstreamFailed.get()) throw IOException("Upstream upload failed")
                    break
                } catch (_: IOException) {
                    if (closed == false && entry.closed.get() == false && localEnded.get() == false) {
                        onCandidateFailed(spec.groupTag, leaf)
                    }
                    if (responseDelivered || localEnded.get() || entry.closed.get() || flight.replayable == false) break
                } finally {
                    deadline.cancel()
                    responseWatch?.cancel()
                    release(socket)
                    sender?.cancelAndJoin()
                    currentUpstream.compareAndSet(socket, null)
                }
            }
        } finally {
            entry.close()
            RelayConnectionRegistry.unregister(entry.id)
            reader.cancelAndJoin()
        }
    }

    private suspend fun handleUdp(spec: FallbackRelaySpec, client: Socket) = coroutineScope {
        val local = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        track(local)
        val currentControl = AtomicReference<Socket?>(null)
        val currentUdp = AtomicReference<DatagramSocket?>(null)
        val endpoint = AtomicReference<InetSocketAddress?>(null)
        val entry = RelayConnectionEntry("udp", client.inetAddress.hostAddress.orEmpty(), client.port, spec.groupTag) {
            runCatching { local.close() }
            runCatching { currentUdp.get()?.close() }
            runCatching { currentControl.get()?.close() }
            runCatching { client.close() }
        }
        RelayConnectionRegistry.register(entry)
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
        val controlReader = launch(Dispatchers.IO) {
            try { while (client.getInputStream().read() >= 0) { /* Association lifetime. */ } }
            catch (_: IOException) { /* Control socket ended. */ }
            finally { entry.close() }
        }
        val reader = launch(Dispatchers.IO) {
            val buffer = ByteArray(65_535)
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                while (isActive) {
                    packet.length = buffer.size
                    local.receive(packet)
                    endpoint.set(InetSocketAddress(packet.address, packet.port))
                    val data = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                    if (entry.destinationPort == 0) {
                        val input = DataInputStream(ByteArrayInputStream(data))
                        input.readUnsignedShort()
                        input.readUnsignedByte()
                        readAddress(input).describe(entry)
                    }
                    flight.firstPayload.complete(SystemClock.elapsedRealtime())
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
            while (isActive && closed == false && entry.closed.get() == false && flight.replayable) {
                val plan = resolvePlan(spec.groupTag)
                val leaf = plan.candidates.firstOrNull { it !in attempted } ?: break
                attempted += leaf
                val control = Socket()
                track(control)
                currentControl.set(control)
                entry.leafTag = leaf
                val beganAt = SystemClock.elapsedRealtime()
                var deadline = deadlineJob(control, beganAt + plan.failoverTimeoutMs)
                var remote: DatagramSocket? = null
                var sender: Job? = null
                var responseDelivered = false
                try {
                    val bound = connectNode(control, leaf, 3, SocksAddress(1, ByteArray(4), 0), plan.failoverTimeoutMs)
                    val upstream = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
                    remote = upstream
                    track(upstream)
                    currentUdp.set(upstream)
                    upstream.connect(InetSocketAddress("127.0.0.1", bound.port))
                    deadline.cancel()
                    deadline = launch(Dispatchers.Default) {
                        val firstPayloadAt = flight.firstPayload.await()
                        delay((maxOf(beganAt, firstPayloadAt) + plan.failoverTimeoutMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
                        upstream.close()
                    }
                    sender = launch(Dispatchers.IO) {
                        try {
                            flight.replay().forEach { upstream.send(DatagramPacket(it, it.size)) }
                            while (isActive) {
                                val data = flight.pending.poll(100, TimeUnit.MILLISECONDS) ?: continue
                                flight.retain(data)
                                upstream.send(DatagramPacket(data, data.size))
                            }
                        } catch (_: IOException) {
                            upstream.close()
                        }
                    }
                    val bytes = ByteArray(65_535)
                    val packet = DatagramPacket(bytes, bytes.size)
                    while (isActive && entry.closed.get() == false) {
                        packet.length = bytes.size
                        upstream.receive(packet)
                        val clientEndpoint = endpoint.get() ?: continue
                        try {
                            local.send(DatagramPacket(packet.data, packet.offset, packet.length, clientEndpoint))
                        } catch (e: IOException) {
                            entry.close()
                            throw e
                        }
                        entry.downloadBytes.addAndGet(packet.length.toLong())
                        if (responseDelivered == false) {
                            deadline.cancel()
                            flight.establish()
                            responseDelivered = true
                            onCandidateConnected(spec.groupTag, leaf)
                        }
                    }
                    break
                } catch (_: IOException) {
                    if (closed == false && entry.closed.get() == false) onCandidateFailed(spec.groupTag, leaf)
                    if (responseDelivered || entry.closed.get() || flight.replayable == false) break
                } finally {
                    deadline.cancel()
                    remote?.let(::release)
                    release(control)
                    sender?.cancelAndJoin()
                    currentUdp.set(null)
                    currentControl.compareAndSet(control, null)
                }
            }
        } finally {
            entry.close()
            release(local)
            RelayConnectionRegistry.unregister(entry.id)
            reader.cancel()
            controlReader.cancel()
            reader.cancelAndJoin()
            controlReader.cancelAndJoin()
        }
    }

    private fun CoroutineScope.deadlineJob(socket: Socket, deadlineAt: Long) = launch(Dispatchers.Default) {
        delay((deadlineAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
        socket.close()
    }

    /** Starts a response deadline from new upload activity and leaves idle sessions open. */
    private fun CoroutineScope.responseDeadline(
        transport: Closeable,
        pendingSince: AtomicLong,
        timeoutMs: Int,
    ) = launch(Dispatchers.Default) {
        while (isActive) {
            delay(100L)
            val sentAt = pendingSince.get()
            if (sentAt > 0 && SystemClock.elapsedRealtime() - sentAt >= timeoutMs) {
                transport.close()
                break
            }
        }
    }

    private fun connectNode(socket: Socket, leaf: String, command: Int, target: SocksAddress, timeout: Int): SocksAddress {
        val user = nodeUsers[leaf] ?: throw IOException("Physical node entry missing")
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress("127.0.0.1", nodeEntryPort), timeout)
        socket.soTimeout = timeout
        val input = DataInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        output.write(byteArrayOf(5, 1, 2))
        if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != 2) throw IOException("SOCKS auth method")
        val userBytes = user.toByteArray()
        val passwordBytes = nodePassword.toByteArray()
        output.write(byteArrayOf(1, userBytes.size.toByte()) + userBytes + byteArrayOf(passwordBytes.size.toByte()) + passwordBytes)
        if (input.readUnsignedByte() != 1 || input.readUnsignedByte() != 0) throw IOException("SOCKS authentication")
        output.write(byteArrayOf(5, command.toByte(), 0, target.type.toByte()) + target.raw +
            byteArrayOf((target.port shr 8).toByte(), target.port.toByte()))
        val version = input.readUnsignedByte()
        val result = input.readUnsignedByte()
        input.readUnsignedByte()
        val bound = readAddress(input)
        if (version != 5 || result != 0) throw IOException("SOCKS target connection $result")
        return bound
    }

    private fun authenticateServer(input: DataInputStream, output: OutputStream, spec: FallbackRelaySpec): Boolean {
        if (input.readUnsignedByte() != 5) return false
        val methods = ByteArray(input.readUnsignedByte()).also(input::readFully)
        if (methods.any { it.toInt() == 2 } == false) {
            output.write(byteArrayOf(5, 0xff.toByte()))
            return false
        }
        output.write(byteArrayOf(5, 2))
        val version = input.readUnsignedByte()
        val user = ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.UTF_8)
        val password = ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.UTF_8)
        val accepted = version == 1 && user == spec.username && password == spec.password
        output.write(byteArrayOf(1, if (accepted) 0 else 1))
        return accepted
    }

    private fun readAddress(input: DataInputStream): SocksAddress {
        val type = input.readUnsignedByte()
        val raw = when (type) {
            1 -> ByteArray(4).also(input::readFully)
            4 -> ByteArray(16).also(input::readFully)
            3 -> {
                val length = input.readUnsignedByte()
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

    /** Retains the complete initial flight; larger transfers continue on their current transport. */
    private class FirstFlight {
        val pending = LinkedBlockingQueue<ByteArray>(8)
        val firstPayload = CompletableDeferred<Long>()
        private val initial = mutableListOf<ByteArray>()
        private var retainedBytes = 0
        @Volatile var established = false
            private set
        @Volatile var replayable = true
            private set

        @Synchronized fun replay(): List<ByteArray> = initial.toList()

        @Synchronized fun retain(bytes: ByteArray) {
            if (established || replayable == false) return
            if (retainedBytes + bytes.size > 256 * 1024) {
                replayable = false
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

    private companion object {
        const val HANDSHAKE_TIMEOUT_MS = 5_000
    }
}
