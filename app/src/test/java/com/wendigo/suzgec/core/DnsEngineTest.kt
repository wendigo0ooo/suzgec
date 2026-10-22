package com.wendigo.suzgec.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DnsEngineTest {

    private class FakeUpstream(private val answer: (ByteArray) -> ByteArray?) : Upstream {
        override val label: String = "fake"
        override fun query(packet: ByteArray): ByteArray? = answer(packet)
    }

    @Test
    fun blocksListedHost() {
        var blockedHost: String? = null
        val engine = DnsEngine(
            zeroAddressMode = true,
            upstream = FakeUpstream { error("upstream çağrılmamalı") },
            rulesProvider = {
                FilterRules.build(
                    mutableSetOf("ads.example.com"),
                    mutableSetOf(),
                    mutableSetOf()
                )
            },
            onResult = { host, blocked -> if (blocked) blockedHost = host }
        )

        val query = DnsWire.buildQuery("ads.example.com")
        val answer = engine.handle(query)
        assertNotNull(answer)
        assertEquals(0, DnsWire.rcode(answer!!))
        assertEquals(1, DnsWire.readU16(answer, 6))
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), answer.copyOfRange(answer.size - 4, answer.size))
        assertEquals("ads.example.com", blockedHost)
    }

    @Test
    fun forwardsAllowedHost() {
        val upstreamAnswer = { packet: ByteArray ->
            DnsWire.buildAddressAnswer(packet, packet.size, byteArrayOf(1, 2, 3, 4))
        }
        val engine = DnsEngine(
            zeroAddressMode = true,
            upstream = FakeUpstream(upstreamAnswer),
            rulesProvider = { FilterRules.EMPTY }
        )
        val query = DnsWire.buildQuery("example.com")
        val answer = engine.handle(query)!!
        assertEquals(0, DnsWire.rcode(answer))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), answer.copyOfRange(answer.size - 4, answer.size))
    }

    @Test
    fun upstreamFailureReturnsServfail() {
        val engine = DnsEngine(
            zeroAddressMode = true,
            upstream = FakeUpstream { null },
            rulesProvider = { FilterRules.EMPTY }
        )
        val answer = engine.handle(DnsWire.buildQuery("example.com"))!!
        assertEquals(2, DnsWire.rcode(answer))
    }

    @Test
    fun ignoresResponsePackets() {
        val engine = DnsEngine(
            zeroAddressMode = true,
            upstream = FakeUpstream { null },
            rulesProvider = { FilterRules.EMPTY }
        )
        val query = DnsWire.buildQuery("example.com")
        val response = DnsWire.buildAddressAnswer(query, query.size, byteArrayOf(1, 1, 1, 1))!!
        assertNull(engine.handle(response))
    }
}
