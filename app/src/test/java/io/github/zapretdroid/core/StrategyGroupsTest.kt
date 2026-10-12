package io.github.zapretdroid.core

import org.junit.Assert.assertEquals
import org.junit.Test

class StrategyGroupsTest {
    @Test
    fun describesGroups() {
        val lines = StrategyGroups.describe(
            listOf("--ip", "127.0.0.1", "--port", "1", "--conn-log", "--disorder", "1", "--auto=torst", "--tlsrec", "1+s", "-An", "--proto", "u"),
        )
        assertEquals(
            listOf(
                "strategy group 0: --disorder 1",
                "strategy group 1 (after a detected block: torst): --tlsrec 1+s",
                "strategy group 2 (if the previous group does not apply): --proto u",
                "connections that match no group pass without desync",
            ),
            lines,
        )
    }
}
