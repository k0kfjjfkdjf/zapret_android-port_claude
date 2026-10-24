package io.github.zapretdroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppCollectTest {
    @Test
    fun parsesOnlyTheLastCollectSession() {
        val lines = listOf(
            "10-16 18:00:00.000 byedpi | conn 5: old.example -> 1.1.1.1:443, TLS, group 0",
            "10-16 18:01:00.000 vpn | collect mode: only com.discord",
            "10-16 18:01:01.000 byedpi | conn 7: discord.com -> 162.159.135.232:443, TLS, group 0",
            "10-16 18:01:01.100 byedpi | conn 8: discord.com -> 162.159.135.232:443, TLS, group 0",
            "10-16 18:01:01.200 byedpi | conn 9: cdn.discordapp.com -> 162.159.130.233:443, TLS, group 1",
            "10-16 18:01:01.300 byedpi | conn 10: 66.22.196.10 -> 66.22.196.10:443, other, group 0",
            "10-16 18:01:02.000 byedpi | udp 6: -> 66.22.196.10:50003, discord, group 2",
            "10-16 18:01:03.000 byedpi | conn 9: block detected (torst) on cdn.discordapp.com (162.159.130.233) with group 1, no group left",
            "10-16 18:01:04.000 byedpi | conn 9 closed: cdn.discordapp.com, up 500 B, down 0 B, rounds 1, 300 ms",
        )
        val r = AppCollect.parse(lines)
        assertEquals(mapOf("discord.com" to 2, "cdn.discordapp.com" to 1), r.domains)
        assertFalse("old.example" in r.domains)
        assertEquals(mapOf("66.22.196.10" to 1), r.ips)
        assertEquals(mapOf("66.22.196.10:50003 discord" to 1), r.udp)
        assertEquals(mapOf("cdn.discordapp.com" to 1), r.blocks)
    }

    @Test
    fun namedUdpFlowsAddDomains() {
        val r = AppCollect.parse(
            listOf(
                "10-16 18:01:00.000 vpn | collect mode: only com.discord",
                "10-16 18:01:02.000 byedpi | udp 6: -> 66.22.196.10:50003, other, host c-fra06.discord.media (dns), group 2",
                "10-16 18:01:03.000 byedpi | udp 7: -> 66.22.196.11:50004, other, host unknown (no SNI, not in DNS answers), group 2",
                "10-16 18:01:04.000 byedpi | udp 8: -> 66.22.196.12:50005, discord, host discord.media (discord ip discovery), group 2",
            ),
        )
        assertEquals(mapOf("c-fra06.discord.media" to 1), r.domains)
        assertEquals(mapOf("66.22.196.10:50003 other" to 1, "66.22.196.11:50004 other" to 1, "66.22.196.12:50005 discord" to 1), r.udp)
    }

    @Test
    fun udpBlocksCount() {
        val r = AppCollect.parse(
            listOf(
                "10-24 18:01:00.000 vpn | collect mode: only com.google.android.youtube",
                "10-24 18:01:02.000 byedpi | udp 6: block detected (udp) on www.youtube.com (142.250.1.1) with group 0, trying group 1",
            ),
        )
        assertEquals(mapOf("www.youtube.com" to 1), r.blocks)
    }
}
