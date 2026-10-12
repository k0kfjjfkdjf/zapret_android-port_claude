package io.github.zapretdroid.test

import java.io.EOFException
import java.io.InputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Stages of one probe, in order; a failure names the stage it happened in. */
enum class ProbeStage { PROXY, CONNECT, TLS, CERTIFICATE, RESPONSE, BODY, DONE }

data class ProbeResult(
    val host: String,
    val address: String?,
    val ok: Boolean,
    /** For a failure: what went wrong. For a success: the HTTP status line. */
    val detail: String,
    val stage: ProbeStage,
    val httpStatus: Int? = null,
    val location: String? = null,
    val connectMs: Long? = null,
    val tlsMs: Long? = null,
    val firstByteMs: Long? = null,
    val totalMs: Long,
    val bytes: Int = 0,
    val tlsVersion: String? = null,
) {
    /** One line for the journal. */
    fun summary(): String = buildString {
        append(host)
        if (address != null) append(" (").append(address).append(')')
        append(if (ok) ": OK, " else ": FAIL at ${stage.name.lowercase()}, ").append(detail)
        location?.let { append(" -> ").append(it) }
        val timings = listOfNotNull(
            connectMs?.let { "connect $it ms" },
            tlsMs?.let { "tls $it ms" },
            firstByteMs?.let { "first byte $it ms" },
            "total $totalMs ms",
        )
        append(" | ").append(timings.joinToString(", "))
        if (bytes > 0) append(", ").append(bytes).append(" B")
        tlsVersion?.let { append(", ").append(it) }
    }
}

/**
 * Opens an HTTPS connection to a site through the local test proxy (SOCKS5) and checks every
 * stage: connection through the proxy, TLS handshake, certificate, HTTP response and body.
 * Any HTTP status (even 404) means the connection got through DPI; blocks show up as resets,
 * timeouts, a stalled or truncated body, or a redirect to a block page.
 */
object SiteProbe {
    private const val MAX_READ = 128 * 1024
    private val BLOCK_PAGE_HINTS = listOf("warning", "block", "zapret", "rkn", "forbidden", "stop", "ban")

