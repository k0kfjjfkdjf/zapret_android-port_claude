package io.github.zapretdroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptTest {
    private fun args(text: String, env: Script.Env) = splitArgs(Script.compile(text, env).text)

    @Test
    fun plainCommandLinePassesThrough() {
        val text = "# === Main ===\n--disorder 1 --auto=none\n--split 2"
        val r = Script.compile(text, Script.Env("wifi"))
        assertEquals(emptyList<Script.Issue>(), r.errors)
        assertEquals(listOf("--disorder", "1", "--auto=none", "--split", "2"), splitArgs(r.text))
        assertFalse(Script.hasDirectives(text))
    }

    @Test
    fun variables() {
        val text = "@set sni = www.google.com\n@set fake = --fake -1 --fake-sni \$sni\n\${fake} --ttl 6"
        assertEquals(listOf("--fake", "-1", "--fake-sni", "www.google.com", "--ttl", "6"), args(text, Script.Env("wifi")))
        val bad = Script.compile("@set a = 1\n--fake-sni \$nope", Script.Env("wifi"))
        assertEquals(2, bad.errors.single().line)
        // Without directives nothing is substituted.
        assertEquals("--fake-sni \$nope", Script.compile("--fake-sni \$nope", Script.Env("wifi")).text)
    }

    @Test
    fun conditionsByNetwork() {
        val text = "--disorder 1\n@if mobile\n--tlsrec 1+s\n@else\n--oob 3\n@end\n@if not wifi\n--mss 88\n@end"
        assertEquals(listOf("--disorder", "1", "--tlsrec", "1+s", "--mss", "88"), args(text, Script.Env("mobile")))
        assertEquals(listOf("--disorder", "1", "--oob", "3"), args(text, Script.Env("wifi")))
        assertTrue(Script.dependsOnNetwork(text))
    }

    @Test
    fun blocksCanBeSwitchedOff() {
        val text = "@block YouTube\n--hosts {list:yt} -C {proxy} --auto=none\n@end\n--disorder 1"
        val on = Script.compile(text, Script.Env("wifi"))
        assertEquals(listOf("YouTube"), on.blocks)
        assertEquals(7, splitArgs(on.text).size)
        assertEquals(listOf("--disorder", "1"), args(text, Script.Env("wifi", setOf("YouTube"))))
    }

    @Test
    fun includes() {
        val saved = mapOf("discord" to "@set t = 4\n--disorder 1 --ttl \$t", "loop" to "@include loop")
        val env = Script.Env("wifi", include = { saved[it] })
        assertEquals(listOf("--split", "1", "--disorder", "1", "--ttl", "4"), args("--split 1\n@include \"discord\"", env))
        assertTrue(Script.compile("@include loop", env).errors.any { "itself" in it.message })
        assertTrue(Script.compile("@include missing", env).errors.single().message.contains("no saved strategy"))
    }

    @Test
    fun errorsHaveLines() {
        val r = Script.compile("--split 1\n@if moon\n@end\n@end\n@block\n@on\n@frobnicate", Script.Env("wifi"))
        val byLine = r.errors.associate { it.line to it.message }
        assertTrue(byLine[2]!!.contains("@if takes"))
        assertTrue(byLine[4]!!.contains("@end without"))
        assertTrue(byLine[6]!!.contains("not supported yet"))
        assertTrue(byLine[7]!!.contains("unknown directive"))
        // the @block on line 5 has no name and no @end
        assertTrue(r.errors.count { it.line == 5 } == 2)
    }

    @Test
    fun tryBecomesSlots() {
        val text = "@sites dc = discord.com, gateway.discord.gg\n@for dc\n@try\n--disorder 1\n@or\n--oob 3 -A torst --split 1\n@end\n--disorder 1"
        val r = Script.compile(text, Script.Env("wifi"))
        assertEquals(emptyList<Script.Issue>(), r.errors)
        val t = r.tries.single()
        assertEquals("dc", t.name)
        assertEquals(listOf("discord.com", "gateway.discord.gg"), t.sites)
        assertEquals(listOf("dc.1", "dc.2"), t.slots)
        assertEquals(
            listOf(
                "--slot", "dc.1", "--disorder", "1", "--auto=none",
                "--slot", "dc.2", "--oob", "3", "-A", "torst", "--slot", "dc.2", "--split", "1", "--auto=none",
                "--disorder", "1",
            ),
            splitArgs(r.text),
        )
    }

    @Test
    fun fallbackBecomesAutoGroups() {
        val text = "@sites yt = youtube.com\n@for yt\n@fallback\n--disorder 1\n@or\n-C {proxy:termux}\n@end"
        val r = Script.compile(text, Script.Env("mobile"))
        assertEquals(emptyList<Script.Issue>(), r.errors)
        assertEquals(listOf("yt.fb"), r.tries.single().slots)
        assertEquals(
            listOf("--slot", "yt.fb", "--disorder", "1", Script.FALLBACK_DETECT, "--slot", "yt.fb", "-C", "{proxy:termux}", "--auto=none"),
            splitArgs(r.text),
        )
    }

    @Test
    fun tryErrors() {
        assertTrue(Script.compile("@try\n--split 1\n@end", Script.Env("wifi")).errors.single().message.contains("@for"))
        assertTrue(Script.compile("@for nope\n@try\n--split 1\n@end", Script.Env("wifi")).errors.any { "no @sites" in it.message })
        assertTrue(Script.compile("@sites a = x.com\n@for a\n@try\n--split 1", Script.Env("wifi")).errors.any { "without @end" in it.message })
        // A @try inside a block that is off records nothing.
        val off = Script.compile("@sites a = x.com\n@block B\n@for a\n@try\n--split 1\n@end\n@end", Script.Env("wifi", setOf("B")))
        assertTrue(off.tries.isEmpty())
    }

    @Test
    fun quoteRoundTrips() {
        val tokens = listOf("a b", "it's", "#x", "plain", "")
        assertEquals(tokens, splitArgs(Script.quote(tokens)))
    }
}
