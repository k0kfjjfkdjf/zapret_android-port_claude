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

    /**
     * The connection's flow-control window is shared by all requests of a connection: without
     * WINDOW_UPDATE on stream 0 a server stops sending after 64 KB of answers in total.
     */
    @Test
    fun connectionWindowIsGivenBack() {
        val body = ByteArray(4000) { it.toByte() }
        val padded = byteArrayOf(3) + body + ByteArray(3) // PADDED: pad length 3, three zero bytes
        val responses = (0 until 20).map { i ->
            val stream = 1 + 2 * i
            frame(1, 0x4, stream, byteArrayOf(0x88.toByte())) + frame(0, 0x1 or 0x8, stream, padded)
        }
        val out = ByteArrayOutputStream()
        val client = Http2(responses.reduce { a, b -> a + b }.inputStream(), out, "dns.example")
        repeat(20) {
            val r = client.post("/dns-query", "application/dns-message", byteArrayOf(1))
            assertArrayEquals(body, r.body)
        }
        // Every DATA frame's length (padding included) went back to the connection's window.
        val sent = out.toByteArray()
        var returned = 0L
        var at = 24 // after the preface
        while (at + 9 <= sent.size) {
            val len = ((sent[at].toInt() and 0xff) shl 16) or ((sent[at + 1].toInt() and 0xff) shl 8) or (sent[at + 2].toInt() and 0xff)
            val type = sent[at + 3].toInt()
            val stream = sent[at + 8].toInt()
            if (type == 8 && stream == 0) {
                returned += ((sent[at + 9].toLong() and 0x7f) shl 24) or ((sent[at + 10].toLong() and 0xff) shl 16) or
                    ((sent[at + 11].toLong() and 0xff) shl 8) or (sent[at + 12].toLong() and 0xff)
            }
            at += 9 + len
        }
        assertEquals(20L * padded.size, returned)
    }
}
