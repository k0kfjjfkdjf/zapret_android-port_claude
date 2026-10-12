package io.github.zapretdroid.test

import java.io.EOFException
import java.io.InputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class ProbeResult(val host: String, val ok: Boolean, val detail: String, val millis: Long)

/**
 * Opens an HTTPS connection to a site through the local test proxy (SOCKS5) and checks that
 * the TLS handshake succeeds with a valid certificate and that an HTTP response comes back.
 * A DPI block shows up as a reset, a timeout or a stalled response.
 */
object SiteProbe {
    private const val MAX_READ = 64 * 1024

    fun probe(proxyPort: Int, host: String, address: InetAddress?, timeoutMs: Int): ProbeResult {
        val start = System.currentTimeMillis()
        fun result(ok: Boolean, detail: String) = ProbeResult(host, ok, detail, System.currentTimeMillis() - start)
        return try {
            Socket().use { raw ->
                raw.soTimeout = timeoutMs
                raw.connect(InetSocketAddress("127.0.0.1", proxyPort), timeoutMs)
                socksConnect(raw, host, address, 443)
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                (factory.createSocket(raw, host, 443, true) as SSLSocket).use { ssl ->
                    ssl.soTimeout = timeoutMs
                    ssl.startHandshake()
                    if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) {
                        return result(false, "certificate does not match (DNS spoofing?)")
                    }
                    ssl.outputStream.apply {
                        write("GET / HTTP/1.1\r\nHost: $host\r\nUser-Agent: Mozilla/5.0\r\nAccept: */*\r\nConnection: close\r\n\r\n".toByteArray())
                        flush()
                    }
                    val status = readResponse(ssl.inputStream, start, timeoutMs)
                    result(true, status)
                }
            }
        } catch (e: Exception) {
            result(false, describe(e))
        }
    }

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
        if (reply[1].toInt() != 0) throw IllegalStateException("connect failed (SOCKS code ${reply[1]})")
        when (reply[3].toInt()) {
            1 -> readN(input, 4 + 2)
            4 -> readN(input, 16 + 2)
            3 -> readN(input, (input.read() and 0xff) + 2)
        }
    }

    /** Returns the status line after reading the response body up to [MAX_READ] bytes. */
    private fun readResponse(input: InputStream, start: Long, timeoutMs: Int): String {
        val buf = ByteArray(8192)
        val head = StringBuilder()
        var total = 0
        while (total < MAX_READ && System.currentTimeMillis() - start < timeoutMs * 2) {
            val n = try {
                input.read(buf)
            } catch (e: java.net.SocketTimeoutException) {
                // A response that stops after the first kilobytes is a typical throttling pattern.
                if (total > 0) throw EOFException("stalled after ${total / 1024} KB") else throw e
            }
            if (n < 0) break
            if (head.length < 64) head.append(String(buf, 0, minOf(n, 64), Charsets.ISO_8859_1))
            total += n
        }
        val status = head.lineSequence().firstOrNull().orEmpty()
        if (!status.startsWith("HTTP/")) throw EOFException("no HTTP response")
        return status.substringAfter(' ').take(24)
    }

    private fun readN(input: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(b, off, n - off)
            if (r < 0) throw EOFException("proxy closed connection")
            off += r
        }
        return b
    }

    private fun describe(e: Exception): String {
        val name = e.javaClass.simpleName
        val msg = e.message?.take(80)
        return when {
            name.contains("Timeout") -> "timeout"
            msg?.contains("reset", ignoreCase = true) == true -> "connection reset"
            msg != null -> "$name: $msg"
            else -> name
        }
    }
}
