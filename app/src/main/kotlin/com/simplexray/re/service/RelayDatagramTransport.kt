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

internal class SocksDatagramTransport(private val socket: DatagramSocket) : RelayDatagramTransport {
    private val bytes = ByteArray(65_535)
    override fun send(packet: ByteArray) { socket.send(DatagramPacket(packet, packet.size)) }
    override fun receive(): ByteArray {
        val packet = DatagramPacket(bytes, bytes.size)
        socket.receive(packet)
        return bytes.copyOfRange(packet.offset, packet.offset + packet.length)
    }
    override fun close() = socket.close()
}

/** UoT framing travels through an Xray Shadowsocks TCP connection. */
internal class UotDatagramTransport(private val socket: Socket, private val version: Int) : RelayDatagramTransport {
    private val input = DataInputStream(socket.getInputStream())
    private val output = DataOutputStream(socket.getOutputStream())
    private var requestSent = false

    override fun send(packet: ByteArray) {
        val socksType = packet[3].toInt() and 255
        val addressSize = when (socksType) {
            1 -> 4
            4 -> 16
            3 -> 1 + (packet[4].toInt() and 255)
            else -> throw IOException("UoT address type $socksType")
        }
        val payloadOffset = 4 + addressSize + 2
        if (requestSent == false && version == 2) {
            output.writeBoolean(false)
            output.write(packet, 3, payloadOffset - 3)
        }
        requestSent = true
        output.writeByte(when (socksType) { 1 -> 0; 4 -> 1; else -> 2 })
        output.write(packet, 4, addressSize + 2)
        output.writeShort(packet.size - payloadOffset)
        output.write(packet, payloadOffset, packet.size - payloadOffset)
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
                input.readUnsignedByte().also { data.writeByte(it) }
            }
            else -> throw IOException("UoT address family $uotType")
        }
        data.write(ByteArray(size).also(input::readFully))
        data.writeShort(input.readUnsignedShort())
        data.write(ByteArray(input.readUnsignedShort()).also(input::readFully))
        return packet.toByteArray()
    }

    override fun close() = socket.close()

    companion object {
        fun host(version: Int) = if (version == 1) "sp.udp-over-tcp.arpa" else "sp.v2.udp-over-tcp.arpa"
    }
}
