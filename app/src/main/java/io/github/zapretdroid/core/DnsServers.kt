package io.github.zapretdroid.core

import android.content.Context
import io.github.zapretdroid.core.crypto.NaCl
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The public DNSCrypt / DoH server list (dnscrypt-resolvers' public-resolvers.md, as in
 * dnscrypt-proxy and InviZible Pro), checked against its minisign signature before use, plus
 * the user's own servers (sdns:// stamps added by hand).
 */
object DnsServers {
    /** Frank Denis' key for the dnscrypt-resolvers lists. */
    const val MINISIGN_KEY = "RWQf6LRCGA9i53mlYecO4IzT51TGPpvWucNSCh1CBM0QTaLn73Y7GFO3"
    private const val LIST = "public-resolvers.md"
    val SOURCES = listOf(
        "https://raw.githubusercontent.com/DNSCrypt/dnscrypt-resolvers/master/v3/$LIST",
        "https://download.dnscrypt.info/resolvers-list/v3/$LIST",
        "https://cdn.jsdelivr.net/gh/DNSCrypt/dnscrypt-resolvers@master/v3/$LIST",
    )
    const val MAX_AGE_MS = 3 * 24 * 60 * 60 * 1000L
    private const val OWN = "own-resolvers.txt"

    class Server(val name: String, val description: String, val stamps: List<String>, val own: Boolean = false) {
        /** The first stamp this app can use. */
        val stamp: DnsStamp by lazy { stamps.firstNotNullOf { runCatching { DnsStamp.parse(it) }.getOrNull() } }
        val stampText: String get() = stamps.first { runCatching { DnsStamp.parse(it) }.isSuccess }

        fun matches(query: String): Boolean {
            if (query.isBlank()) return true
            val q = query.trim().lowercase()
            return name.lowercase().contains(q) || description.lowercase().contains(q)
        }
    }

    fun dir(context: Context) = File(context.filesDir, "dnscrypt").apply { mkdirs() }

    fun listFile(context: Context) = File(dir(context), LIST)

    fun updatedAt(context: Context): Long? = listFile(context).takeIf { it.isFile }?.lastModified()

    /** Downloads the list and its signature from the first source that works; the server count. */
    fun update(context: Context): Int {
        val errors = mutableListOf<String>()
        for (url in SOURCES) {
            try {
                val list = get(url)
                val sig = get("$url.minisig")
                Minisign.verify(MINISIGN_KEY, list, String(sig, Charsets.UTF_8))
                val servers = parse(String(list, Charsets.UTF_8))
                if (servers.size < 20) throw IOException("only ${servers.size} servers")
                val tmp = File(dir(context), "$LIST.tmp")
                tmp.writeBytes(list)
                if (!tmp.renameTo(listFile(context))) throw IOException("cannot save the list")
                AppLog.i("DNS servers: list updated from ${URL(url).host}, ${servers.size} usable servers, signature checked")
                return servers.size
            } catch (e: Exception) {
                errors += "${URL(url).host}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        throw IOException(errors.joinToString("; "))
    }

    /** The cached public list (empty if never downloaded) followed by the user's own servers. */
    fun load(context: Context): List<Server> {
        val public = listFile(context).takeIf { it.isFile }?.let { runCatching { parse(it.readText()) }.getOrNull() } ?: emptyList()
        return public + own(context)
    }

    /** Entries of a resolvers .md: "## name", description lines, one or more sdns:// lines. Unusable stamps are left out. */
    fun parse(md: String): List<Server> {
        val out = mutableListOf<Server>()
        var name: String? = null
        val desc = StringBuilder()
        val stamps = mutableListOf<String>()
        fun flush() {
            val n = name ?: return
            val usable = stamps.filter { runCatching { DnsStamp.parse(it) }.isSuccess }
            if (usable.isNotEmpty()) out += Server(n, desc.toString().trim().replace(Regex("\\s+"), " "), usable)
            name = null
            desc.setLength(0)
            stamps.clear()
        }
        for (raw in md.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("## ") -> { flush(); name = line.removePrefix("## ").trim() }
                name == null -> Unit
                line.startsWith("sdns://") -> stamps += line
                stamps.isEmpty() -> desc.append(line).append(' ')
            }
        }
        flush()
        return out
    }

    fun own(context: Context): List<Server> {
        val f = File(dir(context), OWN)
        if (!f.isFile) return emptyList()
        return f.readLines().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            if (parts.size == 2 && runCatching { DnsStamp.parse(parts[1]) }.isSuccess) Server(parts[0], "", listOf(parts[1]), own = true) else null
        }
    }

