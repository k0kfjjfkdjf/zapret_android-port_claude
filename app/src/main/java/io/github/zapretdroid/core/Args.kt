package io.github.zapretdroid.core

/**
 * Splits a command line into arguments like a POSIX shell does for the simple cases:
 * whitespace separates arguments, single and double quotes group, backslash escapes.
 */
fun splitArgs(line: String): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var inArg = false
    var quote: Char? = null
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            quote != null && c == quote -> quote = null
            quote == '\'' -> current.append(c)
            c == '\\' && i + 1 < line.length && (quote == null || line[i + 1] == '"' || line[i + 1] == '\\') -> {
                current.append(line[++i])
                inArg = true
            }
            quote == '"' -> current.append(c)
            // "# …" to the end of the line is a comment (as in shell scripts), when it starts a word.
            c == '#' && !inArg -> {
                while (i + 1 < line.length && line[i + 1] != '\n') i++
            }
            c == '"' || c == '\'' -> {
                quote = c
                inArg = true
            }
            c.isWhitespace() -> if (inArg) {
                result += current.toString()
                current.clear()
                inArg = false
            }
            else -> {
                current.append(c)
                inArg = true
            }
        }
        i++
    }
    if (inArg) result += current.toString()
    return result
}
