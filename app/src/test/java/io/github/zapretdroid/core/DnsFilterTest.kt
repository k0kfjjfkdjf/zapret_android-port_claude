package io.github.zapretdroid.core

import io.github.zapretdroid.test.DnsClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress

class DnsFilterTest {
    @Test
    fun parsesCommonFormats() {
        val hashes = DnsFilter.parse(
            sequenceOf(
                "# comment", "! adblock comment", "", "ads.example.com", "0.0.0.0 tracker.example.net # inline",
                "||Banner.Example.org^", "*.wild.example", "localhost", "127.0.0.1 localhost", "bad name!", "ads.example.com",
            ),
        )
        assertEquals(4, hashes.size)
        assertTrue(hashes.toList() == hashes.sorted())
    }

    @Test
    fun matchesSubdomainsNotParentsOrTld() {
        val set = DnsFilter.HashSet(DnsFilter.parse(sequenceOf("ads.example.com", "com")))
        assertTrue(set.matches("ads.example.com."))
        assertTrue(set.matches("x.y.ADS.example.com"))
        assertFalse(set.matches("example.com"))
        assertFalse(set.matches("notads.example.com"))
        // A bare TLD in a list does not block everything under it.
        assertFalse(set.matches("google.com"))
    }

    @Test
    fun allowWins() {
        val m = DnsFilter.Matcher(
            listOf("ads" to DnsFilter.HashSet(DnsFilter.parse(sequenceOf("doubleclick.net")))),
            DnsFilter.HashSet(DnsFilter.parse(sequenceOf("ok.doubleclick.net"))),
        )
        assertEquals("ads", m.blocked("ad.doubleclick.net."))
        assertNull(m.blocked("ok.doubleclick.net."))
        assertNull(m.blocked("youtube.com."))
    }

    @Test
    fun fileRoundTrip() {
        val f = File.createTempFile("dnsfilter", ".bin")
        try {
            val hashes = DnsFilter.parse(sequenceOf("a.example", "b.example", "c.example"))
            DnsFilter.write(f, hashes)
            assertArrayEquals(hashes, DnsFilter.read(f))
        } finally {
            f.delete()
        }
    }

    @Test
    fun blockedAnswers() {
        val a = SecureDns.blockedAnswer(DnsClient.buildQuery(0x1234, "ads.example.com"), 1)
        assertEquals(listOf(InetAddress.getByName("0.0.0.0")), DnsClient.parseResponse(a, 0x1234))
        // Not A/AAAA: NXDOMAIN, no answer.
        val q = DnsClient.buildQuery(0x4321, "ads.example.com").also { it[it.size - 3] = 16 } // TXT
        val n = SecureDns.blockedAnswer(q, 16)
        assertEquals(3, n[3].toInt() and 0x0f)
        assertEquals(0, n[7].toInt())
    }
}
