package com.wendigo.suzgec.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsWireTest {

    @Test
    fun parsesQuestionName() {
        val query = DnsWire.buildQuery("Ads.Example.COM", DnsWire.TYPE_A, 0x1234)
        assertEquals("ads.example.com", DnsWire.questionName(query))
        assertEquals(0x1234, DnsWire.transactionId(query))
        assertEquals(DnsWire.TYPE_A, DnsWire.queryType(query, DnsWire.questionEnd(query)!!))
    }

    @Test
    fun detectsResponses() {
        val query = DnsWire.buildQuery("example.com")
        assertTrue(!DnsWire.isResponse(query))
        val response = DnsWire.buildError(query, query.size, DnsWire.RCODE_NXDOMAIN)
        assertTrue(DnsWire.isResponse(response))
        assertEquals(DnsWire.RCODE_NXDOMAIN, DnsWire.rcode(response))
    }

    @Test
    fun errorResponseKeepsTransactionIdAndQuestion() {
        val query = DnsWire.buildQuery("doubleclick.net", DnsWire.TYPE_A, 0x77AA)
        val response = DnsWire.buildError(query, query.size, DnsWire.RCODE_NXDOMAIN)
        assertEquals(0x77AA, DnsWire.transactionId(response))
        assertEquals("doubleclick.net", DnsWire.questionName(response))
        assertEquals(1, DnsWire.readU16(response, 4))
        assertEquals(0, DnsWire.readU16(response, 6))
    }

    @Test
    fun zeroAddressAnswerHasSingleRecord() {
        val query = DnsWire.buildQuery("ads.example.org")
        val response = DnsWire.buildAddressAnswer(query, query.size, byteArrayOf(0, 0, 0, 0))
        assertNotNull(response)
        assertEquals(0, DnsWire.rcode(response!!))
        assertEquals(1, DnsWire.readU16(response, 6))
    }

    @Test
    fun recursionDesiredBitDoesNotPolluteRcode() {
        val query = DnsWire.buildQuery("example.com")
        val response = DnsWire.buildError(query, query.size, 0)
        assertEquals(0, DnsWire.rcode(response))
        assertTrue((DnsWire.readU16(response, 2) and 0x0100) != 0)
    }

    @Test
    fun rejectsTruncatedPacket() {
        val query = DnsWire.buildQuery("example.com")
        val sliced = query.copyOf(14)
        assertNull(DnsWire.questionEnd(sliced))
        assertNull(DnsWire.questionName(sliced))
    }
}
