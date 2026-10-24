package io.github.zapretdroid.core

/**
 * Explains an engine command line group by group for the journal, so that "group N" in the
 * engine's connection lines can be read without knowing the --auto syntax.
 */
object StrategyGroups {
    private val GLOBAL_WITH_VALUE = setOf(
        "-i", "--ip", "-p", "--port", "-x", "--debug", "-T", "--timeout", "--autohost-log",
        "-c", "--max-conn", "-b", "--buf-size", "-g", "--def-ttl", "-I", "--conn-ip",
        "-L", "--auto-mode", "-u", "--cache-ttl", "-y", "--cache-file", "--auto-cache",
    )
    private val GLOBAL_FLAGS = setOf("--conn-log", "-N", "--no-domain", "-U", "--no-udp", "-F", "--tfo")

    fun describe(args: List<String>): List<String> {
        val groups = mutableListOf(Group(null))
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            val auto = when {
                arg.startsWith("--auto=") -> arg.removePrefix("--auto=")
                arg == "--auto" || arg == "-A" -> args.getOrNull(++i).orEmpty()
                arg.startsWith("-A") -> arg.removePrefix("-A")
                else -> null
            }
            when {
                auto != null -> groups += Group(auto)
                arg in GLOBAL_FLAGS -> Unit
                arg in GLOBAL_WITH_VALUE -> i++
                GLOBAL_WITH_VALUE.any { it.startsWith("--") && arg.startsWith("$it=") } -> Unit
                else -> groups.last().args += arg
            }
            i++
        }
        return groups.mapIndexed { index, group ->
            val trigger = when (group.trigger) {
                null -> ""
                "n", "none" -> " (if the previous group does not apply)"
                else -> " (after a detected block: ${group.trigger})"
            }
            "strategy group $index$trigger: ${group.args.joinToString(" ").ifEmpty { "no desync" }}"
        } + "connections that match no group pass without desync"
    }

    private class Group(val trigger: String?) {
        val args = mutableListOf<String>()
    }
}
