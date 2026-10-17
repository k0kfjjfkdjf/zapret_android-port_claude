package io.github.zapretdroid.core

/**
 * Turns the engine's connection log (--conn-log lines in the journal) into events a person can
 * follow while an app connects: which hosts it opens, blocks, freezes after 16-20 KB, UDP flows
 * and their first replies, Discord IP discovery retries. The Discord app shows none of this.
 */
object ConnMonitor {
    enum class Kind { CONNECT, CLOSED, FREEZE, BLOCK, TCP_FAILED, TCP_SLOW, UDP_START, UDP_REPLY, UDP_RETRY, UDP_NO_REPLY, UDP_CLOSED }

    data class Event(val time: String, val kind: Kind, val host: String, val text: String, val discord: Boolean)

    data class Summary(val connections: Int, val blocks: Int, val freezes: Int, val udpNoReply: Int, val retries: Int)

    private const val SESSION_START = "===== VPN session start"
    private val TIME = Regex("""^(\d\d-\d\d \d\d:\d\d:\d\d)""")
    private val CONN = Regex("""\bconn (\d+): (\S+) -> (\S+):(\d+), (\w+), group (\d+)""")
    private val CLOSED = Regex("""\bconn (\d+) closed: (\S+), up (\d+) B, down (\d+) B, rounds (\d+), (\d+) ms""")
    private val BLOCK = Regex("""\bconn (\d+): block detected \((\w+)\) on (\S+) \((\S+)\) with group (\d+), (.*)""")
    private val TCP_FAILED = Regex("""\btcp (\d+): connect to (\S+) FAILED after (\d+) ms: (.*)""")
    private val TCP_SLOW = Regex("""\btcp (\d+): slow connect to (\S+): (\d+) ms""")
    // "host NAME (where from), " is there since the engine names UDP flows (SNI, DNS answers).
    private val UDP_START = Regex("""\budp (\d+): -> (\S+):(\d+), (\w+), (?:host (\S+) \(([^)]*)\), )?group (\d+)""")
    private val UDP_REGROUP = Regex("""\budp (\d+): host (\S+) \(([^)]*)\), group (\d+) -> (\d+)""")
    private val UDP_REPLY = Regex("""\budp (\d+): first reply after (\d+) ms \((\d+) datagrams and (\d+) fakes""")
    private val UDP_RETRY = Regex("""\budp (\d+): discord IP discovery RETRY #(\d+) after (\d+) ms""")
    private val UDP_CLOSED = Regex("""\budp (\d+) closed: -> (\S+):(\d+) (\w+), out (\d+) pkts/\d+ B, in (\d+) pkts""")

    private val DISCORD_PORTS = listOf(50000..65535, 19294..19344, 3478..3481)

    fun isDiscordHost(host: String) = host.contains("discord") || host.endsWith("dis.gd")

    /** Freeze: the server sent about as much as the DPI lets through and then nothing. */
    fun looksFrozen(down: Long) = down in 14 * 1024..32 * 1024

    private fun stripMapped(ip: String) = ip.removePrefix("::ffff:")

