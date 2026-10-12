package io.github.zapretdroid.core

import android.content.Context
import java.io.File
import java.net.IDN
import java.util.zip.GZIPInputStream

/**
 * Host lists in the zapret format: one domain per line, subdomains included, "#" comments.
 * Lists are stored normalized (lowercase ASCII, one domain per line), which is what the engine's
 * --hosts expects, and are referenced from arguments as {list:name}.
 */
object HostLists {
    data class Normalized(val hosts: List<String>, val invalid: List<String>)

    private val NAME = Regex("^[A-Za-z0-9_-]{1,40}$")
    private val DOMAIN = Regex("^[a-z0-9_-]+(\\.[a-z0-9_-]+)*$")
    private const val MAX_IMPORT = 32 * 1024 * 1024

    fun dir(context: Context): File = File(context.filesDir, "lists").apply { mkdirs() }

    fun file(context: Context, name: String) = File(dir(context), "$name.txt")

    fun names(context: Context): List<String> =
        dir(context).listFiles { f -> f.isFile && f.name.endsWith(".txt") }.orEmpty()
            .map { it.name.removeSuffix(".txt") }.sortedBy { it.lowercase() }

    fun isValidName(name: String) = NAME.matches(name)

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

    fun delete(context: Context, name: String) {
        file(context, name).delete()
    }

    /**
     * Accepts zapret lists and loose input: comments, several domains per line, URLs,
     * "*.example.com", ports, uppercase and IDN names.
     */
    fun normalize(text: String): Normalized {
        val hosts = LinkedHashSet<String>()
        val invalid = mutableListOf<String>()
        text.lineSequence().forEach { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            line.split(Regex("[\\s,;]+")).filter { it.isNotEmpty() }.forEach { token ->
                val host = cleanHost(token)
                if (host != null) hosts += host else invalid += token
            }
        }
        return Normalized(hosts.toList(), invalid)
    }

    private fun cleanHost(token: String): String? {
        var h = token.trim().lowercase()
        h = h.substringAfter("://")
        h = h.substringBefore('/').substringBefore('?')
        if (h.startsWith("[")) return null // IPv6 literal
        h = h.substringBefore(':')
        h = h.removePrefix("*.").trimStart('.').trimEnd('.')
        if (h.isEmpty()) return null
        val ascii = runCatching { IDN.toASCII(h, IDN.ALLOW_UNASSIGNED) }.getOrNull()?.lowercase() ?: return null
        return ascii.takeIf { it.length <= 253 && DOMAIN.matches(it) }
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
