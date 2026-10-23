package io.github.zapretdroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppLogTest {
    @Test
    fun formatsLines() {
        val line = AppLog.formatLine(0, "app", "hello")
        assertTrue(line, Regex("""^\d\d-\d\d \d\d:\d\d:\d\d\.\d{3} app \| hello$""").matches(line))
    }

    @Test
    fun readsWholeSmallFile() {
        val file = File.createTempFile("log", ".txt").apply { deleteOnExit() }
        file.writeText("one\ntwo\n\nthree\n")
        assertEquals(listOf("one", "two", "three"), AppLog.readTail(file))
    }

    @Test
    fun dropsPartialFirstLineOfTail() {
        val file = File.createTempFile("log", ".txt").apply { deleteOnExit() }
        file.writeText("first line\nsecond\nthird\n")
        // A 9-byte tail starts inside "second", a 14-byte tail right before it.
        assertEquals(listOf("third"), AppLog.readTail(file, maxBytes = 9))
        assertEquals(listOf("second", "third"), AppLog.readTail(file, maxBytes = 14))
    }

    @Test
    fun missingFileIsEmpty() {
        assertEquals(emptyList<String>(), AppLog.readTail(File("/nonexistent/zapretdroid.log")))
    }
}
