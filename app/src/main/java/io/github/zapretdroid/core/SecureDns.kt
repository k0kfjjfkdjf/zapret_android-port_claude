package io.github.zapretdroid.core

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

/**
 * How the VPN resolves names for apps: plain DNS through the engine, or encrypted by the app:
 * DoH or DoT with a preset, or DNSCRYPT: servers chosen from the public DNSCrypt / DoH list.
 */
enum class DnsMode { PLAIN, DOH, DOT, DNSCRYPT }

/** An encrypted DNS server: reached at [ip], its certificate is checked for [tlsName]. */
class DnsProvider(
    val id: String,
    val title: String,
    val ip: String,
    val tlsName: String,
    val dohPath: String = "/dns-query",
    val dohPort: Int = 443,
    val dotPort: Int = 853,
    /** What the server itself blocks (DnsFilter.Category ids); presets fall back only to the same kind. */
    val filters: Set<String> = emptySet(),
    /** sdns:// stamp of a server from the public list or added by hand (DnsServers). */
    val stamp: String? = null,
)

/**
 * DNS over HTTPS / over TLS for the VPN. The VPN hands apps [VPN_DNS] as their DNS server; the
 * engine sends those queries to [Server] (--dns-redirect), which asks the provider over an
 * encrypted connection and caches the answers. The provider is reached by IP and without SNI
 * by default (nothing for DPI to read), its certificate still checked for its name; the
 * provider's answers cannot be seen or spoofed on the way, unlike plain DNS here (NXDOMAIN for
 * YouTube from 8.8.8.8). Answers still pass the engine, so names for host lists keep working.
 */
object SecureDns {
    /** The DNS address apps see inside the VPN (routed into the tunnel, answered by the app). */
    const val VPN_DNS = "10.10.10.53"

    val providers = listOf(
        DnsProvider("cloudflare", "Cloudflare", "1.1.1.1", "cloudflare-dns.com"),
        DnsProvider("google", "Google", "8.8.8.8", "dns.google"),
        DnsProvider("quad9", "Quad9", "9.9.9.9", "dns.quad9.net", filters = setOf("malware")),
        DnsProvider("adguard", "AdGuard", "94.140.14.14", "dns.adguard-dns.com", filters = setOf("ads")),
        DnsProvider("adguard-family", "AdGuard Family", "94.140.14.15", "family.adguard-dns.com", filters = setOf("ads", "adult")),
        DnsProvider("cloudflare-security", "Cloudflare Security", "1.1.1.2", "security.cloudflare-dns.com", filters = setOf("malware")),
        DnsProvider("cloudflare-family", "Cloudflare Family", "1.1.1.3", "family.cloudflare-dns.com", filters = setOf("malware", "adult")),
    )

    /** A preset by id, or a custom server: "https://host/path", "tls://host" or a host name. */
    fun provider(id: String): DnsProvider {
        providers.firstOrNull { it.id == id }?.let { return it }
        val text = id.trim()
        val authority = text.substringAfter("://").substringBefore('/')
        val host = authority.substringBefore(':')
        val port = authority.substringAfter(':', "").toIntOrNull()
        val path = "/" + text.substringAfter("://", text).substringAfter('/', "dns-query")
        require(host.isNotEmpty()) { "no host in '$id'" }
        // The app is outside the VPN: the system resolver answers (only for this one name).
        val ip = InetAddress.getByName(host).hostAddress ?: throw IOException("cannot resolve $host")
        return DnsProvider(text, host, ip, host, path, dohPort = port ?: 443, dotPort = port ?: 853)
    }

    /** A server of a DNS stamp: DNSCrypt (by its address) or DoH (address, else its host name resolved now). */
    fun fromStamp(name: String, stamp: String): DnsProvider {
        val s = DnsStamp.parse(stamp)
        return when (s.protocol) {
            DnsStamp.Protocol.DNSCRYPT -> DnsProvider(name, name, s.host, s.hostname, stamp = stamp)
            DnsStamp.Protocol.DOH -> {
                val ip = s.host.ifEmpty { InetAddress.getByName(s.hostname).hostAddress ?: throw IOException("cannot resolve ${s.hostname}") }
                DnsProvider(name, name, ip, s.hostname, s.path, dohPort = s.port, stamp = stamp)
            }
        }
    }

