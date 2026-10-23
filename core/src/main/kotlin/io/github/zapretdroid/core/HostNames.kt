package io.github.zapretdroid.core

import java.net.IDN

/**
 * Domain lists in the zapret format, as text: one domain per line, subdomains included, "#"
 * comments. The files themselves are kept by the app (HostLists); this is what both the Android
 * and the desktop app read them with.
 */
object HostNames {
    data class Normalized(val hosts: List<String>, val invalid: List<String>)

    private val NAME = Regex("^[A-Za-z0-9_-]{1,40}$")
    private val DOMAIN = Regex("^[a-z0-9_-]+(\\.[a-z0-9_-]+)*$")

    /** A list name usable as a file name and in {list:name}. */
    fun isValidName(name: String) = NAME.matches(name)

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
        // "^domain" is an exact-match marker in some Windows zapret lists (Flowseal).
        h = h.removePrefix("^").removePrefix("*.").trimStart('.').trimEnd('.')
        if (h.isEmpty()) return null
        val ascii = runCatching { IDN.toASCII(h, IDN.ALLOW_UNASSIGNED) }.getOrNull()?.lowercase() ?: return null
        return ascii.takeIf { it.length <= 253 && DOMAIN.matches(it) }
    }
}
