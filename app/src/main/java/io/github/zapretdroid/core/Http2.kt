package io.github.zapretdroid.core

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The least of HTTP/2 (RFC 9113) that DNS over HTTPS needs, for servers that refuse HTTP/1.1
 * (Quad9, bortzmeyer: 505; a-and-a: 400): POST requests one after another on one connection.
 * Requests are sent with HPACK literals that the server does not index; of the response headers
 * only :status is read, which servers send from the static table or as a literal of digits.
 * A response fits a stream's default flow-control window (a DNS answer is below 64 KB); the
 * connection's window, which all the requests of a connection share, is given back after
 * every DATA frame.
 */
class Http2(private val input: InputStream, private val output: OutputStream, private val authority: String) {
    class Response(val status: Int, val body: ByteArray)

    private var nextStream = 1
    private var started = false

    /** A POST of [body] to [path]; returns the status (0 when it was not readable) and the body. */
    fun post(path: String, contentType: String, body: ByteArray): Response {
        if (!started) {
            output.write(PREFACE)
            // SETTINGS: ENABLE_PUSH = 0.
            writeFrame(TYPE_SETTINGS, 0, 0, byteArrayOf(0, 2, 0, 0, 0, 0))
            started = true
        }
        val stream = nextStream
        nextStream += 2
        val block = ByteArrayOutputStream().apply {
            write(0x83) // :method POST
            write(0x87) // :scheme https
            literal(4, path) // :path
            literal(1, authority) // :authority
            literal(31, contentType) // content-type
            literal(19, contentType) // accept
            literal(28, body.size.toString()) // content-length
        }.toByteArray()
        writeFrame(TYPE_HEADERS, FLAG_END_HEADERS, stream, block)
        writeFrame(TYPE_DATA, FLAG_END_STREAM, stream, body)
        output.flush()

        var status = 0
        val data = ByteArrayOutputStream()
        while (true) {
            val header = readFully(9)
            val length = ((header[0].toInt() and 0xff) shl 16) or ((header[1].toInt() and 0xff) shl 8) or (header[2].toInt() and 0xff)
            val type = header[3].toInt() and 0xff
            val flags = header[4].toInt() and 0xff
            val id = ((header[5].toInt() and 0x7f) shl 24) or ((header[6].toInt() and 0xff) shl 16) or
                ((header[7].toInt() and 0xff) shl 8) or (header[8].toInt() and 0xff)
            if (length > MAX_FRAME) throw IOException("HTTP/2 frame of $length bytes")
            val payload = readFully(length)
            // Flow control: every DATA frame (padding included) uses up the connection's window,
            // shared by all the streams of the connection. Given back at once, else a connection
            // stalls once 64 KB of answers have come over it (a few large DNSSEC or TXT answers).
            // Sent with the next request; a stream's own window is fresh for every request.
            if (type == TYPE_DATA && length > 0) {
                writeFrame(TYPE_WINDOW_UPDATE, 0, 0, byteArrayOf(
                    (length shr 24).toByte(), (length shr 16).toByte(), (length shr 8).toByte(), length.toByte(),
                ))
            }
            when (type) {
                TYPE_SETTINGS -> if (flags and FLAG_ACK == 0) {
                    writeFrame(TYPE_SETTINGS, FLAG_ACK, 0, ByteArray(0))
                    output.flush()
                }
                TYPE_PING -> if (flags and FLAG_ACK == 0) {
                    writeFrame(TYPE_PING, FLAG_ACK, 0, payload)
                    output.flush()
                }
                TYPE_GOAWAY -> {
                    val code = if (payload.size >= 8) int32(payload, 4) else -1
                    throw IOException("HTTP/2 GOAWAY from the server (error $code)")
                }
                TYPE_RST_STREAM -> if (id == stream) {
                    throw IOException("HTTP/2 stream reset by the server (error ${if (payload.size >= 4) int32(payload, 0) else -1})")
                }
                TYPE_HEADERS -> if (id == stream) {
                    var from = 0
                    var to = payload.size
                    if (flags and FLAG_PADDED != 0) { to -= payload[0].toInt() and 0xff; from = 1 }
                    if (flags and FLAG_PRIORITY != 0) from += 5
                    if (status == 0 && from <= to) status = statusOf(payload, from, to)
                    if (flags and FLAG_END_STREAM != 0) return Response(status, data.toByteArray())
                }
                TYPE_DATA -> if (id == stream) {
                    var from = 0
                    var to = payload.size
                    if (flags and FLAG_PADDED != 0) { to -= payload[0].toInt() and 0xff; from = 1 }
                    if (from < to) data.write(payload, from, to - from)
                    if (data.size() > MAX_BODY) throw IOException("HTTP/2 response too large")
                    if (flags and FLAG_END_STREAM != 0) return Response(status, data.toByteArray())
                }
                // WINDOW_UPDATE, PRIORITY, CONTINUATION (only other headers), PUSH_PROMISE (off).
                else -> Unit
            }
        }
    }

