package io.github.zapretdroid.core

import android.content.Context
import java.io.File

/**
 * autohostlist as a debugging aid: the engine appends "<time> <host> <reason>" for every
 * connection where it detected a block, even with the strategy applied.
 */
object AutoHost {
    data class Entry(val host: String, val count: Int, val lastSeen: Long, val reasons: Set<String>)

    fun file(context: Context) = File(context.filesDir, "autohost.log")

    fun entries(context: Context): List<Entry> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return parse(f.readLines())
    }

    fun clear(context: Context) {
        file(context).delete()
    }

    fun parse(lines: List<String>): List<Entry> {
        val byHost = LinkedHashMap<String, Entry>()
        lines.forEach { line ->
            val parts = line.trim().split(' ')
            if (parts.size < 3) return@forEach
            val time = parts[0].toLongOrNull() ?: return@forEach
            val host = parts[1].lowercase()
            val reason = parts[2]
            val old = byHost[host]
            byHost[host] = if (old == null) {
                Entry(host, 1, time, setOf(reason))
            } else {
                Entry(host, old.count + 1, maxOf(old.lastSeen, time), old.reasons + reason)
            }
        }
        return byHost.values.sortedWith(compareByDescending<Entry> { it.count }.thenByDescending { it.lastSeen })
    }
}
