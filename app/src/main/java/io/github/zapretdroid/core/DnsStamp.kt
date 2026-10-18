package io.github.zapretdroid.core

import java.io.ByteArrayOutputStream

/**
 * A DNS stamp (sdns://…, https://dnscrypt.info/stamps-specifications): how to reach a server
 * and what it promises. DNSCrypt and DNS over HTTPS stamps are understood; others are refused.
 */
class DnsStamp(
    val protocol: Protocol,
    val props: Long,
    /** "ip:port" or "[ipv6]:port"; may be empty for DoH (then [hostname] is resolved). */
    val address: String,
    /** DNSCrypt: the provider's Ed25519 key that signs the server's certificates. */
    val providerKey: ByteArray = ByteArray(0),
    /** DNSCrypt: the provider name ("2.dnscrypt-cert.…"); DoH: the server's host name. */
    val hostname: String,
    val path: String = "",
) {
    enum class Protocol(val id: Int) { DNSCRYPT(0x01), DOH(0x02) }

    val dnssec get() = props and 1L != 0L
    val noLog get() = props and 2L != 0L
    val noFilter get() = props and 4L != 0L

    /** The host part of [address] without brackets, or "". */
    val host: String
        get() = when {
            address.startsWith("[") -> address.substringAfter('[').substringBefore(']')
            address.count { it == ':' } > 1 -> address // bare IPv6
            else -> address.substringBefore(':')
        }

    val port: Int
        get() {
            val p = if (address.startsWith("[")) address.substringAfter("]", "").removePrefix(":") else if (address.count { it == ':' } == 1) address.substringAfter(':') else ""
            return p.toIntOrNull() ?: 443
        }

    val ipv6 get() = host.contains(':')

    companion object {
        fun parse(stamp: String): DnsStamp {
            val text = stamp.trim()
            require(text.startsWith("sdns://")) { "not a DNS stamp" }
            val b = base64(text.removePrefix("sdns://"))
            require(b.size >= 9) { "stamp too short" }
            var pos = 9
            fun lp(): ByteArray {
                require(pos < b.size) { "stamp truncated" }
                val len = b[pos].toInt() and 0xff
                require(pos + 1 + len <= b.size) { "stamp truncated" }
                return b.copyOfRange(pos + 1, pos + 1 + len).also { pos += 1 + len }
            }
            val props = (0 until 8).fold(0L) { acc, i -> acc or ((b[1 + i].toLong() and 0xff) shl (8 * i)) }
            return when (b[0].toInt()) {
                0x01 -> {
                    val addr = String(lp(), Charsets.US_ASCII)
                    val pk = lp()
                    require(pk.size == 32) { "bad provider key" }
                    DnsStamp(Protocol.DNSCRYPT, props, addr, pk, String(lp(), Charsets.US_ASCII))
                }
                0x02 -> {
                    val addr = String(lp(), Charsets.US_ASCII)
                    // Certificate hashes: each length byte with 0x80 set means another follows.
                    while (true) {
                        require(pos < b.size) { "stamp truncated" }
                        val more = b[pos].toInt() and 0x80 != 0
                        val len = b[pos].toInt() and 0x7f
                        pos += 1 + len
                        if (!more) break
                    }
                    val host = String(lp(), Charsets.US_ASCII)
                    val path = String(lp(), Charsets.US_ASCII).ifEmpty { "/dns-query" }
                    DnsStamp(Protocol.DOH, props, addr, hostname = host.substringBefore(':'), path = path)
                }
                else -> throw IllegalArgumentException("unsupported stamp type ${b[0].toInt() and 0xff}")
            }
        }

        /** Standard or URL-safe base64, padding optional (java.util.Base64 needs Android 8). */
        fun base64(s: String): ByteArray {
            val out = ByteArrayOutputStream()
            var acc = 0
            var bits = 0
            for (c in s) {
                val v = when (c) {
                    in 'A'..'Z' -> c - 'A'
                    in 'a'..'z' -> c - 'a' + 26
                    in '0'..'9' -> c - '0' + 52
                    '+', '-' -> 62
                    '/', '_' -> 63
                    '=' -> break
                    ' ', '\n', '\r', '\t' -> continue
                    else -> throw IllegalArgumentException("bad base64 character '$c'")
                }
                acc = (acc shl 6) or v
                bits += 6
                if (bits >= 8) {
                    bits -= 8
                    out.write((acc shr bits) and 0xff)
                }
            }
            return out.toByteArray()
        }
    }
}
