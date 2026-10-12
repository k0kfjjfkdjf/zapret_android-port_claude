package io.github.zapretdroid.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android compiles regular expressions with ICU, which rejects a literal "}" that is not
 * escaped, while the JVM used for unit tests accepts it. This scans the sources so that such a
 * pattern fails here instead of crashing on a device.
 */
class RegexSourceTest {
    private val literal = Regex("Regex\\(\\s*(\"\"\"(.*?)\"\"\"|\"((?:\\\\.|[^\"\\\\])*)\")")

    @Test
    fun noBareClosingBraces() {
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue("sources not found", sources.isNotEmpty())
        val offenders = sources.flatMap { file ->
            literal.findAll(file.readText()).mapNotNull { match ->
                val raw = match.groupValues[2].ifEmpty { match.groupValues[3].replace("\\\\", "\\") }
                if (hasBareClosingBrace(raw)) "${file.name}: $raw" else null
            }.toList()
        }
        assertTrue("unescaped '}' in: $offenders", offenders.isEmpty())
    }

    /** A "}" is fine when escaped or when it closes a {n}, {n,} or {n,m} quantifier. */
    private fun hasBareClosingBrace(pattern: String): Boolean {
        var i = 0
        while (i < pattern.length) {
            when (pattern[i]) {
                '\\' -> i++
                '{' -> {
                    val end = pattern.indexOf('}', i)
                    if (end > 0 && Regex("\\d+(,\\d*)?").matches(pattern.substring(i + 1, end))) i = end
                }
                '}' -> return true
            }
            i++
        }
        return false
    }

    @Test
    fun detectorWorks() {
        assertTrue(hasBareClosingBrace("\\{(a)}"))
        assertTrue(!hasBareClosingBrace("\\{(a)\\}"))
        assertTrue(!hasBareClosingBrace("[a-z]{1,40}$"))
        assertTrue(!hasBareClosingBrace("(\\.\\d){3}"))
    }
}
