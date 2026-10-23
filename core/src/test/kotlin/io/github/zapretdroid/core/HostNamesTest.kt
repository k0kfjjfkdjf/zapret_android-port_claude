package io.github.zapretdroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostNamesTest {
    @Test
    fun normalizesZapretAndLooseInput() {
        val text = """
            # comment line
            YouTube.com
            *.googlevideo.com   # inline comment
            https://www.instagram.com/explore/?a=1, x.com:443
            .ggpht.com.
            пример.рф
            youtube.com
            bad_host!  [::1]
        """.trimIndent()
        val result = HostNames.normalize(text)
        assertEquals(
            listOf("youtube.com", "googlevideo.com", "www.instagram.com", "x.com", "ggpht.com", "xn--e1afmkfd.xn--p1ai"),
            result.hosts,
        )
        assertEquals(listOf("bad_host!", "[::1]"), result.invalid)
    }

    @Test
    fun acceptsWindowsZapretAndRouterListFormats() {
        // Flowseal marks exact matches with "^"; itdoginfo lists whole zones as ".ua".
        val result = HostNames.normalize("^dns.google\n^cloudflare-dns.com\n.ua\ndiscord.media\n")
        assertEquals(listOf("dns.google", "cloudflare-dns.com", "ua", "discord.media"), result.hosts)
        assertTrue(result.invalid.isEmpty())
    }

    @Test
    fun validNames() {
        assertEquals(true, HostNames.isValidName("youtube_2"))
        assertEquals(false, HostNames.isValidName("../etc"))
        assertEquals(false, HostNames.isValidName(""))
    }
}
