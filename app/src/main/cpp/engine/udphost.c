/* ZapretDroid: host names for UDP flows and TCP without SNI, see udphost.h. */

#include "udphost.h"
#include "tpws.h"

#include <string.h>

/* --- SHA-256, HMAC, HKDF-Expand-Label (FIPS 180-4, RFC 2104, RFC 8446 7.1) ------------------ */

static const uint32_t SHA_K[64] = {
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
};

struct sha256 {
    uint32_t h[8];
    uint8_t buf[64];
    uint64_t total;
    size_t n;
};

#define ROR(x, r) (((x) >> (r)) | ((x) << (32 - (r))))

static void sha256_block(struct sha256 *s, const uint8_t *p)
{
    uint32_t w[64];
    for (int i = 0; i < 16; i++) {
        w[i] = (uint32_t) p[4 * i] << 24 | (uint32_t) p[4 * i + 1] << 16 | (uint32_t) p[4 * i + 2] << 8 | p[4 * i + 3];
    }
    for (int i = 16; i < 64; i++) {
        uint32_t s0 = ROR(w[i - 15], 7) ^ ROR(w[i - 15], 18) ^ (w[i - 15] >> 3);
        uint32_t s1 = ROR(w[i - 2], 17) ^ ROR(w[i - 2], 19) ^ (w[i - 2] >> 10);
        w[i] = w[i - 16] + s0 + w[i - 7] + s1;
    }
    uint32_t a = s->h[0], b = s->h[1], c = s->h[2], d = s->h[3];
    uint32_t e = s->h[4], f = s->h[5], g = s->h[6], h = s->h[7];
    for (int i = 0; i < 64; i++) {
        uint32_t t1 = h + (ROR(e, 6) ^ ROR(e, 11) ^ ROR(e, 25)) + ((e & f) ^ (~e & g)) + SHA_K[i] + w[i];
        uint32_t t2 = (ROR(a, 2) ^ ROR(a, 13) ^ ROR(a, 22)) + ((a & b) ^ (a & c) ^ (b & c));
        h = g; g = f; f = e; e = d + t1;
        d = c; c = b; b = a; a = t1 + t2;
    }
    s->h[0] += a; s->h[1] += b; s->h[2] += c; s->h[3] += d;
    s->h[4] += e; s->h[5] += f; s->h[6] += g; s->h[7] += h;
}

static void sha256_init(struct sha256 *s)
{
    static const uint32_t iv[8] = {
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
    };
    memcpy(s->h, iv, sizeof(iv));
    s->total = 0;
    s->n = 0;
}

static void sha256_update(struct sha256 *s, const uint8_t *p, size_t len)
{
    s->total += len;
    while (len) {
        size_t k = 64 - s->n < len ? 64 - s->n : len;
        memcpy(s->buf + s->n, p, k);
        s->n += k;
        p += k;
        len -= k;
        if (s->n == 64) {
            sha256_block(s, s->buf);
            s->n = 0;
        }
    }
}

static void sha256_final(struct sha256 *s, uint8_t out[32])
{
    uint64_t bits = s->total * 8;
    uint8_t pad = 0x80, zero = 0;
    sha256_update(s, &pad, 1);
    while (s->n != 56) {
        sha256_update(s, &zero, 1);
    }
    uint8_t len[8];
    for (int i = 0; i < 8; i++) {
        len[i] = (uint8_t) (bits >> (56 - 8 * i));
    }
    sha256_update(s, len, 8);
    for (int i = 0; i < 8; i++) {
        out[4 * i] = (uint8_t) (s->h[i] >> 24);
        out[4 * i + 1] = (uint8_t) (s->h[i] >> 16);
        out[4 * i + 2] = (uint8_t) (s->h[i] >> 8);
        out[4 * i + 3] = (uint8_t) s->h[i];
    }
}

static void hmac_sha256(const uint8_t *key, size_t klen, const uint8_t *msg, size_t mlen, uint8_t out[32])
{
    uint8_t k[64] = {0}, pad[64], inner[32];
    if (klen > 64) {
        struct sha256 s;
        sha256_init(&s);
        sha256_update(&s, key, klen);
        sha256_final(&s, k);
    }
    else {
        memcpy(k, key, klen);
    }
    struct sha256 s;
    for (int i = 0; i < 64; i++) pad[i] = k[i] ^ 0x36;
    sha256_init(&s);
    sha256_update(&s, pad, 64);
    sha256_update(&s, msg, mlen);
    sha256_final(&s, inner);
    for (int i = 0; i < 64; i++) pad[i] = k[i] ^ 0x5c;
    sha256_init(&s);
    sha256_update(&s, pad, 64);
    sha256_update(&s, inner, 32);
    sha256_final(&s, out);
}

