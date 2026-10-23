package io.github.zapretdroid.core

import java.io.File

/**
 * The engine's command line for a strategy written as in the app (several lines, # comments,
 * directives, {fake:}/{list:}/{proxy:}/{ipset:} references) on a machine where the engine runs as
 * a program: the desktop app. Groups for Android apps (--apps) are left out; what the desktop does
 * not carry out yet (@try search, @on reactions) is reported in [Built.notes].
 */
object EngineCommand {
    data class Built(
        /** The engine's arguments; empty when [errors] is not. */
        val args: List<String>,
        val script: Script.Result,
        val errors: List<String>,
        val notes: List<String>,
    )

    fun build(text: String, env: Script.Env, fakeDir: File, listDir: File): Built {
        val script = Script.compile(text, env)
        if (script.errors.isNotEmpty()) {
            return Built(emptyList(), script, script.errors.map { e -> (if (e.line > 0) "строка ${e.line}: " else "") + e.message }, emptyList())
        }
        val notes = mutableListOf<String>()
        val (args, dropped) = withoutApps(splitArgs(script.text))
        if (dropped > 0) notes += "групп с --apps: $dropped — они только для Android, пропущены"
        if (script.tries.any { it.kind == Script.TryKind.TRY }) {
            notes += "@try: подбор вариантов пока только в Android-приложении; сайты идут по остальной стратегии"
        }
        if (script.rules.isNotEmpty()) notes += "@on: реакции на события пока только в Android-приложении"
        if (args.isEmpty()) return Built(emptyList(), script, listOf("стратегия пустая"), notes)
        val resolved = try {
            ArgTemplates.resolve(args, fakeDir, listDir)
        } catch (e: ArgTemplates.UnknownReference) {
            return Built(emptyList(), script, listOf("нет ${e.kind} «${e.name}»"), notes)
        }
        val listen = resolved.firstOrNull { it in setOf("-i", "--ip", "-p", "--port") }
        if (listen != null) return Built(emptyList(), script, listOf("$listen задаёт само приложение (порт в настройках)"), notes)
        return Built(resolved, script, emptyList(), notes)
    }

    /**
     * [args] without the groups that name Android apps, and how many were dropped. A group starts
     * with its --auto (the engine's reading); when the first group goes, the next one's --auto is
     * dropped too, or an empty first group without desync would catch every connection.
     */
    fun withoutApps(args: List<String>): Pair<List<String>, Int> {
        val groups = StrategyArgs.groups(args)
        val kept = groups.filterNot { g -> g.any { it == "--apps" || it.startsWith("--apps=") } }
        val dropped = groups.size - kept.size
        if (dropped == 0) return args to 0
        if (kept.isEmpty()) return emptyList<String>() to dropped
        val first = kept.first()
        val head = when {
            groups.first() in kept || first.isEmpty() || !StrategyArgs.isGroupStart(first[0]) -> 0
            first[0] == "-A" || first[0] == "--auto" -> 2
            else -> 1
        }
        return (first.drop(head) + kept.drop(1).flatten()) to dropped
    }
}
