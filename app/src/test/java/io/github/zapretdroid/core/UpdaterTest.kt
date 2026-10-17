package io.github.zapretdroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test
    fun versions() {
        assertEquals(listOf(0, 9, 4), Updater.parse("v0.9.4"))
        assertEquals(listOf(0, 9, 4), Updater.parse("0.9.4-debug"))
        assertTrue(Updater.newer("0.9.10", "0.9.9"))
        assertTrue(Updater.newer("1.0", "0.9.9"))
        assertTrue(Updater.newer("0.9.5", "0.9.4-debug"))
        assertFalse(Updater.newer("0.9.4", "0.9.4-debug"))
        assertFalse(Updater.newer("0.9.3", "0.9.4"))
        assertFalse(Updater.newer("0.9", "0.9.0"))
    }
}
