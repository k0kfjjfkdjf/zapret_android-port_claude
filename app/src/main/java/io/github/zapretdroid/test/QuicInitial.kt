package io.github.zapretdroid.test

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Builds a QUIC v1 Initial packet carrying a TLS 1.3 ClientHello for a given SNI (RFC 9000,
 * RFC 9001). Initial packets are protected with keys derived from the destination connection
 * ID only, so anyone, DPI included, can decrypt them; a server answers with its own Initial,
 * Retry or Version Negotiation. Any answer means QUIC to that host gets through.
 */
object QuicInitial {
    private val SALT_V1 = hex("38762cf7f55934b34d179ae6a4c80cadccbb7f0a")
    private val random = SecureRandom()

    class Keys(val key: ByteArray, val iv: ByteArray, val hp: ByteArray)

    /** Client Initial keys for a destination connection ID (RFC 9001, 5.2). */
    fun clientKeys(dcid: ByteArray): Keys {
        val initialSecret = hkdfExtract(SALT_V1, dcid)
        val clientSecret = expandLabel(initialSecret, "client in", 32)
        return Keys(
            key = expandLabel(clientSecret, "quic key", 16),
            iv = expandLabel(clientSecret, "quic iv", 12),
            hp = expandLabel(clientSecret, "quic hp", 16),
        )
    }

    /** A complete Initial datagram (padded to 1200 bytes) for [sni]. */
    fun build(sni: String, dcid: ByteArray = randomBytes(8), scid: ByteArray = randomBytes(8)): ByteArray {
        val crypto = ByteArrayOutputStream().apply {
            write(0x06) // CRYPTO frame
            writeVarint(0)
            val hello = clientHello(sni, scid)
            writeVarint(hello.size.toLong())
            write(hello)
        }.toByteArray()
        return protect(crypto, dcid, scid, packetNumber = 0)
    }

    /** Header + AEAD + header protection; the payload is padded so the datagram is 1200 bytes. */
    fun protect(frames: ByteArray, dcid: ByteArray, scid: ByteArray, packetNumber: Int): ByteArray {
        val keys = clientKeys(dcid)
        val pnLength = 4
        val headerWithoutLength = ByteArrayOutputStream().apply {
            write(0xc0 or (pnLength - 1)) // long header, Initial, packet number length 4
            write(byteArrayOf(0, 0, 0, 1)) // version 1
            write(dcid.size); write(dcid)
            write(scid.size); write(scid)
            writeVarint(0) // token length
        }.toByteArray()
        // Pad with PADDING frames: header + 2-byte length + pn + payload + 16-byte tag = 1200.
        val payloadLength = maxOf(frames.size, 1200 - headerWithoutLength.size - 2 - pnLength - 16)
        val payload = frames.copyOf(payloadLength)
        val header = ByteArrayOutputStream().apply {
            write(headerWithoutLength)
            val length = pnLength + payload.size + 16
            write(0x40 or (length shr 8)); write(length and 0xff) // 2-byte varint
            write(byteArrayOf((packetNumber ushr 24).toByte(), (packetNumber ushr 16).toByte(), (packetNumber ushr 8).toByte(), packetNumber.toByte()))
        }.toByteArray()

        val nonce = keys.iv.copyOf()
        for (i in 0 until 4) nonce[nonce.size - 1 - i] = (nonce[nonce.size - 1 - i].toInt() xor (packetNumber ushr (8 * i))).toByte()
        val aead = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(header)
        }
        val packet = header + aead.doFinal(payload)

