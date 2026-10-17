package io.github.zapretdroid.core

import org.junit.Assert.assertEquals
import org.junit.Test

class StrategyArgsTest {
    private val strategy = listOf("--disorder", "1")

    @Test
    fun allHosts() {
        assertEquals(strategy, StrategyArgs.build(strategy, HostScope.ALL, null, null))
    }

    @Test
    fun onlyList() {
        assertEquals(
            listOf("--hosts", "/l.txt", "--disorder", "1"),
            StrategyArgs.build(strategy, HostScope.ONLY_LIST, "/l.txt", null),
        )
    }

    @Test
    fun onlyListLimitsEveryGroup() {
        assertEquals(
            listOf(
                "--hosts", "/l.txt", "--disorder", "1",
                "--auto=none", "--hosts", "/l.txt", "--proto", "u", "--udp-fake", "6",
                "-A", "torst", "--hosts", "/l.txt", "--split", "2",
                "-An", "-H:discord.com", "--oob", "1",
            ),
            StrategyArgs.build(
                listOf("--disorder", "1", "--auto=none", "--proto", "u", "--udp-fake", "6", "-A", "torst", "--split", "2", "-An", "-H:discord.com", "--oob", "1"),
                HostScope.ONLY_LIST, "/l.txt", null,
            ),
        )
    }

    @Test
    fun exceptList() {
        assertEquals(
            listOf("--hosts", "/l.txt", "--auto=none", "--disorder", "1"),
            StrategyArgs.build(strategy, HostScope.EXCEPT_LIST, "/l.txt", null),
        )
    }

    @Test
    fun autohostAddsDetectionGroupAndTimeout() {
        assertEquals(
            listOf("--disorder", "1", "--autohost-log", "/a.log", "--timeout", "4", "--auto=torst,redirect,ssl_err", "--disorder", "1"),
            StrategyArgs.build(strategy, HostScope.ALL, null, "/a.log"),
        )
        assertEquals(
            listOf("--timeout", "2", "--autohost-log", "/a.log", "--auto=torst,redirect,ssl_err"),
            StrategyArgs.build(listOf("--timeout", "2"), HostScope.ALL, null, "/a.log"),
        )
    }
}
