package io.github.zapretdroid.core

import android.content.Context
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Host lists in the zapret format: one domain per line, subdomains included, "#" comments.
 * Lists are stored normalized (lowercase ASCII, one domain per line), which is what the engine's
 * --hosts expects, and are referenced from arguments as {list:name}.
 */
typealias Normalized = HostNames.Normalized

object HostLists {
    private const val MAX_IMPORT = 32 * 1024 * 1024

    fun dir(context: Context): File = File(context.filesDir, "lists").apply { mkdirs() }

    fun file(context: Context, name: String) = File(dir(context), "$name.txt")

    fun names(context: Context): List<String> =
        dir(context).listFiles { f -> f.isFile && f.name.endsWith(".txt") }.orEmpty()
            .map { it.name.removeSuffix(".txt") }.sortedBy { it.lowercase() }

    fun isValidName(name: String) = HostNames.isValidName(name)

    /** See [HostNames.normalize]. */
    fun normalize(text: String): Normalized = HostNames.normalize(text)

    fun count(context: Context, name: String): Int =
        file(context, name).takeIf { it.exists() }?.useLines { lines -> lines.count { it.isNotBlank() } } ?: 0

    fun read(context: Context, name: String): String =
        file(context, name).takeIf { it.exists() }?.readText().orEmpty()

    /** Normalizes [text] and saves it; returns what was kept and what was rejected. */
    fun save(context: Context, name: String, text: String): Normalized {
        require(isValidName(name)) { "bad list name $name" }
        val result = normalize(text)
        file(context, name).writeText(result.hosts.joinToString("\n", postfix = if (result.hosts.isEmpty()) "" else "\n"))
        return result
    }

    /** Imports a plain or gzip-compressed list. */
    fun import(context: Context, name: String, bytes: ByteArray): Normalized {
        val plain = if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            GZIPInputStream(bytes.inputStream()).use { it.readNBytesCompat(MAX_IMPORT) }
        } else {
            bytes
        }
        return save(context, name, String(plain, Charsets.UTF_8))
    }

    fun add(context: Context, name: String, hosts: Collection<String>): Normalized =
        save(context, name, read(context, name) + "\n" + hosts.joinToString("\n"))

    /** The URL a list was downloaded from, kept next to it so it can be updated later. */
    fun source(context: Context, name: String): String? =
        File(dir(context), "$name.src").takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }

    /** Downloads a list (plain or gzip) from [url], saves it as [name] and remembers the URL. */
    fun download(context: Context, name: String, url: String): Normalized {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode}")
            val bytes = conn.inputStream.use { it.readNBytesCompat(MAX_IMPORT) }
            val result = import(context, name, bytes)
            File(dir(context), "$name.src").writeText(url)
            AppLog.i("host list '$name' downloaded from $url: ${result.hosts.size} hosts, ${result.invalid.size} rejected")
            return result
        } finally {
            conn.disconnect()
        }
    }

    fun delete(context: Context, name: String) {
        File(dir(context), "$name.src").delete()
        file(context, name).delete()
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (out.size() < limit) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
