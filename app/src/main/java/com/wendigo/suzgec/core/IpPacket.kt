package com.wendigo.suzgec.core

/**
 * Minimal IPv4/IPv6 UDP codec used by the DNS-only VPN tunnel.
 * It only parses and builds complete, unfragmented UDP datagrams.
 */
object IpPacket {

    data class UdpDatagram(
        val version: Int,
        val source: ByteArray,
        val destination: ByteArray,
        val sourcePort: Int,
        val destinationPort: Int,
        val payload: ByteArray
    )

    fun parseUdp(buffer: ByteArray, length: Int = buffer.size): UdpDatagram? {
        if (length < 20 || length > buffer.size) return null
        val version = (buffer[0].toInt() ushr 4) and 0x0F
        return when (version) {
            4 -> parseIpv4(buffer, length)
            6 -> parseIpv6(buffer, length)
            else -> null
        }
    }

    fun buildResponse(query: UdpDatagram, payload: ByteArray): ByteArray? {
        return when (query.version) {
            4 -> buildIpv4(query, payload)
            6 -> buildIpv6(query, payload)
            else -> null
        }
    }

    private fun parseIpv4(buffer: ByteArray, length: Int): UdpDatagram? {
        val headerLength = (buffer[0].toInt() and 0x0F) * 4
        if (headerLength < 20 || length < headerLength + 8) return null
        val totalLength = u16(buffer, 2)
        val end = if (totalLength in (headerLength + 8)..length) totalLength else length
        val flagsAndOffset = u16(buffer, 6)
        if (flagsAndOffset and 0x3FFF != 0) return null
        if ((buffer[9].toInt() and 0xFF) != PROTOCOL_UDP) return null
        return parseUdpBody(
            version = 4,
            buffer = buffer,
            udpOffset = headerLength,
            end = end,
            source = buffer.copyOfRange(12, 16),
            destination = buffer.copyOfRange(16, 20)
        )
    }

    private fun parseIpv6(buffer: ByteArray, length: Int): UdpDatagram? {
        if (length < 48) return null
        var nextHeader = buffer[6].toInt() and 0xFF
        var offset = 40
        var guard = 0
        while (nextHeader != PROTOCOL_UDP) {
            if (guard++ > 8) return null
            when (nextHeader) {
                NEXT_HOP, ROUTING, DESTINATION_OPTIONS -> {
                    if (offset + 8 > length) return null
                    val extensionLength = ((buffer[offset + 1].toInt() and 0xFF) + 1) * 8
                    nextHeader = buffer[offset].toInt() and 0xFF
                    offset += extensionLength
                    if (offset > length) return null
                }
                FRAGMENT -> return null
                else -> return null
            }
        }
        val payloadLength = u16(buffer, 4)
        val end = if (payloadLength in 8..(length - offset)) offset + payloadLength else length
        return parseUdpBody(
            version = 6,
            buffer = buffer,
            udpOffset = offset,
            end = end,
            source = buffer.copyOfRange(8, 24),
            destination = buffer.copyOfRange(24, 40)
        )
    }

    private fun parseUdpBody(
        version: Int,
        buffer: ByteArray,
        udpOffset: Int,
        end: Int,
        source: ByteArray,
        destination: ByteArray
    ): UdpDatagram? {
        if (udpOffset + 8 > end) return null
        val sourcePort = u16(buffer, udpOffset)
        val destinationPort = u16(buffer, udpOffset + 2)
        val udpLength = u16(buffer, udpOffset + 4)
        val payloadLength = if (udpLength >= 8 && udpOffset + udpLength <= end) {
            udpLength - 8
        } else {
            end - udpOffset - 8
        }
        if (payloadLength < 0) return null
        val payload = buffer.copyOfRange(udpOffset + 8, udpOffset + 8 + payloadLength)
        return UdpDatagram(version, source, destination, sourcePort, destinationPort, payload)
    }

