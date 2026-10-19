package io.github.zapretdroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedStrategiesTest {
    @Test
    fun addKeepsPinAndNameAndMovesToTop() {
        var list = SavedStrategies.add(emptyList(), "--split 1", "first", 1)
        list = SavedStrategies.add(list, "--disorder 1", null, 2)
        list = list.map { if (it.args == "--split 1") it.copy(pinned = true) else it }
        list = SavedStrategies.add(list, "--split 1", null, 3)
        assertEquals(listOf("--split 1", "--disorder 1"), list.map { it.args })
        assertEquals("first", list[0].name)
        assertTrue(list[0].pinned)
    }

    @Test
    fun unpinnedHistoryIsCapped() {
        var list = SavedStrategies.add(emptyList(), "--pinned", "p", 0).map { it.copy(pinned = true) }
        for (i in 1..40) list = SavedStrategies.add(list, "--split $i", null, i.toLong())
        assertEquals(30, list.count { !it.pinned })
        assertTrue(list.any { it.args == "--pinned" })
        assertTrue(list.none { it.args == "--split 1" })
    }

    @Test
    fun keepsLineBreaks() {
        val args = "# === Apps ===\n--apps 1 --auto=none\n# === Main ===\n--disorder 1"
        val back = SavedStrategies.parse(SavedStrategies.format(listOf(SavedStrategy("cmd", args, true, 5))))
        assertEquals(args, back.single().args)
    }

    @Test
    fun formatRoundTrip() {
        val list = listOf(SavedStrategy("multi\tname", "--hosts {list:m} --split 1\n--auto=none", true, 5), SavedStrategy("", "-o1", false, 4))
        val back = SavedStrategies.parse(SavedStrategies.format(list))
        assertEquals("multi name", back[0].name)
        assertEquals("--hosts {list:m} --split 1\n--auto=none", back[0].args)
        assertTrue(back[0].pinned)
        assertEquals("-o1", back[1].args)
    }
}
