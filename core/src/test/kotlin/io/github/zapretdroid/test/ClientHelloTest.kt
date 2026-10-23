package io.github.zapretdroid.test

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ClientHelloTest {
    private val fakeDir = listOf("../app/src/main/assets/fake", "app/src/main/assets/fake").map(::File).first { it.isDirectory }

    @Test
    fun replacesSniAndKeepsLengthsConsistent() {
        val original = File(fakeDir, "tls_clienthello_vk_com_kyber.bin").readBytes()
        assertEquals("vk.com", ClientHello.sni(original))
        val edited = ClientHello.withSni(original, "www.youtube.com")
        assertEquals("www.youtube.com", ClientHello.sni(edited))
        assertEquals(original.size + "www.youtube.com".length - "vk.com".length, edited.size)
        // record length and handshake length cover the rest of the record exactly
        assertEquals(edited.size - 5, ((edited[3].toInt() and 0xff) shl 8) or (edited[4].toInt() and 0xff))
        val hs = ((edited[6].toInt() and 0xff) shl 16) or ((edited[7].toInt() and 0xff) shl 8) or (edited[8].toInt() and 0xff)
        assertEquals(edited.size - 9, hs)
    }

    @Test
    fun everyBrowserHelloCanBeEdited() {
        fakeDir.listFiles { f -> f.name.startsWith("tls_clienthello") }!!.forEach { f ->
            val edited = ClientHello.withSni(f.readBytes(), "discord.com")
            assertEquals(f.name, "discord.com", ClientHello.sni(edited))
        }
    }
}
