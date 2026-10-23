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
        @Volatile var lastRequestLine = ""
        @Volatile var serverError: Throwable? = null

        @JvmStatic @BeforeClass fun setUp() {
            val ks = KeyStore.getInstance("JKS").apply { load(requireNotNull(SiteProbeTest::class.java.getResourceAsStream("/probe-test.jks")) { "probe-test.jks missing from test resources" }, "password".toCharArray()) }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, "password".toCharArray()) }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
            val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, tmf.trustManagers, null) }
            SiteProbe.socketFactory = ctx.socketFactory
            // The JVM's default verifier rejects everything; Android's checks the SAN like this.
            SiteProbe.hostnameVerifier = javax.net.ssl.HostnameVerifier { host, session ->
                (session.peerCertificates[0] as java.security.cert.X509Certificate)
                    .subjectAlternativeNames.orEmpty().any { it[1] == host }
            }
            tls = ctx.serverSocketFactory.createServerSocket(0, 50, InetAddress.getLoopbackAddress()) as SSLServerSocket
            thread(isDaemon = true) {
                while (true) {
                    val s = tls.accept()
                    thread(isDaemon = true) { serve(s).onFailure { serverError = it } }
                }
            }
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
            lastRequestLine = req.lineSequence().first()
            val body = ByteArray(50_000) { 'a'.code.toByte() }
            val big = ByteArray(300_000) { 'b'.code.toByte() }
            when (mode) {
                "ok" -> { o.write("HTTP/1.1 200 OK\r\nContent-Length: 50000\r\n\r\n".toByteArray()); o.write(body) }
                "404" -> { o.write("HTTP/1.1 404 Not Found\r\nContent-Length: 5\r\n\r\nnope!".toByteArray()) }
                "truncated" -> { o.write("HTTP/1.1 200 OK\r\nContent-Length: 50000\r\n\r\n".toByteArray()); o.write(body, 0, 10_000) }
                "stall" -> { o.write("HTTP/1.1 200 OK\r\nContent-Length: 50000\r\n\r\n".toByteArray()); o.write(body, 0, 16_000); o.flush(); Thread.sleep(4000) }
                "blockpage" -> { o.write("HTTP/1.1 302 Found\r\nLocation: http://warning.rt.ru/?id=1\r\nContent-Length: 0\r\n\r\n".toByteArray()) }
                // 80 KB of a 300 KB body, then silence: past the 64 KB mark.
                "slow" -> { o.write("HTTP/1.1 200 OK\r\nContent-Length: 300000\r\n\r\n".toByteArray()); o.write(big, 0, 80_000); o.flush(); Thread.sleep(4000) }
                "bigredirect" -> {
                    if (lastRequestLine.startsWith("GET /download")) { o.write("HTTP/1.1 302 Found\r\nLocation: https://localhost/file.tar.gz\r\nContent-Length: 0\r\n\r\n".toByteArray()) }
                    else { o.write("HTTP/1.1 200 OK\r\nContent-Length: 300000\r\n\r\n".toByteArray()); o.write(big) }
                }
                "big" -> { o.write("HTTP/1.1 200 OK\r\nContent-Length: 300000\r\n\r\n".toByteArray()); o.write(big) }
                "redirect" -> { o.write("HTTP/1.1 301 Moved\r\nLocation: https://www.localhost/\r\nContent-Length: 0\r\n\r\n".toByteArray()) }
            }
            o.flush(); s.close()
        }

        @JvmStatic @AfterClass fun tearDown() {
            SiteProbe.hostnameVerifier = null
            SiteProbe.socketFactory = null
            tls.close()
            socks.close()
        }
    }

    private fun run(m: String, host: String = "localhost", tls: TlsMode = TlsMode.ANY) = run {
        mode = m
        serverError = null
        SiteProbe.probe(socks.localPort, host, InetAddress.getLoopbackAddress(), 1500, tls)
    }.let { result ->
        // Failure messages carry the server side's exception, if any.
        val server = serverError?.let { " | server: ${it.javaClass.simpleName}: ${it.message}" }.orEmpty()
        println("[$m] ${result.summary()}$server")
        if (server.isEmpty()) result else result.copy(detail = result.detail + server)
    }

    @Test fun ok() { val r = run("ok"); assertTrue(r.summary(), r.ok); assertEquals(r.summary(), 200, r.httpStatus); assertTrue(r.summary(), r.bytes >= 50_000) }
    @Test fun notFoundIsReachable() { val r = run("404"); assertTrue(r.summary(), r.ok); assertEquals(r.summary(), 404, r.httpStatus) }
    @Test fun truncated() { val r = run("truncated"); assertTrue(r.summary(), !r.ok); assertTrue(r.detail, r.detail.contains("truncated")) }
    @Test fun stall() { val r = run("stall"); assertTrue(r.summary(), !r.ok); assertTrue(r.detail, r.detail.contains("stalled")); assertEquals(r.summary(), ProbeStage.BODY, r.stage) }
    @Test fun blockPage() { val r = run("blockpage"); assertTrue(r.summary(), !r.ok); assertTrue(r.detail, r.detail.contains("block page")) }
    @Test fun sameSiteRedirectIsFine() { val r = run("redirect"); assertTrue(r.summary(), r.ok); assertEquals(r.summary(), 301, r.httpStatus) }
    @Test fun wrongCertificate() { val r = run("ok", host = "example.com"); assertTrue(r.summary(), !r.ok); assertTrue(r.detail, r.stage == ProbeStage.TLS || r.stage == ProbeStage.CERTIFICATE) }
    @Test fun slowButPastTheFreezeMarkIsOpened() {
        val r = run("slow")
        assertTrue(r.summary(), r.ok)
        assertTrue(r.detail, r.detail.contains("stalled after"))
        assertEquals('+', r.reason)
    }
    @Test fun bigDownloadNeedsEnoughData() {
        val r = run("slow", host = "big:localhost/api/download")
        assertTrue(r.summary(), !r.ok)
        assertEquals(r.summary(), 'f', r.reason)
        assertEquals("GET /api/download HTTP/1.1", lastRequestLine)
        assertEquals("big:localhost/api/download", r.host)
    }
    @Test fun bigDownloadOk() { val r = run("big", host = "big:localhost/file"); assertTrue(r.summary(), r.ok); assertTrue(r.summary(), r.bytes >= SiteProbe.BIG_MIN) }
    @Test fun bigDownloadFollowsRedirect() {
        val r = run("bigredirect", host = "big:localhost/download?platform=linux")
        assertTrue(r.summary(), r.ok)
        assertEquals("GET /file.tar.gz HTTP/1.1", lastRequestLine)
        assertTrue(r.summary(), r.bytes >= SiteProbe.BIG_MIN)
    }
    @Test fun freezeAt16KbIsNamed() { val r = run("stall"); assertEquals('f', r.reason); assertTrue(r.detail, r.detail.contains("16-20 KB")) }
    @Test fun forcedTls12() { val r = run("ok", tls = TlsMode.TLS12); assertTrue(r.summary(), r.ok); assertEquals("TLSv1.2", r.tlsVersion); assertEquals("localhost [tls1.2]", r.host) }
    @Test fun targetParsing() {
        assertEquals(ProbeTarget("discord.com", "/api/v9/gateway"), ProbeTarget.parse("https://Discord.com/api/v9/gateway"))
        assertEquals(ProbeTarget("x.org", "/", big = true), ProbeTarget.parse("big:x.org"))
        assertEquals("big:x.org/a", ProbeTarget.parse("big:x.org/a").spec)
    }
    @Test fun chromeHandshake() {
        val dir = listOf("../app/src/main/assets/fake", "app/src/main/assets/fake").map { java.io.File(it) }.first { it.isDirectory }
        SiteProbe.browserHello = java.io.File(dir, TestEngine.BROWSER_HELLO).readBytes()
        val r = run("ok", tls = TlsMode.CHROME)
        assertTrue(r.summary(), r.ok)
        assertEquals("localhost [chrome]", r.host)
    }
    @Test fun respondedButNotLoaded() {
        val frozen = run("stall")
        assertTrue(frozen.summary(), frozen.responded && !frozen.ok)
        assertTrue(frozen.summary(), frozen.summary().contains("RESPONDED"))
        val refused = run("refused")
        assertTrue(refused.summary(), !refused.responded)
        val blocked = run("blockpage")
        assertTrue(blocked.summary(), !blocked.responded)
        assertTrue(run("ok").responded)
    }
    @Test fun refusedReason() { assertEquals('c', run("refused").reason) }
    @Test fun connectRefused() { val r = run("refused"); assertTrue(r.summary(), !r.ok); assertEquals(r.summary(), ProbeStage.CONNECT, r.stage) }
}