    /**
     * One encrypted exchange: a DNS message in, the answer out. Connections are reused. A
     * provider with a DNSCrypt stamp is asked over DNSCrypt whatever [mode] says; one with a DoH
     * stamp over DoH.
     */
    class Upstream(val provider: DnsProvider, mode: DnsMode) {
        private val stamp = provider.stamp?.let { DnsStamp.parse(it) }
        val mode: DnsMode = when (stamp?.protocol) {
            DnsStamp.Protocol.DNSCRYPT -> DnsMode.DNSCRYPT
            DnsStamp.Protocol.DOH -> DnsMode.DOH
            null -> mode
        }
        private val dnscrypt by lazy { DnsCryptClient(stamp!!, TIMEOUT_MS) }

        private class Conn(val socket: SSLSocket, val input: InputStream, val output: OutputStream) {
            var uses = 0
        }

        private val idle = ArrayBlockingQueue<Conn>(4)

        fun exchange(query: ByteArray): ByteArray {
            if (mode == DnsMode.DNSCRYPT) return dnscrypt.exchange(query)
            var last: Exception? = null
            // A reused connection may have been closed by the server, or the server wants SNI
            // (HTTP 421): up to two more tries on new connections.
            repeat(3) { attempt ->
                val conn = (if (attempt == 0) idle.poll() else null) ?: open()
                try {
                    val answer = if (mode == DnsMode.DOT) dot(conn, query) else doh(conn, query)
                    // Some networks stall a TLS connection after its first 16-20 KB from the
                    // server: a connection serves a limited number of answers.
                    if (++conn.uses < MAX_USES && !idle.offer(conn)) conn.socket.close()
                    else if (conn.uses >= MAX_USES) conn.socket.close()
                    return answer
                } catch (e: Exception) {
                    runCatching { conn.socket.close() }
                    last = e
                }
            }
            throw last ?: IOException("no answer")
        }

        fun close() {
            while (true) runCatching { (idle.poll() ?: return).socket.close() }
        }

        @Volatile
        private var sniOnly = false

        private fun open(): Conn {
            val port = if (mode == DnsMode.DOT) provider.dotPort else provider.dohPort
            if (sniOnly) return openWithSni(port)
            val raw = Socket()
            raw.connect(InetSocketAddress(InetAddress.getByName(provider.ip), port), TIMEOUT_MS)
            raw.soTimeout = TIMEOUT_MS
            // By IP: no SNI in the ClientHello. The certificate must still be the provider's.
            val ssl = socketFactory.createSocket(raw, provider.ip, port, true) as SSLSocket
            val verified = runCatching {
                ssl.startHandshake()
                certifiedFor(ssl, provider.tlsName) || certifiedFor(ssl, provider.ip)
            }.getOrDefault(false)
            if (!verified) {
                runCatching { ssl.close() }
                return openWithSni(port)
            }
            return Conn(ssl, BufferedInputStream(ssl.inputStream), ssl.outputStream)
        }

        /** Servers that give their own certificate only for the name in SNI. */
        private fun openWithSni(port: Int): Conn {
            val raw = Socket()
            raw.connect(InetSocketAddress(InetAddress.getByName(provider.ip), port), TIMEOUT_MS)
            raw.soTimeout = TIMEOUT_MS
            val ssl = socketFactory.createSocket(raw, provider.tlsName, port, true) as SSLSocket
            ssl.sslParameters = ssl.sslParameters.apply {
                serverNames = listOf(SNIHostName(provider.tlsName))
            }
            ssl.startHandshake()
            if (!certifiedFor(ssl, provider.tlsName)) {
                ssl.close()
                throw IOException("certificate of ${provider.ip} is not for ${provider.tlsName}")
            }
            return Conn(ssl, BufferedInputStream(ssl.inputStream), ssl.outputStream)
        }

