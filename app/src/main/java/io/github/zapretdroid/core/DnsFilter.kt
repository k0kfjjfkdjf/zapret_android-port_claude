package io.github.zapretdroid.core

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Blocking by name in the app's own resolver (encrypted DNS modes): ads and trackers, malware
 * and phishing, adult sites, from HaGeZi's DNS blocklists (downloaded on demand, never bundled),
 * plus the user's own lists. A blocked A/AAAA query gets 0.0.0.0 / ::, others NXDOMAIN; a
 * listed domain blocks its subdomains too, the allow list wins over everything.
 *
 * Lists are kept as sorted 64-bit hashes of the names (8 bytes per domain, ~400k domains for all
 * three in ~3 MB): no strings in memory, and a false match needs a 64-bit collision.
 */
object DnsFilter {
    enum class Category(val id: String, val url: String) {
        ADS("ads", "$HAGEZI/multi-onlydomains.txt"),
        MALWARE("malware", "$HAGEZI/tif.mini-onlydomains.txt"),
        ADULT("adult", "$HAGEZI/nsfw-onlydomains.txt"),
    }

    /** The user's lists (Lists screen): names always blocked / never blocked. */
    const val OWN_BLOCK = "dns-block"
    const val OWN_ALLOW = "dns-allow"

    private const val HAGEZI = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard"
    private const val MAGIC = 0x5a44_4631 // "ZDF1"

    /** FNV-1a, 64 bit, over the lower-case name without the trailing dot. */
    fun hash(name: CharSequence): Long {
        var h = -0x340d631b7bdddcdbL
        for (c in name) {
            h = h xor (c.lowercaseChar().code.toLong() and 0xff)
            h *= 0x100000001b3L
        }
        return h
    }

    /** One list: sorted distinct hashes. */
    class HashSet(val hashes: LongArray) {
        val size get() = hashes.size

        /** Whether the name or one of its parents (not the bare TLD) is listed. */
        fun matches(name: String): Boolean {
            var rest = name.trimEnd('.')
            while (rest.indexOf('.') > 0) {
                if (hashes.binarySearch(hash(rest)) >= 0) return true
                rest = rest.substringAfter('.')
            }
            return false
        }
    }

    /** Names of a list in any common format: plain domains, hosts ("0.0.0.0 name"), "||name^", "*.name". */
    fun parse(lines: Sequence<String>): LongArray {
        val out = LongArrayBuilder()
        for (raw in lines) {
            var line = raw.substringBefore('#').trim()
            if (line.isEmpty() || line.startsWith("!")) continue
            val parts = line.split(' ', '\t').filter { it.isNotEmpty() }
            line = if (parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1" || parts[0] == "::")) parts[1] else parts[0]
            line = line.removePrefix("||").removeSuffix("^").removePrefix("*.").removePrefix(".").trimEnd('.').lowercase()
            if (line.indexOf('.') <= 0 || line == "localhost" || !line.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' || it == '_' }) continue
            out.add(hash(line))
        }
        return out.sortedDistinct()
    }

    /** What the resolver asks: the category of a blocked name, or null. */
    class Matcher(private val lists: List<Pair<String, HashSet>>, private val allow: HashSet?) {
        val size get() = lists.sumOf { it.second.size }
        val categories get() = lists.map { it.first }

        fun blocked(name: String): String? {
            if (allow?.matches(name) == true) return null
            return lists.firstOrNull { it.second.matches(name) }?.first
        }
    }

    fun dir(context: Context) = File(context.filesDir, "dnsfilter").apply { mkdirs() }

    fun file(context: Context, category: Category) = File(dir(context), "${category.id}.bin")

    /** Downloaded and compiled, or null when never updated. */
    fun updatedAt(context: Context, category: Category): Long? = file(context, category).takeIf { it.isFile }?.lastModified()

    const val MAX_AGE_MS = 3 * 24 * 60 * 60 * 1000L

    /** Enabled categories never downloaded or older than [MAX_AGE_MS]. */
    fun stale(context: Context, enabled: Set<String>): List<Category> = Category.entries.filter { c ->
        c.id in enabled && (updatedAt(context, c)?.let { System.currentTimeMillis() - it > MAX_AGE_MS } ?: true)
    }

    fun count(context: Context, category: Category): Int? = runCatching {
        DataInputStream(file(context, category).inputStream().buffered()).use { if (it.readInt() == MAGIC) it.readInt() else null }
    }.getOrNull()

    /** Downloads one list and replaces the compiled copy; its domain count. Network: off the main thread. */
    fun update(context: Context, category: Category): Int {
        val hashes = download(category.url) { parse(it.bufferedReader().lineSequence()) }
        if (hashes.size < 100) throw IOException("${category.id}: only ${hashes.size} domains, kept the old list")
        val tmp = File(dir(context), "${category.id}.tmp")
        write(tmp, hashes)
        if (!tmp.renameTo(file(context, category))) throw IOException("cannot save ${category.id}")
        AppLog.i("DNS filter: ${category.id} updated, ${hashes.size} domains")
        return hashes.size
    }

    /** The matcher for the enabled categories (downloaded ones) and the user's lists; null if nothing to block. */
    fun load(context: Context, enabled: Set<String>): Matcher? {
        val lists = mutableListOf<Pair<String, HashSet>>()
        HostLists.file(context, OWN_BLOCK).takeIf { it.isFile }?.let { f ->
            parse(f.readLines().asSequence()).takeIf { it.isNotEmpty() }?.let { lists += "own" to HashSet(it) }
        }
        for (c in Category.entries.filter { it.id in enabled }) {
            val f = file(context, c)
            if (!f.isFile) {
                AppLog.w("DNS filter: ${c.id} is on but not downloaded yet, downloading")
                continue
            }
            runCatching { lists += c.id to HashSet(read(f)) }.onFailure { AppLog.w("DNS filter: ${c.id} unreadable: ${it.message}") }
        }
        if (lists.isEmpty()) return null
        val allow = HostLists.file(context, OWN_ALLOW).takeIf { it.isFile }?.let { HashSet(parse(it.readLines().asSequence())) }
        return Matcher(lists, allow)
    }

    fun write(file: File, hashes: LongArray) = DataOutputStream(file.outputStream().buffered()).use { out ->
        out.writeInt(MAGIC)
        out.writeInt(hashes.size)
        hashes.forEach { out.writeLong(it) }
    }

    fun read(file: File): LongArray = DataInputStream(file.inputStream().buffered(1 shl 16)).use { input ->
        if (input.readInt() != MAGIC) throw IOException("not a filter list")
        LongArray(input.readInt()) { input.readLong() }
    }

    private fun <T> download(url: String, parse: (InputStream) -> T): T {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", "ByeZapret")
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode} from ${URL(url).host}")
            return conn.inputStream.use(parse)
        } finally {
            conn.disconnect()
        }
    }

    private class LongArrayBuilder {
        private var data = LongArray(1024)
        private var n = 0

        fun add(v: Long) {
            if (n == data.size) data = data.copyOf(n * 2)
            data[n++] = v
        }

        fun sortedDistinct(): LongArray {
            val a = data.copyOf(n).also { it.sort() }
            var w = 0
            for (i in a.indices) if (i == 0 || a[i] != a[i - 1]) a[w++] = a[i]
            return a.copyOf(w)
        }
    }
}