/* HKDF-Expand-Label with an empty context, for outputs of up to 32 bytes (one HMAC block). */
static void expand_label(const uint8_t secret[32], const char *label, uint8_t *out, size_t len)
{
    uint8_t info[2 + 1 + 6 + 32 + 1 + 1];
    size_t llen = strlen(label), n = 0;
    info[n++] = 0;
    info[n++] = (uint8_t) len;
    info[n++] = (uint8_t) (6 + llen);
    memcpy(info + n, "tls13 ", 6);
    n += 6;
    memcpy(info + n, label, llen);
    n += llen;
    info[n++] = 0; // context
    info[n++] = 1; // T(1)
    uint8_t t[32];
    hmac_sha256(secret, 32, info, n, t);
    memcpy(out, t, len);
}

/* --- AES-128 encryption (FIPS 197): header protection and GCM's counter mode ---------------- */

static const uint8_t SBOX[256] = {
    0x63, 0x7c, 0x77, 0x7b, 0xf2, 0x6b, 0x6f, 0xc5, 0x30, 0x01, 0x67, 0x2b, 0xfe, 0xd7, 0xab, 0x76,
    0xca, 0x82, 0xc9, 0x7d, 0xfa, 0x59, 0x47, 0xf0, 0xad, 0xd4, 0xa2, 0xaf, 0x9c, 0xa4, 0x72, 0xc0,
    0xb7, 0xfd, 0x93, 0x26, 0x36, 0x3f, 0xf7, 0xcc, 0x34, 0xa5, 0xe5, 0xf1, 0x71, 0xd8, 0x31, 0x15,
    0x04, 0xc7, 0x23, 0xc3, 0x18, 0x96, 0x05, 0x9a, 0x07, 0x12, 0x80, 0xe2, 0xeb, 0x27, 0xb2, 0x75,
    0x09, 0x83, 0x2c, 0x1a, 0x1b, 0x6e, 0x5a, 0xa0, 0x52, 0x3b, 0xd6, 0xb3, 0x29, 0xe3, 0x2f, 0x84,
    0x53, 0xd1, 0x00, 0xed, 0x20, 0xfc, 0xb1, 0x5b, 0x6a, 0xcb, 0xbe, 0x39, 0x4a, 0x4c, 0x58, 0xcf,
    0xd0, 0xef, 0xaa, 0xfb, 0x43, 0x4d, 0x33, 0x85, 0x45, 0xf9, 0x02, 0x7f, 0x50, 0x3c, 0x9f, 0xa8,
    0x51, 0xa3, 0x40, 0x8f, 0x92, 0x9d, 0x38, 0xf5, 0xbc, 0xb6, 0xda, 0x21, 0x10, 0xff, 0xf3, 0xd2,
    0xcd, 0x0c, 0x13, 0xec, 0x5f, 0x97, 0x44, 0x17, 0xc4, 0xa7, 0x7e, 0x3d, 0x64, 0x5d, 0x19, 0x73,
    0x60, 0x81, 0x4f, 0xdc, 0x22, 0x2a, 0x90, 0x88, 0x46, 0xee, 0xb8, 0x14, 0xde, 0x5e, 0x0b, 0xdb,
    0xe0, 0x32, 0x3a, 0x0a, 0x49, 0x06, 0x24, 0x5c, 0xc2, 0xd3, 0xac, 0x62, 0x91, 0x95, 0xe4, 0x79,
    0xe7, 0xc8, 0x37, 0x6d, 0x8d, 0xd5, 0x4e, 0xa9, 0x6c, 0x56, 0xf4, 0xea, 0x65, 0x7a, 0xae, 0x08,
    0xba, 0x78, 0x25, 0x2e, 0x1c, 0xa6, 0xb4, 0xc6, 0xe8, 0xdd, 0x74, 0x1f, 0x4b, 0xbd, 0x8b, 0x8a,
    0x70, 0x3e, 0xb5, 0x66, 0x48, 0x03, 0xf6, 0x0e, 0x61, 0x35, 0x57, 0xb9, 0x86, 0xc1, 0x1d, 0x9e,
    0xe1, 0xf8, 0x98, 0x11, 0x69, 0xd9, 0x8e, 0x94, 0x9b, 0x1e, 0x87, 0xe9, 0xce, 0x55, 0x28, 0xdf,
    0x8c, 0xa1, 0x89, 0x0d, 0xbf, 0xe6, 0x42, 0x68, 0x41, 0x99, 0x2d, 0x0f, 0xb0, 0x54, 0xbb, 0x16,
};

