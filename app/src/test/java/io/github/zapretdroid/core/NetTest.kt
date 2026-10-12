package io.github.zapretdroid.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetTest {
    @Test
    fun acceptsAddresses() {
        listOf("8.8.8.8", "1.1.1.1", "0.0.0.0", "255.255.255.255", "2001:4860:4860::8888", "::1").forEach {
            assertTrue(it, isIpLiteral(it))
        }
    }

    @Test
    fun rejectsEverythingElse() {
        listOf("", "dns.google", "256.1.1.1", "1.2.3", "01.2.3.4", "8.8.8.8 ", "localhost", "fe80::1%wlan0").forEach {
            assertFalse(it, isIpLiteral(it))
        }
    }
}
