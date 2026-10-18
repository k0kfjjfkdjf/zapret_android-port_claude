package io.github.zapretdroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsServersTest {
    private val pub = "RWQBAgMEBQYHCAu8NGpXZnw4ASC9nH/X5R0sX9/qN80vW/QFssa/by14"
    private val data = "## one\n\nA test server.\n\nsdns://AgcAAAAAAAAABzEuMS4xLjEAEmNsb3VkZmxhcmUtZG5zLmNvbQovZG5zLXF1ZXJ5\n".toByteArray()
    private val legacy = "untrusted comment: test\nRWQBAgMEBQYHCJJqbQb/zA0uMxkYraEEtq9niSPeZSvWC+dHybGaxahbd97Z6iVXgcC38CH5oCIdTFjBqIZWDxlAvc9BxWRs1Qc=\n" +
        "trusted comment: timestamp:1700000000\tfile:test.md\nbquD61B4JKe7L0TFdflSKT0kymCE0dZKxqpBDJ6WR5LRiRtX3T/nxWqTn/CHDhA505NHikPsV1LA/dwxbuX0CQ=="
    private val prehashed = "untrusted comment: test\nRUQBAgMEBQYHCIFOnXM2GtUcwPJqKQMRpLqVDfMkZqTlb/H8eBj9q83xHtW7YQ/g+pGxMvnRS8v7s4Jp3ltwkVPRB/R6cGNgUgA=\n" +
        "trusted comment: timestamp:1700000000\tfile:test.md\nvOVRj591LLxURqJPXBv2h2WzKyLR26N1J12IdxoYucJwUsFJL1t30QUEAmWMaeBamkgYA/7wov0huc+sg8TeAg=="

    @Test
    fun minisign() {
        Minisign.verify(pub, data, legacy)
        Minisign.verify(pub, data, prehashed)
        val changed = data.copyOf().also { it[5] = 'x'.code.toByte() }
        assertTrue(runCatching { Minisign.verify(pub, changed, legacy) }.isFailure)
        assertTrue(runCatching { Minisign.verify(pub, changed, prehashed) }.isFailure)
        // A changed trusted comment.
        assertTrue(runCatching { Minisign.verify(pub, data, legacy.replace("1700000000", "1700000001")) }.isFailure)
        // The real list's key: another key id.
        assertTrue(runCatching { Minisign.verify(DnsServers.MINISIGN_KEY, data, legacy) }.isFailure)
    }

    @Test
    fun stamps() {
        val doh = DnsStamp.parse("sdns://AgcAAAAAAAAADTIxNy4xNjkuMjAuMjIADWRucy5hYS5uZXQudWsKL2Rucy1xdWVyeQ")
        assertEquals(DnsStamp.Protocol.DOH, doh.protocol)
        assertEquals("217.169.20.22", doh.host)
        assertEquals(443, doh.port)
        assertEquals("dns.aa.net.uk", doh.hostname)
        assertEquals("/dns-query", doh.path)
        assertTrue(doh.dnssec && doh.noLog && doh.noFilter)
        val dc = DnsStamp.parse("sdns://AQMAAAAAAAAAETk0LjE0MC4xNC4xNDo1NDQzINErR_JS3PLCu_iZEIbq95zkSV2LFsigxDIuUso_OQhzIjIuZG5zY3J5cHQuZGVmYXVsdC5uczEuYWRndWFyZC5jb20")
        assertEquals(DnsStamp.Protocol.DNSCRYPT, dc.protocol)
        assertEquals("94.140.14.14", dc.host)
        assertEquals(5443, dc.port)
        assertEquals("2.dnscrypt.default.ns1.adguard.com", dc.hostname)
        assertTrue(dc.dnssec && dc.noLog && !dc.noFilter)
        assertEquals(32, dc.providerKey.size)
        assertTrue(runCatching { DnsStamp.parse("sdns://BQcAAAAAAAAA") }.isFailure)
    }

    @Test
    fun parsesList() {
        val md = """
            # public-resolvers

            Intro text.

            ## first

            A DoH server.
            Second line.

            sdns://AgcAAAAAAAAADTIxNy4xNjkuMjAuMjIADWRucy5hYS5uZXQudWsKL2Rucy1xdWVyeQ
            sdns://AgcAAAAAAAAADTIxNy4xNjkuMjAuMjMADWRucy5hYS5uZXQudWsKL2Rucy1xdWVyeQ

            ## relay-only

            Unsupported stamp type.

            sdns://gRE1MS4xNTguMTY2Ljk3OjQ0Mw

            ## second

            DNSCrypt.

            sdns://AQMAAAAAAAAAETk0LjE0MC4xNC4xNDo1NDQzINErR_JS3PLCu_iZEIbq95zkSV2LFsigxDIuUso_OQhzIjIuZG5zY3J5cHQuZGVmYXVsdC5uczEuYWRndWFyZC5jb20
        """.trimIndent()
        val list = DnsServers.parse(md)
        assertEquals(listOf("first", "second"), list.map { it.name })
        assertEquals("A DoH server. Second line.", list[0].description)
        assertEquals(2, list[0].stamps.size)
        assertTrue(list[1].matches("dnscrypt") && list[0].matches("SECOND LINE") && !list[0].matches("adguard"))
        val chosen = listOf("first" to "sdns://old", "gone" to "sdns://kept")
        assertEquals(listOf("first" to list[0].stamps[0], "gone" to "sdns://kept"), DnsServers.refreshed(chosen, list))
    }
}
