package io.github.zapretdroid.core

import io.github.zapretdroid.core.ConnMonitor.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnMonitorTest {
    private val lines = listOf(
        "10-17 12:00:00.000 byedpi | conn 5: old.example -> 1.1.1.1:443, TLS, group 0",
        "10-17 12:28:57.886 vpn | ===== VPN session start (io.github.zapretdroid.START) =====",
        "10-17 12:29:00.100 byedpi | conn 80: gateway.discord.gg -> 162.159.135.234:443, TLS, group 0",
        "10-17 12:29:06.700 byedpi | conn 80 closed: gateway.discord.gg, up 900 B, down 24642 B, rounds 3, 6600 ms",
        "10-17 12:29:07.000 byedpi | conn 81: block detected (ssl_err) on discord.com (162.159.128.233) with group 0, no group left",
        "10-17 12:29:08.000 byedpi | tcp 82: slow connect to 162.159.138.232: 1045 ms",
        "10-17 12:29:09.000 byedpi | udp 75: -> ::ffff:66.22.196.10:50003, discord, group 2",
        "10-17 12:29:09.300 byedpi | udp 75: discord IP discovery RETRY #1 after 251 ms without a reply",
        "10-17 12:29:09.800 byedpi | udp 75: first reply after 752 ms (3 datagrams and 18 fakes sent before it)",
        "10-17 12:29:10.000 byedpi | udp 76: -> ::ffff:74.125.250.129:19302, stun, group 0",
        "10-17 12:29:15.000 byedpi | udp 76 closed: -> ::ffff:74.125.250.129:19302 stun, out 2 pkts/40 B, in 0 pkts/0 B, fakes 12, max gap out 2501 ms, in 0 ms, stalls 0, 5 s  <-- NO REPLY",
    )

    @Test
    fun parsesTheLastSession() {
        val events = ConnMonitor.parse(lines)
        assertEquals(
            listOf(Kind.CONNECT, Kind.FREEZE, Kind.BLOCK, Kind.TCP_SLOW, Kind.UDP_START, Kind.UDP_RETRY, Kind.UDP_REPLY, Kind.UDP_START, Kind.UDP_NO_REPLY),
            events.map { it.kind },
        )
        assertTrue(events.none { it.host == "old.example" })
        assertTrue(events[0].discord && events[1].discord && events[4].discord && events[6].discord)
        assertEquals("66.22.196.10:50003", events[4].host)
        val s = ConnMonitor.summarize(events)
        assertEquals(1, s.freezes)
        assertEquals(1, s.blocks)
        assertEquals(1, s.retries)
        assertEquals(1, s.udpNoReply)
    }

    @Test
    fun namedUdpFlows() {
        val events = ConnMonitor.parse(
            listOf(
                "10-17 13:00:00.000 vpn | ===== VPN session start (io.github.zapretdroid.START) =====",
                "10-17 13:00:01.000 byedpi | udp 6: -> ::ffff:66.22.196.10:50003, other, host c-fra06.discord.media (dns), group 1",
                "10-17 13:00:02.000 byedpi | udp 7: -> 142.250.74.14:443, quic, host unknown (SNI in a later Initial), group 3",
                "10-17 13:00:02.050 byedpi | udp 7: host rr1---sn-abc.googlevideo.com (sni in Initial 2), group 3 -> 0",
            ),
        )
        assertEquals(listOf(Kind.UDP_START, Kind.UDP_START, Kind.UDP_START), events.map { it.kind })
        assertEquals("c-fra06.discord.media", events[0].host)
        assertTrue(events[0].discord)
        assertTrue(events[0].text.startsWith("c-fra06.discord.media (66.22.196.10:50003), other, group 1"))
        assertEquals("142.250.74.14:443", events[1].host)
        assertEquals("rr1---sn-abc.googlevideo.com", events[2].host)
        assertTrue(events[2].text.endsWith("group 3 -> 0"))
    }
}
