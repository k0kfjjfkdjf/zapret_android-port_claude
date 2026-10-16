package io.github.zapretdroid.test

import io.github.zapretdroid.core.splitArgs
import java.util.BitSet

/**
 * Builds one engine command from deep check results, as zapret-termux's "blockcheck multi" does:
 * the strategy that opens the most domains is the main one, then strategies are added greedily,
 * each for the domains still not opened that it opens. Each supplement becomes an engine group
 * limited to its domains with --hosts; the main strategy is the last, unlimited group.
 */
object MultiStrategy {
    class Supplement(val result: DeepResult, val domains: BitSet)

    class Plan(val main: DeepResult, val supplements: List<Supplement>, val covered: Int, val openable: Int)

    /** Strategies with their own groups cannot be limited with one --hosts, so they are not supplements. */
    fun isMultiGroup(args: String): Boolean = splitArgs(args).any { it.startsWith("--auto") || it.startsWith("-A") }

    fun plan(results: List<DeepResult>, maxSupplements: Int = 5): Plan? {
        val main = results.maxByOrNull { it.count }?.takeIf { it.count > 0 } ?: return null
        val openable = BitSet().apply { results.forEach { or(it.opened) } }
        val left = (openable.clone() as BitSet).apply { andNot(main.opened) }
        val candidates = results.filter { it !== main && !isMultiGroup(it.args) }
        val supplements = mutableListOf<Supplement>()
        while (supplements.size < maxSupplements && !left.isEmpty) {
            val best = candidates.filter { c -> supplements.none { it.result === c } }
                .map { c -> c to (c.opened.clone() as BitSet).apply { and(left) } }
                .maxByOrNull { it.second.cardinality() }
                ?: break
            if (best.second.isEmpty) break
            supplements += Supplement(best.first, best.second)
            left.andNot(best.second)
        }
        return Plan(main, supplements, openable.cardinality() - left.cardinality(), openable.cardinality())
    }

    /** The command: one group per supplement ({list:name} with its domains), then the main strategy. */
    fun command(plan: Plan, listNames: List<String>): String = buildString {
        plan.supplements.forEachIndexed { i, s ->
            append("--hosts {list:").append(listNames[i]).append("} ").append(s.result.args).append(" --auto=none ")
        }
        append(plan.main.args)
    }.trim()
}
