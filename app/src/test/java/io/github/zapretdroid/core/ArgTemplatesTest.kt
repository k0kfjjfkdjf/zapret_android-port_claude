package io.github.zapretdroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files

class ArgTemplatesTest {
    private val fakeDir = Files.createTempDirectory("fake").toFile().apply {
        resolve("stun.bin").writeText("x")
        deleteOnExit()
    }
    private val listDir = Files.createTempDirectory("lists").toFile().apply {
        resolve("youtube.txt").writeText("youtube.com")
        deleteOnExit()
    }

    @Test
    fun resolvesPlaceholders() {
        val stun = fakeDir.resolve("stun.bin").absolutePath
        val list = listDir.resolve("youtube.txt").absolutePath
        assertEquals(
            listOf("--udp-fake", "3", "--fake-data", stun, "-l$stun", "--hosts", list),
            ArgTemplates.resolve(
                listOf("--udp-fake", "3", "--fake-data", "{fake:stun.bin}", "-l{fake:stun}", "--hosts", "{list:youtube}"),
                fakeDir, listDir,
            ),
        )
    }

    @Test
    fun leavesOtherArgumentsAlone() {
        val args = listOf("--split", "1+s", "--fake-data", ":{GET}", "{fake:}")
        assertEquals(args, ArgTemplates.resolve(args, fakeDir, listDir))
    }

    @Test
    fun rejectsUnknownNames() {
        val e = assertThrows(ArgTemplates.UnknownReference::class.java) {
            ArgTemplates.resolve(listOf("{fake:nope.bin}"), fakeDir, listDir)
        }
        assertEquals("fake", e.kind)
        assertEquals("nope.bin", e.name)
    }
}
