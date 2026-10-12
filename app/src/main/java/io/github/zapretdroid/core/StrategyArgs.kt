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
            // The list limits the first group; hosts that match no group pass untouched.
            HostScope.ONLY_LIST -> {
                add("--hosts"); add(requireNotNull(listPath))
                addAll(strategy)
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
            add("--auto=torst,redirect,ssl_err")
        }
    }
}
