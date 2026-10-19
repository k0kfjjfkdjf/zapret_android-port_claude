package io.github.zapretdroid.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ArgsTest {
    @Test
    fun commentsAndLines() {
        assertEquals(
            listOf("--disorder", "1", "--auto=none", "--split", "2", "--fake-sni", "a#b"),
            splitArgs("# Discord\n--disorder 1 # first group\n--auto=none\n  # UDP\n--split 2 --fake-sni a#b"),
        )
        assertEquals(listOf("x", "#not"), splitArgs("x '#not' # but this is"))
    }

    @Test
    fun blockHeadingsEndAtTheirClosingMarks() {
        // The command line from the settings flattened to one line keeps its groups.
        assertEquals(
            listOf("--apps", "1", "--auto=none", "--hosts", "a", "-C", "x", "--auto=none", "--disorder", "1"),
            splitArgs("--apps 1 --auto=none # === Apps via: main === --hosts a -C x --auto=none # === Main strategy === --disorder 1"),
        )
        // Unclosed, it is an ordinary comment.
        assertEquals(listOf("a"), splitArgs("a # === no end --split 2"))
    }

    @Test
    fun splitsOnWhitespace() {
        assertEquals(listOf("-o1", "-a1", "-r-5+se"), splitArgs("  -o1 \t-a1\n-r-5+se "))
    }

    @Test
    fun keepsQuotedGroups() {
        assertEquals(listOf("--fake-data", ":GET / HTTP/1.1"), splitArgs("--fake-data ':GET / HTTP/1.1'"))
        assertEquals(listOf("-H", ":a.com b.com"), splitArgs("-H \":a.com b.com\""))
    }

    @Test
    fun handlesEscapes() {
        assertEquals(listOf("a b", "c\"d"), splitArgs("""a\ b "c\"d""""))
        assertEquals(listOf("""\n"""), splitArgs("""'\n'"""))
    }

    @Test
    fun keepsEmptyQuotedArgument() {
        assertEquals(listOf("-x", ""), splitArgs("-x ''"))
    }

    @Test
    fun emptyLine() {
        assertEquals(emptyList<String>(), splitArgs("   "))
    }
}
