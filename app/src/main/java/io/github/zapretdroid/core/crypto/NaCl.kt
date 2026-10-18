package io.github.zapretdroid.core.crypto

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The primitives DNSCrypt needs, in plain Kotlin (Android 7 has no X25519 or Ed25519):
 * X25519 (after TweetNaCl, public domain), the NaCl box with XSalsa20-Poly1305 and its
 * XChaCha20-Poly1305 variant (libsodium's crypto_box_curve25519xchacha20poly1305), Ed25519
 * signature checks (RFC 8032, public data only) and BLAKE2b-512 (minisign's prehashed mode).
 */
object NaCl {
    private val random = SecureRandom()

    fun randomBytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }

    // ---- X25519 -------------------------------------------------------------------------

    private fun gf() = LongArray(16)

    private fun car25519(o: LongArray) {
        for (i in 0 until 16) {
            o[i] += 1L shl 16
            val c = o[i] shr 16
            if (i < 15) o[i + 1] += c - 1 else o[0] += 38 * (c - 1)
            o[i] -= c shl 16
        }
    }

    private fun sel25519(p: LongArray, q: LongArray, b: Int) {
        val c = (b - 1).toLong().inv()
        for (i in 0 until 16) {
            val t = c and (p[i] xor q[i])
            p[i] = p[i] xor t
            q[i] = q[i] xor t
        }
    }

    private fun pack25519(o: ByteArray, n: LongArray) {
        val m = gf()
        val t = n.copyOf()
        car25519(t); car25519(t); car25519(t)
        repeat(2) {
            m[0] = t[0] - 0xffed
            for (i in 1 until 15) {
                m[i] = t[i] - 0xffff - ((m[i - 1] shr 16) and 1)
                m[i - 1] = m[i - 1] and 0xffff
            }
            m[15] = t[15] - 0x7fff - ((m[14] shr 16) and 1)
            val b = ((m[15] shr 16) and 1).toInt()
            m[14] = m[14] and 0xffff
            sel25519(t, m, 1 - b)
        }
        for (i in 0 until 16) {
            o[2 * i] = t[i].toByte()
            o[2 * i + 1] = (t[i] shr 8).toByte()
        }
    }

    private fun unpack25519(o: LongArray, n: ByteArray) {
        for (i in 0 until 16) o[i] = (n[2 * i].toLong() and 0xff) + ((n[2 * i + 1].toLong() and 0xff) shl 8)
        o[15] = o[15] and 0x7fff
    }

    private fun add(o: LongArray, a: LongArray, b: LongArray) { for (i in 0 until 16) o[i] = a[i] + b[i] }

    private fun sub(o: LongArray, a: LongArray, b: LongArray) { for (i in 0 until 16) o[i] = a[i] - b[i] }

    private fun mul(o: LongArray, a: LongArray, b: LongArray) {
        val t = LongArray(31)
        for (i in 0 until 16) for (j in 0 until 16) t[i + j] += a[i] * b[j]
        for (i in 0 until 15) t[i] += 38 * t[i + 16]
        for (i in 0 until 16) o[i] = t[i]
        car25519(o); car25519(o)
    }

    private fun inv25519(o: LongArray, i: LongArray) {
        val c = i.copyOf()
        for (a in 253 downTo 0) {
            mul(c, c, c)
            if (a != 2 && a != 4) mul(c, c, i)
        }
        c.copyInto(o)
    }

    private val C121665 = gf().also { it[0] = 0xDB41; it[1] = 1 }

    /** X25519(n, p): the shared point of scalar [n] and point [p]. */
    fun scalarMult(n: ByteArray, p: ByteArray): ByteArray {
        val z = n.copyOf(32)
        z[31] = ((n[31].toInt() and 127) or 64).toByte()
        z[0] = (z[0].toInt() and 248).toByte()
        val x = gf()
        unpack25519(x, p)
        val a = gf(); val b = x.copyOf(); val c = gf(); val d = gf(); val e = gf(); val f = gf()
        a[0] = 1; d[0] = 1
        for (i in 254 downTo 0) {
            val r = (z[i shr 3].toInt() shr (i and 7)) and 1
            sel25519(a, b, r); sel25519(c, d, r)
            add(e, a, c); sub(a, a, c); add(c, b, d); sub(b, b, d)
            mul(d, e, e); mul(f, a, a); mul(a, c, a); mul(c, b, e)
            add(e, a, c); sub(a, a, c); mul(b, a, a); sub(c, d, f)
            mul(a, c, C121665); add(a, a, d); mul(c, c, a); mul(a, d, f)
            mul(d, b, x); mul(b, e, e)
            sel25519(a, b, r); sel25519(c, d, r)
        }
        inv25519(c, c)
        mul(a, a, c)
        return ByteArray(32).also { pack25519(it, a) }
    }

    private val BASE = ByteArray(32).also { it[0] = 9 }

    fun publicKey(secret: ByteArray) = scalarMult(secret, BASE)

    // ---- Salsa20 / ChaCha20 -------------------------------------------------------------

    private val SIGMA = intArrayOf(0x61707865, 0x3320646e, 0x79622d32, 0x6b206574) // "expand 32-byte k"

    private fun le32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or ((b[o + 2].toInt() and 0xff) shl 16) or (b[o + 3].toInt() shl 24)

    private fun put32(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte(); b[o + 2] = (v shr 16).toByte(); b[o + 3] = (v shr 24).toByte()
    }

    private fun salsaRounds(x: IntArray) {
        fun r(a: Int, b: Int, c: Int, s: Int) { x[a] = x[a] xor Integer.rotateLeft(x[b] + x[c], s) }
        repeat(10) {
            r(4, 0, 12, 7); r(8, 4, 0, 9); r(12, 8, 4, 13); r(0, 12, 8, 18)
            r(9, 5, 1, 7); r(13, 9, 5, 9); r(1, 13, 9, 13); r(5, 1, 13, 18)
            r(14, 10, 6, 7); r(2, 14, 10, 9); r(6, 2, 14, 13); r(10, 6, 2, 18)
            r(3, 15, 11, 7); r(7, 3, 15, 9); r(11, 7, 3, 13); r(15, 11, 7, 18)
            r(1, 0, 3, 7); r(2, 1, 0, 9); r(3, 2, 1, 13); r(0, 3, 2, 18)
            r(6, 5, 4, 7); r(7, 6, 5, 9); r(4, 7, 6, 13); r(5, 4, 7, 18)
            r(11, 10, 9, 7); r(8, 11, 10, 9); r(9, 8, 11, 13); r(10, 9, 8, 18)
            r(12, 15, 14, 7); r(13, 12, 15, 9); r(14, 13, 12, 13); r(15, 14, 13, 18)
        }
    }

    /** Salsa20 state: [in16] is the nonce and counter (or HSalsa20's 16-byte input). */
    private fun salsaState(key: ByteArray, in16: ByteArray) = IntArray(16).also { s ->
        s[0] = SIGMA[0]; s[5] = SIGMA[1]; s[10] = SIGMA[2]; s[15] = SIGMA[3]
        for (i in 0 until 4) { s[1 + i] = le32(key, 4 * i); s[11 + i] = le32(key, 16 + 4 * i); s[6 + i] = le32(in16, 4 * i) }
    }

    fun hsalsa20(key: ByteArray, in16: ByteArray): ByteArray {
        val x = salsaState(key, in16)
        salsaRounds(x)
        return ByteArray(32).also { o -> intArrayOf(0, 5, 10, 15, 6, 7, 8, 9).forEachIndexed { i, w -> put32(o, 4 * i, x[w]) } }
    }

    private fun chachaRounds(x: IntArray) {
        fun q(a: Int, b: Int, c: Int, d: Int) {
            x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] xor x[a], 16)
            x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] xor x[c], 12)
            x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] xor x[a], 8)
            x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] xor x[c], 7)
        }
        repeat(10) {
            q(0, 4, 8, 12); q(1, 5, 9, 13); q(2, 6, 10, 14); q(3, 7, 11, 15)
            q(0, 5, 10, 15); q(1, 6, 11, 12); q(2, 7, 8, 13); q(3, 4, 9, 14)
        }
    }

    /** ChaCha20 state (DJB's: 64-bit counter and nonce in [in16]). */
    private fun chachaState(key: ByteArray, in16: ByteArray) = IntArray(16).also { s ->
        for (i in 0 until 4) { s[i] = SIGMA[i]; s[12 + i] = le32(in16, 4 * i) }
        for (i in 0 until 8) s[4 + i] = le32(key, 4 * i)
    }

    fun hchacha20(key: ByteArray, in16: ByteArray): ByteArray {
        val x = chachaState(key, in16)
        chachaRounds(x)
        return ByteArray(32).also { o -> intArrayOf(0, 1, 2, 3, 12, 13, 14, 15).forEachIndexed { i, w -> put32(o, 4 * i, x[w]) } }
    }

    /** [len] bytes of Salsa20 or ChaCha20 keystream with an 8-byte nonce, counter from 0. */
    private fun stream(chacha: Boolean, key: ByteArray, nonce8: ByteArray, len: Int): ByteArray {
        val out = ByteArray(len)
        val in16 = nonce8.copyOf(16)
        var counter = 0L
        var pos = 0
        while (pos < len) {
            val ctr = ByteArray(8).also { for (i in 0 until 8) it[i] = (counter shr (8 * i)).toByte() }
            // Salsa20: nonce then counter; ChaCha20: counter then nonce.
            val block = if (chacha) ctr + nonce8 else in16.copyOf(8) + ctr
            val j = if (chacha) chachaState(key, block) else salsaState(key, block)
            val x = j.copyOf()
            if (chacha) chachaRounds(x) else salsaRounds(x)
            val b = ByteArray(64)
            for (i in 0 until 16) put32(b, 4 * i, x[i] + j[i])
            val n = minOf(64, len - pos)
            b.copyInto(out, pos, 0, n)
            pos += n
            counter++
        }
        return out
    }

    // ---- Poly1305 -----------------------------------------------------------------------

    private val P1305 = BigInteger.ONE.shiftLeft(130).subtract(BigInteger.valueOf(5))
    private val CLAMP = BigInteger("0ffffffc0ffffffc0ffffffc0fffffff", 16)
    private val MASK128 = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE)

    private fun leInt(b: ByteArray, from: Int = 0, to: Int = b.size) = BigInteger(1, b.copyOfRange(from, to).reversedArray())

    fun poly1305(msg: ByteArray, key: ByteArray): ByteArray {
        val r = leInt(key, 0, 16).and(CLAMP)
        val s = leInt(key, 16, 32)
        var acc = BigInteger.ZERO
        var i = 0
        while (i < msg.size) {
            val end = minOf(i + 16, msg.size)
            val n = leInt(msg.copyOfRange(i, end) + byteArrayOf(1))
            acc = acc.add(n).multiply(r).mod(P1305)
            i = end
        }
        val tag = acc.add(s).and(MASK128).toByteArray().reversedArray()
        return tag.copyOf(16)
    }

    // ---- Boxes --------------------------------------------------------------------------

    enum class Cipher { XSALSA20_POLY1305, XCHACHA20_POLY1305 }

    /** The precomputed key of a box between our [secret] and their [public] key (crypto_box_beforenm). */
    fun sharedKey(cipher: Cipher, secret: ByteArray, public: ByteArray): ByteArray {
        val s = scalarMult(secret, public)
        return if (cipher == Cipher.XSALSA20_POLY1305) hsalsa20(s, ByteArray(16)) else hchacha20(s, ByteArray(16))
    }

    private fun keystream(cipher: Cipher, key: ByteArray, nonce24: ByteArray, len: Int): ByteArray {
        val chacha = cipher == Cipher.XCHACHA20_POLY1305
        val sub = if (chacha) hchacha20(key, nonce24.copyOf(16)) else hsalsa20(key, nonce24.copyOf(16))
        return stream(chacha, sub, nonce24.copyOfRange(16, 24), len)
    }

    /** crypto_box_easy_afternm: the 16-byte tag, then the ciphertext. */
    fun seal(cipher: Cipher, key: ByteArray, nonce24: ByteArray, msg: ByteArray): ByteArray {
        val ks = keystream(cipher, key, nonce24, 32 + msg.size)
        val c = ByteArray(msg.size) { (msg[it].toInt() xor ks[32 + it].toInt()).toByte() }
        return poly1305(c, ks.copyOf(32)) + c
    }

    /** The message, or null if the tag does not match. */
    fun open(cipher: Cipher, key: ByteArray, nonce24: ByteArray, box: ByteArray): ByteArray? {
        if (box.size < 16) return null
        val c = box.copyOfRange(16, box.size)
        val ks = keystream(cipher, key, nonce24, 32 + c.size)
        if (!MessageDigest.isEqual(poly1305(c, ks.copyOf(32)), box.copyOf(16))) return null
        return ByteArray(c.size) { (c[it].toInt() xor ks[32 + it].toInt()).toByte() }
    }

    // ---- Ed25519 (verification only) ----------------------------------------------------

    private val P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val L = BigInteger.ONE.shiftLeft(252).add(BigInteger("27742317777372353535851937790883648493"))
    private val D = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
    private val SQRT_M1 = BigInteger.valueOf(2).modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)
    private val TWO = BigInteger.valueOf(2) // BigInteger.TWO needs Android 13

    private class Point(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    private fun pointAdd(p: Point, q: Point): Point {
        val a = p.y.subtract(p.x).multiply(q.y.subtract(q.x)).mod(P)
        val b = p.y.add(p.x).multiply(q.y.add(q.x)).mod(P)
        val c = TWO.multiply(p.t).multiply(q.t).multiply(D).mod(P)
        val d = TWO.multiply(p.z).multiply(q.z).mod(P)
        val e = b.subtract(a); val f = d.subtract(c); val g = d.add(c); val h = b.add(a)
        return Point(e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P))
    }

    private fun pointMul(s: BigInteger, point: Point): Point {
        var q = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
        var p = point
        for (i in 0 until s.bitLength()) {
            if (s.testBit(i)) q = pointAdd(q, p)
            p = pointAdd(p, p)
        }
        return q
    }

    private fun pointEqual(p: Point, q: Point) =
        p.x.multiply(q.z).subtract(q.x.multiply(p.z)).mod(P).signum() == 0 &&
            p.y.multiply(q.z).subtract(q.y.multiply(p.z)).mod(P).signum() == 0

    private fun recoverX(y: BigInteger, sign: Int): BigInteger? {
        if (y >= P) return null
        val x2 = y.multiply(y).subtract(BigInteger.ONE).multiply(D.multiply(y).multiply(y).add(BigInteger.ONE).modInverse(P)).mod(P)
        if (x2.signum() == 0) return if (sign != 0) null else BigInteger.ZERO
        var x = x2.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P)
        if (x.multiply(x).subtract(x2).mod(P).signum() != 0) x = x.multiply(SQRT_M1).mod(P)
        if (x.multiply(x).subtract(x2).mod(P).signum() != 0) return null
        if (x.testBit(0) != (sign == 1)) x = P.subtract(x)
        return x
    }

    private fun decompress(b: ByteArray): Point? {
        if (b.size != 32) return null
        var y = leInt(b)
        val sign = if (y.testBit(255)) 1 else 0
        y = y.clearBit(255)
        val x = recoverX(y, sign) ?: return null
        return Point(x, y, BigInteger.ONE, x.multiply(y).mod(P))
    }

    private val G: Point by lazy {
        val y = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P)
        val x = recoverX(y, 0)!!
        Point(x, y, BigInteger.ONE, x.multiply(y).mod(P))
    }

    /** Whether [signature] (64 bytes) is [public]'s Ed25519 signature of [msg]. */
    fun ed25519Verify(public: ByteArray, msg: ByteArray, signature: ByteArray): Boolean {
        if (signature.size != 64) return false
        val a = decompress(public) ?: return false
        val rBytes = signature.copyOf(32)
        val r = decompress(rBytes) ?: return false
        val s = leInt(signature, 32, 64)
        if (s >= L) return false
        val sha = MessageDigest.getInstance("SHA-512")
        sha.update(rBytes); sha.update(public); sha.update(msg)
        val h = leInt(sha.digest()).mod(L)
        return pointEqual(pointMul(s, G), pointAdd(r, pointMul(h, a)))
    }

    // ---- BLAKE2b-512 --------------------------------------------------------------------

    private val B2_IV = longArrayOf(
        0x6a09e667f3bcc908, -0x4498517a7b3558c5, 0x3c6ef372fe94f82b, -0x5ab00ac5a0e2c90f,
        0x510e527fade682d1, -0x64fa9773d4c193e1, 0x1f83d9abfb41bd6b, 0x5be0cd19137e2179,
    )
    private val B2_SIGMA = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
        intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
        intArrayOf(11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4),
        intArrayOf(7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8),
        intArrayOf(9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13),
        intArrayOf(2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9),
        intArrayOf(12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11),
        intArrayOf(13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10),
        intArrayOf(6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5),
        intArrayOf(10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0),
    )

    fun blake2b512(data: ByteArray): ByteArray {
        val h = B2_IV.copyOf()
        h[0] = h[0] xor 0x01010040
        val blocks = maxOf(1, (data.size + 127) / 128)
        for (blk in 0 until blocks) {
            val last = blk == blocks - 1
            val chunk = data.copyOfRange(blk * 128, minOf(data.size, blk * 128 + 128)).copyOf(128)
            val m = LongArray(16) { i -> (0 until 8).fold(0L) { acc, k -> acc or ((chunk[8 * i + k].toLong() and 0xff) shl (8 * k)) } }
            val t = if (last) data.size.toLong() else (blk + 1) * 128L
            val v = h.copyOf() + B2_IV.copyOf()
            v[12] = v[12] xor t
            if (last) v[14] = v[14].inv()
            fun g(a: Int, b: Int, c: Int, d: Int, x: Long, y: Long) {
                v[a] = v[a] + v[b] + x; v[d] = java.lang.Long.rotateRight(v[d] xor v[a], 32)
                v[c] = v[c] + v[d]; v[b] = java.lang.Long.rotateRight(v[b] xor v[c], 24)
                v[a] = v[a] + v[b] + y; v[d] = java.lang.Long.rotateRight(v[d] xor v[a], 16)
                v[c] = v[c] + v[d]; v[b] = java.lang.Long.rotateRight(v[b] xor v[c], 63)
            }
            for (round in 0 until 12) {
                val s = B2_SIGMA[round % 10]
                g(0, 4, 8, 12, m[s[0]], m[s[1]]); g(1, 5, 9, 13, m[s[2]], m[s[3]])
                g(2, 6, 10, 14, m[s[4]], m[s[5]]); g(3, 7, 11, 15, m[s[6]], m[s[7]])
                g(0, 5, 10, 15, m[s[8]], m[s[9]]); g(1, 6, 11, 12, m[s[10]], m[s[11]])
                g(2, 7, 8, 13, m[s[12]], m[s[13]]); g(3, 4, 9, 14, m[s[14]], m[s[15]])
            }
            for (i in 0 until 8) h[i] = h[i] xor v[i] xor v[i + 8]
        }
        return ByteArray(64) { (h[it / 8] shr (8 * (it % 8))).toByte() }
    }
}
