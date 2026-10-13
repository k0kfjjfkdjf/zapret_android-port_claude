package io.github.zapretdroid.test

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Test vectors from RFC 9001, appendix A. */
class QuicInitialTest {
    private val dcid = QuicInitial.hex("8394c8f03e515708")

    @Test
    fun clientKeys() {
        val keys = QuicInitial.clientKeys(dcid)
        assertArrayEquals(QuicInitial.hex("1f369613dd76d5467730efcbe3b1a22d"), keys.key)
        assertArrayEquals(QuicInitial.hex("fa044b2f42a3fd3b46fb255c"), keys.iv)
        assertArrayEquals(QuicInitial.hex("9f50449e04a0e810283a1e9933adedd2"), keys.hp)
    }

    @Test
    fun headerProtectionMask() {
        val mask = QuicInitial.headerMask(QuicInitial.clientKeys(dcid).hp, QuicInitial.hex("d1b1c98dd7689fb8ec11d242b123dc9b"))
        assertArrayEquals(QuicInitial.hex("437b9aec36"), mask.copyOf(5))
    }

    @Test
    fun builtPacketDecryptsAndCarriesSni() {
        val scid = QuicInitial.hex("0102030405060708")
        val packet = QuicInitial.build("www.youtube.com", dcid, scid)
        assertEquals(1200, packet.size)
        // Undo header protection and AEAD like a server (or DPI) would.
        val keys = QuicInitial.clientKeys(dcid)
        val pnOffset = 1 + 4 + 1 + dcid.size + 1 + scid.size + 1 + 2
        val mask = QuicInitial.headerMask(keys.hp, packet.copyOfRange(pnOffset + 4, pnOffset + 20))
        val header = packet.copyOfRange(0, pnOffset + 4)
        header[0] = (header[0].toInt() xor (mask[0].toInt() and 0x0f)).toByte()
        for (i in 0 until 4) header[pnOffset + i] = (header[pnOffset + i].toInt() xor mask[1 + i].toInt()).toByte()
        assertEquals(0xc3, header[0].toInt() and 0xff)
        val plain = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.key, "AES"), GCMParameterSpec(128, keys.iv))
            updateAAD(header)
        }.doFinal(packet, header.size, packet.size - header.size)
        assertEquals(0x06, plain[0].toInt()) // CRYPTO frame
        assertTrue(String(plain, Charsets.ISO_8859_1).contains("www.youtube.com"))
        assertEquals("Initial (version 0x1)", QuicInitial.describeReply(packet))
    }
}
