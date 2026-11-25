package com.simplexray.re.service

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoWSD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.net.URLDecoder

class ZashboardAssetsTest {
    @Test
    fun `root aliases redirect only to the static entry without credentials`() {
        val assets = ZashboardAssets { error("Redirect must not open assets") }
        for (path in listOf("/", "/ui")) {
            for (method in listOf(Method.GET, Method.HEAD)) {
                assets.serve(method, path)!!.use { response ->
                    assertEquals(Response.Status.FOUND, response.status)
                    assertEquals("/ui/", response.getHeader("Location"))
                    assertEquals("no-cache", response.getHeader("Cache-Control"))
                    assertEquals(0, response.data.readBytes().size)
                }
            }
        }
    }

    @Test
    fun `entry and update-sensitive files are served from assets without caching`() {
        val opened = mutableListOf<String>()
        val assets = ZashboardAssets { path ->
            opened += path
            ByteArrayInputStream("bundled".toByteArray())
        }
        for (path in listOf("", "index.html", "sw.js", "registerSW.js", "manifest.webmanifest", "THIRD_PARTY_NOTICES.md")) {
            assets.serve(Method.GET, "/ui/$path")!!.use { response ->
                assertEquals(Response.Status.OK, response.status)
                assertEquals("no-cache", response.getHeader("Cache-Control"))
                assertEquals("nosniff", response.getHeader("X-Content-Type-Options"))
                assertEquals("bundled", response.data.readBytes().toString(Charsets.UTF_8))
            }
        }
        assertEquals("zashboard/index.html", opened.first())
        assertTrue(opened.all { it.startsWith("zashboard/") })
    }

    @Test
    fun `all bundled file extensions have explicit types and hashed files are immutable`() {
        val expectedTypes = mapOf(
            "html" to "text/html; charset=utf-8",
            "js" to "text/javascript; charset=utf-8",
            "css" to "text/css; charset=utf-8",
            "json" to "application/json; charset=utf-8",
            "webmanifest" to "application/manifest+json; charset=utf-8",
            "svg" to "image/svg+xml; charset=utf-8",
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "webp" to "image/webp",
            "ico" to "image/x-icon",
            "woff" to "font/woff",
            "woff2" to "font/woff2",
            "ttf" to "font/ttf",
            "md" to "text/plain; charset=utf-8",
        )
        val assets = ZashboardAssets { ByteArrayInputStream(byteArrayOf(1)) }
        for ((extension, contentType) in expectedTypes) {
            assets.serve(Method.GET, "/ui/assets/file-abc123.$extension")!!.use { response ->
                assertEquals(extension, contentType, response.mimeType)
                assertEquals("public, max-age=31536000, immutable", response.getHeader("Cache-Control"))
            }
        }
    }

    @Test
    fun `unsafe paths are rejected before opening assets even with repeated encoding`() {
        val assets = ZashboardAssets { error("Unsafe path reached asset opener: $it") }
        val paths = listOf(
            "/ui/../licenses/zashboard/LICENSE", "/ui/assets/../../index.html", "/ui/file..js",
            "/ui/assets\\index.js", "/ui//index.html", "/ui/assets//index.js", "/ui/assets/",
            "/ui/./index.html", "/ui/%2e%2e/index.html", "/ui/%2E%2e/index.html",
            "/ui/%252e%252e/index.html", "/ui/assets%5cindex.js", "/ui/assets%255cindex.js",
            "/ui/%2findex.html", "/ui/assets/%2findex.js", "/ui/%252findex.html", "/ui/%00index.html",
        )
        for (rawPath in paths) {
            // Match NanoHTTPD's one decode pass before serveHttp is called.
            val decoded = URLDecoder.decode(rawPath, "UTF-8")
            assets.serve(Method.GET, decoded)!!.use { response ->
                assertEquals(rawPath, Response.Status.BAD_REQUEST, response.status)
            }
        }
    }

    @Test
    fun `API paths and non-read methods never bypass authentication`() {
        val assets = ZashboardAssets { error("API request reached asset opener") }
        for (path in listOf("/version", "/configs", "/proxies/node", "/rules", "/traffic", "/memory", "/logs", "/connections", "/uix/index.html")) {
            for (method in Method.values()) assertNull("$method $path", assets.serve(method, path))
        }
        for (path in listOf("/", "/ui", "/ui/", "/ui/assets/index.js")) {
            for (method in Method.values().filter { it != Method.GET && it != Method.HEAD }) {
                assertNull("$method $path", assets.serve(method, path))
            }
        }
    }

