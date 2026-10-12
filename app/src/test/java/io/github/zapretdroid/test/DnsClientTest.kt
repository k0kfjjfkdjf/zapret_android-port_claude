package io.github.zapretdroid.test

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DnsClientTest {
    @Test
    fun buildsQuery() {
        val q = DnsClient.buildQuery(0x1234, "a.bc")
        val expected = byteArrayOf(
            0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
            1, 'a'.code.toByte(), 2, 'b'.code.toByte(), 'c'.code.toByte(), 0,
            0, 1, 0, 1,
        )
        assertArrayEquals(expected, q)
    }

    @Test
    fun parsesAnswersWithCompressionAndCname() {
        val query = DnsClient.buildQuery(7, "a.bc")
        val header = byteArrayOf(0, 7, 0x81.toByte(), 0x80.toByte(), 0, 1, 0, 2, 0, 0, 0, 0)
        val question = query.copyOfRange(12, query.size)
        // CNAME pointing elsewhere (type 5), then an A record; both use a name pointer to offset 12.
        val cname = byteArrayOf(0xc0.toByte(), 12, 0, 5, 0, 1, 0, 0, 0, 60, 0, 2, 0xc0.toByte(), 12)
        val a = byteArrayOf(0xc0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 1, 2, 3, 4)
        val answers = DnsClient.parseResponse(header + question + cname + a, 7)!!
        assertEquals(listOf("1.2.3.4"), answers.map { it.hostAddress })
    }

    @Test
    fun ignoresOtherIds() {
        val msg = byteArrayOf(0, 8, 0x81.toByte(), 0x80.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)
        assertNull(DnsClient.parseResponse(msg, 7))
    }
}
