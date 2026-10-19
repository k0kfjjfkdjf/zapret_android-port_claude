package io.github.zapretdroid.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class Http2Test {
    private fun frame(type: Int, flags: Int, stream: Int, payload: ByteArray) =
        byteArrayOf(0, (payload.size shr 8).toByte(), payload.size.toByte(), type.toByte(), flags.toByte(), 0, 0, 0, stream.toByte()) + payload

    @Test
    fun statusFromStaticTableAndLiterals() {
        assertEquals(200, Http2.statusOf(byteArrayOf(0x88.toByte()), 0, 1))
        assertEquals(404, Http2.statusOf(byteArrayOf(0x8d.toByte()), 0, 1))
        // Literal with incremental indexing, name :status (8), value "421" as plain text.
        assertEquals(421, Http2.statusOf(byteArrayOf(0x48, 3, '4'.code.toByte(), '2'.code.toByte(), '1'.code.toByte()), 0, 5))
        // The same value Huffman-coded: 011010 00010 00001.
        assertEquals(421, Http2.statusOf(byteArrayOf(0x48, 0x82.toByte(), 0x68, 0x41), 0, 4))
        // A dynamic table size update first, then :status 200.
        assertEquals(200, Http2.statusOf(byteArrayOf(0x20, 0x88.toByte()), 0, 2))
        // A reference to the server's dynamic table: unknown.
        assertEquals(0, Http2.statusOf(byteArrayOf(0xbe.toByte()), 0, 1))
    }

    @Test
    fun huffmanDigits() {
        assertEquals("421", Http2.huffmanDigits(byteArrayOf(0x68, 0x41)))
        // "200": 00010 00000 00000 and one padding 1-bit.
        assertEquals("200", Http2.huffmanDigits(byteArrayOf(0x10, 0x01)))
        // 'a' (00011) is not a digit.
        assertNull(Http2.huffmanDigits(byteArrayOf(0x1f)))
    }

    @Test
    fun postOverOneStream() {
        val server = frame(4, 0, 0, ByteArray(0)) +
            frame(8, 0, 0, byteArrayOf(0, 0, 0x10, 0)) + // WINDOW_UPDATE, ignored
            frame(1, 0x4, 1, byteArrayOf(0x88.toByte())) +
            frame(0, 0x1, 1, "answer".toByteArray())
        val out = ByteArrayOutputStream()
        val r = Http2(server.inputStream(), out, "dns.example").post("/dns-query", "application/dns-message", byteArrayOf(1, 2, 3))
        assertEquals(200, r.status)
        assertArrayEquals("answer".toByteArray(), r.body)
        val sent = out.toByteArray()
        assertTrue(String(sent, 0, 24, Charsets.US_ASCII).startsWith("PRI * HTTP/2.0"))
        // Our SETTINGS, then the ACK of the server's SETTINGS (type 4, flag 1) after the request.
        val ack = byteArrayOf(0, 0, 0, 4, 1, 0, 0, 0, 0)
        assertTrue(sent.toList().windowed(9).any { it == ack.toList() })
    }
}
