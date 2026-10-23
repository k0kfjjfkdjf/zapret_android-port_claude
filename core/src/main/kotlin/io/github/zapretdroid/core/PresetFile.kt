package io.github.zapretdroid.core

/**
 * The presets file (assets/strategies/presets.txt, updated from the repository): `[id] Name |
 * Description`, then the preset's command line. Read by the Android app (Strategies) and the
 * desktop app.
 */
object PresetFile {
    /** Reserved for the user's own strategy. */
    const val CUSTOM = "custom"

    /** One preset of the presets file: `[id] Name | Description`, then its command line. */
    data class Entry(val id: String, val name: String?, val about: String?, val args: String)

    private val HEADER = Regex("""^\[([a-z0-9_]+)]\s*(.*)$""")

    /**
     * Parses the presets file. Lines before the first `[id]` are comments; a preset's command line
     * may span several lines with `#` comment lines between them, and is joined into one line. Throws on a header
     * that is not `[id]` or a preset without a command line.
     */
    fun parse(text: String): List<Entry> {
        val entries = ArrayList<Entry>()
        var head: Triple<String, String?, String?>? = null
        val body = StringBuilder()
        fun flush() {
            val h = head ?: return
            val args = body.toString().trim().replace(Regex("""\s+"""), " ")
            if (splitArgs(args).isEmpty()) throw IllegalArgumentException("preset [${h.first}] has no command line")
            entries += Entry(h.first, h.second, h.third, args)
            body.clear()
        }
        text.lineSequence().forEachIndexed { i, raw ->
            val line = raw.trimEnd()
            if (line.trimStart().startsWith("[")) {
                val m = HEADER.matchEntire(line.trim()) ?: throw IllegalArgumentException("line ${i + 1}: expected [id] Name | Description")
                flush()
                val (name, about) = m.groupValues[2].split('|', limit = 2).map { it.trim().ifEmpty { null } }.let { it.getOrNull(0) to it.getOrNull(1) }
                head = Triple(m.groupValues[1], name, about)
            } else if (head != null && !line.trimStart().startsWith("#")) {
                body.append(line).append(' ')
            }
        }
        flush()
        val dup = entries.groupBy { it.id }.filterValues { it.size > 1 }.keys
        if (dup.isNotEmpty()) throw IllegalArgumentException("preset [${dup.first()}] appears twice")
        if (entries.any { it.id == CUSTOM }) throw IllegalArgumentException("[$CUSTOM] is reserved")
        return entries
    }

}
