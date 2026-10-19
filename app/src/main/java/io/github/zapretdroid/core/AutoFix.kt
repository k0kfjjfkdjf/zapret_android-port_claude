package io.github.zapretdroid.core

import android.content.Context

/** Sites of host list [list] go with [args] (a strategy, or "-C socks5://…" for a proxy). */
data class FixRule(val list: String, val label: String, val args: String)

/**
 * What the auto-fix found and the person applied: per site, the strategy or proxy that opened
 * it. Each distinct choice is one host list ("autofix_N") and one engine group in front of the
 * general strategy, so the sites keep their fix whatever the main strategy is.
 */
object AutoFix {
    private const val PREFIX = "autofix_"

    /** The rules with at least one site, as the settings keep them. */
    fun rules(context: Context, settings: Settings): List<FixRule> =
        settings.autofixRules.filter { HostLists.count(context, it.list) > 0 }

    fun hosts(context: Context, rule: FixRule): List<String> =
        HostLists.read(context, rule.list).lines().filter { it.isNotBlank() }

    /** Gives [host] the fix [args]: its own rule's list (a new one if no rule has these args). */
    fun apply(context: Context, settings: Settings, host: String, label: String, args: String): FixRule {
        remove(context, settings, host)
        val rules = settings.autofixRules
        val rule = rules.firstOrNull { it.args == args } ?: run {
            val used = rules.map { it.list }.toSet()
            val name = generateSequence(1) { it + 1 }.map { "$PREFIX$it" }.first { it !in used && !HostLists.file(context, it).exists() }
            FixRule(name, label, args).also { settings.autofixRules = rules + it }
        }
        HostLists.add(context, rule.list, listOf(host))
        AppLog.i("auto-fix: $host -> $label ($args)")
        return rule
    }

    /** Takes [host] out of every rule; rules left without sites are dropped. */
    fun remove(context: Context, settings: Settings, host: String) {
        val keep = settings.autofixRules.filter { rule ->
            val left = hosts(context, rule).filter { it != host }
            if (left.isEmpty()) {
                HostLists.delete(context, rule.list)
                false
            } else {
                HostLists.save(context, rule.list, left.joinToString("\n"))
                true
            }
        }
        if (keep != settings.autofixRules) settings.autofixRules = keep
    }

    /**
     * The engine groups: each rule's strategy limited to its list (every group of a strategy
     * with several), closed so that the next groups start afresh. [resolve] turns the stored
     * arguments into the engine's ({fake:…} and the like).
     */
    fun groups(context: Context, settings: Settings, resolve: (String) -> List<String>): List<String> =
        rules(context, settings).flatMap { rule ->
            StrategyArgs.build(resolve(rule.args), HostScope.ONLY_LIST, HostLists.file(context, rule.list).absolutePath, null) + "--auto=none"
        }
}