static void aes128_expand(const uint8_t key[16], uint8_t rk[176])
{
    static const uint8_t rcon[10] = {0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1b, 0x36};
    memcpy(rk, key, 16);
    for (int i = 4; i < 44; i++) {
        uint8_t t[4];
        memcpy(t, rk + 4 * (i - 1), 4);
        if (i % 4 == 0) {
            uint8_t x = t[0];
            t[0] = SBOX[t[1]] ^ rcon[i / 4 - 1];
            t[1] = SBOX[t[2]];
            t[2] = SBOX[t[3]];
            t[3] = SBOX[x];
        }
        for (int j = 0; j < 4; j++) {
            rk[4 * i + j] = rk[4 * (i - 4) + j] ^ t[j];
        }
    }
}

static uint8_t xtime(uint8_t x)
{
    return (uint8_t) ((x << 1) ^ ((x & 0x80) ? 0x1b : 0));
}

static void aes128_encrypt(const uint8_t rk[176], const uint8_t in[16], uint8_t out[16])
{
    uint8_t s[16];
    for (int i = 0; i < 16; i++) s[i] = in[i] ^ rk[i];
    for (int round = 1; round <= 10; round++) {
        uint8_t t[16];
        // SubBytes and ShiftRows (column-major state)
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 4; r++) {
                t[4 * c + r] = SBOX[s[4 * ((c + r) % 4) + r]];
            }
        }
        if (round < 10) {
            for (int c = 0; c < 4; c++) {
                uint8_t *col = t + 4 * c;
                uint8_t a0 = col[0], a1 = col[1], a2 = col[2], a3 = col[3], all = a0 ^ a1 ^ a2 ^ a3;
                col[0] ^= all ^ xtime(a0 ^ a1);
                col[1] ^= all ^ xtime(a1 ^ a2);
                col[2] ^= all ^ xtime(a2 ^ a3);
                col[3] ^= all ^ xtime(a3 ^ a0);
            }
        }
        for (int i = 0; i < 16; i++) s[i] = t[i] ^ rk[16 * round + i];
    }
    memcpy(out, s, 16);
}

/* --- QUIC Initial (RFC 9000 17.2.2, RFC 9001 5, RFC 9369 for v2) ----------------------------- */

static bool read_varint(const uint8_t *d, size_t len, size_t *pos, uint64_t *out)
{
    if (*pos >= len) return 0;
    size_t n = (size_t) 1 << (d[*pos] >> 6);
    if (*pos + n > len) return 0;
    uint64_t v = d[*pos] & 0x3f;
    for (size_t i = 1; i < n; i++) v = v << 8 | d[*pos + i];
    *pos += n;
    *out = v;
    return 1;
}

static void put_crypto(struct quic_crypto *qc, uint64_t off, const uint8_t *data, uint64_t len)
{
    for (uint64_t i = 0; i < len && off + i < QUIC_CRYPTO_MAX; i++) {
        qc->data[off + i] = data[i];
        qc->have[off + i] = 1;
    }
}