    /** Events of the last VPN session in [lines] (the whole input if no session start is found). */
    fun parse(lines: List<String>): List<Event> {
        val start = lines.indexOfLast { it.contains(SESSION_START) }
        val session = if (start >= 0) lines.subList(start, lines.size) else lines
        val udpProto = HashMap<String, String>()
        val udpDiscord = HashMap<String, Boolean>()
        val out = mutableListOf<Event>()
        session.forEach { line ->
            if (!line.contains("byedpi |")) return@forEach
            val time = TIME.find(line)?.groupValues?.get(1)?.substringAfter(' ') ?: ""
            CONN.find(line)?.let { m ->
                val host = m.groupValues[2]
                out += Event(time, Kind.CONNECT, host, "$host (${m.groupValues[3]}), ${m.groupValues[5]}, group ${m.groupValues[6]}", isDiscordHost(host))
                return@forEach
            }
            CLOSED.find(line)?.let { m ->
                val host = m.groupValues[2]
                val down = m.groupValues[4].toLong()
                val ms = m.groupValues[6].toLong()
                val kind = if (looksFrozen(down)) Kind.FREEZE else Kind.CLOSED
                out += Event(time, kind, host, "$host: down ${kb(down)} in ${ms / 1000.0} s, up ${kb(m.groupValues[3].toLong())}", isDiscordHost(host))
                return@forEach
            }
            BLOCK.find(line)?.let { m ->
                val host = m.groupValues[3]
                out += Event(time, Kind.BLOCK, host, "$host (${m.groupValues[4]}): ${m.groupValues[2]}, group ${m.groupValues[5]}, ${m.groupValues[6]}", isDiscordHost(host))
                return@forEach
            }
            TCP_FAILED.find(line)?.let { m ->
                out += Event(time, Kind.TCP_FAILED, m.groupValues[2], "${m.groupValues[2]}: ${m.groupValues[4]} after ${m.groupValues[3]} ms", false)
                return@forEach
            }
            TCP_SLOW.find(line)?.let { m ->
                out += Event(time, Kind.TCP_SLOW, m.groupValues[2], "${m.groupValues[2]}: ${m.groupValues[3]} ms", false)
                return@forEach
            }
            UDP_START.find(line)?.let { m ->
                val fd = m.groupValues[1]
                val ip = stripMapped(m.groupValues[2])
                val port = m.groupValues[3].toInt()
                val proto = m.groupValues[4]
                val name = m.groupValues[5].takeIf { it.isNotEmpty() && it != "unknown" }
                val discord = proto == "discord" || DISCORD_PORTS.any { port in it } || (name != null && isDiscordHost(name))
                udpProto[fd] = proto
                udpDiscord[fd] = discord
                val target = if (name != null) "$name ($ip:$port)" else "$ip:$port"
                out += Event(time, Kind.UDP_START, name ?: "$ip:$port", "$target, $proto, group ${m.groupValues[7]}", discord)
                return@forEach
            }
            UDP_REGROUP.find(line)?.let { m ->
                val name = m.groupValues[2]
                val discord = isDiscordHost(name)
                if (discord) udpDiscord[m.groupValues[1]] = true
                out += Event(time, Kind.UDP_START, name, "$name (${m.groupValues[3]}): group ${m.groupValues[4]} -> ${m.groupValues[5]}", discord)
                return@forEach
            }
            UDP_REPLY.find(line)?.let { m ->
                val fd = m.groupValues[1]
                out += Event(time, Kind.UDP_REPLY, "udp $fd", "first reply after ${m.groupValues[2]} ms (${m.groupValues[3]} sent, ${m.groupValues[4]} fakes)", udpDiscord[fd] == true)
                return@forEach
            }
            UDP_RETRY.find(line)?.let { m ->
                out += Event(time, Kind.UDP_RETRY, "udp ${m.groupValues[1]}", "retry #${m.groupValues[2]} after ${m.groupValues[3]} ms without a reply", true)
                return@forEach
            }
            UDP_CLOSED.find(line)?.let { m ->
                val ip = stripMapped(m.groupValues[2])
                val noReply = m.groupValues[6] == "0"
                val discord = udpDiscord[m.groupValues[1]] ?: (m.groupValues[4] == "discord")
                out += Event(
                    time, if (noReply) Kind.UDP_NO_REPLY else Kind.UDP_CLOSED, "$ip:${m.groupValues[3]}",
                    "$ip:${m.groupValues[3]} ${m.groupValues[4]}: out ${m.groupValues[5]}, in ${m.groupValues[6]} datagrams", discord,
                )
            }
        }
        return out
    }

    fun summarize(events: List<Event>) = Summary(
        connections = events.count { it.kind == Kind.CONNECT },
        blocks = events.count { it.kind == Kind.BLOCK || it.kind == Kind.TCP_FAILED },
        freezes = events.count { it.kind == Kind.FREEZE },
        udpNoReply = events.count { it.kind == Kind.UDP_NO_REPLY },
        retries = events.count { it.kind == Kind.UDP_RETRY },
    )

    private fun kb(b: Long) = if (b < 1024) "$b B" else "${b / 1024} KB"
}
