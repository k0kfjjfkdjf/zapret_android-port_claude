package io.github.zapretdroid.test

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class UdpProbeTest {
    /** Minimal SOCKS5 server with UDP ASSOCIATE that relays datagrams for one client. */
    private class FakeSocks5 : AutoCloseable {
        val control = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val relay = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val port get() = control.localPort

        init {
            thread(isDaemon = true) {
                runCatching {
                    control.accept().use { s ->
                        val input = s.getInputStream()
                        val out = s.getOutputStream()
                        input.readNBytes(3)
                        out.write(byteArrayOf(5, 0))
                        input.readNBytes(10)
                        val p = relay.localPort
                        out.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, (p shr 8).toByte(), p.toByte()))
                        val upstream = DatagramSocket()
                        val buf = ByteArray(4096)
                        val fromClient = DatagramPacket(buf, buf.size)
                        relay.receive(fromClient)
                        val addr = InetAddress.getByAddress(buf.copyOfRange(4, 8))
                        val dport = ((buf[8].toInt() and 0xff) shl 8) or (buf[9].toInt() and 0xff)
                        upstream.send(DatagramPacket(buf, 10, fromClient.length - 10, addr, dport))
                        val back = ByteArray(4096)
                        val reply = DatagramPacket(back, back.size)
                        upstream.receive(reply)
                        val framed = buf.copyOfRange(0, 10) + back.copyOfRange(0, reply.length)
                        relay.send(DatagramPacket(framed, framed.size, fromClient.socketAddress))
                        input.read() // hold the association until the client closes it
                    }
                }
            }
        }

        override fun close() {
            control.close()
            relay.close()
        }
    }

    @Test
    fun stunBindingThroughSocks5() {
        val stun = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            val buf = ByteArray(512)
            val p = DatagramPacket(buf, buf.size)
            stun.receive(p)
            val txid = buf.copyOfRange(8, 20)
            // Binding success with an XOR-MAPPED-ADDRESS attribute
            val resp = byteArrayOf(0x01, 0x01, 0x00, 0x0c, 0x21, 0x12, 0xa4.toByte(), 0x42) + txid +
                byteArrayOf(0x00, 0x20, 0x00, 0x08, 0x00, 0x01, 0x11, 0x2b, 0x5e, 0x12, 0xa4.toByte(), 0x43)
            stun.send(DatagramPacket(resp, resp.size, p.socketAddress))
        }
        FakeSocks5().use { proxy ->
            val target = UdpTarget(UdpTarget.Kind.STUN, "localhost", stun.localPort)
            val r = UdpProbe.probe(proxy.port, target, InetAddress.getByName("127.0.0.1"), 2000)
            assertTrue(r.summary(), r.ok)
            assertTrue(r.detail, r.detail.startsWith("Binding success"))
            assertEquals("stun:localhost:${stun.localPort}", r.host)
        }
        stun.close()
    }

    @Test
    fun noReplyIsAFailureAtResponse() {
        val silent = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        FakeSocks5().use { proxy ->
            val target = UdpTarget(UdpTarget.Kind.QUIC, "example.com", silent.localPort)
            val r = UdpProbe.probe(proxy.port, target, InetAddress.getByName("127.0.0.1"), 600)
            assertFalse(r.ok)
            assertEquals(ProbeStage.RESPONSE, r.stage)
        }
        silent.close()
    }

    @Test
    fun stunReplyMatchesTransaction() {
        val txid = ByteArray(12) { it.toByte() }
        val req = UdpProbe.stunBindingRequest(txid)
        assertEquals(20, req.size)
        val ok = byteArrayOf(0x01, 0x01, 0, 0, 0x21, 0x12, 0xa4.toByte(), 0x42) + txid
        assertTrue(UdpProbe.isStunSuccess(ok, txid))
        assertFalse(UdpProbe.isStunSuccess(ok, ByteArray(12)))
    }

    @Test
    fun proxyRefusalIsReported() {
        val closed = ServerSocket(0).use { it.localPort }
        val r = UdpProbe.probe(closed, UdpTarget(UdpTarget.Kind.STUN, "x", 1), InetAddress.getByName("127.0.0.1"), 500)
        assertFalse(r.ok)
        assertEquals(ProbeStage.PROXY, r.stage)
    }
}
