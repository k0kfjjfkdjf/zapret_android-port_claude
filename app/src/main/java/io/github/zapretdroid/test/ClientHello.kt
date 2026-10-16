package io.github.zapretdroid.test

import java.io.ByteArrayOutputStream

/**
 * Edits a captured TLS ClientHello record: replaces the server_name extension, fixing every
 * length on the way. Used to replay a real browser ClientHello (Chrome with the post-quantum
 * key share is ~1.8 KB and spans two TCP segments, unlike Android's 517-byte one) for any host.
 */
object ClientHello {
    fun withSni(record: ByteArray, host: String): ByteArray {
        require(record.size > 43 && record[0].toInt() == 0x16 && record[5].toInt() == 0x01) { "not a ClientHello record" }
        var p = 9 + 2 + 32 // record header, handshake header, version, random
        p += 1 + u8(record, p) // session id
        p += 2 + u16(record, p) // cipher suites
        p += 1 + u8(record, p) // compression methods
        val extStart = p + 2
        val extEnd = extStart + u16(record, p)
        require(extEnd <= record.size) { "truncated ClientHello" }
        val name = host.toByteArray(Charsets.US_ASCII)
        val sni = ByteArrayOutputStream().apply {
            w16(0); w16(name.size + 5) // type server_name, extension length
            w16(name.size + 3); write(0); w16(name.size) // list length, host_name, name length
            write(name)
        }.toByteArray()
        val ext = ByteArrayOutputStream()
        var q = extStart
        var replaced = false
        while (q + 4 <= extEnd) {
            val type = u16(record, q)
            val len = u16(record, q + 2)
            if (type == 0) {
                ext.write(sni); replaced = true
            } else {
                ext.write(record, q, 4 + len)
            }
            q += 4 + len
        }
        if (!replaced) ext.write(sni)
        val extensions = ext.toByteArray()
        val body = ByteArrayOutputStream().apply {
            write(record, 9, p - 9) // version .. compression
            w16(extensions.size); write(extensions)
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write(record, 0, 3) // content type, record version
            w16(body.size + 4)
            write(0x01); write(body.size shr 16); write((body.size shr 8) and 0xff); write(body.size and 0xff)
            write(body)
        }.toByteArray()
    }

    /** The server_name of a ClientHello record, or null. */
    fun sni(record: ByteArray): String? = runCatching {
        var p = 9 + 2 + 32
        p += 1 + u8(record, p)
        p += 2 + u16(record, p)
        p += 1 + u8(record, p)
        val end = p + 2 + u16(record, p)
        var q = p + 2
        while (q + 4 <= end) {
            val type = u16(record, q)
            val len = u16(record, q + 2)
            if (type == 0) return@runCatching String(record, q + 9, u16(record, q + 7), Charsets.US_ASCII)
            q += 4 + len
        }
        null
    }.getOrNull()

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xff
    private fun u16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun ByteArrayOutputStream.w16(v: Int) { write(v shr 8); write(v and 0xff) }
}
