package io.github.zapretdroid.core

/**
 * The strategy's command line as a script (docs/scripting.md): lines starting with "@" are
 * directives the app runs, everything else is the engine's command line as before (with "#"
 * comments and {…} references). The result is again plain command-line text.
 *
 * Stage 1:
 *   @set name = value        a variable, used as $name or ${name} on the lines after it
 *   @include name            a strategy from "My strategies" (by name), compiled in place
 *   @if wifi | mobile | not … / @else / @end
 *   @block Name … @end       a named block the UI can switch off
 *
 * Stage 2:
 *   @sites name = a.com b.com     a set of sites
 *   @for name                     the sites of the next @try or @fallback
 *   @try … @or … @end             variants tried by the app; each one becomes a --slot group and
 *                                 the sites are moved to the winner's slot while the engine runs
 *   @fallback … @or … @end        variants as the engine's --auto groups (no testing beforehand)
 *   @restart …                    accepted; with slots a variant changes without a restart
 *
 * Pure: the network type, switched-off blocks, saved strategies and the winners of earlier
 * tries come in through [Env].
 */
object Script {
    data class Env(
        /** "wifi", "mobile" or "other". */
        val network: String,
        val disabledBlocks: Set<String> = emptySet(),
        /** The text of a saved strategy by name, or null. */
        val include: (String) -> String? = { null },
    )

    data class Issue(val line: Int, val message: String)

    enum class TryKind { TRY, FALLBACK }

    /**
     * A @try or @fallback: its [sites] go to slot [slots] (one per variant for TRY, the one
     * shared slot for FALLBACK); [variants] are the variants' command lines as written.
     */
    data class TryBlock(val name: String, val kind: TryKind, val sites: List<String>, val variants: List<String>, val slots: List<String>)

    data class Result(
        val text: String,
        val errors: List<Issue>,
        val blocks: List<String>,
        val tries: List<TryBlock> = emptyList(),
        val restart: Map<String, String> = emptyMap(),
    )

    /** Directives of later stages: reported as not supported yet instead of passed to the engine. */
    private val LATER = setOf("on", "scenario")
    private val RESTART = setOf("restart", "restart-delay", "restart-idle", "restart-limit", "restart-keep")