        val pnOffset = header.size - pnLength
        val mask = headerMask(keys.hp, packet.copyOfRange(pnOffset + 4, pnOffset + 4 + 16))
        packet[0] = (packet[0].toInt() xor (mask[0].toInt() and 0x0f)).toByte()
        for (i in 0 until pnLength) packet[pnOffset + i] = (packet[pnOffset + i].toInt() xor mask[1 + i].toInt()).toByte()
        return packet
    }

    /** Header protection mask: AES-ECB of the 16-byte sample (RFC 9001, 5.4.3). */
    fun headerMask(hp: ByteArray, sample: ByteArray): ByteArray =
        Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(hp, "AES")) }.doFinal(sample)

    /** Describes a datagram received from a server in reply. */
    fun describeReply(d: ByteArray): String {
        if (d.isEmpty()) return "empty datagram"
        val first = d[0].toInt() and 0xff
        if (first and 0x80 == 0) return "short header packet"
        if (d.size < 5) return "truncated long header"
        val version = ((d[1].toLong() and 0xff) shl 24) or ((d[2].toLong() and 0xff) shl 16) or
            ((d[3].toLong() and 0xff) shl 8) or (d[4].toLong() and 0xff)
        if (version == 0L) return "Version Negotiation"
        return when ((first shr 4) and 3) {
            0 -> "Initial"
            1 -> "0-RTT"
            2 -> "Handshake"
            else -> "Retry"
        } + " (version 0x${version.toString(16)})"
    }

    private fun clientHello(sni: String, scid: ByteArray): ByteArray {
        val extensions = ByteArrayOutputStream().apply {
            val host = sni.toByteArray(Charsets.US_ASCII)
            extension(0x0000, list16(byteArrayOf(0) + u16(host.size) + host)) // server_name
            extension(0x000a, list16(u16(0x001d) + u16(0x0017))) // supported_groups: x25519, secp256r1
            extension(0x000d, list16(u16(0x0403) + u16(0x0804) + u16(0x0401) + u16(0x0503) + u16(0x0805) + u16(0x0501))) // signature_algorithms
            extension(0x0010, list16(byteArrayOf(2) + "h3".toByteArray())) // ALPN
            extension(0x002b, byteArrayOf(2, 0x03, 0x04)) // supported_versions: TLS 1.3
            extension(0x002d, byteArrayOf(1, 1)) // psk_key_exchange_modes: psk_dhe_ke
            extension(0x0033, list16(u16(0x001d) + u16(32) + randomBytes(32))) // key_share: x25519
            extension(0x0039, transportParameters(scid)) // quic_transport_parameters
        }.toByteArray()
        val body = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x03, 0x03)) // legacy_version
            write(randomBytes(32)) // random
            write(0) // legacy_session_id: empty in QUIC
            write(list16(u16(0x1301) + u16(0x1302) + u16(0x1303))) // cipher suites
            write(byteArrayOf(1, 0)) // compression: null
            write(list16(extensions))
        }.toByteArray()
        return byteArrayOf(1, (body.size shr 16).toByte(), (body.size shr 8).toByte(), body.size.toByte()) + body
    }

    private fun transportParameters(scid: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        fun param(id: Int, value: ByteArray) { writeVarint(id.toLong()); writeVarint(value.size.toLong()); write(value) }
        fun varintParam(id: Int, v: Long) = param(id, ByteArrayOutputStream().apply { writeVarint(v) }.toByteArray())
        varintParam(0x01, 30_000) // max_idle_timeout
        varintParam(0x04, 1_048_576) // initial_max_data
        varintParam(0x05, 262_144) // initial_max_stream_data_bidi_local
        varintParam(0x06, 262_144) // initial_max_stream_data_bidi_remote
        varintParam(0x07, 262_144) // initial_max_stream_data_uni
        varintParam(0x08, 100) // initial_max_streams_bidi
        varintParam(0x09, 100) // initial_max_streams_uni
        param(0x0f, scid) // initial_source_connection_id
    }.toByteArray()

    private fun ByteArrayOutputStream.extension(type: Int, data: ByteArray) {
        write(u16(type)); write(u16(data.size)); write(data)
    }

    private fun list16(data: ByteArray) = u16(data.size) + data

    private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())

    private fun ByteArrayOutputStream.writeVarint(v: Long) {
        when {
            v < 0x40 -> write(v.toInt())
            v < 0x4000 -> { write(0x40 or (v shr 8).toInt()); write((v and 0xff).toInt()) }
            v < 0x40000000 -> for (i in 3 downTo 0) write(((v shr (8 * i)) and 0xff).toInt() or if (i == 3) 0x80 else 0)
            else -> for (i in 7 downTo 0) write(((v shr (8 * i)) and 0xff).toInt() or if (i == 7) 0xc0 else 0)
        }
    }

    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt, "HmacSHA256")) }.doFinal(ikm)

    /** HKDF-Expand-Label with an empty context (RFC 8446, 7.1); lengths here fit one block. */
    fun expandLabel(secret: ByteArray, label: String, length: Int): ByteArray {
        val fullLabel = ("tls13 $label").toByteArray(Charsets.US_ASCII)
        val info = u16(length) + byteArrayOf(fullLabel.size.toByte()) + fullLabel + byteArrayOf(0)
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
        return mac.doFinal(info + byteArrayOf(1)).copyOf(length)
    }

    private fun randomBytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }

    fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}