        private fun dot(conn: Conn, query: ByteArray): ByteArray {
            conn.output.write(byteArrayOf((query.size shr 8).toByte(), query.size.toByte()) + query)
            conn.output.flush()
            val len = (readByte(conn.input) shl 8) or readByte(conn.input)
            return readFully(conn.input, len)
        }

        private fun doh(conn: Conn, query: ByteArray): ByteArray {
            val head = "POST ${provider.dohPath} HTTP/1.1\r\nHost: ${provider.tlsName}\r\n" +
                "Content-Type: application/dns-message\r\nAccept: application/dns-message\r\n" +
                "Content-Length: ${query.size}\r\n\r\n"
            conn.output.write(head.toByteArray(Charsets.US_ASCII) + query)
            conn.output.flush()
            val status = readLine(conn.input)
            val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("bad HTTP status '$status'")
            var length = -1
            var chunked = false
            var close = false
            while (true) {
                val line = readLine(conn.input)
                if (line.isEmpty()) break
                val name = line.substringBefore(':').trim().lowercase()
                val value = line.substringAfter(':').trim()
                when (name) {
                    "content-length" -> length = value.toIntOrNull() ?: -1
                    "transfer-encoding" -> chunked = value.contains("chunked", ignoreCase = true)
                    "connection" -> close = value.equals("close", ignoreCase = true)
                }
            }
            val body = when {
                chunked -> readChunked(conn.input)
                length >= 0 -> readFully(conn.input, length)
                // HTTP/1.0 style: the body ends with the connection.
                else -> readToEnd(conn.input).also { close = true }
            }
            if (close) conn.uses = MAX_USES
            // 421: the server wants the name in SNI (Cloudflare without SNI): from now on with SNI.
            if (code == 421 && !sniOnly) {
                sniOnly = true
                conn.uses = MAX_USES
                throw IOException("HTTP 421 from ${provider.title}, retrying with SNI")
            }
            if (code == 505 || code == 400) {
                // Servers that speak only HTTP/2 answer an HTTP/1.1 request like this.
                throw IOException("HTTP $code from ${provider.title}: probably HTTP/2 only (not supported yet), use DoT or another server")
            }
            if (code != 200) throw IOException("HTTP $code from ${provider.title}")
            return body
        }

        private fun readToEnd(input: InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (out.size() < 65_536) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }

        private fun readChunked(input: InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            while (true) {
                val size = readLine(input).substringBefore(';').trim().toInt(16)
                if (size == 0) {
                    while (readLine(input).isNotEmpty()) Unit
                    return out.toByteArray()
                }
                out.write(readFully(input, size))
                readLine(input)
            }
        }
    }

    /**
     * The resolver the engine redirects the VPN's DNS to: UDP on 127.0.0.1, answers from the
     * provider (the other presets if it fails), cached for their TTL. With several servers
     * (DNSCrypt mode) the fastest of those that answer is asked first.
     */
    class Server(servers: List<DnsProvider>, mode: DnsMode, filter: DnsFilter.Matcher? = null) {
        constructor(provider: DnsProvider, mode: DnsMode, filter: DnsFilter.Matcher? = null) : this(withFallbacks(provider), mode, filter)

        /** Replaced when the lists are updated while the VPN runs. */
        @Volatile
        var filter: DnsFilter.Matcher? = filter

        private class Stat(var ms: Double, var failedUntil: Long = 0)

        init {
            require(servers.isNotEmpty()) { "no DNS servers" }
        }

        private val upstreams = servers.map { Upstream(it, mode) }
        // Initial scores keep the chosen order until real answer times are known.
        private val stats = upstreams.indices.map { Stat(it.toDouble()) }
        private val socket = DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        private val workers: ExecutorService = Executors.newFixedThreadPool(4)
        private val cache = Cache()
        private val queries = AtomicInteger()
        private val hits = AtomicInteger()
        private val failures = AtomicInteger()
        private val blocked = AtomicInteger()

