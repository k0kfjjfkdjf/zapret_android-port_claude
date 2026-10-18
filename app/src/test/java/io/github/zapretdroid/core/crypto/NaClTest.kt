package io.github.zapretdroid.core.crypto

import io.github.zapretdroid.core.crypto.NaCl.Cipher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Known vectors: RFC 7748, NaCl's core tests, the XChaCha20 draft, RFC 8439, RFC 8032, BLAKE2 (hashlib). */
class NaClTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test
    fun x25519() {
        val alice = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bob = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", NaCl.publicKey(alice).hex())
        assertEquals("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f", NaCl.publicKey(bob).hex())
        val shared = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
        assertEquals(shared, NaCl.scalarMult(alice, NaCl.publicKey(bob)).hex())
        assertEquals(shared, NaCl.scalarMult(bob, NaCl.publicKey(alice)).hex())
    }

    @Test
    fun hsalsaAndXsalsa() {
        val shared = hex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")
        val first = NaCl.hsalsa20(shared, ByteArray(16))
        assertEquals("1b27556473e985d462cd51197a9a46c76009549eac6474f206c4ee0844f68389", first.hex())
        assertEquals("dc908dda0b9344a953629b733820778880f3ceb421bb61b91cbd4c3e66256ce4", NaCl.hsalsa20(first, hex("69696ee955b62b73cd62bda875fc73d6")).hex())
        // NaCl's box test: the tag of its 131-byte message starts the box; sealing an empty message checks the first keystream block.
        val key = NaCl.sharedKey(Cipher.XSALSA20_POLY1305, hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"),
            hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"))
        assertArrayEquals(first, key)
    }

    @Test
    fun hchacha() {
        assertEquals(
            "82413b4227b27bfed30e42508a877d73a0f9e4d58a74a853c12ec41326d3ecdc",
            NaCl.hchacha20(ByteArray(32) { it.toByte() }, hex("000000090000004a0000000031415927")).hex(),
        )
    }

    @Test
    fun poly1305() {
        val key = hex("85d6be7857556d337f4452fe42d506a80103808afb0db2fd4abff6af4149f51b")
        assertEquals("a8061dc1305136c6c22b8baf0c0127a9", NaCl.poly1305("Cryptographic Forum Research Group".toByteArray(), key).hex())
    }

    @Test
    fun xchachaBoxMatchesReference() {
        // Python: cryptography's X25519, ChaCha20 and Poly1305 with HChaCha20 (scratch ref.py).
        val sk = ByteArray(32) { it.toByte() }
        val pk = hex("7d9c24316539825c1896e57f28197746793ce60cbee3ad47da9d07b85fa55e2a")
        val key = NaCl.sharedKey(Cipher.XCHACHA20_POLY1305, sk, pk)
        assertEquals("9f2ef3c837bee3c6bbed0bb27beaa6fd74fde60be89c99615114bc4854d514d1", key.hex())
        val nonce = ByteArray(24) { (50 + it).toByte() }
        val msg = "DNSCrypt test message, longer than one block of sixty-four bytes........".toByteArray()
        val box = NaCl.seal(Cipher.XCHACHA20_POLY1305, key, nonce, msg)
        assertEquals(
            "271c730801ff7c8751cbcd51ed07aee1ff7c2009afbd17e11714cd671c79a6dd1684d8b64907122b39275b2c221ad957cae5124e6bfe8871fedd4d45fa54c976999d8d3b4b362bf7fe5355dc96159ba683af6dfa6d79c40e",
            box.hex(),
        )
        assertArrayEquals(msg, NaCl.open(Cipher.XCHACHA20_POLY1305, key, nonce, box))
        box[20] = (box[20].toInt() xor 1).toByte()
        assertNull(NaCl.open(Cipher.XCHACHA20_POLY1305, key, nonce, box))
    }

    @Test
    fun boxesRoundTrip() {
        for (cipher in Cipher.entries) {
            val a = NaCl.randomBytes(32)
            val b = NaCl.randomBytes(32)
            val k1 = NaCl.sharedKey(cipher, a, NaCl.publicKey(b))
            val k2 = NaCl.sharedKey(cipher, b, NaCl.publicKey(a))
            assertArrayEquals(k1, k2)
            for (len in listOf(0, 1, 31, 32, 33, 64, 300)) {
                val m = NaCl.randomBytes(len)
                val n = NaCl.randomBytes(24)
                assertArrayEquals(m, NaCl.open(cipher, k2, n, NaCl.seal(cipher, k1, n, m)))
            }
        }
    }

    @Test
    fun ed25519() {
        val pk = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val sig = hex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")
        assertTrue(NaCl.ed25519Verify(pk, ByteArray(0), sig))
        assertFalse(NaCl.ed25519Verify(pk, byteArrayOf(0), sig))
        // RFC 8032 test 2: one byte 0x72.
        assertTrue(
            NaCl.ed25519Verify(
                hex("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"), byteArrayOf(0x72),
                hex("92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"),
            ),
        )
    }

    @Test
    fun blake2b() {
        assertEquals(
            "ba80a53f981c4d0d6a2797b69f12f6e94c212f14685ac4b74b12bb6fdbffa2d17d87c5392aab792dc252d5de4533cc9518d38aa8dbf1925ab92386edd4009923",
            NaCl.blake2b512("abc".toByteArray()).hex(),
        )
        assertEquals(
            "786a02f742015903c6c6fd852552d272912f4740e15847618a86e217f71f5419d25e1031afee585313896444934eb04b903a685b1448b755d56f701afe9be2ce",
            NaCl.blake2b512(ByteArray(0)).hex(),
        )
        val data = ByteArray(768) { it.toByte() } // 6 full blocks
        assertEquals(
            "323e97a7a859ee63c9013debb0ca995811e73117a2f574723416e596ebc184e37a59b66d2f597df4a7c1b0d1d41a1a7f28774f46a6864d56c57b9d6c5f7302fb",
            NaCl.blake2b512(data).hex(),
        )
    }
}
