package io.github.zapretdroid.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalStrategyTest {
    private fun safe(vararg args: String) = ExternalStrategy.isSafe(args.toList())

    @Test
    fun ordinaryStrategiesPass() {
        assertTrue(safe("-o1", "-a1", "-r-5+se"))
        assertTrue(safe("--split", "1+s", "--disorder", "-1", "--fake", "-1", "--ttl", "8"))
        assertTrue(safe("-Kt,h", "-H", "{list:youtube}", "--fake-data", "{fake:tls_google}", "-A", "torst"))
        assertTrue(safe("--ipset", "{list:discord-ip}", "--pf", "443", "--conn-log", "--udp-fake", "2"))
        assertTrue(safe("-Mhostcase,hostpad=64", "-s", "sniext+1"))
        // A value glued to its option: "-n" takes "yandex.ru", whose 'y' is not -y.
        assertTrue(safe("-n", "yandex.ru", "-nyandex.ru"))
    }

    @Test
    fun proxiesAndDnsRedirectionAreRefused() {
        // Without a scheme there is no '/', and the engine still takes it as a SOCKS5 proxy.
        assertFalse(safe("-o1", "-C", "203.0.113.5:1080"))
        assertFalse(safe("-C203.0.113.5:1080"))
        assertFalse(safe("-NC203.0.113.5:1080")) // -N, then -C with the rest as its value
        assertFalse(safe("--connect-to", "203.0.113.5:1080"))
        assertFalse(safe("--connect-to=203.0.113.5:1080"))
        assertFalse(safe("--connect", "203.0.113.5:1080")) // getopt_long takes unambiguous prefixes
        assertFalse(safe("--dns-redirect", "8.8.8.8=203.0.113.5:53"))
        assertFalse(safe("--dns-r=8.8.8.8=203.0.113.5:53"))
        assertFalse(safe("--udp-proxy"))
        assertFalse(safe("--names-dns", "203.0.113.5"))
        assertFalse(safe("--conn-ip", "10.0.0.1"))
    }

    @Test
    fun listenOptionsFilesAndVariablesAreRefused() {
        assertFalse(safe("-i", "0.0.0.0"))
        assertFalse(safe("-p1080"))
        assertFalse(safe("--port=1080"))
        assertFalse(safe("--po", "1080"))
        assertFalse(safe("-E"))
        assertFalse(safe("-H", "/data/data/io.github.zapretdroid/files/x"))
        assertFalse(safe("--cache-file", "out.txt"))
        assertFalse(safe("--autohost-log", "x"))
        assertFalse(safe("-y", "x"))
        assertFalse(safe("--udp-proto-fake", "quic:x"))
        assertFalse(safe("-\$c", "203.0.113.5:1080"))
    }

    @Test
    fun valuesThatLookLikeOptionsAreNotMisread() {
        // "-1", "-5+se": values of the previous option.
        assertTrue(safe("--split", "-1", "-r", "-5+se"))
        // "--ipset" is not --ip; "--conn-log" is not --conn-ip.
        assertTrue(safe("--ipset", "{list:x}", "--conn-log"))
    }

    @Test
    fun autoIsNotTakenForAutoCacheOrAutohostLog() {
        // getopt_long takes the exact name "auto" before a prefix of auto-cache/autohost-log.
        assertTrue(safe("-s1", "--auto", "torst", "-s2"))
        assertTrue(safe("-s1", "--auto=torst", "-s2"))
        assertTrue(safe("-s1", "-A", "torst", "-s2"))
        assertFalse(safe("--auto-cache", "x"))
        assertFalse(safe("--auto-c=x"))
        assertFalse(safe("--autoh", "x"))
    }
}
