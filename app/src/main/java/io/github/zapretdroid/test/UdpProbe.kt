package io.github.zapretdroid.test

import java.io.EOFException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom

/** A UDP service to check: QUIC (HTTP/3) of a site or a STUN server (calls). */
data class UdpTarget(val kind: Kind, val host: String, val port: Int) {
    enum class Kind { QUIC, STUN }

    val label get() = "${kind.name.lowercase()}:$host:$port"
}

/**
 * Sends a QUIC Initial or a STUN Binding request through the test proxy's SOCKS5 UDP
 * ASSOCIATE, so the engine applies the strategy's UDP desync, and waits for the server's reply.
 */
object UdpProbe {
    private val random = SecureRandom()

    fun probe(proxyPort: Int, target: UdpTarget, address: InetAddress?, timeoutMs: Int, sends: Int = 2): ProbeResult {
        val start = System.currentTimeMillis()
        val addr = address?.hostAddress
        fun result(ok: Boolean, stage: ProbeStage, detail: String, firstByte: Long? = null, bytes: Int = 0) = ProbeResult(
            target.label, addr, ok, detail, stage, firstByteMs = firstByte, totalMs = System.currentTimeMillis() - start, bytes = bytes,
        )
        if (address !is Inet4Address) return result(false, ProbeStage.PROXY, "no IPv4 address")
        return try {
            Socket().use { control ->
                control.soTimeout = timeoutMs
                control.connect(InetSocketAddress("127.0.0.1", proxyPort), timeoutMs)
                val relayPort = associate(control)
                DatagramSocket().use { udp ->
                    udp.soTimeout = maxOf(200, timeoutMs / sends)
                    val relay = InetSocketAddress("127.0.0.1", relayPort)
                    val header = byteArrayOf(0, 0, 0, 1) + address.address + byteArrayOf((target.port shr 8).toByte(), target.port.toByte())
                    val txid = ByteArray(12).also { random.nextBytes(it) }
                    val payload = when (target.kind) {
                        UdpTarget.Kind.QUIC -> QuicInitial.build(target.host)
                        UdpTarget.Kind.STUN -> stunBindingRequest(txid)
                    }
                    var sent = 0
                    val buf = ByteArray(4096)
                    while (sent < sends) {
                        val datagram = header + payload
                        udp.send(DatagramPacket(datagram, datagram.size, relay))
                        sent++
                        try {
                            val reply = DatagramPacket(buf, buf.size)
                            udp.receive(reply)
                            if (reply.length <= 10) continue
                            val data = buf.copyOfRange(10, reply.length) // SOCKS5 UDP header for IPv4
                            val firstByte = System.currentTimeMillis() - start
                            val detail = when (target.kind) {
                                UdpTarget.Kind.QUIC -> "server replied: ${QuicInitial.describeReply(data)}, ${data.size} B"
                                UdpTarget.Kind.STUN -> if (isStunSuccess(data, txid)) "Binding success" else "unexpected reply, ${data.size} B"
                            }
                            return result(true, ProbeStage.DONE, "$detail (after $sent sent)", firstByte, data.size)
                        } catch (_: SocketTimeoutException) {
                            // resend, as a real client would
                        }
                    }
                    result(false, ProbeStage.RESPONSE, "no reply to $sent datagram(s)")
                }
            }
        } catch (e: Exception) {
            result(false, ProbeStage.PROXY, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * One datagram to [address]:[port] through the proxy's UDP relay; the reply's source address
     * (from the SOCKS5 header) and data, or null without a reply.
     */
    fun exchange(proxyPort: Int, address: Inet4Address, port: Int, payload: ByteArray, timeoutMs: Int): Pair<InetSocketAddress, ByteArray>? =
        Socket().use { control ->
            control.soTimeout = timeoutMs
            control.connect(InetSocketAddress("127.0.0.1", proxyPort), timeoutMs)
            val relay = InetSocketAddress("127.0.0.1", associate(control))
            DatagramSocket().use { udp ->
                udp.soTimeout = timeoutMs
                val datagram = byteArrayOf(0, 0, 0, 1) + address.address + byteArrayOf((port shr 8).toByte(), port.toByte()) + payload
                udp.send(DatagramPacket(datagram, datagram.size, relay))
                val buf = ByteArray(4096)
                val reply = DatagramPacket(buf, buf.size)
                try {
                    udp.receive(reply)
                } catch (_: SocketTimeoutException) {
                    return null
                }
                if (reply.length <= 10 || buf[3].toInt() != 1) return null
                val from = InetSocketAddress(InetAddress.getByAddress(buf.copyOfRange(4, 8)), ((buf[8].toInt() and 0xff) shl 8) or (buf[9].toInt() and 0xff))
                from to buf.copyOfRange(10, reply.length)
            }
        }

    /** SOCKS5 UDP ASSOCIATE; returns the relay port. */
    private fun associate(control: Socket): Int {
        val out = control.getOutputStream()
        val input = control.getInputStream()
        out.write(byteArrayOf(5, 1, 0)); out.flush()
        val hello = readN(input, 2)
        if (hello[1].toInt() != 0) throw IllegalStateException("proxy refused handshake")
        out.write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()
        val reply = readN(input, 4)
        if (reply[1].toInt() != 0) throw IllegalStateException("UDP ASSOCIATE refused (${reply[1]})")
        val rest = when (reply[3].toInt()) {
            1 -> readN(input, 6)
            4 -> readN(input, 18)
            else -> throw IllegalStateException("unexpected address type")
        }
        return ((rest[rest.size - 2].toInt() and 0xff) shl 8) or (rest[rest.size - 1].toInt() and 0xff)
    }

    /** RFC 5389 Binding request without attributes. */
    fun stunBindingRequest(txid: ByteArray): ByteArray =
        byteArrayOf(0x00, 0x01, 0x00, 0x00, 0x21, 0x12, 0xa4.toByte(), 0x42) + txid

    fun isStunSuccess(d: ByteArray, txid: ByteArray): Boolean =
        d.size >= 20 && d[0].toInt() == 0x01 && d[1].toInt() == 0x01 && d.copyOfRange(8, 20).contentEquals(txid)

    private fun readN(input: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(b, off, n - off)
            if (r < 0) throw EOFException("proxy closed the connection")
            off += r
        }
        return b
    }
}