    /** Adds (or replaces) an own server; the name it was saved under. */
    fun addOwn(context: Context, name: String, stamp: String): String {
        val s = DnsStamp.parse(stamp.trim())
        val clean = name.trim().replace(Regex("\\s+"), "-").ifEmpty { "own-" + s.hostname.ifEmpty { s.host } }
        val rest = own(context).filter { it.name != clean }.map { "${it.name} ${it.stamps.first()}" }
        File(dir(context), OWN).writeText((rest + "$clean ${stamp.trim()}").joinToString("\n", postfix = "\n"))
        return clean
    }

    fun removeOwn(context: Context, name: String) {
        val rest = own(context).filter { it.name != name }.map { "${it.name} ${it.stamps.first()}" }
        File(dir(context), OWN).writeText(rest.joinToString("\n", postfix = if (rest.isEmpty()) "" else "\n"))
    }

    /** The resolver's upstreams for the chosen servers, in the order chosen; unusable ones are logged and left out. */
    fun providers(chosen: List<Pair<String, String>>): List<DnsProvider> = chosen.mapNotNull { (name, stamp) ->
        runCatching { SecureDns.fromStamp(name, stamp) }.onFailure { AppLog.w("DNS servers: '$name' unusable: ${it.message}") }.getOrNull()
    }

    /** The chosen servers with stamps from the current list (servers move); those gone from it are kept as they were. */
    fun refreshed(chosen: List<Pair<String, String>>, servers: List<Server>): List<Pair<String, String>> {
        val byName = servers.associateBy { it.name }
        return chosen.map { (name, stamp) -> name to (byName[name]?.stampText ?: stamp) }
    }

    private fun get(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "ByeZapret")
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}

/** minisign signature checks (https://jedisct1.github.io/minisign/): legacy "Ed" and prehashed "ED". */
object Minisign {
    fun verify(publicKey: String, data: ByteArray, signatureFile: String) {
        val pk = DnsStamp.base64(publicKey.trim())
        if (pk.size != 42 || pk[0] != 'E'.code.toByte() || pk[1] != 'd'.code.toByte()) throw IOException("bad minisign key")
        val lines = signatureFile.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 4) throw IOException("bad signature file")
        val sig = DnsStamp.base64(lines[1])
        if (sig.size != 74) throw IOException("bad signature")
        if (!sig.copyOfRange(2, 10).contentEquals(pk.copyOfRange(2, 10))) throw IOException("signed with another key")
        val key = pk.copyOfRange(10, 42)
        val message = when (String(sig, 0, 2, Charsets.US_ASCII)) {
            "Ed" -> data
            "ED" -> NaCl.blake2b512(data)
            else -> throw IOException("unknown signature algorithm")
        }
        if (!NaCl.ed25519Verify(key, message, sig.copyOfRange(10, 74))) throw IOException("signature does not match")
        val trusted = lines[2].removePrefix("trusted comment: ")
        val global = DnsStamp.base64(lines[3])
        if (!NaCl.ed25519Verify(key, sig.copyOfRange(10, 74) + trusted.toByteArray(Charsets.UTF_8), global)) {
            throw IOException("trusted comment signature does not match")
        }
    }
}
