package io.github.zapretdroid.core

import io.github.zapretdroid.test.DnsClient
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread

/**
 * DNS over HTTPS and over TLS against local servers with the test certificate (CN/SAN
 * localhost, test resources), and the resolver the engine redirects the VPN's DNS to.
 */
class SecureDnsTest {
    companion object {
        lateinit var doh: SSLServerSocket
        lateinit var dot: SSLServerSocket
        val served = AtomicInteger()

        @JvmStatic @BeforeClass fun setUp() {
            val ks = KeyStore.getInstance("JKS").apply {
                load(requireNotNull(SecureDnsTest::class.java.getResourceAsStream("/probe-test.jks")), "password".toCharArray())
            }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, "password".toCharArray()) }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
            val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, tmf.trustManagers, null) }
            SecureDns.socketFactory = ctx.socketFactory
            SecureDns.hostnameVerifier = HostnameVerifier { host, session ->
                (session.peerCertificates[0] as java.security.cert.X509Certificate).subjectAlternativeNames.orEmpty().any { it[1] == host }
            }
            doh = ctx.serverSocketFactory.createServerSocket(0, 50, InetAddress.getLoopbackAddress()) as SSLServerSocket
            dot = ctx.serverSocketFactory.createServerSocket(0, 50, InetAddress.getLoopbackAddress()) as SSLServerSocket
            serve(doh) { s -> serveDoh(s) }
            serve(dot) { s -> serveDot(s) }
        }

        @JvmStatic @AfterClass fun tearDown() {
            doh.close()
            dot.close()
        }

        private fun serve(server: SSLServerSocket, handler: (Socket) -> Unit) = thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { s.use(handler) } }
            }
        }

        /** An answer to [query]: A 127.0.0.2, TTL 300. */
        fun answer(query: ByteArray): ByteArray {
            served.incrementAndGet()
            var end = 12
            while (query[end].toInt() != 0) end += 1 + query[end]
            end += 5
            return query.copyOfRange(0, 2) + byteArrayOf(0x81.toByte(), 0x80.toByte(), 0, 1, 0, 1, 0, 0, 0, 0) +
                query.copyOfRange(12, end) +
                byteArrayOf(0xc0.toByte(), 0x0c, 0, 1, 0, 1, 0, 0, 1, 0x2c, 0, 4, 127, 0, 0, 2)
        }

        private fun line(input: InputStream): String {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) throw java.io.EOFException()
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar())
            }
        }

        /** HTTP/1.1 keep-alive, POST application/dns-message. */
        private fun serveDoh(s: Socket) {
            val input = BufferedInputStream(s.getInputStream())
            val out = s.getOutputStream()
            while (true) {
                val request = line(input)
                check(request.startsWith("POST /dns-query ")) { request }
                var length = 0
                while (true) {
                    val h = line(input)
                    if (h.isEmpty()) break
                    if (h.lowercase().startsWith("content-length:")) length = h.substringAfter(':').trim().toInt()
                }
                val body = ByteArray(length).also { DataInputStream(input).readFully(it) }
                val a = answer(body)
                out.write("HTTP/1.1 200 OK\r\nContent-Type: application/dns-message\r\nContent-Length: ${a.size}\r\n\r\n".toByteArray() + a)
                out.flush()
            }
        }

        /** Two-byte length prefix per message. */
        private fun serveDot(s: Socket) {
            val input = DataInputStream(s.getInputStream())
            val out = s.getOutputStream()
            while (true) {
                val q = ByteArray(input.readUnsignedShort()).also { input.readFully(it) }
                val a = answer(q)
                out.write(byteArrayOf((a.size shr 8).toByte(), a.size.toByte()) + a)
                out.flush()
            }
        }
    }

    private fun provider() = DnsProvider("test", "Test", "127.0.0.1", "localhost", dohPort = doh.localPort, dotPort = dot.localPort)

    @Test
    fun dohAndDotExchange() {
        for (mode in listOf(DnsMode.DOH, DnsMode.DOT)) {
            val up = SecureDns.Upstream(provider(), mode)
            // Several on one kept-alive connection.
            repeat(3) { i ->
                val a = up.exchange(DnsClient.buildQuery(0x100 + i, "www.youtube.com"))
                assertEquals(listOf(InetAddress.getByName("127.0.0.2")), DnsClient.parseResponse(a, 0x100 + i))
            }
            up.close()
        }
    }

    @Test
    fun serverAnswersAndCaches() {
        val server = SecureDns.Server(provider(), DnsMode.DOH)
        try {
            DatagramSocket().use { s ->
                s.soTimeout = 5000
                val before = served.get()
                repeat(3) { i ->
                    val q = DnsClient.buildQuery(0x2000 + i, "Discord.com")
                    s.send(DatagramPacket(q, q.size, InetSocketAddress(InetAddress.getLoopbackAddress(), server.port)))
                    val buf = ByteArray(2048)
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    // The cached answer carries each query's own ID.
                    assertEquals(listOf(InetAddress.getByName("127.0.0.2")), DnsClient.parseResponse(buf.copyOf(p.length), 0x2000 + i))
                }
                assertEquals("one upstream exchange, the rest from the cache", 1, served.get() - before)
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun customProviders() {
        val p = SecureDns.provider("https://localhost:8443/q")
        assertEquals("localhost", p.tlsName)
        assertEquals(8443, p.dohPort)
        assertEquals("/q", p.dohPath)
        val t = SecureDns.provider("tls://localhost")
        assertEquals(853, t.dotPort)
        assertTrue(SecureDns.provider("google") === SecureDns.providers[1])
    }
}
