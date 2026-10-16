package io.github.zapretdroid.test

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.BitSet

class MultiStrategyTest {
    private fun result(index: Int, args: String, vararg opened: Int) =
        DeepResult(index, args, BitSet().apply { opened.forEach { set(it) } }, emptyMap())

    @Test
    fun greedyCoverPicksMainThenBestSupplements() {
        val results = listOf(
            result(1, "--disorder 1", 0, 1, 2, 3, 4),
            result(2, "--split 1", 0, 5),
            result(3, "--tlsrec 1+s", 5, 6, 7),
            result(4, "--oob 1", 8),
            result(5, "--fake -1 --auto=torst --split 2", 9, 10, 11, 12), // multi-group: never a supplement
        )
        val plan = MultiStrategy.plan(results)!!
        assertEquals("--disorder 1", plan.main.args)
        assertEquals(listOf("--tlsrec 1+s", "--oob 1"), plan.supplements.map { it.result.args })
        assertEquals(BitSet().apply { set(5); set(6); set(7) }, plan.supplements[0].domains)
        assertEquals(9, plan.covered) // 0-8; 9-12 are only opened by the multi-group strategy
        assertEquals(13, plan.openable)
        assertEquals(
            "--hosts {list:m1} --tlsrec 1+s --auto=none --hosts {list:m2} --oob 1 --auto=none --disorder 1",
            MultiStrategy.command(plan, listOf("m1", "m2")),
        )
    }

    @Test
    fun nothingOpened() {
        assertNull(MultiStrategy.plan(listOf(result(1, "--split 1"))))
    }

    @Test
    fun multiGroupDetection() {
        assertTrue(MultiStrategy.isMultiGroup("--proto t --disorder 1 --auto=none --split 1"))
        assertTrue(MultiStrategy.isMultiGroup("-d1 -At,r -s1"))
        assertFalse(MultiStrategy.isMultiGroup("-o1 -a1 -r-5+se"))
    }
}