    fun probe(proxyPort: Int, host: String, address: InetAddress?, timeoutMs: Int): ProbeResult {
        val start = System.currentTimeMillis()
        var stage = ProbeStage.PROXY
        var connectMs: Long? = null
        var tlsMs: Long? = null
        var firstByteMs: Long? = null
        var bytes = 0
        var tlsVersion: String? = null
        val addr = address?.hostAddress
        fun elapsed() = System.currentTimeMillis() - start
        fun fail(detail: String) = ProbeResult(
            host, addr, false, detail, stage, connectMs = connectMs, tlsMs = tlsMs,
            firstByteMs = firstByteMs, totalMs = elapsed(), bytes = bytes, tlsVersion = tlsVersion,
        )
        return try {
            Socket().use { raw ->
                raw.soTimeout = timeoutMs
                raw.connect(InetSocketAddress("127.0.0.1", proxyPort), timeoutMs)
                stage = ProbeStage.CONNECT
                socksConnect(raw, host, address, 443)
                connectMs = elapsed()
                stage = ProbeStage.TLS
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                (factory.createSocket(raw, host, 443, true) as SSLSocket).use { ssl ->
                    ssl.soTimeout = timeoutMs
                    val tlsStart = System.currentTimeMillis()
                    ssl.startHandshake()
                    tlsMs = System.currentTimeMillis() - tlsStart
                    tlsVersion = ssl.session.protocol
                    stage = ProbeStage.CERTIFICATE
                    if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) {
                        return fail("certificate is not for $host (DNS spoofing or a block page?)")
                    }
                    stage = ProbeStage.RESPONSE
                    ssl.outputStream.apply {
                        write("GET / HTTP/1.1\r\nHost: $host\r\nUser-Agent: Mozilla/5.0\r\nAccept: */*\r\nConnection: close\r\n\r\n".toByteArray())
                        flush()
                    }
                    val response = readResponse(ssl.inputStream, start, timeoutMs) { first, total ->
                        if (first) firstByteMs = elapsed()
                        bytes = total
                        if (first) stage = ProbeStage.BODY
                    }
                    stage = ProbeStage.DONE
                    val blockPage = response.location?.let { looksLikeBlockPage(host, it) } == true
                    if (blockPage) stage = ProbeStage.RESPONSE
                    ProbeResult(
                        host, addr, ok = !blockPage,
                        detail = if (blockPage) "redirect to what looks like a block page (${response.statusLine})" else response.statusLine,
                        stage = stage, httpStatus = response.code, location = response.location,
                        connectMs = connectMs, tlsMs = tlsMs, firstByteMs = firstByteMs,
                        totalMs = elapsed(), bytes = bytes, tlsVersion = tlsVersion,
                    )
                }
            }
        } catch (e: Exception) {
            fail(describe(e, stage))
        }
    }

    private class Response(val code: Int, val statusLine: String, val location: String?)

    private fun socksConnect(socket: Socket, host: String, address: InetAddress?, port: Int) {
        val out = socket.getOutputStream()
        val input = socket.getInputStream()
        out.write(byteArrayOf(5, 1, 0))
        out.flush()
        val hello = readN(input, 2)
        if (hello[0].toInt() != 5 || hello[1].toInt() != 0) throw IllegalStateException("proxy refused handshake")
        val target = when (address) {
            is Inet4Address -> byteArrayOf(1) + address.address
            is Inet6Address -> byteArrayOf(4) + address.address
            else -> host.toByteArray().let { byteArrayOf(3, it.size.toByte()) + it }
        }
        out.write(byteArrayOf(5, 1, 0) + target + byteArrayOf((port shr 8).toByte(), port.toByte()))
        out.flush()
        val reply = readN(input, 4)
        if (reply[1].toInt() != 0) {
            throw IllegalStateException("could not connect to the server (SOCKS reply ${reply[1]}: ${socksError(reply[1].toInt())})")
        }
        when (reply[3].toInt()) {
            1 -> readN(input, 4 + 2)
            4 -> readN(input, 16 + 2)
            3 -> readN(input, (input.read() and 0xff) + 2)
        }
    }

    private fun socksError(code: Int) = when (code) {
        1 -> "general failure"
        2 -> "not allowed"
        3 -> "network unreachable"
        4 -> "host unreachable"
        5 -> "connection refused"
        6 -> "TTL expired"
        else -> "error"
    }

    /** Reads headers and up to [MAX_READ] bytes of the body; detects stalls and truncation. */
    private fun readResponse(input: InputStream, start: Long, timeoutMs: Int, progress: (Boolean, Int) -> Unit): Response {
        val buf = ByteArray(8192)
        val head = StringBuilder()
        var headerEnd = -1
        var total = 0
        var bodyTarget = Int.MAX_VALUE
        var declared: Long? = null
        while (total < MAX_READ && System.currentTimeMillis() - start < timeoutMs * 2L) {
            val n = try {
                input.read(buf)
            } catch (e: SocketTimeoutException) {
                // A response that stops after the first kilobytes is a typical throttling pattern.
                if (total > 0) throw EOFException("stalled after $total B") else throw e
            }
            if (n < 0) break
            progress(total == 0, total + n)
            total += n
            if (headerEnd < 0) {
                head.append(String(buf, 0, n, Charsets.ISO_8859_1))
                headerEnd = head.indexOf("\r\n\r\n")
                if (headerEnd >= 0) {
                    declared = Regex("(?im)^content-length:\\s*(\\d+)").find(head)?.groupValues?.get(1)?.toLongOrNull()
                    declared?.let { bodyTarget = (headerEnd + 4 + it).coerceAtMost(MAX_READ.toLong()).toInt() }
                } else if (head.length > 32 * 1024) {
                    break
                }
            }
            if (total >= bodyTarget) break
        }
        val statusLine = head.lineSequence().firstOrNull().orEmpty().trim()
        if (!statusLine.startsWith("HTTP/")) throw EOFException("no HTTP response ($total B received)")
        if (declared != null && headerEnd >= 0 && total < bodyTarget) {
            val body = total - headerEnd - 4
            throw EOFException("body truncated: $body of $declared B")
        }
        val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
        val location = Regex("(?im)^location:\\s*(\\S+)").find(head)?.groupValues?.get(1)
        return Response(code, statusLine.substringAfter(' ').take(40).let { "HTTP $it" }, location)
    }

    private fun looksLikeBlockPage(host: String, location: String): Boolean {
        val target = location.substringAfter("://", "").substringBefore('/').substringBefore(':').lowercase()
        if (target.isEmpty()) return false
        val sameSite = target == host || target.endsWith(".$host") || host.endsWith(".$target") ||
            secondLevel(target) == secondLevel(host)
        return !sameSite && BLOCK_PAGE_HINTS.any { target.contains(it) || location.contains(it, ignoreCase = true) }
    }

    private fun secondLevel(host: String) = host.split('.').takeLast(2).joinToString(".")

    private fun readN(input: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(b, off, n - off)
            if (r < 0) throw EOFException("proxy closed the connection")
            off += r
        }
        return b
    }

    private fun describe(e: Exception, stage: ProbeStage): String {
        val msg = e.message?.take(120)
        val where = when (stage) {
            ProbeStage.PROXY -> "connecting to the test proxy"
            ProbeStage.CONNECT -> "connecting to the server"
            ProbeStage.TLS -> "TLS handshake"
            ProbeStage.CERTIFICATE -> "certificate check"
            ProbeStage.RESPONSE -> "waiting for the HTTP response"
            ProbeStage.BODY -> "reading the response"
            ProbeStage.DONE -> "finishing"
        }
        return when {
            e is SocketTimeoutException -> "timeout during $where"
            msg?.contains("reset", ignoreCase = true) == true -> "connection reset during $where"
            (e is EOFException || e is IllegalStateException) && msg != null -> "$msg during $where"
            msg != null -> "${e.javaClass.simpleName}: $msg during $where"
            else -> "${e.javaClass.simpleName} during $where"
        }
    }
}
