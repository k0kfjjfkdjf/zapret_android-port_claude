package io.github.zapretdroid.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/**
 * DNS blocklists as sorted 64-bit hashes of the names (8 bytes per domain): no strings in
 * memory, and a false match needs a 64-bit collision. The resolver (SecureDns) asks a [Matcher];
 * the app downloads and stores the lists (DnsFilter).
 */
object DnsBlocklist {
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

    fun write(file: File, hashes: LongArray) = DataOutputStream(file.outputStream().buffered()).use { out ->
        out.writeInt(MAGIC)
        out.writeInt(hashes.size)
        hashes.forEach { out.writeLong(it) }
    }

    fun read(file: File): LongArray = DataInputStream(file.inputStream().buffered(1 shl 16)).use { input ->
        if (input.readInt() != MAGIC) throw IOException("not a filter list")
        LongArray(input.readInt()) { input.readLong() }
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
