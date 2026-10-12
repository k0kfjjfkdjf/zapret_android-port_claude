package io.github.zapretdroid.test

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread

/**
 * Probes against a local TLS server (self-signed certificate for "localhost" in
 * test resources) behind a minimal SOCKS5 relay, one server behaviour per test.
 */
class SiteProbeTest {
    companion object {
        lateinit var tls: SSLServerSocket
        lateinit var socks: ServerSocket
        var mode = "ok"

        @JvmStatic @BeforeClass fun setUp() {
            val ks = KeyStore.getInstance("JKS").apply { load(SiteProbeTest::class.java.getResourceAsStream("/probe-test.jks"), "password".toCharArray()) }
            val kmf = KeyManagerFactory.getInstance("SunX509").apply { init(ks, "password".toCharArray()) }
            val tmf = TrustManagerFactory.getInstance("SunX509").apply { init(ks) }
            val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, tmf.trustManagers, null) }
            SSLContext.setDefault(ctx)
            // The JVM's default verifier rejects everything; Android's checks the SAN like this.
            SiteProbe.hostnameVerifier = javax.net.ssl.HostnameVerifier { host, session ->
                (session.peerCertificates[0] as java.security.cert.X509Certificate)
                    .subjectAlternativeNames.orEmpty().any { it[1] == host }
            }
            tls = ctx.serverSocketFactory.createServerSocket(0, 50, InetAddress.getLoopbackAddress()) as SSLServerSocket
            thread(isDaemon = true) { while (true) { val s = tls.accept(); thread(isDaemon = true) { serve(s) } } }
            // Minimal SOCKS5 relay that always connects to the TLS server.
            socks = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
            thread(isDaemon = true) {
                while (true) {
                    val c = socks.accept()
                    thread(isDaemon = true) {
                        runCatching {
                            val i = c.getInputStream(); val o = c.getOutputStream()
                            i.readNBytes(3); o.write(byteArrayOf(5, 0))
                            val h = i.readNBytes(4)
                            when (h[3].toInt()) { 1 -> i.readNBytes(6); 3 -> i.readNBytes(i.read() + 2); 4 -> i.readNBytes(18) }
                            if (mode == "refused") { o.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0)); c.close(); return@thread }
                            val up = Socket(InetAddress.getLoopbackAddress(), tls.localPort)
                            o.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                            thread(isDaemon = true) { runCatching { up.getInputStream().copyTo(o) }; runCatching { c.close() } }
                            runCatching { i.copyTo(up.getOutputStream()) }; runCatching { up.close() }
                        }
                    }
                }
            }
        }

        private fun serve(s: Socket) = runCatching {
            s.soTimeout = 5000
            val i = s.getInputStream(); val o = s.getOutputStream()
            val req = StringBuilder()
            while (!req.endsWith("\r\n\r\n")) { val b = i.read(); if (b < 0) return@runCatching; req.append(b.toChar()) }
            val body = ByteArray(50_000) { 'a'.code.toByte() }
            when (mode) {
                "ok" -> { o.write("HTTP/1.1 200 OK\r\nContent-Length: 50000\r\n\r\n".toByteArray()); o.write(body) }
                "404" -> { o.write("HTTP/1.1 404 Not Found\r\nContent-Length: 5\r\n\r\nnope!".toByteArray()) }
                "truncated" -> { o.write("HTTP/1.1 200 OK\r\nContent-Length: 50000\r\n\r\n".toByteArray()); o.write(body, 0, 10_000) }
                "stall" -> { o.write("HTTP/1.1 200 OK\r\nContent-Length: 50000\r\n\r\n".toByteArray()); o.write(body, 0, 16_000); o.flush(); Thread.sleep(4000) }
                "blockpage" -> { o.write("HTTP/1.1 302 Found\r\nLocation: http://warning.rt.ru/?id=1\r\nContent-Length: 0\r\n\r\n".toByteArray()) }
                "redirect" -> { o.write("HTTP/1.1 301 Moved\r\nLocation: https://www.localhost/\r\nContent-Length: 0\r\n\r\n".toByteArray()) }
            }
            o.flush(); s.close()
        }

        @JvmStatic @AfterClass fun tearDown() {
            SiteProbe.hostnameVerifier = null
            tls.close()
            socks.close()
        }
    }

    private fun run(m: String, host: String = "localhost") = run { mode = m; SiteProbe.probe(socks.localPort, host, InetAddress.getLoopbackAddress(), 1500) }.also { println("[$m] ${it.summary()}") }

    @Test fun ok() { val r = run("ok"); assertTrue(r.summary(), r.ok); assertEquals(r.summary(), 200, r.httpStatus); assertTrue(r.summary(), r.bytes >= 50_000) }
    @Test fun notFoundIsReachable() { val r = run("404"); assertTrue(r.summary(), r.ok); assertEquals(r.summary(), 404, r.httpStatus) }
    @Test fun truncated() { val r = run("truncated"); assertTrue(r.summary(), !r.ok); assertTrue(r.detail, r.detail.contains("truncated")) }
    @Test fun stall() { val r = run("stall"); assertTrue(r.summary(), !r.ok); assertTrue(r.detail, r.detail.contains("stalled")); assertEquals(r.summary(), ProbeStage.BODY, r.stage) }
    @Test fun blockPage() { val r = run("blockpage"); assertTrue(r.summary(), !r.ok); assertTrue(r.detail, r.detail.contains("block page")) }
    @Test fun sameSiteRedirectIsFine() { val r = run("redirect"); assertTrue(r.summary(), r.ok); assertEquals(r.summary(), 301, r.httpStatus) }
    @Test fun wrongCertificate() { val r = run("ok", host = "example.com"); assertTrue(r.summary(), !r.ok); assertTrue(r.detail, r.stage == ProbeStage.TLS || r.stage == ProbeStage.CERTIFICATE) }
    @Test fun connectRefused() { val r = run("refused"); assertTrue(r.summary(), !r.ok); assertEquals(r.summary(), ProbeStage.CONNECT, r.stage) }
}