bool quic_add_initial(struct quic_crypto *qc, const uint8_t *pkt, size_t len)
{
    static const uint8_t salt_v1[20] = {
        0x38, 0x76, 0x2c, 0xf7, 0xf5, 0x59, 0x34, 0xb3, 0x4d, 0x17,
        0x9a, 0xe6, 0xa4, 0xc8, 0x0c, 0xad, 0xcc, 0xbb, 0x7f, 0x0a,
    };
    static const uint8_t salt_v2[20] = {
        0x0d, 0xed, 0xe3, 0xde, 0xf7, 0x00, 0xa6, 0xdb, 0x81, 0x93,
        0x81, 0xbe, 0x6e, 0x26, 0x9d, 0xcb, 0xf9, 0xbd, 0x2e, 0xd9,
    };
    if (len < 7 || (pkt[0] & 0xc0) != 0xc0) return 0;
    uint32_t ver = (uint32_t) pkt[1] << 24 | (uint32_t) pkt[2] << 16 | (uint32_t) pkt[3] << 8 | pkt[4];
    bool v2 = ver == 0x6b3343cf;
    if (ver != 1 && !v2) return 0;
    if (((pkt[0] >> 4) & 3) != (v2 ? 1 : 0)) return 0;

    size_t pos = 5;
    uint8_t dcid_len = pkt[pos++];
    if (dcid_len > 20 || pos + dcid_len + 1 > len) return 0;
    const uint8_t *dcid = pkt + pos;
    if (qc->dcid_len && (qc->dcid_len != dcid_len || memcmp(qc->dcid, dcid, dcid_len))) return 0;
    pos += dcid_len;
    uint8_t scid_len = pkt[pos++];
    if (scid_len > 20 || pos + scid_len > len) return 0;
    pos += scid_len;
    uint64_t token_len, length;
    if (!read_varint(pkt, len, &pos, &token_len) || token_len > len - pos) return 0;
    pos += token_len;
    if (!read_varint(pkt, len, &pos, &length) || length > len - pos || length < 20) return 0;
    size_t pn_off = pos;
    if (pn_off + 4 + 16 > len) return 0;

    uint8_t initial[32], client[32], key[16], iv[12], hp[16], rk[176], mask[16];
    hmac_sha256(v2 ? salt_v2 : salt_v1, 20, dcid, dcid_len, initial);
    expand_label(initial, "client in", client, 32);
    expand_label(client, v2 ? "quicv2 key" : "quic key", key, 16);
    expand_label(client, v2 ? "quicv2 iv" : "quic iv", iv, 12);
    expand_label(client, v2 ? "quicv2 hp" : "quic hp", hp, 16);

    aes128_expand(hp, rk);
    aes128_encrypt(rk, pkt + pn_off + 4, mask);
    uint8_t first = pkt[0] ^ (mask[0] & 0x0f);
    size_t pn_len = (first & 3) + 1;
    uint64_t pn = 0;
    for (size_t i = 0; i < pn_len; i++) {
        pn = pn << 8 | (uint8_t) (pkt[pn_off + i] ^ mask[1 + i]);
    }
    if (length < pn_len + 16) return 0;
    size_t clen = length - pn_len - 16;
    const uint8_t *ct = pkt + pn_off + pn_len;

    // AES-GCM without checking the tag: counter mode from J0 + 1, J0 = nonce || 1.
    uint8_t plain[1500];
    if (clen > sizeof(plain)) return 0;
    uint8_t ctr[16], stream[16];
    memcpy(ctr, iv, 12);
    for (int i = 0; i < 8; i++) ctr[11 - i] ^= (uint8_t) (pn >> (8 * i));
    aes128_expand(key, rk);
    uint32_t counter = 2;
    for (size_t off = 0; off < clen; off += 16) {
        ctr[12] = (uint8_t) (counter >> 24);
        ctr[13] = (uint8_t) (counter >> 16);
        ctr[14] = (uint8_t) (counter >> 8);
        ctr[15] = (uint8_t) counter;
        counter++;
        aes128_encrypt(rk, ctr, stream);
        for (size_t i = 0; i < 16 && off + i < clen; i++) plain[off + i] = ct[off + i] ^ stream[i];
    }

    // Frames: only those a client's Initial may carry.
    pos = 0;
    bool any = 0;
    while (pos < clen) {
        uint64_t type, a, b, c;
        if (!read_varint(plain, clen, &pos, &type)) break;
        if (type == 0x00 || type == 0x01) continue; // PADDING, PING
        if (type == 0x02 || type == 0x03) { // ACK
            uint64_t count;
            if (!read_varint(plain, clen, &pos, &a) || !read_varint(plain, clen, &pos, &b)
                    || !read_varint(plain, clen, &pos, &count) || !read_varint(plain, clen, &pos, &c)) break;
            bool ok = 1;
            for (uint64_t i = 0; i < count && ok; i++) {
                ok = read_varint(plain, clen, &pos, &a) && read_varint(plain, clen, &pos, &b);
            }
            for (int i = 0; type == 0x03 && i < 3 && ok; i++) {
                ok = read_varint(plain, clen, &pos, &a);
            }
            if (!ok) break;
            continue;
        }
        if (type == 0x06) { // CRYPTO
            if (!read_varint(plain, clen, &pos, &a) || !read_varint(plain, clen, &pos, &b) || b > clen - pos) break;
            put_crypto(qc, a, plain + pos, b);
            pos += b;
            any = 1;
            if (!qc->dcid_len) {
                memcpy(qc->dcid, dcid, dcid_len);
                qc->dcid_len = dcid_len;
            }
            continue;
        }
        break; // CONNECTION_CLOSE or not a client Initial
    }
    return any;
}

/* --- AES-128-GCM (NIST SP 800-38D): counter mode and the tag ------------------------------- */

/* x = x * h in GF(2^128), GCM's bit order. */
static void gf128_mul(uint8_t x[16], const uint8_t h[16])
{
    uint8_t z[16] = { 0 }, v[16];
    memcpy(v, h, 16);
    for (int i = 0; i < 128; i++) {
        if ((x[i / 8] >> (7 - i % 8)) & 1) {
            for (int j = 0; j < 16; j++) z[j] ^= v[j];
        }
        uint8_t lsb = v[15] & 1;
        for (int j = 15; j > 0; j--) v[j] = (uint8_t) (v[j] >> 1 | v[j - 1] << 7);
        v[0] >>= 1;
        if (lsb) v[0] ^= 0xe1;
    }
    memcpy(x, z, 16);
}

static void ghash_update(uint8_t y[16], const uint8_t h[16], const uint8_t *d, size_t len)
{
    for (size_t off = 0; off < len; off += 16) {
        for (size_t i = 0; i < 16 && off + i < len; i++) y[i] ^= d[off + i];
        gf128_mul(y, h);
    }
}