    private fun buildIpv4(query: UdpDatagram, payload: ByteArray): ByteArray {
        val totalLength = IPV4_HEADER + UDP_HEADER + payload.size
        val out = ByteArray(totalLength)
        out[0] = 0x45
        out[1] = 0
        putU16(out, 2, totalLength)
        putU16(out, 4, 0)
        putU16(out, 6, 0)
        out[8] = 64
        out[9] = PROTOCOL_UDP.toByte()
        query.destination.copyInto(out, 12, 0, 4)
        query.source.copyInto(out, 16, 0, 4)
        putU16(out, 10, checksum(out, 0, IPV4_HEADER))

        writeUdp(out, IPV4_HEADER, query, payload)
        val udpLength = UDP_HEADER + payload.size
        val pseudo = Sum()
        pseudo.add(query.destination, 0, 4)
        pseudo.add(query.source, 0, 4)
        pseudo.addByte(0)
        pseudo.addByte(PROTOCOL_UDP)
        pseudo.addU16(udpLength)
        pseudo.add(out, IPV4_HEADER, udpLength)
        putU16(out, IPV4_HEADER + 6, pseudo.value())
        return out
    }

    private fun buildIpv6(query: UdpDatagram, payload: ByteArray): ByteArray {
        val udpLength = UDP_HEADER + payload.size
        val out = ByteArray(IPV6_HEADER + udpLength)
        out[0] = 0x60
        putU16(out, 4, udpLength)
        out[6] = PROTOCOL_UDP.toByte()
        out[7] = 64
        query.destination.copyInto(out, 8, 0, 16)
        query.source.copyInto(out, 24, 0, 16)

        writeUdp(out, IPV6_HEADER, query, payload)
        val pseudo = Sum()
        pseudo.add(query.destination, 0, 16)
        pseudo.add(query.source, 0, 16)
        pseudo.addU16(udpLength)
        pseudo.addU16(0)
        pseudo.addU16(0)
        pseudo.addU16(PROTOCOL_UDP)
        pseudo.add(out, IPV6_HEADER, udpLength)
        putU16(out, IPV6_HEADER + 6, pseudo.value())
        return out
    }

    private fun writeUdp(out: ByteArray, offset: Int, query: UdpDatagram, payload: ByteArray) {
        putU16(out, offset, query.destinationPort)
        putU16(out, offset + 2, query.sourcePort)
        putU16(out, offset + 4, UDP_HEADER + payload.size)
        putU16(out, offset + 6, 0)
        payload.copyInto(out, offset + UDP_HEADER)
    }

    private fun checksum(buffer: ByteArray, offset: Int, length: Int): Int {
        val sum = Sum()
        sum.add(buffer, offset, length)
        return sum.value()
    }

    private fun u16(buffer: ByteArray, offset: Int): Int =
        ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)

    private fun putU16(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = ((value ushr 8) and 0xFF).toByte()
        buffer[offset + 1] = (value and 0xFF).toByte()
    }

    private const val IPV4_HEADER = 20
    private const val IPV6_HEADER = 40
    private const val UDP_HEADER = 8
    private const val PROTOCOL_UDP = 17
    private const val NEXT_HOP = 0
    private const val ROUTING = 43
    private const val FRAGMENT = 44
    private const val DESTINATION_OPTIONS = 60

    private class Sum {
        private var sum = 0L

        fun add(buffer: ByteArray, offset: Int, length: Int) {
            var i = offset
            val end = offset + length
            while (i + 1 < end) {
                addU16(((buffer[i].toInt() and 0xFF) shl 8) or (buffer[i + 1].toInt() and 0xFF))
                i += 2
            }
            if (i < end) addByte(buffer[i].toInt() and 0xFF)
        }

        fun addU16(value: Int) {
            sum += value.toLong() and 0xFFFF
            while (sum ushr 16 != 0L) {
                sum = (sum and 0xFFFF) + (sum ushr 16)
            }
        }

        fun addByte(value: Int) {
            sum += value.toLong() shl 8
        }

        fun value(): Int {
            while (sum ushr 16 != 0L) {
                sum = (sum and 0xFFFF) + (sum ushr 16)
            }
            val folded = sum.toInt() and 0xFFFF
            return if (folded == 0) 0xFFFF else folded.inv() and 0xFFFF
        }
    }
}
