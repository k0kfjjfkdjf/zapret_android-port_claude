/*
 * libFuzzer target: QUIC Initial decryption and SNI extraction, and the DNS answer cache
 * (udphost.c). udphost.c is included so that its static AES/HKDF can encrypt the fuzzer's
 * plaintext into a well-formed Initial: random ciphertext would almost never decrypt into
 * frames, and the frame and ClientHello parsers would stay unreached.
 */
#include <stdlib.h>

#include "../../main/cpp/engine/udphost.c"
#include "packets.h"
#include "tpws.h"

/* A client Initial (v1) carrying [plain] as its frames, packet number [pn]. */
static size_t make_initial(uint8_t *pkt, size_t cap, const uint8_t *dcid, uint8_t dlen,
        const uint8_t *plain, size_t plen, uint8_t pn)
{
    static const uint8_t salt[20] = {
        0x38, 0x76, 0x2c, 0xf7, 0xf5, 0x59, 0x34, 0xb3, 0x4d, 0x17,
        0x9a, 0xe6, 0xa4, 0xc8, 0x0c, 0xad, 0xcc, 0xbb, 0x7f, 0x0a,
    };
    if (plen > 1400 || 64 + plen > cap) return 0;
    size_t pos = 0;
    pkt[pos++] = 0xc0; // long header, Initial, 1-byte packet number
    pkt[pos++] = 0; pkt[pos++] = 0; pkt[pos++] = 0; pkt[pos++] = 1;
    pkt[pos++] = dlen;
    memcpy(pkt + pos, dcid, dlen);
    pos += dlen;
    pkt[pos++] = 0; // scid
    pkt[pos++] = 0; // token
    size_t length = 1 + plen + 16;
    pkt[pos++] = 0x40 | length >> 8;
    pkt[pos++] = length;
    size_t pn_off = pos;
    pkt[pos++] = pn;

    uint8_t initial[32], client[32], key[16], iv[12], hp[16], rk[176], mask[16];
    hmac_sha256(salt, 20, dcid, dlen, initial);
    expand_label(initial, "client in", client, 32);
    expand_label(client, "quic key", key, 16);
    expand_label(client, "quic iv", iv, 12);
    expand_label(client, "quic hp", hp, 16);

    uint8_t nonce[12];
    memcpy(nonce, iv, 12);
    nonce[11] ^= pn;
    aes128_expand(key, rk);
    gcm_ctr(rk, nonce, plain, pkt + pos, plen);
    // A real tag (the header unprotected as additional data): --quic-split checks it.
    gcm_tag(rk, nonce, pkt, pn_off + 1, pkt + pos, plen, pkt + pos + plen);
    pos += plen;
    pos += 16;
    // Header protection over the sample 4 bytes after the packet number's start.
    aes128_expand(hp, rk);
    aes128_encrypt(rk, pkt + pn_off + 4, mask);
    pkt[0] ^= mask[0] & 0x0f;
    pkt[pn_off] ^= mask[1];
    return pos;
}

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size)
{
    if (size < 2) return 0;
    uint8_t mode = data[0];
    data++;
    size--;
    struct quic_crypto *qc = calloc(1, sizeof(*qc));
    if (!qc) return 0;
    char sni[256];
    if (mode % 3 == 0) {
        // Raw datagrams: the whole input, then its two halves as two packets.
        quic_add_initial(qc, data, size);
        quic_add_initial(qc, data, size / 2);
        quic_add_initial(qc, data + size / 2, size - size / 2);
        udp_detect_proto((const char *) data, size);
    }
    else if (mode % 3 == 1) {
        // Fuzzer bytes are the decrypted frames of one or two Initials.
        static const uint8_t dcid[8] = { 1, 2, 3, 4, 5, 6, 7, 8 };
        uint8_t *pkt = malloc(1500);
        if (pkt) {
            size_t half = (mode >> 2) & 1 ? size / 2 : size;
            size_t n = make_initial(pkt, 1500, dcid, 8, data, half, 0);
            if (n) quic_add_initial(qc, pkt, n);
            // --quic-split on the same packet, in a buffer with room to grow by a few bytes.
            if (n) {
                uint8_t *copy = malloc(n + 8);
                if (copy) {
                    memcpy(copy, pkt, n);
                    size_t m = n;
                    int marker = (mode >> 3) % (MK_SNIEXT + 1);
                    if (quic_split_initial(copy, &m, n + (mode & 8), marker, (long) (mode >> 5) - 2)) {
                        // The result must decrypt with a valid tag again: a split of it succeeds
                        // or is refused for room, never reads past it.
                        size_t k = m;
                        quic_split_initial(copy, &k, m, 0, 1);
                    }
                    free(copy);
                }
            }
            if (half < size) {
                n = make_initial(pkt, 1500, dcid, 8, data + half, size - half, 1);
                if (n) quic_add_initial(qc, pkt, n);
            }
            free(pkt);
        }
    }
    else {
        // Fuzzer bytes are the CRYPTO stream itself (a ClientHello).
        size_t n = size < QUIC_CRYPTO_MAX ? size : QUIC_CRYPTO_MAX;
        memcpy(qc->data, data, n);
        memset(qc->have, 1, n);
        // A hole in the stream, chosen by the mode byte.
        if (n && (mode & 0x80)) qc->have[(mode * 7) % n] = 0;
    }
    quic_crypto_sni(qc, sni, (mode & 0x40) ? 8 : sizeof(sni));
    free(qc);

    // The same bytes as a DNS response, then lookups of what it may have stored.
    dns_cache_feed(data, size);
    union sockaddr_u a = { 0 };
    a.in.sin_family = AF_INET;
    if (size >= 4) memcpy(&a.in.sin_addr, data + size - 4, 4);
    const char *names[DNS_NAMES];
    dns_cache_get(&a);
    dns_cache_names(&a, names);
    return 0;
}
