package com.simplexray.re.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

internal data class SimpleObfsSpec(
    val nodeTag: String,
    val username: String,
    val password: String,
    val mode: String,
    val host: String,
    val frontProxy: FallbackRelaySpec?,
)

/** SIP003 simple-obfs framing around the ciphertext produced by Xray Shadowsocks. */
internal class SimpleObfsRelay(
    private val port: Int,
    private val specs: List<SimpleObfsSpec>,
) : Closeable {
    private var listener: ServerSocket? = null
    private var job: Job? = null
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val io = Dispatchers.IO.limitedParallelism(256)

    fun start(scope: CoroutineScope) {
        if (specs.isEmpty()) return
        val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", port)) }
        listener = server
        job = scope.launch(io) {
            while (isActive) {
                val client = try { server.accept() } catch (_: IOException) { break }
                sockets.add(client)
                launch {
                    try { forward(client) } catch (_: IOException) { /* Socket teardown ends this transport. */ }
                    finally { sockets.remove(client); client.close() }
                }
            }
        }
    }

    override fun close() {
        listener?.close()
        listener = null
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        job?.cancel()
        job = null
    }

    private suspend fun forward(client: Socket) = coroutineScope {
        client.tcpNoDelay = true
        client.soTimeout = 10_000
        val input = DataInputStream(client.getInputStream())
        val output = client.getOutputStream()
        if (input.readUnsignedByte() != 5) return@coroutineScope
        val methods = ByteArray(input.readUnsignedByte()).also(input::readFully)
        if (2.toByte() !in methods) return@coroutineScope
        output.write(byteArrayOf(5, 2))
        if (input.readUnsignedByte() != 1) return@coroutineScope
        val user = readSizedText(input)
        val password = readSizedText(input)
        val spec = specs.firstOrNull { it.username == user && it.password == password } ?: return@coroutineScope
        output.write(byteArrayOf(1, 0))
        if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != 1) return@coroutineScope
        input.readUnsignedByte()
        val address = readAddress(input)
        val upstream = Socket()
        sockets.add(upstream)
        try {
            upstream.tcpNoDelay = true
            upstream.soTimeout = 10_000
            val front = spec.frontProxy
            if (front == null) {
                upstream.connect(InetSocketAddress(address.host, address.port), 10_000)
            } else {
                upstream.connect(InetSocketAddress("127.0.0.1", front.port), 10_000)
                connectThroughGroup(upstream, front, address)
            }
            output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0))
            client.soTimeout = 0
            upstream.soTimeout = 0
            val sender = launch(io) {
                try {
                    writeObfs(input, upstream.getOutputStream(), spec)
                    runCatching { upstream.shutdownOutput() }
                } catch (_: IOException) {
                    upstream.close()
                }
            }
            try {
                readObfs(DataInputStream(upstream.getInputStream()), output, spec.mode)
            } finally {
                upstream.close()
                client.close()
                sender.cancel()
            }
        } finally {
            sockets.remove(upstream)
            upstream.close()
        }
    }

    private fun writeObfs(input: InputStream, output: OutputStream, spec: SimpleObfsSpec) {
        val buffer = ByteArray(16_384)
        var first = true
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return
            if (spec.mode == "tls") {
                if (first) output.write(clientHello(buffer.copyOf(count), spec.host))
                else {
                    output.write(byteArrayOf(0x17, 3, 3, (count ushr 8).toByte(), count.toByte()))
                    output.write(buffer, 0, count)
                }
            } else {
                if (first) {
                    val key = Base64.getEncoder().encodeToString(randomBytes(16))
                    output.write(("GET / HTTP/1.1\r\nHost: ${spec.host}\r\n" +
                        "User-Agent: curl/7.88.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                        "Sec-WebSocket-Key: $key\r\nContent-Length: $count\r\n\r\n").toByteArray(Charsets.US_ASCII))
                }
                output.write(buffer, 0, count)
            }
            output.flush()
            first = false
        }
    }

    private fun readObfs(input: DataInputStream, output: OutputStream, mode: String) {
        if (mode == "http") {
            var tail = 0
            while (tail != 0x0d0a0d0a) tail = (tail shl 8) or input.readUnsignedByte()
            input.copyTo(output)
            return
        }
        var encryptedHandshake = false
        while (true) {
            val type = input.read()
            if (type < 0) return
            input.readUnsignedShort()
            val length = input.readUnsignedShort()
            val payload = ByteArray(length).also(input::readFully)
            when (type) {
                0x14 -> encryptedHandshake = true
                0x16 -> if (encryptedHandshake) { output.write(payload); encryptedHandshake = false }
                0x17 -> output.write(payload)
                else -> throw IOException("simple-obfs TLS record type $type")
            }
            output.flush()
        }
    }

    private fun clientHello(payload: ByteArray, host: String): ByteArray {
        val name = host.toByteArray(Charsets.US_ASCII)
        val extensions = packet {
            writeShort(0x0023); writeShort(payload.size); write(payload)
            writeShort(0); writeShort(name.size + 5); writeShort(name.size + 3)
            writeByte(0); writeShort(name.size); write(name)
            writeShort(11); writeShort(4); write(byteArrayOf(3, 1, 0, 2))
            writeShort(10); writeShort(10); writeShort(8)
            listOf(29, 23, 25, 24).forEach(::writeShort)
            writeShort(13); writeShort(32); writeShort(30)
            for (algorithm in listOf(6, 5, 4, 3, 2)) {
                for (signature in 1..3) { writeByte(algorithm); writeByte(signature) }
            }
            writeShort(22); writeShort(0); writeShort(23); writeShort(0)
        }
        val body = packet {
            writeShort(0x0303)
            writeInt((System.currentTimeMillis() / 1_000L).toInt())
            write(randomBytes(28)); writeByte(32); write(randomBytes(32))
            val ciphers = listOf(0xc02c, 0xc030, 0x009f, 0xcca9, 0xcca8, 0xccaa, 0xc02b,
                0xc02f, 0x009e, 0xc024, 0xc028, 0x006b, 0xc023, 0xc027, 0x0067, 0xc00a,
                0xc014, 0x0039, 0xc009, 0xc013, 0x0033, 0x009d, 0x009c, 0x003d, 0x003c,
                0x0035, 0x002f, 0x00ff)
            writeShort(ciphers.size * 2); ciphers.forEach(::writeShort)
            writeByte(1); writeByte(0); writeShort(extensions.size); write(extensions)
        }
        return packet {
            writeByte(0x16); writeShort(0x0301); writeShort(body.size + 4)
            writeByte(1); writeByte(body.size ushr 16); writeShort(body.size); write(body)
        }
    }

    private fun connectThroughGroup(socket: Socket, front: FallbackRelaySpec, address: Address) {
        val input = DataInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        output.write(byteArrayOf(5, 1, 2))
        if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != 2) throw IOException("SOCKS method")
        val user = front.username.toByteArray()
        val password = front.password.toByteArray()
        output.write(byteArrayOf(1, user.size.toByte()) + user + byteArrayOf(password.size.toByte()) + password)
        if (input.readUnsignedByte() != 1 || input.readUnsignedByte() != 0) throw IOException("SOCKS authentication")
        output.write(byteArrayOf(5, 1, 0) + address.wire)
        if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != 0) throw IOException("SOCKS target")
        input.readUnsignedByte()
        readAddress(input)
    }

    private data class Address(val host: String, val port: Int, val wire: ByteArray)

    private fun readAddress(input: DataInputStream): Address {
        val type = input.readUnsignedByte()
        val bytes = when (type) {
            1 -> ByteArray(4).also(input::readFully)
            4 -> ByteArray(16).also(input::readFully)
            3 -> ByteArray(input.readUnsignedByte()).also(input::readFully)
            else -> throw IOException("SOCKS address type $type")
        }
        val host = if (type == 3) bytes.toString(Charsets.UTF_8) else java.net.InetAddress.getByAddress(bytes).hostAddress.orEmpty()
        val port = input.readUnsignedShort()
        val wire = packet {
            writeByte(type)
            if (type == 3) writeByte(bytes.size)
            write(bytes); writeShort(port)
        }
        return Address(host, port, wire)
    }

    private fun readSizedText(input: DataInputStream): String =
        ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.UTF_8)

    private fun packet(write: DataOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use(write) }.toByteArray()

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    companion object {
        private val random = SecureRandom()
    }
}