    @Test
    fun `missing bundle or asset returns plain 404 without exposing exception details`() {
        val assets = ZashboardAssets { throw IOException("private-file-or-secret") }
        for (path in listOf("/ui/", "/ui/not-found.js")) {
            assets.serve(Method.GET, path)!!.use { response ->
                assertEquals(Response.Status.NOT_FOUND, response.status)
                assertEquals("text/plain; charset=utf-8", response.mimeType)
                assertEquals("no-cache", response.getHeader("Cache-Control"))
                assertEquals("Bundled dashboard file not found.", response.data.readBytes().toString(Charsets.UTF_8))
            }
        }
    }

    @Test
    fun `GET keeps asset as a stream and response owns its lifetime`() {
        var closed = false
        val stream = object : InputStream() {
            override fun read(): Int = error("Serving must not eagerly read the asset")
            override fun close() { closed = true }
        }
        val assets = ZashboardAssets { stream }
        val response = assets.serve(Method.GET, "/ui/assets/large-abc123.js")!!
        assertTrue(response.data === stream)
        assertFalse(closed)
        response.close()
        assertTrue(closed)
    }

    @Test
    fun `HEAD closes the asset without reading its contents`() {
        var closed = false
        val assets = ZashboardAssets {
            object : InputStream() {
                override fun available() = 1_234_567
                override fun read(): Int = error("HEAD must not read the asset")
                override fun close() { closed = true }
            }
        }
        assets.serve(Method.HEAD, "/ui/assets/large-abc123.js")!!.use { response ->
            assertTrue(closed)
            assertEquals(Response.Status.OK, response.status)
            assertEquals(0, response.data.readBytes().size)
        }
    }

    @Test
    fun `NanoWSD sends chunked GET and genuinely bodyless HEAD including errors`() {
        val server = TestServer()
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        try {
            val get = request(server, "GET", "/ui/")
            assertTrue(get.first.startsWith("HTTP/1.1 200"))
            assertTrue(get.first.contains("Transfer-Encoding: chunked", ignoreCase = true))
            assertTrue(get.second.contains("offline dashboard"))
            val head = request(server, "HEAD", "/ui/")
            assertTrue(head.first.startsWith("HTTP/1.1 200"))
            assertTrue(head.first.contains("Content-Length: 17"))
            assertEquals("", head.second)
            for ((path, code) in listOf("/" to 302, "/ui" to 302, "/ui/missing" to 404, "/ui/%252e%252e/private" to 400, "/ui/assets%5cprivate" to 400, "/ui//index.html" to 400)) {
                val response = request(server, "HEAD", path)
                assertTrue("$path: ${response.first}", response.first.startsWith("HTTP/1.1 $code"))
                assertEquals("", response.second)
            }
            assertTrue(request(server, "GET", "/version").first.startsWith("HTTP/1.1 401"))
            assertTrue(request(server, "POST", "/ui/").first.startsWith("HTTP/1.1 401"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `WebSocket upgrade at root still bypasses serveHttp and goes to WebSocket handler`() {
        val server = TestServer()
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        try {
            val response = request(server, "GET", "/", "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n")
            assertTrue(response.first.startsWith("HTTP/1.1 101"))
            assertFalse(response.first.contains("Location:"))
            assertTrue(server.socketOpened)
        } finally {
            server.stop()
        }
    }

    private fun request(server: NanoHTTPD, method: String, path: String, extraHeaders: String = ""): Pair<String, String> =
        Socket("127.0.0.1", server.listeningPort).use { socket ->
            socket.soTimeout = 5_000
            val connection = if (extraHeaders.isEmpty()) "Connection: close\r\n" else ""
            socket.getOutputStream().write("$method $path HTTP/1.1\r\nHost: 127.0.0.1\r\n$connection$extraHeaders\r\n".toByteArray())
            val response = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            response.substringBefore("\r\n\r\n") to response.substringAfter("\r\n\r\n")
        }

    private class TestServer : NanoWSD("127.0.0.1", 0) {
        @Volatile var socketOpened = false
        private val assets = ZashboardAssets { path ->
            if (path != "zashboard/index.html") throw IOException("missing")
            ByteArrayInputStream("offline dashboard".toByteArray())
        }

        override fun serveHttp(session: IHTTPSession): Response =
            assets.serve(session.method, session.uri)
                ?: newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "Unauthorized")

        override fun openWebSocket(handshake: IHTTPSession): WebSocket = object : WebSocket(handshake) {
            override fun onOpen() {
                socketOpened = true
                close(WebSocketFrame.CloseCode.NormalClosure, "Unauthorized", false)
            }
            override fun onClose(code: WebSocketFrame.CloseCode, reason: String, initiatedByRemote: Boolean) {}
            override fun onMessage(message: WebSocketFrame) {}
            override fun onPong(pong: WebSocketFrame) {}
            override fun onException(exception: IOException) {}
        }
    }
}
