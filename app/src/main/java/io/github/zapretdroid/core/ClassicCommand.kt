package io.github.zapretdroid.core

import io.github.zapretdroid.classic.utility.shellSplit

/**
 * The command line of the ByeByeDPI interface and ByeZapret's strategy text are one setting seen
 * from two sides. ByeZapret's text may be a script (several lines, "# …" comments, @directives)
 * with references ({list:…}, {proxy:…}, {app:…}); the classic side used to read it as one
 * shell line and write it back flattened, which turned the rest of the text after the first
 * comment into a comment and broke host lists. These helpers keep the text as it is and only
 * change what has to change.
 */
object ClassicCommand {
    private val LIST = Regex("""\{list:([^\}]+)\}""")
    private val LISTEN = setOf("-i", "--ip", "-p", "--port")

    /**
     * ByeByeDPI's {list:name}: the domains of its list, space-separated. Only inside an inline
     * host list (":{list:x}", ByeByeDPI's use) or when ByeZapret has no host list of that name;
     * otherwise it is ByeZapret's {list:name} (a file for --hosts): ByeByeDPI's built-in lists are
     * called Telegram, Discord, Youtube…, and "--hosts {list:telegram}" became "--hosts t.me
     * telegram.org …", a file named t.me the engine could not open.
     */
    fun expandLists(cmd: String, classicDomains: (String) -> List<String>?, zapretListExists: (String) -> Boolean): String =
        LIST.replace(cmd) { m ->
            val name = m.groupValues[1].trim()
            val inline = m.range.first > 0 && cmd[m.range.first - 1] == ':'
            val domains = classicDomains(name)
            if (domains.isNullOrEmpty() || (!inline && zapretListExists(name))) m.value else domains.joinToString(" ")
        }

    /** Where a "# …" comment starts in [line] (a '#' that begins a word, outside quotes), or -1. */
    fun commentStart(line: String): Int {
        var quote: Char? = null
        for (i in line.indices) {
            val c = line[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == '#' && (i == 0 || line[i - 1].isWhitespace()) -> return i
            }
        }
        return -1
    }

    /** [text] without comments and without script directive lines: what a plain command line has. */
    fun stripComments(text: String): String = text.replace("\r", "").lines().joinToString("\n") { line ->
        if (line.trimStart().startsWith("@")) "" else commentStart(line).let { if (it < 0) line else line.substring(0, it) }
    }

    /**
     * The classic command line as ByeZapret's strategy: line breaks, comments and @directives
     * kept, the program name in front ("ciadpi …", as ByeByeDPI allows) and --ip/--port (the VPN
     * sets its own) removed. A line that needs no change is kept exactly as it was.
     */
    fun toZapret(text: String): String {
        var first = true
        return text.replace("\r", "").lines().joinToString("\n") { raw ->
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("@")) return@joinToString raw
            val cut = commentStart(raw)
            val code = if (cut < 0) raw else raw.substring(0, cut)
            val comment = if (cut < 0) "" else raw.substring(cut)
            var tokens = shellSplit(code)
            if (tokens.isEmpty()) return@joinToString raw
            var changed = false
            if (first) {
                first = false
                val dropped = tokens.dropWhile { !it.startsWith("-") }
                if (dropped.size != tokens.size) { tokens = dropped; changed = true }
            }
            val kept = stripListen(tokens)
            if (kept.size != tokens.size) changed = true
            if (!changed) raw
            else (kept.joinToString(" ") { quote(it) } + if (comment.isNotEmpty()) " $comment" else "").trim()
        }.trim()
    }

    /** Removes --ip/--port (-i/-p, with their values) from engine arguments. */
    fun stripListen(args: List<String>): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a in LISTEN -> i++
                a.startsWith("--ip=") || a.startsWith("--port=") -> Unit
                a.length > 2 && (a.startsWith("-i") || a.startsWith("-p")) && !a.startsWith("--") -> Unit
                else -> out += a
            }
            i++
        }
        return out
    }

    /** Quoted for splitArgs: whitespace, quotes and a leading '#' (a comment otherwise) need quotes. */
    fun quote(t: String) =
        if (t.isNotEmpty() && t.none { it.isWhitespace() || it == '"' || it == '\'' || it == '\\' } && !t.startsWith("#")) t
        else "\"" + t.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
