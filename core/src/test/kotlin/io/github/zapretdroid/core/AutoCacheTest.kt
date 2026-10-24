package io.github.zapretdroid.core

import java.nio.file.Files
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue

class AutoCacheTest {
    @Test
    fun onlyStrategiesWithAutoGroupsGetACache() {
        assertTrue(AutoCache.hasAuto(listOf("-s1", "-A", "torst", "-s2")))
        assertTrue(AutoCache.hasAuto(listOf("-s1", "--auto=torst", "-s2")))
        assertTrue(AutoCache.hasAuto(listOf("-s1", "-Atorst", "-s2")))
        assertFalse(AutoCache.hasAuto(listOf("-s1", "--auto-mode", "1", "-d1")))
        assertFalse(AutoCache.hasAuto(listOf("-s1", "-d1")))
        // Groups the app adds for excluded hosts and routed apps detect nothing.
        assertFalse(AutoCache.hasAuto(listOf("-H", "x", "--auto=none", "-s1")))
        assertFalse(AutoCache.hasAuto(listOf("-A", "n", "-s1", "-A", "none,p=2")))
        assertTrue(AutoCache.hasAuto(listOf("-s1", "--auto=udp", "-a1")))
        assertTrue(AutoCache.hasAuto(listOf("-s1", "-A", "none", "-d1", "-A", "ssl_err")))
        val dir = Files.createTempDirectory("ac").toFile()
        assertEquals(emptyList<String>(), AutoCache.args(dir, listOf("-s1"), "wifi"))
        val args = AutoCache.args(dir, listOf("-s1", "-A", "torst", "-s2"), "wifi")
        assertEquals("--auto-cache", args[0])
        assertTrue(args[1].startsWith(dir.absolutePath) && args[1].endsWith(".cache"))
    }

    @Test
    fun fileDependsOnStrategyAndNetwork() {
        val a = listOf("-s1", "-A", "torst", "-s2")
        assertEquals(AutoCache.fileName(a, "wifi"), AutoCache.fileName(a.toList(), "wifi"))
        assertNotEquals(AutoCache.fileName(a, "wifi"), AutoCache.fileName(a, "mobile"))
        assertNotEquals(AutoCache.fileName(a, "wifi"), AutoCache.fileName(listOf("-s1", "-A", "torst", "-s3"), "wifi"))
        // Arguments are separated: "-s1" "2" is not "-s12".
        assertNotEquals(AutoCache.fileName(listOf("-s1", "2"), "wifi"), AutoCache.fileName(listOf("-s12"), "wifi"))
        assertFalse('/' in AutoCache.fileName(a, "../x"))
    }

    @Test
    fun oldFilesArePruned() {
        val dir = Files.createTempDirectory("ac").toFile()
        val files = (0 until AutoCache.KEEP + 4).map { i ->
            java.io.File(dir, "wifi-$i.cache").apply { writeText("x"); setLastModified(1_000_000L * (i + 1)) }
        }
        val now = 10_000_000_000L
        java.io.File(dir, "wifi-x.cache.tmp").apply { writeText("x"); setLastModified(now - 120_000) }
        // Being written by an engine right now.
        java.io.File(dir, "wifi-y.cache.tmp").apply { writeText("x"); setLastModified(now - 1_000) }
        val current = java.io.File(dir, "mobile-cur.cache").apply { writeText("x") }
        AutoCache.prune(dir, current, now)
        val left = dir.listFiles()!!.map { it.name }.toSet()
        assertTrue("mobile-cur.cache" in left)
        assertFalse("wifi-x.cache.tmp" in left)
        assertTrue("wifi-y.cache.tmp" in left)
        assertEquals(AutoCache.KEEP + 2, left.size)
        // The newest ones stay.
        assertTrue(files.takeLast(AutoCache.KEEP).all { it.name in left })
    }
}
