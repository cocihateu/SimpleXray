package com.simplexray.re.service

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Socket

class RelayDatagramTransportTest {
    @Test
    fun `UoT v1 and v2 preserve every address and payload byte`() {
        val payload = ByteArray(512) { it.toByte() }
        val addresses = listOf(
            byteArrayOf(1, 192.toByte(), 0, 2, 255.toByte()),
            byteArrayOf(4) + ByteArray(16) { (it * 17).toByte() },
            byteArrayOf(3, 200.toByte()) + ByteArray(200) { (it + 32).toByte() },
        )
        for (version in 1..2) for (address in addresses) {
            val packet = byteArrayOf(0, 0, 0) + address + byteArrayOf(255.toByte(), 254.toByte()) + payload
            val header = parseSocksDatagram(packet)
            val socket = MemorySocket()
            val sender = UotDatagramTransport(socket, version)
            sender.send(packet)
            sender.send(packet)
            val family = when (address[0].toInt()) { 1 -> 0; 4 -> 1; else -> 2 }
            val frame = byteArrayOf(family.toByte()) + packet.copyOfRange(4, header.payloadOffset) +
                byteArrayOf(2, 0) + payload
            val preface = if (version == 2) byteArrayOf(0) + packet.copyOfRange(3, header.payloadOffset) else byteArrayOf()
            assertArrayEquals(preface + frame + frame, socket.output.toByteArray())
            val receiver = UotDatagramTransport(MemorySocket(frame + frame), version)
            assertArrayEquals(packet, receiver.receive())
            assertArrayEquals(packet, receiver.receive())
        }
    }

    @Test
    fun `empty payload and maximum loopback datagram round trip`() {
        for (size in listOf(0, MAX_RELAY_DATAGRAM_SIZE - 10)) {
            val packet = byteArrayOf(0, 0, 0, 1, 127, 0, 0, 1, 0, 53) + ByteArray(size) { it.toByte() }
            val socket = MemorySocket()
            UotDatagramTransport(socket, 1).send(packet)
            assertArrayEquals(packet, UotDatagramTransport(MemorySocket(socket.output.toByteArray()), 1).receive())
        }
    }

    @Test
    fun `malformed SOCKS datagrams always fail with IOException before writing`() {
        val valid = byteArrayOf(0, 0, 0, 1, 127, 0, 0, 1, 0, 53)
        val malformed = (0 until valid.size).map { valid.copyOf(it) } + listOf(
            valid.clone().apply { this[0] = 1 },
            valid.clone().apply { this[1] = 1 },
            valid.clone().apply { this[2] = 1 },
            valid.clone().apply { this[2] = 128.toByte() },
            valid.clone().apply { this[3] = 9 },
            byteArrayOf(0, 0, 0, 3),
            byteArrayOf(0, 0, 0, 3, 0, 0, 53),
            byteArrayOf(0, 0, 0, 3, 255.toByte(), 0, 53),
            byteArrayOf(0, 0, 0, 4) + ByteArray(17),
            valid + ByteArray(MAX_RELAY_DATAGRAM_SIZE - valid.size + 1),
        )
        for (packet in malformed) {
            assertThrows(IOException::class.java) { parseSocksDatagram(packet) }
            val socket = MemorySocket()
            assertThrows(IOException::class.java) { UotDatagramTransport(socket, 2).send(packet) }
            assertEquals(0, socket.output.size())
        }
    }

    @Test
    fun `invalid UoT families domains lengths and truncated frames are rejected`() {
        val malformed = listOf(
            byteArrayOf(),
            byteArrayOf(3),
            byteArrayOf(2, 0),
            byteArrayOf(2, 3, 65),
            byteArrayOf(0, 127, 0, 0),
            byteArrayOf(0, 127, 0, 0, 1, 0),
            byteArrayOf(0, 127, 0, 0, 1, 0, 53, 0, 2, 1),
            byteArrayOf(0, 127, 0, 0, 1, 0, 53, 255.toByte(), 255.toByte()),
        )
        for (frame in malformed) {
            assertThrows(IOException::class.java) { UotDatagramTransport(MemorySocket(frame), 1).receive() }
        }
        assertThrows(IOException::class.java) { UotDatagramTransport(MemorySocket(), 3) }
        assertThrows(IOException::class.java) { UotDatagramTransport.host(3) }
    }

    private class MemorySocket(input: ByteArray = byteArrayOf()) : Socket() {
        private val source = ByteArrayInputStream(input)
        val output = ByteArrayOutputStream()
        override fun getInputStream() = source
        override fun getOutputStream() = output
    }
}