/* Counter mode from J0 + 1 (J0 = nonce || 1), as GCM encrypts and decrypts. */
static void gcm_ctr(const uint8_t rk[176], const uint8_t nonce[12], const uint8_t *in, uint8_t *out, size_t len)
{
    uint8_t ctr[16], stream[16];
    memcpy(ctr, nonce, 12);
    uint32_t counter = 2;
    for (size_t off = 0; off < len; off += 16) {
        ctr[12] = (uint8_t) (counter >> 24);
        ctr[13] = (uint8_t) (counter >> 16);
        ctr[14] = (uint8_t) (counter >> 8);
        ctr[15] = (uint8_t) counter;
        counter++;
        aes128_encrypt(rk, ctr, stream);
        for (size_t i = 0; i < 16 && off + i < len; i++) out[off + i] = in[off + i] ^ stream[i];
    }
}

static void gcm_tag(const uint8_t rk[176], const uint8_t nonce[12], const uint8_t *aad, size_t alen,
        const uint8_t *ct, size_t clen, uint8_t tag[16])
{
    uint8_t h[16] = { 0 }, y[16] = { 0 }, j0[16], ek[16], lens[16];
    aes128_encrypt(rk, h, h);
    ghash_update(y, h, aad, alen);
    ghash_update(y, h, ct, clen);
    uint64_t abits = (uint64_t) alen * 8, cbits = (uint64_t) clen * 8;
    for (int i = 0; i < 8; i++) {
        lens[i] = (uint8_t) (abits >> (56 - 8 * i));
        lens[8 + i] = (uint8_t) (cbits >> (56 - 8 * i));
    }
    ghash_update(y, h, lens, 16);
    memcpy(j0, nonce, 12);
    j0[12] = 0; j0[13] = 0; j0[14] = 0; j0[15] = 1;
    aes128_encrypt(rk, j0, ek);
    for (int i = 0; i < 16; i++) tag[i] = y[i] ^ ek[i];
}

static size_t varint_size(uint64_t v)
{
    return v < 64 ? 1 : v < 16384 ? 2 : v < 1073741824 ? 4 : 8;
}

static size_t put_varint(uint8_t *out, uint64_t v)
{
    size_t n = varint_size(v);
    uint8_t prefix = n == 1 ? 0 : n == 2 ? 0x40 : n == 4 ? 0x80 : 0xc0;
    for (size_t i = 0; i < n; i++) out[i] = (uint8_t) (v >> (8 * (n - 1 - i)));
    out[0] |= prefix;
    return n;
}

/* --- ZapretDroid --quic-split: a client Initial with its ClientHello in two CRYPTO frames --- */

