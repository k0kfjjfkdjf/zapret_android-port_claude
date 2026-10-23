package io.github.zapretdroid.core

import io.github.zapretdroid.core.crypto.NaCl
import io.github.zapretdroid.core.crypto.NaCl.Cipher
import io.github.zapretdroid.test.DnsClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetAddress.getByName
import kotlin.concurrent.thread

/**
 * The DNSCrypt client against a small server in the test: certificates signed beforehand
 * (Python, cryptography: provider key bytes 1..32, valid 2020..2100), queries decrypted with
 * the resolver's key and answered with 127.0.0.2. Checked once more against an independent
 * Python server (own Salsa20, cryptography's ChaCha20/X25519/Ed25519) during development.
 */
class DnsCryptTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private val providerKey = hex("79b5562e8fe654f94078b112e8a98ba7901f853ae695bed7e0e3910bad049664")
    private val resolverSecret = hex("28292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f4041424344454647")
    private val cert1 = hex("444e534300010000a9b5836b377c64ad5d674a9c6a800e7d71ace466369419396a9c1886009951ad1c232f3cda994740aad6e43800dfbd55398a9c79ccec8eb5c1ae5c96addada08492f1cb85847ffc7bb427865f64c5e8041ec0499b37a1da945a15b4102af027e544553544d414743000000015e0be100f4865700")
    private val cert2 = hex("444e5343000200003add5791ece6f523fd536d9e0c4c76da64743c38e11d85ee0e16454316874f8e46531a673bd5a86e0ef7108020eefa9f0b74241fcc7491342f308ed9f93e350a492f1cb85847ffc7bb427865f64c5e8041ec0499b37a1da945a15b4102af027e544553544d414743000000025e0be100f4865700")
    private val expired = hex("444e534300020000b01c007ebf71154814f52947b1bc533ed08e833e06a9e33ada29aeb441aa2e857cff09f8a7f708a5ec155eb03b59c797d8dab14913754b2da63ee4caca2da90f492f1cb85847ffc7bb427865f64c5e8041ec0499b37a1da945a15b4102af027e544553544d414743000000035e0be1005fee6600")
    private val providerName = "2.dnscrypt-cert.test.local"

    private fun stamp(port: Int): String {
        val addr = "127.0.0.1:$port".toByteArray()
        val b = ByteArrayOutputStream()
        b.write(1); b.write(ByteArray(8).also { it[0] = 7 })
        b.write(addr.size); b.write(addr)
        b.write(32); b.write(providerKey)
        b.write(providerName.length); b.write(providerName.toByteArray())
        return "sdns://" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b.toByteArray())
    }

    /** Serves [certs] for TXT queries and answers encrypted queries until closed. */
    private fun server(certs: List<ByteArray>): DatagramSocket {
        val sock = DatagramSocket(0, getByName("127.0.0.1"))
        thread(isDaemon = true) {
            val buf = ByteArray(4096)
            while (!sock.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                runCatching { sock.receive(p) }.onFailure { return@thread }
                val d = buf.copyOf(p.length)
                val reply = if (!d.copyOf(8).contentEquals("TESTMAGC".toByteArray())) {
                    // Plain TXT query: the certificates.
                    var end = 12
                    while (d[end].toInt() != 0) end += 1 + d[end]
                    end += 5
                    val rr = ByteArrayOutputStream()
                    certs.forEach { c ->
                        rr.write(byteArrayOf(0xc0.toByte(), 0x0c, 0, 16, 0, 1, 0, 0, 0x0e, 0x10, 0, (c.size + 1).toByte(), c.size.toByte()))
                        rr.write(c)
                    }
                    d.copyOf(2) + byteArrayOf(0x81.toByte(), 0x80.toByte(), 0, 1, 0, certs.size.toByte(), 0, 0, 0, 0) + d.copyOfRange(12, end) + rr.toByteArray()
                } else {
                    val cipher = if (certs.any { it[5].toInt() == 2 }) Cipher.XCHACHA20_POLY1305 else Cipher.XSALSA20_POLY1305
                    val key = NaCl.sharedKey(cipher, resolverSecret, d.copyOfRange(8, 40))
                    val clientNonce = d.copyOfRange(40, 52)
                    val padded = NaCl.open(cipher, key, clientNonce + ByteArray(12), d.copyOfRange(52, d.size))!!
                    assertTrue("UDP queries are padded to 256 bytes", padded.size >= 256)
                    val q = DnsCryptClient.unpad(padded)!!
                    var end = 12
                    while (q[end].toInt() != 0) end += 1 + q[end]
                    end += 5
                    val answer = q.copyOf(2) + byteArrayOf(0x81.toByte(), 0x80.toByte(), 0, 1, 0, 1, 0, 0, 0, 0) + q.copyOfRange(12, end) +
                        byteArrayOf(0xc0.toByte(), 0x0c, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 127, 0, 0, 2)
                    val nonce = clientNonce + NaCl.randomBytes(12)
                    DnsCryptClient.RESOLVER_MAGIC + nonce + NaCl.seal(cipher, key, nonce, DnsCryptClient.pad(answer, 0))
                }
                runCatching { sock.send(DatagramPacket(reply, reply.size, p.socketAddress)) }
            }
        }
        return sock
    }

    @Test
    fun stampOfTheTestServer() {
        val s = DnsStamp.parse(stamp(5443))
        assertEquals(DnsStamp.Protocol.DNSCRYPT, s.protocol)
        assertEquals("127.0.0.1", s.host)
        assertEquals(5443, s.port)
        assertEquals(providerName, s.hostname)
        assertTrue(s.dnssec && s.noLog && s.noFilter)
    }

    @Test
    fun certificates() {
        assertEquals(Cipher.XSALSA20_POLY1305, DnsCryptClient.parseCert(cert1, providerKey)?.cipher)
        assertEquals(Cipher.XCHACHA20_POLY1305, DnsCryptClient.parseCert(cert2, providerKey)?.cipher)
        // Another provider's key, or a changed byte: refused.
        assertNull(DnsCryptClient.parseCert(cert2, ByteArray(32) { 9 }))
        assertNull(DnsCryptClient.parseCert(cert2.copyOf().also { it[100] = (it[100] + 1).toByte() }, providerKey))
    }

    @Test
    fun exchangeBothCiphers() {
        for ((certs, cipher) in listOf(listOf(cert1) to Cipher.XSALSA20_POLY1305, listOf(cert1, cert2, expired) to Cipher.XCHACHA20_POLY1305)) {
            val srv = server(certs)
            try {
                val client = DnsCryptClient(DnsStamp.parse(stamp(srv.localPort)), 3000)
                repeat(3) { i ->
                    val a = client.exchange(DnsClient.buildQuery(0x700 + i, "www.example.org"))
                    assertEquals(listOf(InetAddress.getByName("127.0.0.2")), DnsClient.parseResponse(a, 0x700 + i))
                }
                assertEquals(cipher, client.cert().cipher)
            } finally {
                srv.close()
            }
        }
    }

    @Test
    fun expiredCertificateIsRefused() {
        val srv = server(listOf(expired))
        try {
            val e = runCatching { DnsCryptClient(DnsStamp.parse(stamp(srv.localPort)), 3000).fetchCert() }.exceptionOrNull()
            assertNotNull(e)
            assertTrue(e!!.message!!.contains("not valid now"))
        } finally {
            srv.close()
        }
    }

    @Test
    fun throughTheResolver() {
        val srv = server(listOf(cert2))
        val resolver = SecureDns.Server(listOf(SecureDns.fromStamp("test", stamp(srv.localPort))), DnsMode.DNSCRYPT)
        try {
            DatagramSocket().use { s ->
                s.soTimeout = 5000
                val q = DnsClient.buildQuery(0x1111, "discord.com")
                s.send(DatagramPacket(q, q.size, getByName("127.0.0.1"), resolver.port))
                val buf = ByteArray(2048)
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                assertEquals(listOf(getByName("127.0.0.2")), DnsClient.parseResponse(buf.copyOf(p.length), 0x1111))
            }
        } finally {
            resolver.stop()
            srv.close()
        }
    }

    @Test
    fun padding() {
        val p = DnsCryptClient.pad(ByteArray(10) { 1 }, 256)
        assertEquals(256, p.size)
        assertEquals(10, DnsCryptClient.unpad(p)!!.size)
        assertEquals(64, DnsCryptClient.pad(ByteArray(63), 0).size)
        assertEquals(128, DnsCryptClient.pad(ByteArray(64), 0).size)
        assertNull(DnsCryptClient.unpad(ByteArray(8)))
    }
}
