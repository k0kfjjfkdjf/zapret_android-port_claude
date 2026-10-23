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
    fun resolvesReferences() {
        ArgTemplates.refs = object : ArgTemplates.Refs {
            override fun uid(pkg: String) = mapOf("com.discord" to 10100, "org.telegram.messenger" to 10200)[pkg]
            override fun proxy(name: String) = mapOf("" to "socks5://127.0.0.1:9050", "termux" to "socks5://127.0.0.1:1080")[name]
            override fun ipset(name: String) = if (name == "telegram") "/data/telegram-ip.txt" else null
        }
        try {
            assertEquals(
                listOf("--apps", "10100,10200", "-C", "socks5://127.0.0.1:1080", "--ipset", "/data/telegram-ip.txt", "-C", "socks5://127.0.0.1:9050"),
                ArgTemplates.resolve(
                    listOf("--apps", "{app:com.discord,org.telegram.messenger}", "-C", "{proxy:termux}", "--ipset", "{ipset:telegram}", "-C", "{proxy}"),
                    fakeDir, listDir,
                ),
            )
            // An app removed since: left out, so that the VPN still starts; a group with no app
            // left gets a uid no app has (it matches nothing).
            assertEquals(listOf("--apps", "10100"), ArgTemplates.resolve(listOf("--apps", "{app:not.installed,com.discord}"), fakeDir, listDir))
            assertEquals(listOf("--apps", "${ArgTemplates.NO_APP}"), ArgTemplates.resolve(listOf("--apps", "{app:not.installed}"), fakeDir, listDir))
            val e = assertThrows(ArgTemplates.UnknownReference::class.java) {
                ArgTemplates.resolve(listOf("--apps", "{app:,}"), fakeDir, listDir)
            }
            assertEquals("app", e.kind)
            assertThrows(ArgTemplates.UnknownReference::class.java) { ArgTemplates.resolve(listOf("-C", "{proxy:nope}"), fakeDir, listDir) }
        } finally {
            ArgTemplates.refs = null
        }
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
