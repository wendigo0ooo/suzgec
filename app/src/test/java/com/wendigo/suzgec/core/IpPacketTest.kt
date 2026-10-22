package com.wendigo.suzgec.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class IpPacketTest {

    private val dnsPayload = byteArrayOf(0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0, 7, 101, 120, 97, 109)

    @Test
    fun parsesIpv4UdpDatagram() {
        val packet = ipv4Query(dnsPayload)
        val parsed = IpPacket.parseUdp(packet)
        assertNotNull(parsed)
        val udp = parsed!!
        assertEquals(4, udp.version)
        assertArrayEquals(byteArrayOf(10, 0, 0, 2), udp.source)
        assertArrayEquals(byteArrayOf(10, 111, 222, 1), udp.destination)
        assertEquals(40000, udp.sourcePort)
        assertEquals(53, udp.destinationPort)
        assertArrayEquals(dnsPayload, udp.payload)
    }

    @Test
    fun buildsIpv4ResponseWithSwappedEndpoints() {
        val query = IpPacket.parseUdp(ipv4Query(dnsPayload))!!
        val answer = dnsPayload.copyOf()
        answer[2] = 0x81.toByte()
        answer[3] = 0x80.toByte()
        val response = IpPacket.buildResponse(query, answer)!!
        assertEquals(0x45, response[0].toInt() and 0xFF)
        assertEquals(response.size, ((response[2].toInt() and 0xFF) shl 8) or (response[3].toInt() and 0xFF))

        val parsed = IpPacket.parseUdp(response)!!
        assertArrayEquals(query.destination, parsed.source)
        assertArrayEquals(query.source, parsed.destination)
        assertEquals(53, parsed.sourcePort)
        assertEquals(40000, parsed.destinationPort)
        assertArrayEquals(answer, parsed.payload)
        assertEquals(0xFFFF, headerSum(response))
    }

    @Test
    fun parsesAndBuildsIpv6Response() {
        val packet = ipv6Query(dnsPayload)
        val query = IpPacket.parseUdp(packet)!!
        assertEquals(6, query.version)
        assertEquals(53, query.destinationPort)

        val response = IpPacket.buildResponse(query, dnsPayload)!!
        assertEquals(0x60, response[0].toInt() and 0xFF)
        val parsed = IpPacket.parseUdp(response)!!
        assertEquals(6, parsed.version)
        assertArrayEquals(query.destination, parsed.source)
        assertArrayEquals(query.source, parsed.destination)
        assertEquals(53, parsed.sourcePort)
        assertEquals(40000, parsed.destinationPort)
        assertArrayEquals(dnsPayload, parsed.payload)
    }

    @Test
    fun rejectsFragmentedIpv4() {
        val packet = ipv4Query(dnsPayload)
        packet[7] = 0x01
        assertNull(IpPacket.parseUdp(packet))
    }

    @Test
    fun rejectsNonUdpIpv4() {
        val packet = ipv4Query(dnsPayload)
        packet[9] = 6
        assertNull(IpPacket.parseUdp(packet))
    }

    @Test
    fun ignoresTruncatedPacket() {
        assertNull(IpPacket.parseUdp(ByteArray(10)))
    }

    private fun ipv4Query(payload: ByteArray): ByteArray {
        val total = 20 + 8 + payload.size
        val packet = ByteArray(total)
        packet[0] = 0x45
        packet[2] = ((total ushr 8) and 0xFF).toByte()
        packet[3] = (total and 0xFF).toByte()
        packet[8] = 64
        packet[9] = 17
        packet[12] = 10
        packet[15] = 2
        packet[16] = 10
        packet[17] = 111.toByte()
        packet[18] = 222.toByte()
        packet[19] = 1
        packet[20] = 0x9C.toByte()
        packet[21] = 0x40
        packet[22] = 0
        packet[23] = 53
        val udpLength = 8 + payload.size
        packet[24] = ((udpLength ushr 8) and 0xFF).toByte()
        packet[25] = (udpLength and 0xFF).toByte()
        payload.copyInto(packet, 28)
        return packet
    }

    private fun ipv6Query(payload: ByteArray): ByteArray {
        val udpLength = 8 + payload.size
        val packet = ByteArray(40 + udpLength)
        packet[0] = 0x60
        packet[4] = ((udpLength ushr 8) and 0xFF).toByte()
        packet[5] = (udpLength and 0xFF).toByte()
        packet[6] = 17
        packet[7] = 64
        packet[23] = 2
        packet[24] = 0xFD.toByte()
        packet[39] = 1
        packet[40] = 0x9C.toByte()
        packet[41] = 0x40
        packet[42] = 0
        packet[43] = 53
        packet[44] = ((udpLength ushr 8) and 0xFF).toByte()
        packet[45] = (udpLength and 0xFF).toByte()
        payload.copyInto(packet, 48)
        return packet
    }

    private fun headerSum(packet: ByteArray): Int {
        var sum = 0
        var i = 0
        while (i < 20) {
            sum += (((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF))
            i += 2
        }
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum
    }
}