    private val DIRECTIVE = Regex("""^\s*@([A-Za-z][A-Za-z-]*)\s*(.*)$""")
    private val SET = Regex("""^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$""")
    private val VARIABLE = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)\}|\$([A-Za-z_][A-Za-z0-9_]*)""")
    private const val MAX_DEPTH = 5

    /** Groups after the first of a @fallback: the engine moves on when it sees one of these. */
    const val FALLBACK_DETECT = "--auto=torst,redirect,ssl_err"

    /** Whether [text] has any directive (a line starting with "@"). */
    fun hasDirectives(text: String) = text.lineSequence().any { DIRECTIVE.matches(it) }

    /** Whether the result depends on the network type. */
    fun dependsOnNetwork(text: String) = text.lineSequence().any { DIRECTIVE.find(it)?.groupValues?.get(1) == "if" }

    fun compile(text: String, env: Env): Result {
        // Without directives the text is the plain command line it always was: left untouched.
        if (!hasDirectives(text)) return Result(text, emptyList(), emptyList())
        val st = State(env)
        val out = st.compile(text, emptyList(), null)
        st.pendingFor?.let { (line, name) -> st.errors += Issue(line, "@for $name without @try or @fallback after it") }
        return Result(out, st.errors, st.blocks, st.tries, st.restart)
    }

    private class Frame(val kind: String, val line: Int, val parentActive: Boolean, var active: Boolean, var seenElse: Boolean = false)

    private class TryBuilder(val kind: TryKind, val line: Int, val name: String, val sites: List<String>, val active: Boolean) {
        val variants = mutableListOf(StringBuilder())
    }

    private class State(val env: Env) {
        val errors = mutableListOf<Issue>()
        val blocks = mutableListOf<String>()
        val vars = mutableMapOf<String, String>()
        val sites = mutableMapOf<String, List<String>>()
        val tries = mutableListOf<TryBlock>()
        val restart = mutableMapOf<String, String>()
        var pendingFor: Pair<Int, String>? = null

        fun compile(text: String, includes: List<String>, from: String?): String {
            val out = StringBuilder()
            val stack = ArrayDeque<Frame>()
            var open: TryBuilder? = null
            fun active() = stack.lastOrNull()?.active ?: true
            fun error(line: Int, message: String) {
                errors += Issue(line, if (from == null) message else "$message (in @include $from)")
            }
            fun substitute(s: String, line: Int): String = VARIABLE.replace(s) { m ->
                val name = m.groupValues[1].ifEmpty { m.groupValues[2] }
                vars[name] ?: run {
                    error(line, "unknown variable \$$name")
                    m.value
                }
            }

            text.lines().forEachIndexed { index, raw ->
                val line = if (from == null) index + 1 else 0
                val d = DIRECTIVE.find(raw)
                val t = open
                if (t != null) {
                    // Inside @try/@fallback: lines go to the current variant; only @or and @end.
                    when (d?.groupValues?.get(1)?.lowercase()) {
                        null -> if (t.active) t.variants.last().append(substitute(raw, line)).append('\n')
                        "or" -> t.variants += StringBuilder()
                        "end" -> {
                            open = null
                            if (t.active) out.append(finish(t, ::error))
                        }
                        else -> error(line, "only @or and @end inside @${t.kind.name.lowercase()}")
                    }
                    return@forEachIndexed
                }
                if (d == null) {
                    if (active()) out.append(substitute(raw, line)).append('\n')
                    return@forEachIndexed
                }
                val name = d.groupValues[1].lowercase()
                // A comment after a directive ends it, as elsewhere in the command line.
                val arg = d.groupValues[2].substringBefore(" #").trim()
                when (name) {
                    "if" -> {
                        val cond = condition(arg)
                        if (cond == null) error(line, "@if takes wifi, mobile or not …: \"$arg\"")
                        val parent = active()
                        stack.addLast(Frame("if", line, parent, parent && cond == true))
                    }
                    "else" -> {
                        val f = stack.lastOrNull()
                        if (f == null || f.kind != "if" || f.seenElse) {
                            error(line, "@else without @if")
                        } else {
                            f.seenElse = true
                            f.active = f.parentActive && !f.active
                        }
                    }
                    "block" -> {
                        if (arg.isEmpty()) error(line, "@block needs a name")
                        if (from == null && arg.isNotEmpty() && arg !in blocks) blocks += arg
                        val parent = active()
                        stack.addLast(Frame("block", line, parent, parent && arg !in env.disabledBlocks))
                    }
                    "end" -> if (stack.removeLastOrNull() == null) error(line, "@end without @if, @block or @try")
                    "set" -> if (active()) {
                        val m = SET.find(arg)
                        if (m == null) error(line, "@set name = value")
                        else vars[m.groupValues[1]] = substitute(m.groupValues[2].trim().removeSurrounding("\""), line)
                    }
                    "include" -> if (active()) {
                        val target = arg.removeSurrounding("\"").trim()
                        val body = env.include(target)
                        when {
                            target.isEmpty() -> error(line, "@include needs a strategy name")
                            target in includes -> error(line, "@include \"$target\" includes itself")
                            includes.size >= MAX_DEPTH -> error(line, "@include nested deeper than $MAX_DEPTH")
                            body == null -> error(line, "no saved strategy \"$target\"")
                            else -> out.append(compile(body, includes + target, from ?: target))
                        }
                    }
                    "sites" -> if (active()) {
                        val m = SET.find(arg)
                        val hosts = m?.let { HostLists.normalize(substitute(it.groupValues[2], line)).hosts }.orEmpty()
                        when {
                            m == null -> error(line, "@sites name = site site …")
                            hosts.isEmpty() -> error(line, "@sites ${m.groupValues[1]}: no sites")
                            else -> sites[m.groupValues[1]] = hosts
                        }
                    }
                    "for" -> if (active()) {
                        if (arg !in sites) error(line, "no @sites $arg before @for")
                        pendingFor = line to arg
                    }
                    "try", "fallback" -> {
                        val kind = if (name == "try") TryKind.TRY else TryKind.FALLBACK
                        val target = pendingFor?.second
                        pendingFor = null
                        val hosts = target?.let { sites[it] }
                        if (active() && target == null) error(line, "@$name needs @for <sites> before it")
                        val unique = target?.let { base ->
                            generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base#$it" }.first { n -> tries.none { it.name == n } }
                        } ?: "try"
                        open = TryBuilder(kind, line, unique, hosts.orEmpty(), active() && hosts != null)
                    }
                    "or" -> error(line, "@or without @try or @fallback")
                    in RESTART -> if (active()) restart[name] = arg
                    in LATER -> error(line, "@$name is not supported yet")
                    else -> error(line, "unknown directive @$name")
                }
            }
            open?.let { error(it.line, "@${it.kind.name.lowercase()} without @end") }
            stack.forEach { error(it.line, "@${it.kind} without @end") }
            return out.toString()
        }

        /** The engine's groups of a finished @try/@fallback, and the block recorded. */
        private fun finish(t: TryBuilder, error: (Int, String) -> Unit): String {
            val variants = t.variants.map { it.toString().trim() }.filter { splitArgs(it).isNotEmpty() }
            if (variants.isEmpty()) {
                error(t.line, "@${t.kind.name.lowercase()} without variants")
                return ""
            }
            val out = StringBuilder("# === ${t.kind.name.lowercase()} ${t.name} ===\n")
            val slots = when (t.kind) {
                TryKind.TRY -> variants.mapIndexed { i, v ->
                    val slot = "${t.name}.${i + 1}"
                    out.append(quote(StrategyArgs.withSlot(splitArgs(v), slot) + "--auto=none")).append('\n')
                    slot
                }
                TryKind.FALLBACK -> {
                    val slot = "${t.name}.fb"
                    variants.forEachIndexed { i, v ->
                        val tokens = splitArgs(v)
                        if (StrategyArgs.groups(tokens).size > 1) error(t.line, "@fallback variant ${i + 1} has groups of its own (--auto)")
                        val head = if (i == 0) emptyList() else listOf(FALLBACK_DETECT)
                        out.append(quote(head + listOf("--slot", slot) + tokens)).append('\n')
                    }
                    out.append("--auto=none\n")
                    listOf(slot)
                }
            }
            tries += TryBlock(t.name, t.kind, t.sites, variants, slots)
            return out.toString()
        }

        private fun condition(arg: String): Boolean? {
            val words = arg.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
            return when {
                words.size == 2 && (words[0] == "not" || words[0] == "!") -> condition(words[1])?.not()
                words.size == 1 && words[0].startsWith("!") -> condition(words[0].drop(1))?.not()
                words == listOf("wifi") -> env.network == "wifi"
                words == listOf("mobile") -> env.network == "mobile"
                else -> null
            }
        }
    }

    /** Tokens back to one command line, quoted where splitArgs would split or comment them. */
    fun quote(tokens: List<String>): String = tokens.joinToString(" ") { t ->
        if (t.isEmpty() || t.any { it.isWhitespace() || it == '\'' || it == '"' || it == '\\' } || t.startsWith("#")) {
            "'" + t.replace("'", "'\"'\"'") + "'"
        } else t
    }
}
