package com.wendigo.suzgec.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleSetTest {

    private fun parse(vararg lines: String): FilterRules {
        return RuleParser.parse(lines.asSequence()).rules
    }

    @Test
    fun blocksDomainAndSubdomains() {
        val rules = parse("||doubleclick.net^")
        assertTrue(rules.isBlocked("doubleclick.net"))
        assertTrue(rules.isBlocked("ad.g.doubleclick.net"))
        assertFalse(rules.isBlocked("notdoubleclick.net"))
        assertFalse(rules.isBlocked("doubleclick.net.example.org"))
    }

    @Test
    fun exceptionWinsOverBlock() {
        val rules = parse(
            "||example.com^",
            "@@||good.example.com^"
        )
        assertTrue(rules.isBlocked("ads.example.com"))
        assertFalse(rules.isBlocked("good.example.com"))
    }

    @Test
    fun importantWinsOverException() {
        val rules = parse(
            "||tracker.io^",
            "@@||tracker.io^",
            "||tracker.io^$important"
        )
        assertTrue(rules.isBlocked("tracker.io"))
    }

    @Test
    fun skipsUnknownModifiers() {
        val result = RuleParser.parse(
            sequenceOf(
                "||script-ad.com^$script",
                "||image-ad.com^$image,third-party",
                "||plain-ad.com^"
            )
        )
        assertEquals(1, result.kept)
        assertEquals(2, result.skipped)
        assertTrue(result.rules.isBlocked("plain-ad.com"))
        assertFalse(result.rules.isBlocked("script-ad.com"))
    }

    @Test
    fun acceptsHostsFormat() {
        val rules = parse(
            "0.0.0.0 ads.hosts.net",
            "127.0.0.1 tracker.hosts.net",
            "plain.hosts.net"
        )
        assertTrue(rules.isBlocked("ads.hosts.net"))
        assertTrue(rules.isBlocked("tracker.hosts.net"))
        assertTrue(rules.isBlocked("plain.hosts.net"))
    }

    @Test
    fun rejectsInvalidPatterns() {
        assertNull(RuleParser.toDomain("||example.com/path|"))
        assertNull(RuleParser.toDomain("||*.wild.com^"))
        assertNull(RuleParser.toDomain("localhost"))
        assertNull(RuleParser.toDomain("1.2.3.4"))
        assertNull(RuleParser.toDomain("||exa mple.com^"))
        assertEquals("example.com", RuleParser.toDomain("||example.com^"))
        assertEquals("sub.example.com", RuleParser.toDomain("*.sub.example.com"))
        assertEquals("xn--80ak6aa92e.com", RuleParser.toDomain("||xn--80ak6aa92e.com^"))
    }

    @Test
    fun handlesTrailingDotAndUppercase() {
        val rules = parse("||MixedCase.Example^")
        assertTrue(rules.isBlocked("MIXEDCASE.EXAMPLE."))
    }

    @Test
    fun emptyRulesetNeverBlocks() {
        assertFalse(FilterRules.EMPTY.isBlocked("example.com"))
        assertEquals(FilterRules.Verdict.PASS, FilterRules.EMPTY.verdict("example.com"))
    }
}
