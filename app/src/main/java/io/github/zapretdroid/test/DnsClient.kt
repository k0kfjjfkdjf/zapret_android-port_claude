package io.github.zapretdroid.test

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlin.random.Random

/**
 * Minimal DNS client (A records over UDP). The tester resolves names through the DNS server
 * chosen in the settings, like the VPN does, instead of the system resolver.
 */
object DnsClient {
    fun resolve(host: String, server: InetAddress, timeoutMs: Int = 2000, attempts: Int = 2): List<Inet4Address> {
        val id = Random.nextInt(0, 0x10000)
        val query = buildQuery(id, host)
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            repeat(attempts) {
                socket.send(DatagramPacket(query, query.size, InetSocketAddress(server, 53)))
                val buf = ByteArray(1500)
                val packet = DatagramPacket(buf, buf.size)
                try {
                    while (true) {
                        socket.receive(packet)
                        val answers = parseResponse(buf.copyOf(packet.length), id) ?: continue
                        return answers
                    }
                } catch (_: SocketTimeoutException) {
                    // retry
                }
            }
        }
        throw SocketTimeoutException("no DNS answer for $host from ${server.hostAddress}")
    }

    fun buildQuery(id: Int, host: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id shr 8); out.write(id and 0xff)
        out.write(0x01); out.write(0x00) // recursion desired
        out.write(0); out.write(1) // QDCOUNT
        repeat(6) { out.write(0) } // ANCOUNT, NSCOUNT, ARCOUNT
        host.trimEnd('.').split('.').forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            require(bytes.size in 1..63) { "bad host name $host" }
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        out.write(0); out.write(1) // QTYPE A
        out.write(0); out.write(1) // QCLASS IN
        return out.toByteArray()
    }

    /** A records from a response to query [id]; null if the packet is not that response. */
    fun parseResponse(msg: ByteArray, id: Int): List<Inet4Address>? {
        if (msg.size < 12) return null
        if (u16(msg, 0) != id || (msg[2].toInt() and 0x80) == 0) return null
        val rcode = msg[3].toInt() and 0x0f
        if (rcode != 0) throw IllegalStateException("DNS error code $rcode")
        val qd = u16(msg, 4)
        val an = u16(msg, 6)
        var pos = 12
        repeat(qd) { pos = skipName(msg, pos) + 4 }
        val result = mutableListOf<Inet4Address>()
        repeat(an) {
            pos = skipName(msg, pos)
            if (pos + 10 > msg.size) return result
            val type = u16(msg, pos)
            val len = u16(msg, pos + 8)
            pos += 10
            if (type == 1 && len == 4 && pos + 4 <= msg.size) {
                result += InetAddress.getByAddress(msg.copyOfRange(pos, pos + 4)) as Inet4Address
            }
            pos += len
        }
        return result
    }

    private fun skipName(msg: ByteArray, start: Int): Int {
        var pos = start
        while (pos < msg.size) {
            val len = msg[pos].toInt() and 0xff
            when {
                len == 0 -> return pos + 1
                len and 0xc0 == 0xc0 -> return pos + 2
                else -> pos += len + 1
            }
        }
        return pos
    }

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
}
