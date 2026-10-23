package io.github.zapretdroid.core

/**
 * Collecting the domains an app uses, as zapret-termux's "collect": the VPN routes only that app,
 * and the engine's connection log (--conn-log) names every host it connects to. The domains can
 * then be saved as a host list for a strategy of their own.
 */
object AppCollect {
    /** Journal line that starts a collect session; the lines after it belong to the session. */
    const val MARKER = "collect mode: only "

    data class Result(val domains: Map<String, Int>, val ips: Map<String, Int>, val udp: Map<String, Int>, val blocks: Map<String, Int>)

    private val CONN = Regex("""\bconn \d+: (\S+) -> ([0-9a-fA-F:.\[\]]+):(\d+), """)
    private val UDP = Regex("""\budp \d+: -> ([0-9a-fA-F:.\[\]]+):(\d+), (\w+), (?:host (\S+) \(([^)]*)\)|group)""")
    private val BLOCK = Regex("""\bconn \d+: block detected \(\w+\) on (\S+) """)
    private val IP = Regex("""^[0-9.]+$|:""")

    /** Parses the journal lines of one session (after [MARKER]); counts connections per host. */
    fun parse(lines: List<String>): Result {
        val start = lines.indexOfLast { it.contains(MARKER) }
        val session = if (start >= 0) lines.subList(start + 1, lines.size) else lines
        val domains = HashMap<String, Int>()
        val ips = HashMap<String, Int>()
        val udp = HashMap<String, Int>()
        val blocks = HashMap<String, Int>()
        session.forEach { line ->
            CONN.find(line)?.let { m ->
                val host = m.groupValues[1].lowercase()
                val map = if (IP.containsMatchIn(host)) ips else domains
                map[host] = (map[host] ?: 0) + 1
            }
            UDP.find(line)?.let { m ->
                val key = "${m.groupValues[1]}:${m.groupValues[2]} ${m.groupValues[3]}"
                udp[key] = (udp[key] ?: 0) + 1
                // The engine names UDP flows from QUIC's SNI and DNS answers: such names go to the list too.
                // Only names the app really used: SNI or DNS, not the one put in for Discord IP discovery.
                val name = m.groupValues[4].lowercase()
                val real = m.groupValues[5] == "sni" || m.groupValues[5] == "dns"
                if (real && name.isNotEmpty() && !IP.containsMatchIn(name)) domains[name] = (domains[name] ?: 0) + 1
            }
            BLOCK.find(line)?.let { m ->
                val host = m.groupValues[1].lowercase()
                if (host != "-") blocks[host] = (blocks[host] ?: 0) + 1
            }
        }
        return Result(domains, ips, udp, blocks)
    }
}
