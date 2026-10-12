package io.github.zapretdroid.core

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoHostTest {
    @Test
    fun aggregatesByHost() {
        val entries = AutoHost.parse(
            listOf(
                "100 www.youtube.com torst",
                "105 Discord.com ssl_err",
                "110 www.youtube.com ssl_err",
                "garbage",
                "120 www.youtube.com torst",
            ),
        )
        assertEquals(listOf("www.youtube.com", "discord.com"), entries.map { it.host })
        assertEquals(3, entries[0].count)
        assertEquals(120L, entries[0].lastSeen)
        assertEquals(setOf("torst", "ssl_err"), entries[0].reasons)
    }
}
