package io.github.zapretdroid.core

import org.junit.Assert.assertEquals
import org.junit.Test

class HostListsTest {
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
        val result = HostLists.normalize(text)
        assertEquals(
            listOf("youtube.com", "googlevideo.com", "www.instagram.com", "x.com", "ggpht.com", "xn--e1afmkfd.xn--p1ai"),
            result.hosts,
        )
        assertEquals(listOf("bad_host!", "[::1]"), result.invalid)
    }

    @Test
    fun validNames() {
        assertEquals(true, HostLists.isValidName("youtube_2"))
        assertEquals(false, HostLists.isValidName("../etc"))
        assertEquals(false, HostLists.isValidName(""))
    }
}
