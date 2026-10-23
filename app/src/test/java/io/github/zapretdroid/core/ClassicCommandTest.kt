package io.github.zapretdroid.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ClassicCommandTest {
    /** What "Move into my strategy" writes: block headings, a scope note, references. */
    private val moved = """
        # === Exclusions ===
        --hosts {list:exclude} --auto=none
        # === Telegram (main proxy) ===
        --hosts {list:telegram} -C {proxy} --udp-proxy --auto=none
        --ipset {ipset:telegram} -C {proxy} --udp-proxy --auto=none
        # === Main strategy ===
        # only for the sites of list youtube
        --hosts {list:youtube} --disorder 1 --tlsrec 1+s
    """.trimIndent()

    @Test
    fun unchangedLinesStayAsTheyAre() {
        assertEquals(moved, ClassicCommand.toZapret(moved))
        val script = "@if wifi\n--disorder 1 # on Wi-Fi\n@else\n--oob 3\n@end"
        assertEquals(script, ClassicCommand.toZapret(script))
    }

    /** The old round trip flattened this into one line: everything after "# only for…" was lost. */
    @Test
    fun theStrategyIsNotCutAtAComment() {
        val args = splitArgs(ClassicCommand.toZapret(moved))
        assertEquals(listOf("--disorder", "1", "--tlsrec", "1+s"), args.takeLast(4))
        assertEquals(3, args.count { it == "--auto=none" })
    }

    @Test
    fun programNameAndListenOptionsGo() {
        assertEquals("-o1 -a1", ClassicCommand.toZapret("ciadpi --ip 127.0.0.1 --port 1080 -o1 -a1"))
        assertEquals("# a comment\n-o1 # keep this\n--disorder 1",
            ClassicCommand.toZapret("# a comment\nciadpi -p1080 -o1 # keep this\n--disorder 1 -i 0.0.0.0"))
        // Values that need quotes keep them.
        assertEquals("-n \"a b\" --fake-data \"#x\"", ClassicCommand.toZapret("ciadpi -n 'a b' --fake-data \"#x\""))
    }

    @Test
    fun classicListsOnlyWhereTheyAreByeByeDpiLists() {
        val classic = mapOf("telegram" to listOf("t.me", "telegram.org"), "youtube" to listOf("youtube.com"))
        val zapret = setOf("telegram")
        fun expand(s: String) = ClassicCommand.expandLists(s, { classic[it.lowercase()] }, { it in zapret })
        // ByeZapret's own list: a file for --hosts, not "--hosts t.me telegram.org".
        assertEquals("--hosts {list:telegram}", expand("--hosts {list:telegram}"))
        // Inline (ByeByeDPI's way) or no ByeZapret list of that name: the domains.
        assertEquals("-H \":t.me telegram.org\"", expand("-H \":{list:Telegram}\""))
        assertEquals("-H \":youtube.com\"", expand("-H \":{list:youtube}\""))
        assertEquals("--hosts youtube.com", expand("--hosts {list:youtube}"))
        assertEquals("--hosts {list:nope}", expand("--hosts {list:nope}"))
    }

    @Test
    fun commentsAreStrippedForAPlainCommandLine() {
        assertEquals("\n-o1 \n-H \"#not a comment\"\n", ClassicCommand.stripComments("# head\n-o1 # tail\n-H \"#not a comment\"\n@if wifi"))
        assertEquals(-1, ClassicCommand.commentStart("-H ':a#b'"))
        assertEquals(4, ClassicCommand.commentStart("-o1 #x"))
    }
}
