package com.simplexray.re.service

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Socket

internal interface RelayDatagramTransport : Closeable {
    fun send(packet: ByteArray)
    fun receive(): ByteArray
}

// Both sides of the association use IPv4 loopback UDP sockets.
internal const val MAX_RELAY_DATAGRAM_SIZE = 65_507

internal data class SocksDatagramHeader(val type: Int, val addressSize: Int, val payloadOffset: Int)

/** Validate before indexing, forwarding, or claiming the client's UDP endpoint. */
internal fun parseSocksDatagram(packet: ByteArray): SocksDatagramHeader {
    if (packet.size < 4 || packet.size > MAX_RELAY_DATAGRAM_SIZE) throw IOException("SOCKS datagram length")
    if (packet[0] != 0.toByte() || packet[1] != 0.toByte()) throw IOException("SOCKS datagram reserved field")
    if (packet[2] != 0.toByte()) throw IOException("SOCKS datagram fragmentation is unsupported")
    val type = packet[3].toInt() and 255
    val addressSize = when (type) {
        1 -> 4
        4 -> 16
        3 -> {
            if (packet.size < 5) throw IOException("SOCKS datagram domain length")
            val length = packet[4].toInt() and 255
            if (length == 0) throw IOException("SOCKS datagram empty domain")
            1 + length
        }
        else -> throw IOException("SOCKS datagram address type $type")
    }
    val payloadOffset = 4 + addressSize + 2
    if (packet.size < payloadOffset) throw IOException("SOCKS datagram truncated address")
    return SocksDatagramHeader(type, addressSize, payloadOffset)
}

internal class SocksDatagramTransport(private val socket: DatagramSocket) : RelayDatagramTransport {
    private val bytes = ByteArray(65_535)
    override fun send(packet: ByteArray) {
        parseSocksDatagram(packet)
        socket.send(DatagramPacket(packet, packet.size))
    }
    override fun receive(): ByteArray {
        val packet = DatagramPacket(bytes, bytes.size)
        socket.receive(packet)
        return bytes.copyOfRange(packet.offset, packet.offset + packet.length).also(::parseSocksDatagram)
    }
    override fun close() = socket.close()
}

/** UoT framing travels through an Xray Shadowsocks TCP connection. */
internal class UotDatagramTransport(private val socket: Socket, private val version: Int) : RelayDatagramTransport {
    init {
        if (version != 1 && version != 2) throw IOException("Unsupported UoT version $version")
    }

    private val input = DataInputStream(socket.getInputStream())
    private val output = DataOutputStream(socket.getOutputStream())
    private var requestSent = false

    override fun send(packet: ByteArray) {
        val header = parseSocksDatagram(packet)
        if (requestSent == false && version == 2) {
            output.writeBoolean(false)
            output.write(packet, 3, header.payloadOffset - 3)
        }
        requestSent = true
        output.writeByte(when (header.type) { 1 -> 0; 4 -> 1; else -> 2 })
        output.write(packet, 4, header.addressSize + 2)
        output.writeShort(packet.size - header.payloadOffset)
        output.write(packet, header.payloadOffset, packet.size - header.payloadOffset)
        output.flush()
    }

    override fun receive(): ByteArray {
        val uotType = input.readUnsignedByte()
        val packet = ByteArrayOutputStream()
        val data = DataOutputStream(packet)
        data.write(byteArrayOf(0, 0, 0))
        val size = when (uotType) {
            0 -> { data.writeByte(1); 4 }
            1 -> { data.writeByte(4); 16 }
            2 -> {
                data.writeByte(3)
                input.readUnsignedByte().also {
                    if (it == 0) throw IOException("UoT empty domain")
                    data.writeByte(it)
                }
            }
            else -> throw IOException("UoT address family $uotType")
        }
        data.write(ByteArray(size).also(input::readFully))
        data.writeShort(input.readUnsignedShort())
        val payloadSize = input.readUnsignedShort()
        if (payloadSize > MAX_RELAY_DATAGRAM_SIZE - packet.size()) throw IOException("UoT payload length")
        data.write(ByteArray(payloadSize).also(input::readFully))
        return packet.toByteArray().also(::parseSocksDatagram)
    }

    override fun close() = socket.close()

    companion object {
        fun host(version: Int): String = when (version) {
            1 -> "sp.udp-over-tcp.arpa"
            2 -> "sp.v2.udp-over-tcp.arpa"
            else -> throw IOException("Unsupported UoT version $version")
        }
    }
}
