package io.github.zapretdroid.core

/** Which connections the strategy applies to. */
enum class HostScope { ALL, ONLY_LIST, EXCEPT_LIST }

/**
 * Turns the strategy into the engine's arguments: host list scope and the autohostlist
 * debugging aid. Pure, so that it can be unit tested; file paths are passed in.
 */
object StrategyArgs {
    fun build(
        strategy: List<String>,
        scope: HostScope,
        listPath: String?,
        autohostLog: String?,
    ): List<String> = buildList {
        when (scope) {
            HostScope.ALL -> addAll(strategy)
            // Every group without its own host list gets the list (the engine checks hosts per
            // group: a list in front of the first one only would leave later groups for all
            // hosts); hosts that match no group pass untouched.
            HostScope.ONLY_LIST -> {
                val path = requireNotNull(listPath)
                groups(strategy).filter { it.isNotEmpty() }.forEach { group ->
                    if (isGroupStart(group[0])) {
                        val head = if (group[0] == "-A" || group[0] == "--auto") 2 else 1
                        addAll(group.take(head))
                        if (!hasHosts(group)) { add("--hosts"); add(path) }
                        addAll(group.drop(head))
                    } else {
                        if (!hasHosts(group)) { add("--hosts"); add(path) }
                        addAll(group)
                    }
                }
            }
            // A first group without desync catches the listed hosts.
            HostScope.EXCEPT_LIST -> {
                add("--hosts"); add(requireNotNull(listPath)); add("--auto=none")
                addAll(strategy)
            }
        }
        if (autohostLog != null) {
            add("--autohost-log"); add(autohostLog)
            // Blocks are detected by --auto groups only: add a last, empty one. Timeouts need -T.
            if (strategy.none { it == "-T" || it.startsWith("--timeout") || (it.startsWith("-T") && it.length > 2) }) {
                add("--timeout"); add("4")
            }
            // The detection group is also where a blocked connection goes next: it repeats the
            // first group's desync, so that turning the aid on does not turn bypass off.
            add("--auto=torst,redirect,ssl_err")
            addAll(withoutHosts(groups(strategy).firstOrNull().orEmpty()))
        }
    }

    /**
     * A strategy for some apps only: every group gets "--apps <uids>" (the engine matches the app
     * per connection), and a closing "--auto=none" so that the groups after it start afresh.
     */
    fun forApps(strategy: List<String>, uids: List<Int>): List<String> = buildList {
        val apps = listOf("--apps", uids.joinToString(","))
        groups(strategy).filter { it.isNotEmpty() }.forEach { group ->
            if (isGroupStart(group[0])) {
                val head = if (group[0] == "-A" || group[0] == "--auto") 2 else 1
                addAll(group.take(head))
                addAll(apps)
                addAll(group.drop(head))
            } else {
                addAll(apps)
                addAll(group)
            }
        }
        add("--auto=none")
    }

    /**
     * The strategy as the engine's --slot [slot] groups: every group gets "--slot <name>" (the
     * engine fills their host lists while it runs, see ByeDpi.assignSlot).
     */
    fun withSlot(strategy: List<String>, slot: String): List<String> = buildList {
        val tag = listOf("--slot", slot)
        groups(strategy).filter { it.isNotEmpty() }.forEach { group ->
            if (isGroupStart(group[0])) {
                val head = if (group[0] == "-A" || group[0] == "--auto") 2 else 1
                addAll(group.take(head))
                addAll(tag)
                addAll(group.drop(head))
            } else {
                addAll(tag)
                addAll(group)
            }
        }
    }

    /** Whether [arg] starts a new group (-A/--auto, with its value attached or next). */
    fun isGroupStart(arg: String) = arg == "-A" || arg == "--auto" || arg.startsWith("--auto=") || (arg.startsWith("-A") && arg.length > 2)

    /** The strategy split into groups; every group but the first starts with its -A/--auto. */
    fun groups(args: List<String>): List<List<String>> {
        val out = mutableListOf(mutableListOf<String>())
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (isGroupStart(a)) out += mutableListOf<String>()
            out.last() += a
            if ((a == "-A" || a == "--auto") && i + 1 < args.size) out.last() += args[++i]
            i++
        }
        return out.filterIndexed { index, group -> index == 0 || group.isNotEmpty() }
    }

    private fun hasHosts(group: List<String>) = group.any { it == "-H" || it == "--hosts" || it.startsWith("--hosts=") || (it.startsWith("-H") && it.length > 2) }

    /** The group's desync arguments without its group start, host options and the global -T. */
    private fun withoutHosts(group: List<String>): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < group.size) {
            val a = group[i]
            when {
                a == "-H" || a == "--hosts" || a == "-A" || a == "--auto" || a == "-T" || a == "--timeout" -> i++
                a.startsWith("--hosts=") || (a.startsWith("-H") && a.length > 2) || isGroupStart(a) ||
                    a.startsWith("--timeout=") || (a.startsWith("-T") && a.length > 2) -> Unit
                else -> out += a
            }
            i++
        }
        return out
    }
}
