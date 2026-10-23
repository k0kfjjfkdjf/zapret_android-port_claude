package io.github.zapretdroid.core

import io.github.zapretdroid.core.crypto.NaCl
import io.github.zapretdroid.core.crypto.NaCl.Cipher
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom

/**
 * A DNSCrypt v2 client (https://dnscrypt.info/protocol) for one server of a stamp: fetches and
 * checks the server's certificate (a TXT record signed with the provider's Ed25519 key), then
 * sends each query in a box for the server's short-term key, over UDP (TCP if the answer is
 * truncated or UDP gets no answer). One key pair per client, so the shared key is computed once
 * per certificate.
 */
class DnsCryptClient(val stamp: DnsStamp, private val timeoutMs: Int = 4000) {
    class Cert(
        val cipher: Cipher,
        val resolverKey: ByteArray,
        val clientMagic: ByteArray,
        val serial: Long,
        val notBefore: Long,
        val notAfter: Long,
    )

    private class Session(val cert: Cert, val sharedKey: ByteArray)

    private val secret = NaCl.randomBytes(32)
    private val public = NaCl.publicKey(secret)
    private val server = InetSocketAddress(InetAddress.getByName(stamp.host), stamp.port)

    @Volatile
    private var session: Session? = null

    init {
        require(stamp.protocol == DnsStamp.Protocol.DNSCRYPT) { "not a DNSCrypt stamp" }
    }

    /** The certificate in use, fetching one first if needed. */
    fun cert(): Cert = current().cert

    fun exchange(query: ByteArray): ByteArray {
        val s = current()
        val answer = try {
            udp(s, query).takeUnless { it.size >= 3 && it[2].toInt() and 0x02 != 0 } // TC: again over TCP
        } catch (_: SocketTimeoutException) {
            null
        }
        return answer ?: tcp(s, query)
    }

    private fun current(): Session {
        val now = System.currentTimeMillis() / 1000
        session?.takeIf { now < it.cert.notAfter }?.let { return it }
        synchronized(this) {
            session?.takeIf { now < it.cert.notAfter }?.let { return it }
            val cert = fetchCert()
            return Session(cert, NaCl.sharedKey(cert.cipher, secret, cert.resolverKey)).also { session = it }
        }
    }

    /** Asks the server for its certificates in plain DNS (TXT for the provider name) and keeps the best valid one. */
    fun fetchCert(): Cert {
        val id = random.nextInt(0x10000)
        val query = plainQuery(id, stamp.hostname, TYPE_TXT)
        var answer = DatagramSocket().use { s ->
            s.soTimeout = timeoutMs
            s.send(DatagramPacket(query, query.size, server))
            val buf = ByteArray(4096)
            val p = DatagramPacket(buf, buf.size)
            try {
                s.receive(p)
                buf.copyOf(p.length)
            } catch (_: SocketTimeoutException) {
                null
            }
        }
        if (answer == null || answer.size < 12 || answer[2].toInt() and 0x02 != 0) answer = tcpRaw(query)
        val now = System.currentTimeMillis() / 1000
        val certs = txtRecords(answer, id).mapNotNull { parseCert(it, stamp.providerKey) }
        if (certs.isEmpty()) throw IOException("${stamp.hostname}: no valid signed certificate")
        val valid = certs.filter { now in it.notBefore..it.notAfter }
        if (valid.isEmpty()) {
            throw IOException("${stamp.hostname}: certificate not valid now (check the phone's clock): " +
                certs.joinToString { "${it.notBefore}..${it.notAfter}" } + ", now $now")
        }
        // The newest certificate; XChaCha20 when the server offers both.
        return valid.maxWith(compareBy<Cert> { it.serial }.thenBy { it.cipher.ordinal })
    }

    private fun seal(s: Session, query: ByteArray, minLen: Int): Pair<ByteArray, ByteArray> {
        val clientNonce = NaCl.randomBytes(12)
        val padded = pad(query, minLen)
        val box = NaCl.seal(s.cert.cipher, s.sharedKey, clientNonce + ByteArray(12), padded)
        return clientNonce to (s.cert.clientMagic + public + clientNonce + box)
    }

    private fun open(s: Session, clientNonce: ByteArray, packet: ByteArray): ByteArray {
        if (packet.size < 8 + 24 + 16 || !packet.copyOf(8).contentEquals(RESOLVER_MAGIC)) throw IOException("not a DNSCrypt answer")
        val nonce = packet.copyOfRange(8, 32)
        if (!nonce.copyOf(12).contentEquals(clientNonce)) throw IOException("answer to another query")
        val plain = NaCl.open(s.cert.cipher, s.sharedKey, nonce, packet.copyOfRange(32, packet.size))
            ?: throw IOException("answer does not decrypt")
        return unpad(plain) ?: throw IOException("bad padding")
    }