    private fun writeFrame(type: Int, flags: Int, stream: Int, payload: ByteArray) {
        val n = payload.size
        output.write(byteArrayOf(
            (n shr 16).toByte(), (n shr 8).toByte(), n.toByte(), type.toByte(), flags.toByte(),
            (stream shr 24).toByte(), (stream shr 16).toByte(), (stream shr 8).toByte(), stream.toByte(),
        ))
        output.write(payload)
    }

    private fun readFully(n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) throw EOFException("HTTP/2 connection closed")
            off += r
        }
        return buf
    }

    companion object {
        /**
         * Offers "h2" in ALPN, before the handshake: SSLParameters.setApplicationProtocols where
         * it exists (Java 9, Android 10), else the platform Conscrypt socket's setAlpnProtocols
         * (Android 7-9). Found by reflection: this file is shared with the JVM tests.
         */
        fun offerH2(ssl: javax.net.ssl.SSLSocket): Boolean {
            runCatching {
                val params = ssl.sslParameters
                javax.net.ssl.SSLParameters::class.java.getMethod("setApplicationProtocols", Array<String>::class.java).invoke(params, arrayOf("h2"))
                ssl.sslParameters = params
                return true
            }
            return runCatching {
                ssl.javaClass.getMethod("setAlpnProtocols", ByteArray::class.java).invoke(ssl, byteArrayOf(2, 'h'.code.toByte(), '2'.code.toByte()))
                true
            }.getOrDefault(false)
        }

        /** The protocol ALPN chose in the handshake, or null. */
        fun negotiated(ssl: javax.net.ssl.SSLSocket): String? {
            runCatching {
                // Through the public class: the socket's own class may not be accessible.
                val p = javax.net.ssl.SSLSocket::class.java.getMethod("getApplicationProtocol").invoke(ssl) as String?
                if (!p.isNullOrEmpty()) return p
            }
            return runCatching {
                (ssl.javaClass.getMethod("getAlpnSelectedProtocol").invoke(ssl) as ByteArray?)?.let { String(it, Charsets.US_ASCII) }
            }.getOrNull()
        }

        private val PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".toByteArray(Charsets.US_ASCII)
        private const val TYPE_DATA = 0
        private const val TYPE_HEADERS = 1
        private const val TYPE_RST_STREAM = 3
        private const val TYPE_SETTINGS = 4
        private const val TYPE_PING = 6
        private const val TYPE_GOAWAY = 7
        private const val TYPE_WINDOW_UPDATE = 8
        private const val FLAG_END_STREAM = 0x1
        private const val FLAG_ACK = 0x1
        private const val FLAG_END_HEADERS = 0x4
        private const val FLAG_PADDED = 0x8
        private const val FLAG_PRIORITY = 0x20
        private const val MAX_FRAME = 1 shl 24
        private const val MAX_BODY = 65_536

        /** :status values of the HPACK static table, indexes 8 to 14. */
        private val STATIC_STATUS = intArrayOf(200, 204, 206, 304, 400, 404, 500)

        private fun int32(b: ByteArray, at: Int) =
            ((b[at].toInt() and 0xff) shl 24) or ((b[at + 1].toInt() and 0xff) shl 16) or ((b[at + 2].toInt() and 0xff) shl 8) or (b[at + 3].toInt() and 0xff)

        /** An HPACK integer with an N-bit prefix (RFC 7541 5.1). */
        private fun ByteArrayOutputStream.integer(value: Int, prefixBits: Int, first: Int) {
            val max = (1 shl prefixBits) - 1
            if (value < max) { write(first or value); return }
            write(first or max)
            var v = value - max
            while (v >= 128) { write((v and 0x7f) or 0x80); v = v shr 7 }
            write(v)
        }

        /** A literal header field without indexing, its name from the static table, the value as is. */
        private fun ByteArrayOutputStream.literal(nameIndex: Int, value: String) {
            integer(nameIndex, 4, 0x00)
            val bytes = value.toByteArray(Charsets.UTF_8)
            integer(bytes.size, 7, 0x00)
            write(bytes)
        }

        private class Reader(val b: ByteArray, var at: Int, val end: Int) {
            fun integer(prefixBits: Int): Int {
                val max = (1 shl prefixBits) - 1
                var v = (b[at++].toInt() and 0xff) and max
                if (v < max) return v
                var shift = 0
                while (at < end) {
                    val c = b[at++].toInt() and 0xff
                    v += (c and 0x7f) shl shift
                    shift += 7
                    if (c and 0x80 == 0) break
                }
                return v
            }
        }

        /**
         * The :status of a header block (it comes first: it is the only response pseudo-header).
         * 0 when it cannot be read here (the server refers to its dynamic table).
         */
        fun statusOf(block: ByteArray, from: Int, to: Int): Int {
            val r = Reader(block, from, to)
            while (r.at < to) {
                val c = block[r.at].toInt() and 0xff
                when {
                    c and 0x80 != 0 -> { // indexed field
                        val index = r.integer(7)
                        return if (index in 8..14) STATIC_STATUS[index - 8] else 0
                    }
                    c and 0xe0 == 0x20 -> r.integer(5) // dynamic table size update
                    else -> { // literal: with incremental indexing (6-bit prefix) or without / never (4-bit)
                        val index = if (c and 0x40 != 0) r.integer(6) else r.integer(4)
                        if (index !in 8..14) return 0
                        if (r.at >= to) return 0
                        val huffman = block[r.at].toInt() and 0x80 != 0
                        val len = r.integer(7)
                        if (r.at + len > to) return 0
                        val raw = block.copyOfRange(r.at, r.at + len)
                        val text = if (huffman) huffmanDigits(raw) else String(raw, Charsets.US_ASCII)
                        return text?.toIntOrNull() ?: 0
                    }
                }
            }
            return 0
        }

        /** Huffman codes (RFC 7541 appendix B) of '0'..'9': all a status needs. */
        private val DIGIT_CODES = arrayOf(
            0b00000 to 5, 0b00001 to 5, 0b00010 to 5, 0b011001 to 6, 0b011010 to 6,
            0b011011 to 6, 0b011100 to 6, 0b011101 to 6, 0b011110 to 6, 0b011111 to 6,
        )

        /** Decodes a Huffman string of digits; null if it holds anything else. */
        fun huffmanDigits(raw: ByteArray): String? {
            val out = StringBuilder()
            var bits = 0L
            var count = 0
            for (byte in raw) {
                bits = (bits shl 8) or (byte.toLong() and 0xff)
                count += 8
                while (true) {
                    val match = DIGIT_CODES.indices.firstOrNull { d ->
                        val (code, len) = DIGIT_CODES[d]
                        count >= len && ((bits shr (count - len)) and ((1L shl len) - 1)).toInt() == code
                    } ?: break
                    count -= DIGIT_CODES[match].second
                    out.append('0' + match)
                }
                if (count > 7 + 8) return null
            }
            // What is left must be padding: up to 7 one-bits.
            if (count > 7 || (bits and ((1L shl count) - 1)) != (1L shl count) - 1) return null
            return out.toString()
        }
    }
}
