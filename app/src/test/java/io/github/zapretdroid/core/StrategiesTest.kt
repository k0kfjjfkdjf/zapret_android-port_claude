package io.github.zapretdroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategiesTest {
    @Test
    fun idsAreUnique() {
        val ids = Strategies.presets.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(Strategies.CUSTOM !in ids)
    }

    @Test
    fun presetsDoNotSetListenAddress() {
        // --ip/--port are added by the service; a preset overriding them would break the tunnel.
        Strategies.presets.forEach { preset ->
            val args = splitArgs(preset.args)
            assertTrue(preset.id, args.isNotEmpty())
            assertTrue(preset.id, args.none { it in setOf("-i", "--ip", "-p", "--port") })
        }
    }
}
