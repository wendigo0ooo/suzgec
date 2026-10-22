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

    @Test
    fun extractsCnameTargetsFromAnswer() {
        val query = DnsWire.buildQuery("ads.example.com", DnsWire.TYPE_A, 0x4242)
        val qEnd = DnsWire.questionEnd(query)!!
        val target = encodeName("tracker.evil.net")
        val header = query.copyOf(qEnd)
        DnsWire.setU16(header, 2, 0x8180)
        DnsWire.setU16(header, 6, 1)
        val body = java.io.ByteArrayOutputStream()
        body.write(header)
        body.write(0xC0)
        body.write(0x0C)
        body.write(0); body.write(5)
        body.write(0); body.write(1)
        body.write(byteArrayOf(0, 0, 0, 60))
        body.write((target.size shr 8) and 0xFF)
        body.write(target.size and 0xFF)
        body.write(target)
        val msg = body.toByteArray()
        assertEquals(listOf("tracker.evil.net"), DnsWire.cnameTargets(msg))
    }

    @Test
    fun aaaaBlockReturnsNoData() {
        val query = DnsWire.buildQuery("ads.example.org", DnsWire.TYPE_AAAA)
        val response = DnsWire.buildAddressAnswer(query, query.size, byteArrayOf(0, 0, 0, 0))
        assertNotNull(response)
        assertEquals(0, DnsWire.rcode(response!!))
        assertEquals(0, DnsWire.readU16(response, 6))
    }

    private fun encodeName(name: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (label in name.split('.')) {
            out.write(label.length)
            out.write(label.toByteArray())
        }
        out.write(0)
        return out.toByteArray()
    }
}
