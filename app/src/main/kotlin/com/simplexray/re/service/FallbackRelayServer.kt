package com.simplexray.re.service

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList

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

/**
 * Local authenticated SOCKS5 relay for FALLBACK groups.
 *
 * Buffers the initial client payload (e.g. TLS ClientHello) so when the primary outbound fails to
 * connect, closes with zero upstream bytes, or exceeds failoverTimeoutMs before first byte, this
 * exact connection immediately redials the pre-warmed fallback target and flips the group into
 * fallback mode with a recovery countdown.
 */
internal class FallbackRelayServer(
    private val specs: List<FallbackRelaySpec>,
    private val nodeEntryPort: Int,
    private val nodeUsers: Map<String, String>,
    private val nodePassword: String,
    private val resolvePlan: (String) -> FailoverPlan,
    private val onPrimaryFailed: (groupTag: String, failedLeaf: String) -> Unit,
) : Closeable {
    private val servers = CopyOnWriteArrayList<ServerSocket>()
    private val jobs = CopyOnWriteArrayList<Job>()
    @Volatile private var closed = false

    fun start(scope: CoroutineScope) {
        for (spec in specs) {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), spec.port))
            servers.add(server)
            val acceptJob = scope.launch(Dispatchers.IO) {
                while (isActive && closed == false) {
                    val client = try {
                        server.accept()
                    } catch (_: IOException) {
                        break
                    }
                    launch(Dispatchers.IO) {
                        handleClient(scope, spec, client)
                    }
                }
            }
            jobs.add(acceptJob)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        servers.forEach { runCatching { it.close() } }
        servers.clear()
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    private suspend fun handleClient(scope: CoroutineScope, spec: FallbackRelaySpec, client: Socket) {
        try {
            client.tcpNoDelay = true
            client.soTimeout = HANDSHAKE_TIMEOUT_MS
            val input = DataInputStream(client.getInputStream())
            val output = client.getOutputStream()

            if (performServerAuth(input, output, spec.username, spec.password) == false) {
                client.close()
                return
            }

            val ver = input.readUnsignedByte()
            val cmd = input.readUnsignedByte()
            input.readUnsignedByte() // RSV
            if (ver != 5) {
                client.close()
                return
            }
            val target = readSocksAddress(input) ?: run {
                client.close()
                return
            }

            when (cmd) {
                CMD_CONNECT -> handleTcpConnect(scope, spec, client, input, output, target)
                CMD_UDP_ASSOCIATE -> handleUdpAssociate(scope, spec, client, input, output)
                else -> {
                    sendSocksReply(output, 0x07)
                    client.close()
                }
            }
        } catch (_: Exception) {
            runCatching { client.close() }
        }
    }

    private suspend fun handleTcpConnect(
        scope: CoroutineScope,
        spec: FallbackRelaySpec,
        client: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        target: SocksAddress,
    ) {
        sendSocksReply(clientOut, 0x00)

        val initialPayload = ByteArray(INITIAL_BUFFER_BYTES)
        var initialLength = 0
        try {
            client.soTimeout = CLIENT_PEEK_TIMEOUT_MS
            val read = clientIn.read(initialPayload)
            if (read < 0) {
                client.close()
                return
            }
            initialLength = read
        } catch (_: SocketTimeoutException) {
            initialLength = 0
        } catch (_: IOException) {
            client.close()
            return
        } finally {
            runCatching { client.soTimeout = 0 }
        }

        val plan = resolvePlan(spec.groupTag)
        if (plan.candidates.isEmpty()) {
            client.close()
            return
        }

        val firstChunk = ByteArray(FIRST_RESPONSE_BUFFER_BYTES)
        for ((index, leaf) in plan.candidates.withIndex()) {
            val hasMore = index + 1 < plan.candidates.size
            val upstream = dialUpstreamViaNode(leaf, target, plan.failoverTimeoutMs)
            if (upstream == null) {
                if (hasMore && leaf == plan.primaryLeaf) {
                    onPrimaryFailed(spec.groupTag, leaf)
                }
                continue
            }

            var established = false
            var firstResponseLen = 0
            try {
                val upOut = upstream.getOutputStream()
                val upIn = upstream.getInputStream()
                if (initialLength > 0) {
                    upOut.write(initialPayload, 0, initialLength)
                    upOut.flush()
                }

                if (hasMore) {
                    upstream.soTimeout = plan.failoverTimeoutMs
                    val n = upIn.read(firstChunk)
                    if (n > 0) {
                        upstream.soTimeout = 0
                        firstResponseLen = n
                        established = true
                    } else {
                        established = false
                    }
                } else {
                    upstream.soTimeout = 0
                    established = true
                }
            } catch (_: SocketTimeoutException) {
                established = false
            } catch (_: IOException) {
                established = false
            }

            if (established) {
                try {
                    if (firstResponseLen > 0) {
                        clientOut.write(firstChunk, 0, firstResponseLen)
                        clientOut.flush()
                    }
                    pipeBidirectional(scope, client, upstream)
                } finally {
                    runCatching { upstream.close() }
                    runCatching { client.close() }
                }
                return
            } else {
                runCatching { upstream.close() }
                if (client.isClosed || client.isInputShutdown) {
                    runCatching { client.close() }
                    return
                }
                if (hasMore && leaf == plan.primaryLeaf) {
                    Log.w(TAG, "Primary '$leaf' failed for group '${spec.groupTag}', failing over immediately.")
                    onPrimaryFailed(spec.groupTag, leaf)
                }
            }
        }
        runCatching { client.close() }
    }

    private suspend fun handleUdpAssociate(
        scope: CoroutineScope,
        spec: FallbackRelaySpec,
        client: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
    ) {
        val plan = resolvePlan(spec.groupTag)
        val leaf = plan.candidates.firstOrNull() ?: run {
            sendSocksReply(clientOut, 0x01)
            client.close()
            return
        }
        val user = nodeUsers[leaf] ?: run {
            sendSocksReply(clientOut, 0x01)
            client.close()
            return
        }

        val upstreamControl = Socket()
        var localUdp: DatagramSocket? = null
        var upstreamUdp: DatagramSocket? = null
        try {
            upstreamControl.connect(InetSocketAddress("127.0.0.1", nodeEntryPort), 2_000)
            upstreamControl.soTimeout = HANDSHAKE_TIMEOUT_MS
            val upIn = DataInputStream(upstreamControl.getInputStream())
            val upOut = upstreamControl.getOutputStream()
            if (performClientAuth(upIn, upOut, user, nodePassword) == false) {
                sendSocksReply(clientOut, 0x01)
                return
            }
            upOut.write(byteArrayOf(0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            upOut.flush()
            val rVer = upIn.readUnsignedByte()
            val rRep = upIn.readUnsignedByte()
            upIn.readUnsignedByte()
            val bound = readSocksAddress(upIn)
            if (rVer != 5 || rRep != 0 || bound == null) {
                sendSocksReply(clientOut, 0x01)
                return
            }
            val xrayUdpAddr = InetSocketAddress(InetAddress.getByName("127.0.0.1"), bound.port)

            localUdp = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
            upstreamUdp = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
            val localPort = localUdp.localPort
            sendSocksReply(clientOut, 0x00, localPort)
            client.soTimeout = 0
            upstreamControl.soTimeout = 0

            @Volatile var clientEndpoint: InetSocketAddress? = null
            val c2u = scope.launch(Dispatchers.IO) {
                val buf = ByteArray(UDP_BUFFER_BYTES)
                val packet = DatagramPacket(buf, buf.size)
                while (isActive && localUdp.isClosed == false) {
                    try {
                        localUdp.receive(packet)
                        clientEndpoint = InetSocketAddress(packet.address, packet.port)
                        upstreamUdp.send(DatagramPacket(packet.data, packet.offset, packet.length, xrayUdpAddr))
                    } catch (_: IOException) {
                        break
                    }
                }
            }
            val u2c = scope.launch(Dispatchers.IO) {
                val buf = ByteArray(UDP_BUFFER_BYTES)
                val packet = DatagramPacket(buf, buf.size)
                while (isActive && upstreamUdp.isClosed == false) {
                    try {
                        upstreamUdp.receive(packet)
                        val dest = clientEndpoint ?: continue
                        localUdp.send(DatagramPacket(packet.data, packet.offset, packet.length, dest))
                    } catch (_: IOException) {
                        break
                    }
                }
            }
            runCatching {
                val drain = ByteArray(64)
                while (clientIn.read(drain) >= 0) {
                    // Keep UDP association open until control TCP closes.
                }
            }
            c2u.cancel()
            u2c.cancel()
        } catch (_: Exception) {
            runCatching { sendSocksReply(clientOut, 0x01) }
        } finally {
            runCatching { localUdp?.close() }
            runCatching { upstreamUdp?.close() }
            runCatching { upstreamControl.close() }
            runCatching { client.close() }
        }
    }

    private fun dialUpstreamViaNode(leaf: String, target: SocksAddress, timeoutMs: Int): Socket? {
        val user = nodeUsers[leaf] ?: return null
        val socket = Socket()
        return try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress("127.0.0.1", nodeEntryPort), minOf(timeoutMs, 2_500))
            socket.soTimeout = timeoutMs
            val input = DataInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            if (performClientAuth(input, output, user, nodePassword) == false) {
                socket.close()
                return null
            }
            val req = ByteArray(4 + target.rawAddress.size + 2)
            req[0] = 0x05
            req[1] = CMD_CONNECT.toByte()
            req[2] = 0x00
            req[3] = target.atyp.toByte()
            System.arraycopy(target.rawAddress, 0, req, 4, target.rawAddress.size)
            req[4 + target.rawAddress.size] = ((target.port shr 8) and 0xFF).toByte()
            req[4 + target.rawAddress.size + 1] = (target.port and 0xFF).toByte()
            output.write(req)
            output.flush()

            val ver = input.readUnsignedByte()
            val rep = input.readUnsignedByte()
            input.readUnsignedByte()
            readSocksAddress(input)
            if (ver != 5 || rep != 0) {
                socket.close()
                return null
            }
            socket
        } catch (_: Exception) {
            runCatching { socket.close() }
            null
        }
    }

    private suspend fun pipeBidirectional(scope: CoroutineScope, client: Socket, upstream: Socket) {
        val cIn = client.getInputStream()
        val cOut = client.getOutputStream()
        val uIn = upstream.getInputStream()
        val uOut = upstream.getOutputStream()

        val uploadJob = scope.launch(Dispatchers.IO) {
            copyStream(cIn, uOut)
            runCatching { upstream.shutdownOutput() }
        }
        try {
            copyStream(uIn, cOut)
            runCatching { client.shutdownOutput() }
        } finally {
            uploadJob.join()
        }
    }

    private fun copyStream(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(PIPE_BUFFER_BYTES)
        try {
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                output.flush()
            }
        } catch (_: IOException) {
            // Normal half-close or peer reset during stream shutdown.
        }
    }

    private fun performServerAuth(
        input: DataInputStream,
        output: OutputStream,
        expectedUser: String,
        expectedPass: String,
    ): Boolean {
        val ver = input.readUnsignedByte()
        if (ver != 5) return false
        val nMethods = input.readUnsignedByte()
        val methods = ByteArray(nMethods)
        input.readFully(methods)
        val supportsUserPass = methods.any { it.toInt() == 0x02 }
        if (supportsUserPass == false) {
            output.write(byteArrayOf(0x05, 0xFF.toByte()))
            output.flush()
            return false
        }
        output.write(byteArrayOf(0x05, 0x02))
        output.flush()

        val authVer = input.readUnsignedByte()
        val uLen = input.readUnsignedByte()
        val uBytes = ByteArray(uLen)
        input.readFully(uBytes)
        val pLen = input.readUnsignedByte()
        val pBytes = ByteArray(pLen)
        input.readFully(pBytes)
        val ok = authVer == 1 &&
            String(uBytes, Charsets.UTF_8) == expectedUser &&
            String(pBytes, Charsets.UTF_8) == expectedPass
        output.write(byteArrayOf(0x01, if (ok) 0x00 else 0x01))
        output.flush()
        return ok
    }

    private fun performClientAuth(
        input: DataInputStream,
        output: OutputStream,
        user: String,
        pass: String,
    ): Boolean {
        output.write(byteArrayOf(0x05, 0x01, 0x02))
        output.flush()
        val ver = input.readUnsignedByte()
        val method = input.readUnsignedByte()
        if (ver != 5 || method != 0x02) return false
        val uBytes = user.toByteArray(Charsets.UTF_8)
        val pBytes = pass.toByteArray(Charsets.UTF_8)
        val pkt = ByteArray(3 + uBytes.size + pBytes.size)
        pkt[0] = 0x01
        pkt[1] = uBytes.size.toByte()
        System.arraycopy(uBytes, 0, pkt, 2, uBytes.size)
        pkt[2 + uBytes.size] = pBytes.size.toByte()
        System.arraycopy(pBytes, 0, pkt, 3 + uBytes.size, pBytes.size)
        output.write(pkt)
        output.flush()
        val aVer = input.readUnsignedByte()
        val status = input.readUnsignedByte()
        return aVer == 1 && status == 0
    }

    private fun readSocksAddress(input: DataInputStream): SocksAddress? {
        val atyp = input.readUnsignedByte()
        val rawAddr = when (atyp) {
            1 -> ByteArray(4).also { input.readFully(it) }
            3 -> {
                val len = input.readUnsignedByte()
                ByteArray(1 + len).also {
                    it[0] = len.toByte()
                    input.readFully(it, 1, len)
                }
            }

            4 -> ByteArray(16).also { input.readFully(it) }
            else -> return null
        }
        val port = input.readUnsignedShort()
        return SocksAddress(atyp, rawAddr, port)
    }

    private fun sendSocksReply(output: OutputStream, rep: Int, bindPort: Int = 0) {
        output.write(
            byteArrayOf(
                0x05,
                rep.toByte(),
                0x00,
                0x01,
                127,
                0,
                0,
                1,
                ((bindPort shr 8) and 0xFF).toByte(),
                (bindPort and 0xFF).toByte(),
            )
        )
        output.flush()
    }

    private class SocksAddress(
        val atyp: Int,
        val rawAddress: ByteArray,
        val port: Int,
    )

    companion object {
        private const val TAG = "FallbackRelayServer"
        private const val CMD_CONNECT = 0x01
        private const val CMD_UDP_ASSOCIATE = 0x03
        private const val HANDSHAKE_TIMEOUT_MS = 5_000
        private const val CLIENT_PEEK_TIMEOUT_MS = 150
        private const val INITIAL_BUFFER_BYTES = 16 * 1024
        private const val FIRST_RESPONSE_BUFFER_BYTES = 16 * 1024
        private const val PIPE_BUFFER_BYTES = 32 * 1024
        private const val UDP_BUFFER_BYTES = 65_535
    }
}