int quic_split_initial(uint8_t *pkt, size_t *plen, size_t cap, int marker, long offset)
{
    size_t len = *plen;
    static const uint8_t salt_v1[20] = {
        0x38, 0x76, 0x2c, 0xf7, 0xf5, 0x59, 0x34, 0xb3, 0x4d, 0x17,
        0x9a, 0xe6, 0xa4, 0xc8, 0x0c, 0xad, 0xcc, 0xbb, 0x7f, 0x0a,
    };
    static const uint8_t salt_v2[20] = {
        0x0d, 0xed, 0xe3, 0xde, 0xf7, 0x00, 0xa6, 0xdb, 0x81, 0x93,
        0x81, 0xbe, 0x6e, 0x26, 0x9d, 0xcb, 0xf9, 0xbd, 0x2e, 0xd9,
    };
    if (len < 7 || (pkt[0] & 0xc0) != 0xc0) return 0;
    uint32_t ver = (uint32_t) pkt[1] << 24 | (uint32_t) pkt[2] << 16 | (uint32_t) pkt[3] << 8 | pkt[4];
    bool v2 = ver == 0x6b3343cf;
    if (ver != 1 && !v2) return 0;
    if (((pkt[0] >> 4) & 3) != (v2 ? 1 : 0)) return 0;

    size_t pos = 5;
    uint8_t dcid_len = pkt[pos++];
    if (dcid_len > 20 || pos + dcid_len + 1 > len) return 0;
    const uint8_t *dcid = pkt + pos;
    pos += dcid_len;
    uint8_t scid_len = pkt[pos++];
    if (scid_len > 20 || pos + scid_len > len) return 0;
    pos += scid_len;
    uint64_t token_len, length;
    if (!read_varint(pkt, len, &pos, &token_len) || token_len > len - pos) return 0;
    pos += token_len;
    size_t lpos = pos;
    if (!read_varint(pkt, len, &pos, &length) || length > len - pos || length < 20) return 0;
    size_t pn_off = pos;
    size_t lsz = pn_off - lpos; // the Length field's encoding, kept when it grows
    if (pn_off + 4 + 16 > len) return 0;

    uint8_t initial[32], client[32], key[16], iv[12], hp[16], hrk[176], rk[176], mask[16];
    hmac_sha256(v2 ? salt_v2 : salt_v1, 20, dcid, dcid_len, initial);
    expand_label(initial, "client in", client, 32);
    expand_label(client, v2 ? "quicv2 key" : "quic key", key, 16);
    expand_label(client, v2 ? "quicv2 iv" : "quic iv", iv, 12);
    expand_label(client, v2 ? "quicv2 hp" : "quic hp", hp, 16);
    aes128_expand(hp, hrk);
    aes128_expand(key, rk);

    aes128_encrypt(hrk, pkt + pn_off + 4, mask);
    uint8_t first = pkt[0] ^ (mask[0] & 0x0f);
    size_t pn_len = (first & 3) + 1;
    if (length < pn_len + 16) return 0;
    size_t clen = length - pn_len - 16;
    if (clen > 1500) return 0;

    // The header as the AEAD saw it: first byte and packet number without protection.
    uint8_t aad[64 + 2 * 20 + 1500];
    size_t alen = pn_off + pn_len;
    if (alen > sizeof(aad)) return 0;
    memcpy(aad, pkt, alen);
    aad[0] = first;
    uint64_t pn = 0;
    for (size_t i = 0; i < pn_len; i++) {
        aad[pn_off + i] = pkt[pn_off + i] ^ mask[1 + i];
        pn = pn << 8 | aad[pn_off + i];
    }
    uint8_t nonce[12];
    memcpy(nonce, iv, 12);
    for (int i = 0; i < 8; i++) nonce[11 - i] ^= (uint8_t) (pn >> (8 * i));

    uint8_t *ct = pkt + pn_off + pn_len;
    uint8_t tag[16];
    // Only a packet whose tag checks out was read right; anything else goes as it came.
    gcm_tag(rk, nonce, aad, alen, ct, clen, tag);
    if (memcmp(tag, ct + clen, 16)) return 0;

    uint8_t plain[1500];
    gcm_ctr(rk, nonce, ct, plain, clen);

    // The ClientHello from offset 0, in CRYPTO frames with only PADDING and PING besides.
    uint8_t ch[1500];
    size_t ch_len = 0;
    bool seen[1500] = { 0 };
    pos = 0;
    while (pos < clen) {
        uint64_t type, off, flen;
        if (!read_varint(plain, clen, &pos, &type)) return 0;
        if (type == 0x00 || type == 0x01) continue;
        if (type != 0x06) return 0;
        if (!read_varint(plain, clen, &pos, &off) || !read_varint(plain, clen, &pos, &flen)
                || flen > clen - pos || off > sizeof(ch) || flen > sizeof(ch) - off) return 0;
        for (uint64_t i = 0; i < flen; i++) {
            ch[off + i] = plain[pos + i];
            seen[off + i] = 1;
        }
        if (off + flen > ch_len) ch_len = off + flen;
        pos += flen;
    }
    for (size_t i = 0; i < ch_len; i++) {
        if (!seen[i]) return 0; // a later part of the stream, or a hole
    }
    if (ch_len < 4 || ch[0] != 1) return 0;

    // The split point in the ClientHello: a marker as for TLS, given a record header to read.
    long split = offset;
    if (marker) {
        uint8_t rec[5 + 1500];
        rec[0] = 0x16; rec[1] = 3; rec[2] = 1;
        rec[3] = (uint8_t) (ch_len >> 8); rec[4] = (uint8_t) ch_len;
        memcpy(rec + 5, ch, ch_len);
        long at = tpws_marker_pos(marker, (const char *) rec, 5 + ch_len);
        if (at < 5) return 0;
        split = at - 5 + offset;
    }
    if (split <= 0 || (size_t) split >= ch_len) return 0;

    // The second part first, then the first; the rest of the packet is PADDING. A packet filled
    // to the last byte (no PADDING to take the second frame header from) grows by those bytes.
    size_t a = (size_t) split, b = ch_len - a;
    size_t need = 1 + varint_size(a) + varint_size(b) + b + 1 + 1 + varint_size(a) + a;
    size_t nclen = need > clen ? need : clen, grow = nclen - clen;
    uint64_t nlength = length + grow;
    if (grow && (len + grow > cap || len + grow > QUIC_SPLIT_MAX || nlength >= ((uint64_t) 1 << (8 * lsz - 2)))) {
        return 0;
    }
    uint8_t np[1500 + 64];
    if (nclen > sizeof(np)) return 0;
    size_t w = 0;
    np[w++] = 0x06;
    w += put_varint(np + w, a);
    w += put_varint(np + w, b);
    memcpy(np + w, ch + a, b);
    w += b;
    np[w++] = 0x06;
    np[w++] = 0;
    w += put_varint(np + w, a);
    memcpy(np + w, ch, a);
    w += a;
    memset(np + w, 0, nclen - w);

    if (grow) {
        // Packets coalesced after this one move along; the Length field keeps its size.
        size_t tail = pn_off + pn_len + clen + 16;
        memmove(pkt + tail + grow, pkt + tail, len - tail);
        uint8_t prefix = lsz == 1 ? 0 : lsz == 2 ? 0x40 : lsz == 4 ? 0x80 : 0xc0;
        for (size_t i = 0; i < lsz; i++) {
            pkt[lpos + i] = aad[lpos + i] = (uint8_t) (nlength >> (8 * (lsz - 1 - i)));
        }
        pkt[lpos] |= prefix;
        aad[lpos] |= prefix;
        *plen = len + grow;
    }
    gcm_ctr(rk, nonce, np, ct, nclen);
    gcm_tag(rk, nonce, aad, alen, ct, nclen, ct + nclen);
    // Header protection again: its sample is the new ciphertext.
    aes128_encrypt(hrk, pkt + pn_off + 4, mask);
    pkt[0] = first ^ (mask[0] & 0x0f);
    for (size_t i = 0; i < pn_len; i++) pkt[pn_off + i] = aad[pn_off + i] ^ mask[1 + i];
    return 1;
}

