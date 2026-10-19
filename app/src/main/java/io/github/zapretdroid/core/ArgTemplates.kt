package io.github.zapretdroid.core

import java.io.File

/**
 * Placeholders that users can put into ByeDPI arguments instead of file paths and numbers:
 * {fake:name} is a zapret2 fake payload (".bin" may be omitted), {list:name} a host list;
 * with [refs] set (the app sets it), also {app:package,…} (Android uids for --apps),
 * {proxy} / {proxy:name} (socks5://address of the main or a named proxy, for -C) and
 * {ipset:telegram} (Telegram's address ranges, for --ipset).
 */
object ArgTemplates {
    // Braces are escaped everywhere: Android's ICU regex engine rejects a bare "}".
    private val PLACEHOLDER = Regex("""\{(fake|list):([^\{\}\s]+)\}""")
    private val REFERENCE = Regex("""\{(app|proxy|ipset)(?::([^\{\}\s]+))?\}""")

    /** What the references stand for on this device; null leaves them as they are. */
    interface Refs {
        fun uid(pkg: String): Int?
        fun proxy(name: String): String?
        fun ipset(name: String): String?
    }

    @Volatile
    var refs: Refs? = null

    /** Whether [args] (already resolved) route by app: the VPN must then pass source addresses. */
    fun usesApps(args: List<String>) = args.any { it == "--apps" || it.startsWith("--apps=") }

    /** Whether [args] send a group to an upstream proxy (-C), whose app must bypass the VPN. */
    fun usesProxy(args: List<String>) = args.any { it == "-C" || it == "--connect-to" || it.startsWith("--connect-to=") }

    class UnknownReference(val kind: String, val name: String) :
        IllegalArgumentException("unknown $kind '$name'")

    /** Replaces placeholders with absolute paths; throws [UnknownReference] for missing files. */
    fun resolve(args: List<String>, fakeDir: File, listDir: File): List<String> = args.map { arg ->
        resolveRefs(PLACEHOLDER.replace(arg) { match ->
            val (kind, name) = match.destructured
            val file = when (kind) {
                "fake" -> File(fakeDir, name).takeIf { it.isFile } ?: File(fakeDir, "$name.bin")
                else -> File(listDir, name).takeIf { it.isFile } ?: File(listDir, "$name.txt")
            }
            if (!file.isFile) throw UnknownReference(kind, name)
            file.absolutePath
        })
    }

    private fun resolveRefs(arg: String): String {
        val r = refs ?: return arg
        return REFERENCE.replace(arg) { match ->
            val kind = match.groupValues[1]
            val name = match.groupValues[2]
            when (kind) {
                "app" -> name.split(',').filter { it.isNotBlank() }
                    .map { pkg -> r.uid(pkg.trim()) ?: throw UnknownReference("app", pkg.trim()) }
                    .joinToString(",")
                "proxy" -> r.proxy(name) ?: throw UnknownReference("proxy", name.ifEmpty { "main" })
                else -> r.ipset(name) ?: throw UnknownReference("ipset", name)
            }
        }
    }

    fun fake(name: String) = "{fake:$name}"
}
