package io.github.zapretdroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

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

    @Test
    fun parsesPresetsFile() {
        val entries = Strategies.parse(
            """
            # comment
            [auto]
            --disorder 1
            # a step
            --auto=torst --tlsrec 1+s
            [mine] My preset | Something
            --oob 3
            """.trimIndent(),
        )
        assertEquals(listOf("auto", "mine"), entries.map { it.id })
        assertEquals("--disorder 1 --auto=torst --tlsrec 1+s", entries[0].args)
        assertNull(entries[0].name)
        assertEquals("My preset", entries[1].name)
        assertEquals("Something", entries[1].about)
    }

    @Test
    fun badPresetsFilesAreRejected() {
        listOf("[auto]\n", "[auto]\n--a\n[auto]\n--b", "[Bad Id]\n--a", "[custom]\n--a").forEach { text ->
            try {
                Strategies.parse(text)
                fail(text)
            } catch (_: IllegalArgumentException) {
            }
        }
        assertEquals("preset [x] sets the listen address", BuiltinLists.badPresets("[x] X\n--port 1"))
    }

    @Test
    fun mergeOverridesArgsAndAddsNewPresets() {
        val merged = Strategies.merge(Strategies.bundled, Strategies.parse("[disorder]\n--disorder 2\n[mine] Mine\n--oob 3"))
        assertEquals(Strategies.bundled.size + 1, merged.size)
        assertEquals("--disorder 2", merged.first { it.id == "disorder" }.args)
        assertEquals("Mine", merged.last().name)
    }

    @Test
    fun bundledFileMatchesTheBuiltInPresets() {
        // The file in the APK is what the repository serves: it must cover every built-in preset
        // with the same command line, so that downloading it changes nothing by itself.
        val file = listOf("src/main/assets/${Strategies.FILE}", "app/src/main/assets/${Strategies.FILE}").map(::File).first { it.exists() }
        assertNull(BuiltinLists.badPresets(file.readText()))
        val entries = Strategies.parse(file.readText()).associateBy { it.id }
        Strategies.bundled.forEach { p -> assertEquals(p.id, p.args, entries[p.id]?.args) }
    }
}