    private fun udp(s: Session, query: ByteArray): ByteArray {
        val (nonce, packet) = seal(s, query, MIN_UDP_QUERY)
        DatagramSocket().use { sock ->
            sock.soTimeout = timeoutMs
            sock.send(DatagramPacket(packet, packet.size, server))
            val buf = ByteArray(4096)
            while (true) {
                val p = DatagramPacket(buf, buf.size)
                sock.receive(p)
                // Anything else on the socket (a stray or spoofed packet) is skipped.
                runCatching { return open(s, nonce, buf.copyOf(p.length)) }
            }
        }
    }

    private fun tcp(s: Session, query: ByteArray): ByteArray {
        val (nonce, packet) = seal(s, query, 0)
        return open(s, nonce, tcpRaw(packet))
    }

    private fun tcpRaw(msg: ByteArray): ByteArray = Socket().use { sock ->
        sock.soTimeout = timeoutMs
        sock.connect(server, timeoutMs)
        sock.getOutputStream().apply { write(byteArrayOf((msg.size shr 8).toByte(), msg.size.toByte()) + msg); flush() }
        val input = DataInputStream(sock.getInputStream())
        ByteArray(input.readUnsignedShort()).also { input.readFully(it) }
    }

    companion object {
        private val random = SecureRandom()
        private const val TYPE_TXT = 16
        private const val MIN_UDP_QUERY = 256
        private val CERT_MAGIC = byteArrayOf(0x44, 0x4e, 0x53, 0x43) // "DNSC"
        val RESOLVER_MAGIC = byteArrayOf(0x72, 0x36, 0x66, 0x6e, 0x76, 0x57, 0x6a, 0x38)

        /** ISO/IEC 7816-4 padding to a multiple of 64 bytes, at least [minLen]. */
        fun pad(msg: ByteArray, minLen: Int): ByteArray {
            val len = maxOf(minLen, (msg.size + 1 + 63) / 64 * 64)
            return msg.copyOf(len).also { it[msg.size] = 0x80.toByte() }
        }

        fun unpad(msg: ByteArray): ByteArray? {
            var i = msg.size - 1
            while (i >= 0 && msg[i].toInt() == 0) i--
            return if (i >= 0 && msg[i] == 0x80.toByte()) msg.copyOf(i) else null
        }

        /** A certificate from a TXT record, if its signature by [providerKey] checks out. */
        fun parseCert(b: ByteArray, providerKey: ByteArray): Cert? {
            if (b.size < 124 || !b.copyOf(4).contentEquals(CERT_MAGIC) || b[6].toInt() != 0 || b[7].toInt() != 0) return null
            val cipher = when ((b[4].toInt() shl 8) or b[5].toInt()) {
                1 -> Cipher.XSALSA20_POLY1305
                2 -> Cipher.XCHACHA20_POLY1305
                else -> return null
            }
            if (!NaCl.ed25519Verify(providerKey, b.copyOfRange(72, b.size), b.copyOfRange(8, 72))) return null
            fun u32(o: Int) = (0 until 4).fold(0L) { acc, i -> (acc shl 8) or (b[o + i].toLong() and 0xff) }
            return Cert(cipher, b.copyOfRange(72, 104), b.copyOfRange(104, 112), u32(112), u32(116), u32(120))
        }

        fun plainQuery(id: Int, name: String, type: Int): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(byteArrayOf((id shr 8).toByte(), id.toByte(), 1, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            name.trimEnd('.').split('.').forEach { label ->
                val bytes = label.toByteArray(Charsets.US_ASCII)
                require(bytes.size in 1..63) { "bad name $name" }
                out.write(bytes.size)
                out.write(bytes)
            }
            out.write(byteArrayOf(0, 0, type.toByte(), 0, 1))
            return out.toByteArray()
        }

        /** The TXT records of the answer section (each record's strings joined). */
        fun txtRecords(msg: ByteArray, id: Int): List<ByteArray> {
            if (msg.size < 12 || ((msg[0].toInt() and 0xff) shl 8 or (msg[1].toInt() and 0xff)) != id) return emptyList()
            fun u16(o: Int) = (msg[o].toInt() and 0xff) shl 8 or (msg[o + 1].toInt() and 0xff)
            fun skipName(start: Int): Int {
                var pos = start
                while (true) {
                    val len = msg[pos].toInt() and 0xff
                    if (len == 0) return pos + 1
                    if (len and 0xc0 == 0xc0) return pos + 2
                    pos += len + 1
                }
            }
            return runCatching {
                var pos = 12
                repeat(u16(4)) { pos = skipName(pos) + 4 }
                val out = mutableListOf<ByteArray>()
                repeat(u16(6)) {
                    pos = skipName(pos)
                    val type = u16(pos)
                    val rdlen = u16(pos + 8)
                    val rdata = pos + 10
                    if (type == TYPE_TXT) {
                        val rec = ByteArrayOutputStream()
                        var p = rdata
                        while (p < rdata + rdlen) {
                            val l = msg[p].toInt() and 0xff
                            rec.write(msg, p + 1, l)
                            p += 1 + l
                        }
                        out += rec.toByteArray()
                    }
                    pos = rdata + rdlen
                }
                out
            }.getOrDefault(emptyList())
        }
    }
}
