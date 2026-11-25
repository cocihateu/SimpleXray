package com.simplexray.re.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class FallbackRelayServerTest {
    @Test
    fun `failure before response replays complete ordered bytes with unchanged destination`() = runBlocking {
        val first = ByteArray(70_000) { (it * 31).toByte() }
        val suffix = ByteArray(40_000) { (255 - it).toByte() }
        val epoch = AtomicLong(11L)
        val failed = CompletableDeferred<Long>()
        val retry = CompletableDeferred<Unit>()
        val received = LinkedBlockingQueue<Pair<String, ByteArray>>()
        val connected = LinkedBlockingQueue<Pair<String, Long>>()
        val destination = domain("MiXeD.Example", 443)
        Harness(
            plan = { FailoverPlan("a", listOf("a", "b"), 2_000, watchResponse = false, generation = epoch.get()) },
            failed = { _, leaf, generation ->
                assertEquals("a", leaf)
                failed.complete(generation)
                retry.await()
            },
            connected = { _, leaf, generation -> connected.add(leaf to generation) },
        ) { attempt ->
            attempt.accept()
            val bytes = attempt.input.bytes(if (attempt.user == "a") first.size else first.size + suffix.size)
            received.add(attempt.user to bytes)
            if (attempt.user == "a") attempt.socket.setSoLinger(true, 0)
            else attempt.socket.getOutputStream().write(byteArrayOf(6, 7))
        }.use { harness ->
            val client = harness.client(destination).socket
            client.getOutputStream().write(first)
            assertEquals(11L, withTimeout(5_000) { failed.await() })
            // These bytes enter the queue while no sender exists. Half-close must not
            // suppress retry or discard the queued suffix.
            client.getOutputStream().write(suffix)
            client.shutdownOutput()
            epoch.set(22L)
            retry.complete(Unit)
            assertArrayEquals(byteArrayOf(6, 7), DataInputStream(client.getInputStream()).bytes(2))
            val a = received.poll(5, TimeUnit.SECONDS)!!
            val b = received.poll(5, TimeUnit.SECONDS)!!
            assertEquals("a", a.first)
            assertArrayEquals(first, a.second)
            assertEquals("b", b.first)
            assertArrayEquals(first + suffix, b.second)
            assertEquals("b" to 22L, connected.poll(5, TimeUnit.SECONDS))
            assertEquals(2, harness.attempts.size)
            harness.attempts.forEach { assertArrayEquals(destination, it.destination) }
        }
    }

    @Test
    fun `failure after one response byte never opens a backup socket`() = runBlocking {
        val closeRemote = CompletableDeferred<Unit>()
        val failures = AtomicInteger()
        Harness(failed = { _, _, _ -> failures.incrementAndGet() }) { attempt ->
            attempt.accept()
            if (attempt.user == "a") {
                attempt.input.bytes(3)
                attempt.socket.getOutputStream().write(22)
                closeRemote.await()
                attempt.socket.setSoLinger(true, 0)
            } else {
                attempt.socket.getOutputStream().write(99)
            }
        }.use { harness ->
            val client = harness.client().socket
            client.getOutputStream().write(byteArrayOf(1, 2, 3))
            assertEquals(22, client.getInputStream().read())
            closeRemote.complete(Unit)
            assertEnded(client)
            assertEquals(listOf("a"), harness.attempts.map { it.user })
            assertEquals(0, failures.get())
        }
    }

    @Test
    fun `retention cap forbids suffix-only replay`() {
        val payload = ByteArray(300 * 1024) { it.toByte() }
        Harness { attempt ->
            attempt.accept()
            assertEquals("a", attempt.user)
            assertArrayEquals(payload, attempt.input.bytes(payload.size))
            attempt.socket.setSoLinger(true, 0)
        }.use { harness ->
            val client = harness.client().socket
            client.getOutputStream().write(payload)
            assertEnded(client)
            assertEquals(listOf("a"), harness.attempts.map { it.user })
        }
    }

    @Test
    fun `direct server-first streams preserve IPv4 and IPv6 targets`() {
        val destinations = listOf(
            byteArrayOf(1, 192.toByte(), 0, 2, 19, 0, 25),
            byteArrayOf(4) + ByteArray(16) { it.toByte() } + byteArrayOf(0, 25),
        )
        val probes = AtomicInteger()
        Harness(
            plan = { FailoverPlan("a", listOf("a"), 2_000, watchResponse = false, generation = 5L) },
            stalled = { _, _, _, _ -> probes.incrementAndGet() },
        ) { attempt ->
            attempt.accept()
            attempt.socket.getOutputStream().write(byteArrayOf(2, 2, 0))
            assertArrayEquals(byteArrayOf(1, 2), attempt.input.bytes(2))
            attempt.socket.getOutputStream().write(9)
        }.use { harness ->
            for (destination in destinations) {
                val client = harness.client(destination).socket
                assertArrayEquals(byteArrayOf(2, 2, 0), DataInputStream(client.getInputStream()).bytes(3))
                client.getOutputStream().write(byteArrayOf(1, 2))
                assertEquals(9, client.getInputStream().read())
            }
            assertEquals(0, probes.get())
            destinations.zip(harness.attempts).forEach { (expected, attempt) ->
                assertArrayEquals(expected, attempt.destination)
            }
        }
    }

    @Test
    fun `healthy stalled target remains open and keeps attempt generation`() = runBlocking {
        val healthyProbe = CompletableDeferred<Long>()
        val response = CompletableDeferred<Unit>()
        val epoch = AtomicLong(31L)
        val connected = LinkedBlockingQueue<Long>()
        Harness(
            plan = { FailoverPlan("a", listOf("a", "b"), 500, generation = epoch.get()) },
            stalled = { _, _, generation, _ -> healthyProbe.complete(generation) },
            connected = { _, _, generation -> connected.add(generation) },
        ) { attempt ->
            // Change controller state only after this physical attempt has its plan.
            epoch.set(32L)
            attempt.accept()
            attempt.input.bytes(1)
            response.await()
            attempt.socket.getOutputStream().write(8)
        }.use { harness ->
            val client = harness.client().socket
            client.getOutputStream().write(1)
            assertEquals(31L, withTimeout(5_000) { healthyProbe.await() })
            response.complete(Unit)
            assertEquals(8, client.getInputStream().read())
            assertEquals(31L, connected.poll(5, TimeUnit.SECONDS))
            assertEquals(listOf("a"), harness.attempts.map { it.user })
        }
    }

    @Test
    fun `malformed SOCKS requests do not terminate the listener`() {
        Harness { attempt ->
            attempt.accept()
            attempt.socket.getOutputStream().write(7)
        }.use { harness ->
            val requests = listOf(
                byteArrayOf(4, 1, 0) + domain("host", 443) to 1,
                byteArrayOf(5, 1, 1) + domain("host", 443) to 1,
                byteArrayOf(5, 1, 0, 9) to 8,
                byteArrayOf(5, 1, 0, 3, 0) to 8,
                byteArrayOf(5, 2, 0) + domain("host", 443) to 7,
            )
            for ((request, expected) in requests) {
                val client = harness.authenticated()
                client.getOutputStream().write(request)
                val reply = DataInputStream(client.getInputStream()).bytes(10)
                assertEquals(expected, reply[1].toInt())
                assertEnded(client)
            }
            assertEquals(7, harness.client().socket.getInputStream().read())
            assertEquals(1, harness.attempts.size)
        }
    }

    @Test
    fun `failed upstream handshake returns SOCKS failure instead of success`() {
        Harness { attempt -> attempt.accept(result = 5) }.use { harness ->
            val client = harness.authenticated()
            client.getOutputStream().write(byteArrayOf(5, 1, 0) + domain("host", 443))
            val reply = DataInputStream(client.getInputStream()).bytes(10)
            assertEquals(1, reply[1].toInt())
            assertEnded(client)
            assertEquals(listOf("a", "b"), harness.attempts.map { it.user })
        }
    }

    @Test
    fun `UDP rejects malformed packets and binds responses to one local endpoint`() = runBlocking {
        DatagramSocket(0, LOOPBACK).use { physical ->
            physical.soTimeout = 3_000
            Harness { attempt ->
                if (attempt.command == 3) {
                    attempt.accept(physical.localPort)
                    awaitCancellation()
                } else {
                    attempt.accept()
                    attempt.socket.getOutputStream().write(7)
                }
            }.use { harness ->
                val association = harness.client(byteArrayOf(1, 0, 0, 0, 0, 0, 0), command = 3)
                DatagramSocket(0, LOOPBACK).use { legitimate ->
                    DatagramSocket(0, LOOPBACK).use { other ->
                        legitimate.soTimeout = 3_000
                        other.soTimeout = 200
                        val data = byteArrayOf(0, 0, 0) + domain("Original.Example", 53) + byteArrayOf(1, 2, 255.toByte())
                        val bad = listOf(byteArrayOf(), byteArrayOf(0, 0, 0, 3, 255.toByte()), data.clone().apply { this[2] = 1 })
                        bad.forEach { send(other, association.port, it) }
                        send(legitimate, association.port, data)
                        val initial = receive(physical)
                        assertArrayEquals(data, initial.data.copyOf(initial.length))
                        // A second local sender must neither forward nor steal the return address.
                        send(other, association.port, data)
                        physical.soTimeout = 200
                        assertThrows(SocketTimeoutException::class.java) { receive(physical) }
                        physical.send(DatagramPacket(data, data.size, initial.socketAddress))
                        assertArrayEquals(data, receive(legitimate).let { it.data.copyOf(it.length) })
                        assertThrows(SocketTimeoutException::class.java) { receive(other) }
                        bad.forEach { send(legitimate, association.port, it) }
                        send(legitimate, association.port, data)
                        physical.soTimeout = 3_000
                        assertArrayEquals(data, receive(physical).let { it.data.copyOf(it.length) })
                        // Another association/protocol is unaffected by rejected datagrams.
                        assertEquals(7, harness.client().socket.getInputStream().read())
                    }
                }
            }
        }
    }

    @Test
    fun `scope cancellation closes blocked sockets and joins sessions`() = runBlocking {
        val request = CompletableDeferred<Unit>()
        Harness { attempt ->
            attempt.accept()
            attempt.input.bytes(1)
            request.complete(Unit)
            awaitCancellation()
        }.use { harness ->
            val client = harness.client().socket
            client.getOutputStream().write(1)
            withTimeout(5_000) { request.await() }
            harness.stopScope()
            assertEnded(client)
        }
    }

    @Test
    fun `initial flight cap and pinning cannot expose a partial replay`() {
        val flight = FallbackRelayServer.FirstFlight()
        val chunks = List(8) { part -> ByteArray(32 * 1024) { (it + part).toByte() } }
        chunks.forEach(flight::retain)
        assertTrue(flight.canReplay)
        chunks.zip(flight.replay()).forEach { (a, b) -> assertArrayEquals(a, b) }
        flight.retain(byteArrayOf(1))
        assertFalse(flight.canReplay)
        assertTrue(flight.replay().isEmpty())
        flight.retain(byteArrayOf(2, 3))
        assertTrue(flight.replay().isEmpty())
        val observed = FallbackRelayServer.FirstFlight()
        observed.retain(byteArrayOf(22, 3, 3))
        observed.establish()
        observed.retain(byteArrayOf(4))
        assertFalse(observed.canReplay)
        assertTrue(observed.replay().isEmpty())
    }

    @Test
    fun `healthy probes rearm with backoff only until a response arrives`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val delays = Channel<Pair<Long, CompletableDeferred<Unit>>>(Channel.UNLIMITED)
        val probes = AtomicInteger()
        val wait = FallbackRelayServer.UploadResponseWait(scope, 100, true, { true }, waitFor = { ms ->
            val gate = CompletableDeferred<Unit>()
            delays.send(ms to gate)
            gate.await()
        }) { probes.incrementAndGet() }
        try {
            wait.write { }
            val first = withTimeout(5_000) { delays.receive() }
            assertEquals(100L, first.first)
            first.second.complete(Unit)
            val second = withTimeout(5_000) { delays.receive() }
            assertEquals(200L, second.first)
            second.second.complete(Unit)
            val third = withTimeout(5_000) { delays.receive() }
            assertEquals(400L, third.first)
            assertEquals(2, probes.get())
            wait.responded()
            third.second.complete(Unit)
            assertNull(withTimeoutOrNull(100) { delays.receive() })
            assertEquals(2, probes.get())
        } finally {
            wait.close()
            scope.cancel()
        }
    }

    @Test
    fun `response during slow write rejects stale failure without rearming`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val delays = Channel<CompletableDeferred<Unit>>(Channel.UNLIMITED)
        val apply = CompletableDeferred<(() -> Unit) -> Boolean>()
        val finishWrite = CountDownLatch(1)
        val wait = FallbackRelayServer.UploadResponseWait(scope, 100, true, { true }, waitFor = {
            val gate = CompletableDeferred<Unit>()
            delays.send(gate)
            gate.await()
        }) { callback ->
            apply.complete(callback)
            awaitCancellation()
        }
        try {
            val writing = async(Dispatchers.IO) { wait.write { assertTrue(finishWrite.await(5, TimeUnit.SECONDS)) } }
            withTimeout(5_000) { delays.receive() }.complete(Unit)
            val lateFailure = withTimeout(5_000) { apply.await() }
            wait.responded()
            finishWrite.countDown()
            writing.await()
            var applied = false
            assertFalse(lateFailure { applied = true })
            assertFalse(applied)
            assertNull(withTimeoutOrNull(100) { delays.receive() })
        } finally {
            finishWrite.countDown()
            wait.close()
            scope.cancel()
        }
    }

    private class Harness(
        plan: () -> FailoverPlan = { FailoverPlan("a", listOf("a", "b"), 2_000, watchResponse = false) },
        failed: suspend (String, String, Long) -> Unit = { _, _, _ -> },
        connected: (String, String, Long) -> Unit = { _, _, _ -> },
        stalled: suspend (String, String, Long, (() -> Unit) -> Boolean) -> Unit = { _, _, _, _ -> },
        private val serve: suspend (Attempt) -> Unit,
    ) : Closeable {
        private val job = SupervisorJob()
        private val scope = CoroutineScope(job + Dispatchers.IO)
        private val physical = ServerSocket(0, 50, LOOPBACK)
        private val sockets = CopyOnWriteArrayList<Socket>()
        private val errors = ConcurrentLinkedQueue<Throwable>()
        private val port = ServerSocket(0, 50, LOOPBACK).use { it.localPort }
        val attempts = CopyOnWriteArrayList<Attempt>()
        @Volatile private var closing = false
        private val relay = FallbackRelayServer(
            listOf(FallbackRelaySpec("group", port, "u", "p")), physical.localPort,
            mapOf("a" to "a", "b" to "b"), "secret", emptyMap(), { plan() },
            failed, connected, stalled, nowElapsedRealtimeMs = { System.nanoTime() / 1_000_000L },
        )

        init {
            scope.launch {
                while (isActive) {
                    val socket = try { physical.accept() } catch (_: IOException) { break }
                    sockets += socket
                    launch {
                        try {
                            socket.use {
                                socket.soTimeout = 5_000
                                val input = DataInputStream(socket.getInputStream())
                                assertArrayEquals(byteArrayOf(5, 1, 2), input.bytes(3))
                                socket.getOutputStream().write(byteArrayOf(5, 2))
                                assertEquals(1, input.readUnsignedByte())
                                val user = input.bytes(input.readUnsignedByte()).toString(Charsets.UTF_8)
                                assertEquals("secret", input.bytes(input.readUnsignedByte()).toString(Charsets.UTF_8))
                                socket.getOutputStream().write(byteArrayOf(1, 0))
                                assertEquals(5, input.readUnsignedByte())
                                val command = input.readUnsignedByte()
                                assertEquals(0, input.readUnsignedByte())
                                val type = input.readUnsignedByte()
                                val raw = when (type) {
                                    1 -> input.bytes(4)
                                    4 -> input.bytes(16)
                                    3 -> input.readUnsignedByte().let { byteArrayOf(it.toByte()) + input.bytes(it) }
                                    else -> throw AssertionError("Unexpected address type $type")
                                }
                                val destination = byteArrayOf(type.toByte()) + raw + input.bytes(2)
                                val attempt = Attempt(socket, input, user, command, destination)
                                attempts += attempt
                                serve(attempt)
                            }
                        } catch (e: Throwable) {
                            if (closing == false && isActive) errors.add(e)
                        }
                    }
                }
            }
            relay.start(scope)
        }

        fun authenticated(): Socket {
            val socket = Socket(LOOPBACK, port)
            sockets += socket
            socket.soTimeout = 5_000
            socket.tcpNoDelay = true
            val input = DataInputStream(socket.getInputStream())
            socket.getOutputStream().write(byteArrayOf(5, 1, 2))
            assertArrayEquals(byteArrayOf(5, 2), input.bytes(2))
            socket.getOutputStream().write(byteArrayOf(1, 1, 117, 1, 112))
            assertArrayEquals(byteArrayOf(1, 0), input.bytes(2))
            return socket
        }

        fun client(destination: ByteArray = domain("Original.Example", 443), command: Int = 1): Client {
            val socket = authenticated()
            socket.getOutputStream().write(byteArrayOf(5, command.toByte(), 0) + destination)
            val reply = DataInputStream(socket.getInputStream()).bytes(10)
            assertEquals(0, reply[1].toInt())
            return Client(socket, ((reply[8].toInt() and 255) shl 8) or (reply[9].toInt() and 255))
        }

        suspend fun stopScope() {
            // The fake physical listener also blocks; the relay owns only its own sockets.
            physical.close()
            job.cancel()
            withTimeout(5_000) { job.join() }
        }

        override fun close() {
            closing = true
            relay.close()
            physical.close()
            sockets.forEach { runCatching { it.close() } }
            job.cancel()
            runBlocking { withTimeout(5_000) { job.join() } }
            if (errors.isNotEmpty()) throw AssertionError("Fake physical node failed", errors.first())
        }
    }

    private data class Client(val socket: Socket, val port: Int)

    private data class Attempt(
        val socket: Socket,
        val input: DataInputStream,
        val user: String,
        val command: Int,
        val destination: ByteArray,
    ) {
        fun accept(port: Int = 0, result: Int = 0) {
            socket.getOutputStream().write(byteArrayOf(5, result.toByte(), 0, 1, 127, 0, 0, 1, (port shr 8).toByte(), port.toByte()))
        }
    }

    private companion object {
        val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
        fun domain(host: String, port: Int): ByteArray {
            val bytes = host.toByteArray(Charsets.UTF_8)
            return byteArrayOf(3, bytes.size.toByte()) + bytes + byteArrayOf((port shr 8).toByte(), port.toByte())
        }
        fun DataInputStream.bytes(count: Int): ByteArray = ByteArray(count).also(::readFully)
        fun send(socket: DatagramSocket, port: Int, data: ByteArray) =
            socket.send(DatagramPacket(data, data.size, LOOPBACK, port))
        fun receive(socket: DatagramSocket): DatagramPacket = DatagramPacket(ByteArray(65_535), 65_535).also(socket::receive)
        fun assertEnded(socket: Socket) {
            try { assertEquals(-1, socket.getInputStream().read()) }
            catch (e: SocketTimeoutException) { throw AssertionError("Relay left failed stream open", e) }
            catch (_: IOException) { /* A reset is also a closed logical stream. */ }
        }
    }
}
