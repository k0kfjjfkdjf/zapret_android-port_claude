package io.github.zapretdroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorProxyTest {
    @Test
    fun addresses() {
        assertTrue(TorProxy.isValid("127.0.0.1:9050"))
        assertTrue(TorProxy.isValid(" 10.0.0.2:1080 "))
        assertTrue(TorProxy.isValid("[::1]:2080"))
        // The engine takes no host names for -C.
        assertFalse(TorProxy.isValid("localhost:1080"))
        assertFalse(TorProxy.isValid("127.0.0.1"))
        assertFalse(TorProxy.isValid("127.0.0.256:1080"))
        assertFalse(TorProxy.isValid("127.0.0.1:0"))
        assertFalse(TorProxy.isValid("127.0.0.1:70000"))
    }

    @Test
    fun group() {
        assertEquals(listOf("--hosts", "/l", "-C", "socks5://127.0.0.1:9050", "--auto=none"), TorProxy.group("/l", "127.0.0.1:9050"))
        assertEquals(
            listOf("--hosts", "/l", "-C", "socks5://127.0.0.1:2080", "--udp-proxy", "--auto=none"),
            TorProxy.group("/l", "127.0.0.1:2080", udp = true),
        )
    }
}
