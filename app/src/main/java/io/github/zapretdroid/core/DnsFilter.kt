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
    private const val MAGIC = 0x5a44_4631 // "ZDF1", as in DnsBlocklist

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
        val hashes = download(category.url) { DnsBlocklist.parse(it.bufferedReader().lineSequence()) }
        if (hashes.size < 100) throw IOException("${category.id}: only ${hashes.size} domains, kept the old list")
        val tmp = File(dir(context), "${category.id}.tmp")
        DnsBlocklist.write(tmp, hashes)
        if (!tmp.renameTo(file(context, category))) throw IOException("cannot save ${category.id}")
        AppLog.i("DNS filter: ${category.id} updated, ${hashes.size} domains")
        return hashes.size
    }

    /** The matcher for the enabled categories (downloaded ones) and the user's lists; null if nothing to block. */
    fun load(context: Context, enabled: Set<String>): DnsBlocklist.Matcher? {
        val lists = mutableListOf<Pair<String, DnsBlocklist.HashSet>>()
        HostLists.file(context, OWN_BLOCK).takeIf { it.isFile }?.let { f ->
            DnsBlocklist.parse(f.readLines().asSequence()).takeIf { it.isNotEmpty() }?.let { lists += "own" to DnsBlocklist.HashSet(it) }
        }
        for (c in Category.entries.filter { it.id in enabled }) {
            val f = file(context, c)
            if (!f.isFile) {
                AppLog.w("DNS filter: ${c.id} is on but not downloaded yet, downloading")
                continue
            }
            runCatching { lists += c.id to DnsBlocklist.HashSet(DnsBlocklist.read(f)) }.onFailure { AppLog.w("DNS filter: ${c.id} unreadable: ${it.message}") }
        }
        if (lists.isEmpty()) return null
        val allow = HostLists.file(context, OWN_ALLOW).takeIf { it.isFile }?.let { DnsBlocklist.HashSet(DnsBlocklist.parse(it.readLines().asSequence())) }
        return DnsBlocklist.Matcher(lists, allow)
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
}