        @Volatile
        private var running = true

        val port: Int get() = socket.localPort

        init {
            thread(name = "secure-dns", isDaemon = true) {
                val buf = ByteArray(4096)
                while (running) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (_: IOException) {
                        break
                    }
                    val query = buf.copyOf(packet.length)
                    val from = packet.socketAddress
                    workers.execute { answer(query)?.let { runCatching { socket.send(DatagramPacket(it, it.size, from)) } } }
                }
            }
        }

        private fun answer(query: ByteArray): ByteArray? {
            if (query.size < 12) return null
            queries.incrementAndGet()
            val key = Cache.key(query) ?: return null
            filter?.blocked(key.substringBefore('/'))?.let { category ->
                if (blocked.incrementAndGet() <= 200) AppLog.i("DNS filter: ${key.substringBefore('/').trimEnd('.')} blocked ($category)")
                return blockedAnswer(query, key.split('/').let { it[1].toInt() shl 8 or it[2].toInt() })
            }
            cache.get(key)?.let {
                hits.incrementAndGet()
                return it.withId(query)
            }
            for (i in order()) {
                val upstream = upstreams[i]
                val start = System.nanoTime()
                try {
                    val answer = upstream.exchange(query)
                    if (answer.size >= 12) {
                        val ms = (System.nanoTime() - start) / 1e6
                        synchronized(stats) { stats[i].ms = stats[i].ms * 0.7 + ms * 0.3; stats[i].failedUntil = 0 }
                        cache.put(key, answer)
                        return answer.withId(query)
                    }
                } catch (e: Exception) {
                    synchronized(stats) { stats[i].failedUntil = System.currentTimeMillis() + 60_000 }
                    if (failures.incrementAndGet() <= 20) {
                        AppLog.w("secure DNS: ${upstream.provider.title} (${upstream.mode}) failed: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
            }
            // SERVFAIL: the app tries again instead of waiting for a timeout.
            return query.copyOf().also {
                it[2] = (it[2].toInt() or 0x80).toByte()
                it[3] = 0x82.toByte()
            }
        }

        /** Servers that did not fail in the last minute first, each group by its answer time. */
        private fun order(): List<Int> = synchronized(stats) {
            val now = System.currentTimeMillis()
            upstreams.indices.sortedWith(compareBy<Int> { stats[it].failedUntil > now }.thenBy { stats[it].ms })
        }

        fun stop() {
            running = false
            socket.close()
            workers.shutdownNow()
            upstreams.forEach { it.close() }
            AppLog.i("secure DNS: ${queries.get()} queries, ${hits.get()} from cache, ${blocked.get()} blocked, ${failures.get()} failed exchanges")
        }
    }

    /** A preset and the other presets that block the same (a family filter never falls back to an unfiltered one). */
    fun withFallbacks(provider: DnsProvider): List<DnsProvider> =
        listOf(provider) + providers.filter { it.id != provider.id && providers.contains(provider) && it.filters == provider.filters }

    /**
     * The answer to a blocked name: 0.0.0.0 for A, :: for AAAA (apps give up at once, no retries
     * elsewhere), NXDOMAIN for other types. Only the question is kept (no EDNS).
     */
    fun blockedAnswer(query: ByteArray, type: Int): ByteArray {
        var end = 12
        while (query[end].toInt() != 0) end += 1 + (query[end].toInt() and 0xff)
        end += 5
        val address = when (type) {
            1 -> ByteArray(4)
            28 -> ByteArray(16)
            else -> null
        }
        val header = byteArrayOf(
            query[0], query[1], (0x80 or (query[2].toInt() and 0x01)).toByte(), if (address != null) 0x80.toByte() else 0x83.toByte(),
            0, 1, 0, if (address != null) 1 else 0, 0, 0, 0, 0,
        )
        val answer = address?.let {
            byteArrayOf(0xc0.toByte(), 0x0c, 0, type.toByte(), 0, 1, 0, 0, 1, 0x2c, 0, it.size.toByte()) + it
        } ?: ByteArray(0)
        return header + query.copyOfRange(12, end) + answer
    }

    /** Answers by question (name, type, class), until their smallest TTL runs out. */
    private class Cache {
        private class Entry(val answer: ByteArray, val expires: Long)

        private val map = object : LinkedHashMap<String, Entry>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) = size > 1024
        }

        @Synchronized
        fun get(key: String): ByteArray? {
            val e = map[key] ?: return null
            if (System.currentTimeMillis() > e.expires) {
                map.remove(key)
                return null
            }
            return e.answer
        }

        @Synchronized
        fun put(key: String, answer: ByteArray) {
            val rcode = answer[3].toInt() and 0x0f
            if (rcode != 0 && rcode != 3) return
            val ttl = (minTtl(answer) ?: 60).coerceIn(30, 3600)
            map[key] = Entry(answer, System.currentTimeMillis() + ttl * 1000L)
        }

        companion object {
            /** The question section, lower case: same question, same answer. */
            fun key(msg: ByteArray): String? {
                if (((msg[4].toInt() and 0xff) shl 8 or (msg[5].toInt() and 0xff)) != 1) return null
                var pos = 12
                val sb = StringBuilder()
                while (pos < msg.size) {
                    val len = msg[pos].toInt() and 0xff
                    if (len == 0) break
                    if (len and 0xc0 != 0 || pos + 1 + len > msg.size) return null
                    sb.append(String(msg, pos + 1, len, Charsets.ISO_8859_1).lowercase()).append('.')
                    pos += len + 1
                }
                if (pos + 5 > msg.size) return null
                for (i in pos + 1 until pos + 5) sb.append('/').append(msg[i].toInt() and 0xff)
                return sb.toString()
            }

            fun minTtl(msg: ByteArray): Int? = runCatching {
                val qd = (msg[4].toInt() and 0xff) shl 8 or (msg[5].toInt() and 0xff)
                val an = (msg[6].toInt() and 0xff) shl 8 or (msg[7].toInt() and 0xff)
                var pos = 12
                repeat(qd) { pos = skipName(msg, pos) + 4 }
                var min: Int? = null
                repeat(an) {
                    pos = skipName(msg, pos)
                    val ttl = ((msg[pos + 4].toInt() and 0xff) shl 24) or ((msg[pos + 5].toInt() and 0xff) shl 16) or
                        ((msg[pos + 6].toInt() and 0xff) shl 8) or (msg[pos + 7].toInt() and 0xff)
                    min = minOf(min ?: ttl, ttl)
                    pos += 10 + ((msg[pos + 8].toInt() and 0xff) shl 8 or (msg[pos + 9].toInt() and 0xff))
                }
                min
            }.getOrNull()

            private fun skipName(msg: ByteArray, start: Int): Int {
                var pos = start
                while (true) {
                    val len = msg[pos].toInt() and 0xff
                    if (len == 0) return pos + 1
                    if (len and 0xc0 == 0xc0) return pos + 2
                    pos += len + 1
                }
            }
        }
    }

    /** Replaced in unit tests (a test certificate; the JVM's default verifier rejects everything). */
    var socketFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
    var hostnameVerifier: HostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()

    /** Whether the server's certificate (already trusted by the handshake) names [host]. */
    private fun certifiedFor(ssl: SSLSocket, host: String) = hostnameVerifier.verify(host, ssl.session)

    private const val TIMEOUT_MS = 3000
    private const val MAX_USES = 40

    private fun ByteArray.withId(query: ByteArray): ByteArray = copyOf().also {
        it[0] = query[0]
        it[1] = query[1]
    }

    private fun readByte(input: InputStream): Int {
        val b = input.read()
        if (b < 0) throw EOFException()
        return b
    }

    private fun readFully(input: InputStream, len: Int): ByteArray {
        val out = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(out, off, len - off)
            if (n < 0) throw EOFException()
            off += n
        }
        return out
    }

    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = readByte(input)
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 8192) throw IOException("HTTP line too long")
        }
    }
}
