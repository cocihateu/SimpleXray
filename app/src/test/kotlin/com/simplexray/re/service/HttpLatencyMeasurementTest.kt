package com.simplexray.re.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class HttpLatencyMeasurementTest {
    @Test
    fun `only successful 2xx statuses are healthy`() {
        for (code in 0..599) assertEquals("HTTP $code", code in 200..299, HttpLatencyMeasurement.isHealthyStatus(code))
        assertFalse(HttpLatencyMeasurement.isHealthyStatus(-1))
        assertFalse(HttpLatencyMeasurement.isHealthyStatus(Int.MAX_VALUE))
    }

    @Test
    fun `invalid and excessive timeouts cannot make probes unbounded`() {
        assertEquals(5_000L, HttpLatencyMeasurement.boundedTimeoutMs(-1))
        assertEquals(5_000L, HttpLatencyMeasurement.boundedTimeoutMs(0))
        assertEquals(1L, HttpLatencyMeasurement.boundedTimeoutMs(1))
        assertEquals(2_000L, HttpLatencyMeasurement.boundedTimeoutMs(2_000))
        assertEquals(60_000L, HttpLatencyMeasurement.boundedTimeoutMs(Int.MAX_VALUE))
    }

    @Test
    fun `loopback HEAD accepts success but rejects proxy errors and redirects`() = runBlocking {
        val status = AtomicInteger(204)
        val requests = AtomicInteger()
        val client = OkHttpClient()
        val clock = AtomicLong()
        try {
            HttpServer { socket ->
                requests.incrementAndGet()
                val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                assertTrue(input.readLine().startsWith("HEAD /probe HTTP/1.1"))
                while (input.readLine()?.isNotEmpty() == true) { /* Consume the full request. */ }
                socket.getOutputStream().write((
                    "HTTP/1.1 ${status.get()} Test\r\n" +
                        "Location: /portal\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    ).toByteArray(Charsets.US_ASCII))
            }.use { server ->
                for (code in listOf(200, 204, 299, 301, 302, 304, 307, 400, 401, 403, 407, 500, 503)) {
                    status.set(code)
                    val latency = HttpLatencyMeasurement.measure(client, server.url, 2_000) { clock.getAndAdd(7L) }
                    if (code in 200..299) assertEquals(7L, latency) else assertNull("HTTP $code", latency)
                }
                assertEquals(13, requests.get())
            }
            assertNull(HttpLatencyMeasurement.measure(client, "not a URL"))
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }

    @Test
    fun `deadline cancels a server that never returns response headers`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun canceled(call: Call) { cancelled.complete(Unit) }
        }).build()
        try {
            HttpServer { socket ->
                socket.getInputStream().read()
                started.complete(Unit)
                assertTrue(release.await(5, TimeUnit.SECONDS))
            }.use { server ->
                try {
                    val result = async { HttpLatencyMeasurement.measure(client, server.url, 500) { 1L } }
                    withTimeout(5_000) { started.await() }
                    assertNull(withTimeout(5_000) { result.await() })
                    withTimeout(5_000) { cancelled.await() }
                } finally {
                    release.countDown()
                }
            }
        } finally {
            release.countDown()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }

    @Test
    fun `coroutine cancellation wins over a late response without duplicate resume`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val lateResponse = CountDownLatch(1)
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun canceled(call: Call) { cancelled.complete(Unit) }
        }).build()
        try {
            HttpServer { socket ->
                socket.getInputStream().read()
                started.complete(Unit)
                assertTrue(release.await(5, TimeUnit.SECONDS))
                try {
                    socket.getOutputStream().write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.US_ASCII))
                } catch (_: SocketException) {
                    // The cancelled call may already have reset its loopback socket.
                } finally {
                    lateResponse.countDown()
                }
            }.use { server ->
                try {
                    val measurement = async(Dispatchers.Default) {
                        HttpLatencyMeasurement.measure(client, server.url, 5_000) { 1L }
                    }
                    withTimeout(5_000) { started.await() }
                    measurement.cancelAndJoin()
                    withTimeout(5_000) { cancelled.await() }
                    assertTrue(measurement.isCancelled)
                    release.countDown()
                    assertTrue(lateResponse.await(5, TimeUnit.SECONDS))
                } finally {
                    release.countDown()
                }
            }
        } finally {
            release.countDown()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }

    private class HttpServer(private val serve: (Socket) -> Unit) : Closeable {
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newCachedThreadPool()
        private val sockets = CopyOnWriteArrayList<Socket>()
        private val failures = ConcurrentLinkedQueue<Throwable>()
        @Volatile private var closing = false
        val url: String get() = "http://127.0.0.1:${server.localPort}/probe"

        init {
            executor.execute {
                while (closing == false) {
                    val socket = try { server.accept() } catch (_: SocketException) { break }
                    sockets += socket
                    executor.execute {
                        try {
                            socket.soTimeout = 5_000
                            socket.use(serve)
                        } catch (e: Throwable) {
                            if (closing == false) failures.add(e)
                        }
                    }
                }
            }
        }

        override fun close() {
            closing = true
            server.close()
            sockets.forEach { runCatching { it.close() } }
            executor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            if (failures.isNotEmpty()) throw AssertionError("Loopback HTTP server failed", failures.first())
        }
    }
}