int quic_crypto_sni(const struct quic_crypto *qc, char *out, size_t size)
{
    // The contiguous start of the stream: a ClientHello without the TLS record header.
    size_t avail = 0;
    while (avail < QUIC_CRYPTO_MAX && qc->have[avail]) avail++;
    const uint8_t *d = qc->data;
    if (avail < 4 || d[0] != 1) return 0;
    size_t end = 4 + ((size_t) d[1] << 16 | (size_t) d[2] << 8 | d[3]);
    size_t pos = 4 + 2 + 32;
    if (pos + 1 > avail) return 0;
    pos += 1 + d[pos]; // session id
    if (pos + 2 > avail) return 0;
    pos += 2 + ((size_t) d[pos] << 8 | d[pos + 1]); // cipher suites
    if (pos + 1 > avail) return 0;
    pos += 1 + d[pos]; // compression
    if (pos + 2 > avail) return 0;
    size_t ext_end = pos + 2 + ((size_t) d[pos] << 8 | d[pos + 1]);
    pos += 2;
    if (ext_end > end) ext_end = end;
    while (pos + 4 <= ext_end && pos + 4 <= avail) {
        unsigned type = d[pos] << 8 | d[pos + 1];
        size_t elen = (size_t) d[pos + 2] << 8 | d[pos + 3];
        pos += 4;
        if (type == 0) {
            // server_name: list length, name type 0 (host_name), name length, name
            if (pos + 5 > avail) return 0;
            size_t nlen = (size_t) d[pos + 3] << 8 | d[pos + 4];
            if (d[pos + 2] != 0 || pos + 5 + nlen > avail || nlen == 0 || nlen >= size) return 0;
            for (size_t i = 0; i < nlen; i++) {
                char ch = (char) d[pos + 5 + i];
                out[i] = ch >= 'A' && ch <= 'Z' ? ch + 32 : ch;
            }
            out[nlen] = 0;
            return host_name_ok(out, nlen) ? (int) nlen : 0;
        }
        pos += elen;
    }
    return 0;
}

/* --- DNS answers (RFC 1035 4.1) ----------------------------------------------------------- */

#define DNS_CACHE_SIZE 2048
#define DNS_NAME_MAX 254

bool host_name_ok(const char *name, size_t len)
{
    if (!len) return 0;
    for (size_t i = 0; i < len; i++) {
        char c = name[i];
        if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_')) return 0;
    }
    return 1;
}

struct dns_entry {
    uint8_t family; // 0: free, 4 or 6
    uint8_t addr[16];
    uint32_t stamp;
    uint8_t names_n;
    char names[DNS_NAMES][DNS_NAME_MAX]; // latest first
};

static struct dns_entry dns_cache[DNS_CACHE_SIZE];
static uint32_t dns_stamp;

void dns_cache_reset(void)
{
    memset(dns_cache, 0, sizeof(dns_cache));
    dns_stamp = 0;
}

static uint32_t addr_hash(uint8_t family, const uint8_t *addr)
{
    uint32_t h = 2166136261u ^ family;
    for (int i = 0; i < (family == 4 ? 4 : 16); i++) h = (h ^ addr[i]) * 16777619u;
    return h;
}

