package io.github.zapretdroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptEventsTest {
    @Test
    fun parsesLogLines() {
        assertEquals(ScriptEvents.Event("block", "discord.com", "tcp"),
            ScriptEvents.parse("10-19 byedpi | conn 81: block detected (ssl_err) on discord.com (162.159.128.233) with group 0, no group left"))
        assertEquals(ScriptEvents.Event("block", "www.youtube.com", "udp"),
            ScriptEvents.parse("10-24 byedpi | udp 12: block detected (udp) on www.youtube.com (::ffff:142.250.1.1) with group 0, trying group 1"))
        assertEquals(ScriptEvents.Event("noreply", "www.youtube.com", "tcp"),
            ScriptEvents.parse("byedpi | conn 4 closed: www.youtube.com, up 517 B, down 0 B, rounds 1, 5000 ms"))
        assertEquals(ScriptEvents.Event("freeze", "discord.com", "tcp"),
            ScriptEvents.parse("byedpi | conn 80 closed: discord.com, up 900 B, down 24642 B, rounds 3, 6600 ms"))
        assertNull(ScriptEvents.parse("byedpi | conn 7 closed: ok.example, up 900 B, down 90000 B, rounds 9, 900 ms"))
        assertEquals("proxy-down", ScriptEvents.parse("byedpi | group 1: proxy 127.0.0.1:9050 refuses connections: is it running?")?.reason)
        assertEquals(ScriptEvents.Event("udp-noreply", "74.125.250.129", "udp"),
            ScriptEvents.parse("byedpi | udp 76 closed: -> ::ffff:74.125.250.129:19302 stun, out 2 pkts/40 B, in 0 pkts/0 B, 5 s  <-- NO REPLY"))
    }

    private fun rules(text: String) = Script.compile(text, Script.Env("wifi")).also { assertEquals(emptyList<Script.Issue>(), it.errors) }.rules

    @Test
    fun masksProtoAndOrder() {
        val r = rules("@on any *.doubleclick.net -> ignore\n@on freeze,noreply discord.gg,*.discord.com tcp -> notify\n@on proxy-down -> notify")
        assertTrue(ScriptEvents.maskMatches("*.discord.com", "discord.com"))
        assertTrue(ScriptEvents.maskMatches("discord.gg", "cdn.discord.gg"))
        val m = ScriptEvents.Matcher(r)
        assertNull(m.offer(ScriptEvents.Event("noreply", "ad.doubleclick.net", "tcp")))
        assertEquals(2, m.offer(ScriptEvents.Event("freeze", "gateway.discord.com", "tcp"))?.index)
        assertNull(m.offer(ScriptEvents.Event("freeze", "example.com", "tcp")))
        assertEquals(3, m.offer(ScriptEvents.Event("proxy-down", "127.0.0.1:9050", "tcp"))?.index)
    }

    @Test
    fun thresholdsAndCooldown() {
        var t = 0L
        val m = ScriptEvents.Matcher(rules("@on noreply x3/1m -> notify"), now = { t }, cooldownMs = 600_000L)
        val e = ScriptEvents.Event("noreply", "a.com", "tcp")
        assertNull(m.offer(e)); t += 10_000
        assertNull(m.offer(e)); t += 10_000
        assertEquals(1, m.offer(e)?.index)            // the third within a minute
        t += 10_000
        repeat(3) { assertNull(m.offer(e)) }           // cooling down
        t += 700_000
        assertNull(m.offer(e)); assertNull(m.offer(e))
        assertEquals(1, m.offer(e)?.index)
        // events spread out over more than the window never add up
        t += 700_000
        repeat(5) { assertNull(m.offer(ScriptEvents.Event("noreply", "b.com", "tcp"))); t += 61_000 }
    }
}
