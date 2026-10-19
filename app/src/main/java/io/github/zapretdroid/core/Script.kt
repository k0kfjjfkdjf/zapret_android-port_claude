package io.github.zapretdroid.core

/**
 * The strategy's command line as a script (docs/scripting.md, stage 1): lines starting with "@"
 * are directives the app runs, everything else is the engine's command line as before (with
 * "#" comments and {…} references). The result is again plain command-line text.
 *
 *   @set name = value        a variable, used as $name or ${name} on the lines after it
 *   @include name            a strategy from "My strategies" (by name), compiled in place
 *   @if wifi | mobile | not … / @else / @end
 *   @block Name … @end       a named block the UI can switch off
 *
 * Pure: the network type, switched-off blocks and saved strategies come in through [Env].
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

    data class Result(val text: String, val errors: List<Issue>, val blocks: List<String>)

    /** Directives of later stages: reported as not supported yet instead of passed to the engine. */
    private val LATER = setOf("sites", "for", "try", "or", "fallback", "restart", "restart-delay", "restart-idle",
        "restart-limit", "restart-keep", "on", "scenario")

    private val DIRECTIVE = Regex("""^\s*@([A-Za-z][A-Za-z-]*)\s*(.*)$""")
    private val SET = Regex("""^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$""")
    private val VARIABLE = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)\}|\$([A-Za-z_][A-Za-z0-9_]*)""")
    private const val MAX_DEPTH = 5

    /** Whether [text] has any directive (a line starting with "@"). */
    fun hasDirectives(text: String) = text.lineSequence().any { DIRECTIVE.matches(it) }

    /** Whether the result depends on the network type. */
    fun dependsOnNetwork(text: String) = text.lineSequence().any { DIRECTIVE.find(it)?.groupValues?.get(1) == "if" }

    fun compile(text: String, env: Env): Result {
        // Without directives the text is the plain command line it always was: left untouched.
        if (!hasDirectives(text)) return Result(text, emptyList(), emptyList())
        val errors = mutableListOf<Issue>()
        val blocks = mutableListOf<String>()
        val vars = mutableMapOf<String, String>()
        val out = compile(text, env, vars, errors, blocks, emptyList(), null)
        return Result(out, errors, blocks)
    }

    private class Frame(val kind: String, val line: Int, val parentActive: Boolean, var active: Boolean, var seenElse: Boolean = false)

    private fun compile(
        text: String,
        env: Env,
        vars: MutableMap<String, String>,
        errors: MutableList<Issue>,
        blocks: MutableList<String>,
        includes: List<String>,
        from: String?,
    ): String {
        val out = StringBuilder()
        val stack = ArrayDeque<Frame>()
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
            if (d == null) {
                if (active()) out.append(substitute(raw, line)).append('\n')
                return@forEachIndexed
            }
            val name = d.groupValues[1].lowercase()
            // A comment after a directive ends it, as elsewhere in the command line.
            val arg = d.groupValues[2].substringBefore(" #").trim()
            when (name) {
                "if" -> {
                    val cond = condition(arg, env)
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
                "end" -> if (stack.removeLastOrNull() == null) error(line, "@end without @if or @block")
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
                        else -> {
                            val inner = compile(body, env, vars, errors, blocks, includes + target, from ?: target)
                            out.append(inner)
                        }
                    }
                }
                in LATER -> error(line, "@$name is not supported yet")
                else -> error(line, "unknown directive @$name")
            }
        }
        stack.forEach { error(it.line, "@${it.kind} without @end") }
        return out.toString()
    }

    /** wifi, mobile, not <cond>; null if not understood. */
    private fun condition(arg: String, env: Env): Boolean? {
        val words = arg.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return when {
            words.size == 2 && (words[0] == "not" || words[0] == "!") -> condition(words[1], env)?.not()
            words.size == 1 && words[0].startsWith("!") -> condition(words[0].drop(1), env)?.not()
            words == listOf("wifi") -> env.network == "wifi"
            words == listOf("mobile") -> env.network == "mobile"
            else -> null
        }
    }
}
