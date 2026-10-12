package io.github.zapretdroid.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ArgsTest {
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
