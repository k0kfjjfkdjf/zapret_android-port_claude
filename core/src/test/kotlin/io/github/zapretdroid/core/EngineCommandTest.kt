package io.github.zapretdroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EngineCommandTest {
    private val assets = listOf("../app/src/main/assets", "app/src/main/assets").map(::File).first { it.isDirectory }
    private val fakeDir = File(assets, "fake")
    private val listDir = File(assets, "lists")

    private fun build(text: String) = EngineCommand.build(text, Script.Env("wifi"), fakeDir, listDir)

    @Test
    fun dropsAppGroupsKeepingTheRest() {
        val (args, dropped) = EngineCommand.withoutApps(
            splitArgs("--disorder 1 --auto=none --apps 10123 -C socks5://127.0.0.1:1 --auto=none --oob 3"),
        )
        assertEquals(1, dropped)
        assertEquals(splitArgs("--disorder 1 --auto=none --oob 3"), args)
    }

    @Test
    fun aDroppedFirstGroupTakesTheNextOnesAutoAlong() {
        // As in a command line from the app: the app routes come first.
        val (args, dropped) = EngineCommand.withoutApps(
            splitArgs("--apps 10123 -C socks5://127.0.0.1:1 --auto=none --apps 10200 --auto none --proto t,h --disorder 1"),
        )
        assertEquals(2, dropped)
        assertEquals(splitArgs("--proto t,h --disorder 1"), args)
    }

    @Test
    fun resolvesFakesAndCommentsLikeTheApp() {
        val built = build(
            """
            # === TCP ===
            --proto t,h --disorder 2 --disorder midsld --tlsrec 1+s --auto=none
            --proto u --pf 443 --udp-fake 6 --udp-proto-fake quic:{fake:quic_initial_www_google_com}
            """.trimIndent(),
        )
        assertTrue(built.errors.toString(), built.errors.isEmpty())
        val fake = built.args.first { it.startsWith("quic:") }.removePrefix("quic:")
        assertTrue(fake, File(fake).isFile)
        assertEquals("--proto", built.args.first())
    }

    @Test
    fun reportsUnknownReferencesAndListenOptions() {
        assertEquals(listOf("нет fake «nope»"), build("--fake-data {fake:nope}").errors)
        assertTrue(build("-p 1080 --disorder 1").errors.single().startsWith("-p"))
        assertTrue(build("@try\n--disorder 1\n").errors.isNotEmpty())
    }

    @Test
    fun notesWhatOnlyTheAndroidAppDoes() {
        val built = build("--apps {app:com.example} --auto=none\n--disorder 1\n@on freeze -> notify\n")
        assertTrue(built.errors.toString(), built.errors.isEmpty())
        assertEquals(splitArgs("--disorder 1"), built.args)
        assertEquals(2, built.notes.size)
    }
}
