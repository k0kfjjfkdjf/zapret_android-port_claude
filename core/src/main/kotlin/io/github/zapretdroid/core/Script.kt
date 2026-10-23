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
 * Stage 3:
 *   @on <reasons> [masks] [tcp|udp] [xN/T] -> <action>
 *       reasons: block, freeze, noreply, connfail, udp-noreply, proxy-down, any (comma-separated)
 *       masks:   domains, "*.example.com" for subdomains (comma-separated; none: every domain)
 *       xN/T:    only after N events within T (s, m, h), e.g. x3/5m
 *       action:  use <args> | proxy <name> | try { <args> | <args> … } | autofix | notify | ignore
 *   The rules are checked in order against the engine's connection log while the VPN runs; the
 *   first one that matches acts. use/proxy/try/autofix move the domain to a slot without a restart.
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
        /** The candidates of "-> autofix": strategies, then proxies. */
        val autofix: List<String> = emptyList(),
    )

    /** What an @on rule does; slot actions name their slots. */
    sealed class Action {
        data class Use(val slot: String, val args: String) : Action()
        data class Try(val slots: List<String>, val variants: List<String>) : Action()
        object Notify : Action()
        object Ignore : Action()
    }

    data class OnRule(
        val index: Int,
        val line: Int,
        val reasons: Set<String>,
        val masks: List<String>,
        val proto: String?,
        val count: Int,
        val windowMs: Long,
        val action: Action,
        val text: String,
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
        val rules: List<OnRule> = emptyList(),
    )

    /** Directives of later stages: reported as not supported yet instead of passed to the engine. */
    private val LATER = setOf("scenario")
    val REASONS = setOf("block", "freeze", "noreply", "connfail", "udp-noreply", "proxy-down", "any")
    private val THRESHOLD = Regex("""^x(\d+)/(\d+)([smh])$""")
    private const val MAX_WINDOW_MS = 24 * 3_600_000L
    private val MASK = Regex("""^(\*\.)?[a-z0-9-]+(\.[a-z0-9-]+)*$""")
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
        return Result(out, st.errors, st.blocks, st.tries, st.restart, st.rules)
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
        val rules = mutableListOf<OnRule>()
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
                        val hosts = m?.let { HostNames.normalize(substitute(it.groupValues[2], line)).hosts }.orEmpty()
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
                    "on" -> if (active()) out.append(onRule(substitute(d.groupValues[2].trim(), line), line, ::error))
                    in LATER -> error(line, "@$name is not supported yet")
                    else -> error(line, "unknown directive @$name")
                }
            }
            open?.let { error(it.line, "@${it.kind.name.lowercase()} without @end") }
            stack.forEach { error(it.line, "@${it.kind} without @end") }
            return out.toString()
        }

        /** An @on rule: recorded, and the slot groups of its action as the engine's command line. */
        private fun onRule(text: String, line: Int, error: (Int, String) -> Unit): String {
            val arrow = text.indexOf("->")
            if (arrow < 0) {
                error(line, "@on <reasons> [domains] [tcp|udp] [xN/T] -> <action>")
                return ""
            }
            val words = text.substring(0, arrow).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val action = text.substring(arrow + 2).trim()
            val reasons = words.firstOrNull()?.lowercase()?.split(',')?.filter { it.isNotEmpty() }?.toSet().orEmpty()
            if (reasons.isEmpty() || !REASONS.containsAll(reasons)) {
                error(line, "@on reasons are ${REASONS.joinToString()}: \"${words.firstOrNull().orEmpty()}\"")
                return ""
            }
            var masks = emptyList<String>()
            var proto: String? = null
            var count = 1
            var window = 0L
            for (w in words.drop(1)) {
                val lw = w.lowercase()
                val th = THRESHOLD.find(lw)
                when {
                    lw == "tcp" || lw == "udp" -> proto = lw
                    th != null -> {
                        // Digits only, but any number of them: "x99999999999/1m" is a script error,
                        // not an exception out of compile() (the editor compiles while typing).
                        val n = th.groupValues[1].toIntOrNull()
                        val unit = when (th.groupValues[3]) { "s" -> 1000L; "m" -> 60_000L; else -> 3_600_000L }
                        val ms = th.groupValues[2].toLongOrNull()?.takeIf { it <= MAX_WINDOW_MS / unit }?.times(unit)
                        if (n == null || ms == null) {
                            error(line, "@on: xN/T with N up to ${Int.MAX_VALUE} and T up to 24h: \"$w\"")
                            return ""
                        }
                        count = n.coerceAtLeast(1)
                        window = ms
                    }
                    else -> {
                        val list = lw.split(',').filter { it.isNotEmpty() }
                        val bad = list.firstOrNull { !MASK.matches(it) }
                        if (bad != null) {
                            error(line, "@on: not a domain or *.domain: \"$bad\"")
                            return ""
                        }
                        masks = masks + list
                    }
                }
            }
            val index = rules.size + 1
            val base = "on$index"
            val out = StringBuilder()
            fun slotGroups(variants: List<String>, slots: List<String>) = variants.zip(slots).forEach { (v, s) ->
                out.append(quote(StrategyArgs.withSlot(splitArgs(v), s) + "--auto=none")).append('\n')
            }
            val parsed: Action = when {
                action == "notify" -> Action.Notify
                action == "ignore" -> Action.Ignore
                action.startsWith("use ") -> {
                    val args = action.removePrefix("use ").trim()
                    if (splitArgs(args).isEmpty()) {
                        error(line, "@on … -> use <args>")
                        return ""
                    }
                    slotGroups(listOf(args), listOf(base))
                    Action.Use(base, args)
                }
                action == "proxy" || action.startsWith("proxy ") -> {
                    val name = action.removePrefix("proxy").trim()
                    val args = "-C " + (if (name.isEmpty()) "{proxy}" else "{proxy:$name}") + if (proto == "udp") " --udp-proxy" else ""
                    slotGroups(listOf(args), listOf(base))
                    Action.Use(base, args)
                }
                action.startsWith("try") -> {
                    val body = action.removePrefix("try").trim()
                    val variants = if (body.startsWith("{") && body.endsWith("}")) {
                        body.substring(1, body.length - 1).split(" | ").map { it.trim() }.filter { splitArgs(it).isNotEmpty() }
                    } else emptyList()
                    if (variants.isEmpty()) {
                        error(line, "@on … -> try { <args> | <args> … }")
                        return ""
                    }
                    val slots = variants.indices.map { "$base.${it + 1}" }
                    slotGroups(variants, slots)
                    Action.Try(slots, variants)
                }
                action == "autofix" -> {
                    val variants = env.autofix
                    if (variants.isEmpty()) {
                        error(line, "@on … -> autofix: no candidates")
                        return ""
                    }
                    val slots = variants.indices.map { "$base.${it + 1}" }
                    slotGroups(variants, slots)
                    Action.Try(slots, variants)
                }
                else -> {
                    error(line, "@on action: use <args>, proxy <name>, try { … | … }, autofix, notify or ignore")
                    return ""
                }
            }
            rules += OnRule(index, line, reasons, masks, proto, count, window, parsed, text)
            return if (out.isEmpty()) "" else "# === on $index ===\n$out"
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