static void dns_cache_put(uint8_t family, const uint8_t *addr, const char *name)
{
    size_t alen = family == 4 ? 4 : 16;
    uint32_t h = addr_hash(family, addr);
    struct dns_entry *slot = 0;
    // Four slots per address: the same address, else a free one, else the oldest.
    for (int i = 0; i < 4; i++) {
        struct dns_entry *e = &dns_cache[(h + i) % DNS_CACHE_SIZE];
        if (e->family == family && !memcmp(e->addr, addr, alen)) {
            slot = e;
            break;
        }
        if (!slot || (slot->family && (!e->family || e->stamp < slot->stamp))) slot = e;
    }
    bool same = slot->family == family && !memcmp(slot->addr, addr, alen);
    if (!same) {
        slot->family = family;
        memset(slot->addr, 0, sizeof(slot->addr));
        memcpy(slot->addr, addr, alen);
        slot->names_n = 0;
    }
    slot->stamp = ++dns_stamp;
    // The name moves to the front; others shift, the oldest beyond DNS_NAMES drops.
    int at = slot->names_n < DNS_NAMES ? slot->names_n : DNS_NAMES - 1;
    for (int i = 0; i < slot->names_n; i++) {
        if (!strcmp(slot->names[i], name)) {
            at = i;
            break;
        }
    }
    if (at == slot->names_n) slot->names_n++;
    memmove(slot->names[1], slot->names[0], (size_t) at * DNS_NAME_MAX);
    strncpy(slot->names[0], name, DNS_NAME_MAX - 1);
    slot->names[0][DNS_NAME_MAX - 1] = 0;
}

static struct dns_entry *dns_cache_find(const union sockaddr_u *dst)
{
    uint8_t family;
    const uint8_t *addr;
    if (dst->sa.sa_family == AF_INET) {
        family = 4;
        addr = (const uint8_t *) &dst->in.sin_addr;
    }
    else if (dst->sa.sa_family == AF_INET6) {
        static const uint8_t mapped[12] = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff};
        addr = (const uint8_t *) &dst->in6.sin6_addr;
        if (!memcmp(addr, mapped, 12)) {
            family = 4;
            addr += 12;
        }
        else {
            family = 6;
        }
    }
    else {
        return 0;
    }
    uint32_t h = addr_hash(family, addr);
    for (int i = 0; i < 4; i++) {
        struct dns_entry *e = &dns_cache[(h + i) % DNS_CACHE_SIZE];
        if (e->family == family && !memcmp(e->addr, addr, family == 4 ? 4 : 16)) {
            return e;
        }
    }
    return 0;
}

const char *dns_cache_get(const union sockaddr_u *dst)
{
    struct dns_entry *e = dns_cache_find(dst);
    return e && e->names_n ? e->names[0] : 0;
}

int dns_cache_names(const union sockaddr_u *dst, const char *names[DNS_NAMES])
{
    struct dns_entry *e = dns_cache_find(dst);
    if (!e) return 0;
    for (int i = 0; i < e->names_n; i++) names[i] = e->names[i];
    return e->names_n;
}

/* Skips a possibly compressed name; false if it runs past the message. */
static bool dns_skip_name(const uint8_t *m, size_t len, size_t *pos)
{
    while (*pos < len) {
        uint8_t l = m[*pos];
        if ((l & 0xc0) == 0xc0) {
            *pos += 2;
            return *pos <= len;
        }
        *pos += 1 + l;
        if (!l) return 1;
    }
    return 0;
}

void dns_cache_feed(const uint8_t *m, size_t len)
{
    if (len < 12 || !(m[2] & 0x80) || (m[3] & 0x0f)) return; // a response without an error
    unsigned qd = m[4] << 8 | m[5], an = m[6] << 8 | m[7];
    if (qd != 1 || !an) return;
    // The question's name, which is what the application asked for (answers may be CNAMEs).
    char name[DNS_NAME_MAX];
    size_t pos = 12, n = 0;
    while (pos < len && m[pos]) {
        uint8_t l = m[pos++];
        if ((l & 0xc0) || pos + l > len || n + l + 1 >= sizeof(name)) return;
        if (n) name[n++] = '.';
        for (uint8_t i = 0; i < l; i++) {
            char ch = (char) m[pos + i];
            name[n++] = ch >= 'A' && ch <= 'Z' ? ch + 32 : ch;
        }
        pos += l;
    }
    if (!n || pos >= len || !host_name_ok(name, n)) return;
    name[n] = 0;
    pos += 1 + 4; // root label, type, class
    for (unsigned i = 0; i < an && pos < len; i++) {
        if (!dns_skip_name(m, len, &pos) || pos + 10 > len) return;
        unsigned type = m[pos] << 8 | m[pos + 1];
        size_t rdlen = (size_t) m[pos + 8] << 8 | m[pos + 9];
        pos += 10;
        if (pos + rdlen > len) return;
        if (type == 1 && rdlen == 4) dns_cache_put(4, m + pos, name);
        else if (type == 28 && rdlen == 16) dns_cache_put(6, m + pos, name);
        pos += rdlen;
    }
}
