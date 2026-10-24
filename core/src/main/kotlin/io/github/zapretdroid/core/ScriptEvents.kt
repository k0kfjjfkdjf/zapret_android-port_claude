package io.github.zapretdroid.core

/**
 * The script's @on rules against the engine's connection log (stage 3): which line is which
 * event, and which rule acts on it. Pure, so that it can be unit tested; the VPN service feeds
 * it the log as it grows and carries out the actions.
 */
object ScriptEvents {
    /** A failure seen in the log: [reason] as in Script.REASONS, the domain (or address), tcp/udp. */
    data class Event(val reason: String, val host: String, val proto: String)

    private val BLOCK = Regex("""\b(conn|udp) \d+: block detected \(\w+\) on (\S+) """)
    private val CLOSED = Regex("""\bconn \d+ closed: (\S+), up (\d+) B, down (\d+) B""")
    private val CONNFAIL = Regex("""\btcp \d+: connect to (\S+) FAILED""")
    // "(block reported)": the flow's "block detected (udp)" line was already one event.
    private val UDP_NO_REPLY = Regex("""\budp \d+ closed: -> (\S+):\d+ .*<-- NO REPLY(?! \(block reported\))""")
    private val PROXY_DOWN = Regex("""\bgroup \d+: proxy (\S+) (?:still )?refuses connections""")

    fun parse(line: String): Event? {
        BLOCK.find(line)?.let { m ->
            val proto = if (m.groupValues[1] == "udp") "udp" else "tcp"
            return m.groupValues[2].takeIf { it != "-" }?.let { Event("block", it.lowercase(), proto) }
        }
        CLOSED.find(line)?.let { m ->
            val host = m.groupValues[1].lowercase().takeIf { it != "-" } ?: return null
            val up = m.groupValues[2].toLong()
            val down = m.groupValues[3].toLong()
            return when {
                down == 0L && up > 0 -> Event("noreply", host, "tcp")
                ConnMonitor.looksFrozen(down) -> Event("freeze", host, "tcp")
                else -> null
            }
        }
        CONNFAIL.find(line)?.let { m -> return Event("connfail", m.groupValues[1].removePrefix("::ffff:"), "tcp") }
        UDP_NO_REPLY.find(line)?.let { m -> return Event("udp-noreply", m.groupValues[1].removePrefix("::ffff:"), "udp") }
        PROXY_DOWN.find(line)?.let { m -> return Event("proxy-down", m.groupValues[1], "tcp") }
        return null
    }

    /** A mask "x.com" or "*.x.com" matches x.com and its subdomains. */
    fun maskMatches(mask: String, host: String): Boolean {
        val base = mask.removePrefix("*.")
        return host == base || host.endsWith(".$base")
    }

    fun matches(rule: Script.OnRule, e: Event): Boolean =
        (e.reason in rule.reasons || ("any" in rule.reasons && e.reason != "proxy-down")) &&
            (rule.proto == null || rule.proto == e.proto) &&
            (rule.masks.isEmpty() || rule.masks.any { maskMatches(it, e.host) })

    /**
     * Thresholds, a pause per rule and domain after acting, and a limit on actions per hour, so
     * that a rule cannot loop. [offer] returns the rule to carry out, or null.
     */
    class Matcher(
        private val rules: List<Script.OnRule>,
        private val now: () -> Long = System::currentTimeMillis,
        private val cooldownMs: Long = 10 * 60_000L,
        private val maxPerHour: Int = 30,
    ) {
        private val seen = HashMap<Pair<Int, String>, ArrayDeque<Long>>()
        private val acted = HashMap<Pair<Int, String>, Long>()
        private val actions = ArrayDeque<Long>()

        fun offer(e: Event): Script.OnRule? {
            // The first rule that matches decides (an "ignore" rule stops the others).
            val rule = rules.firstOrNull { matches(it, e) } ?: return null
            val t = now()
            val key = rule.index to e.host
            val times = seen.getOrPut(key) { ArrayDeque() }
            times.addLast(t)
            if (rule.windowMs > 0) while (times.isNotEmpty() && t - times.first() > rule.windowMs) times.removeFirst()
            if (times.size < rule.count) return null
            times.clear()
            if (rule.action == Script.Action.Ignore) return null
            acted[key]?.let { if (t - it < cooldownMs) return null }
            while (actions.isNotEmpty() && t - actions.first() > 3_600_000L) actions.removeFirst()
            if (actions.size >= maxPerHour) return null
            acted[key] = t
            actions.addLast(t)
            return rule
        }
    }
}
