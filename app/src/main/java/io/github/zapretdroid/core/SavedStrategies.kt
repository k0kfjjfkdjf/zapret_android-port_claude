package io.github.zapretdroid.core

import android.content.Context
import java.io.File

data class SavedStrategy(val name: String, val args: String, val pinned: Boolean = false, val time: Long = 0)

/**
 * History of custom strategies, as ByeByeDPI's command history: every strategy applied from
 * the tester, the deep check or the editor is remembered; pinned ones are offered on the home
 * screen. Stored as text, one strategy per line: pinned, time, name, arguments (tab-separated).
 */
object SavedStrategies {
    private const val MAX_UNPINNED = 30

    fun file(context: Context) = File(context.filesDir, "strategies.tsv")

    fun all(context: Context): List<SavedStrategy> =
        file(context).takeIf { it.exists() }?.readText()?.let(::parse).orEmpty()

    /** Adds [args] (or moves it to the top), keeping its pin and name; drops the oldest unpinned. */
    fun remember(context: Context, args: String, name: String? = null, now: Long = System.currentTimeMillis()) {
        if (args.isBlank()) return
        save(context, add(all(context), args.trim(), name, now))
    }

    fun update(context: Context, args: String, change: (SavedStrategy) -> SavedStrategy?) {
        save(context, all(context).mapNotNull { if (it.args == args) change(it) else it })
    }

    fun save(context: Context, list: List<SavedStrategy>) = file(context).writeText(format(list))

    fun add(list: List<SavedStrategy>, args: String, name: String?, now: Long): List<SavedStrategy> {
        val old = list.firstOrNull { it.args == args }
        val entry = SavedStrategy(name ?: old?.name ?: "", args, old?.pinned ?: false, now)
        val rest = list.filter { it.args != args }
        val unpinned = rest.filter { !it.pinned }.sortedByDescending { it.time }.take(MAX_UNPINNED - 1)
        return (listOf(entry) + rest.filter { it.pinned } + unpinned).sortedByDescending { it.time }
    }

    fun parse(text: String): List<SavedStrategy> = text.lineSequence().mapNotNull { line ->
        val p = line.split('\t')
        if (p.size < 4 || p[3].isBlank()) null
        else SavedStrategy(p[2], p[3], p[0] == "1", p[1].toLongOrNull() ?: 0)
    }.toList()

    fun format(list: List<SavedStrategy>): String = list.joinToString("") {
        "${if (it.pinned) 1 else 0}\t${it.time}\t${clean(it.name)}\t${clean(it.args)}\n"
    }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').trim()
}
