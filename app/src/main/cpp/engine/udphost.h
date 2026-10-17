#ifndef UDPHOST_H
#define UDPHOST_H

/*
 * ZapretDroid: host names where the packet itself has none, so that host lists (-H) also
 * select groups for UDP and for TCP without a readable SNI:
 *  - the SNI of a QUIC Initial (v1, v2), decrypted with the keys every observer can derive
 *    (RFC 9001 5.2), also when the ClientHello spans several Initials (Chrome with Kyber);
 *  - the name an address was resolved from, kept from the DNS answers the engine relays.
 */

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "params.h"

#define QUIC_CRYPTO_MAX 4096

/* The CRYPTO stream of a client's Initial packets, collected by offset. */
struct quic_crypto {
    uint8_t data[QUIC_CRYPTO_MAX];
    uint8_t have[QUIC_CRYPTO_MAX];
    // The DCID of the first Initial: later packets with another one (after the server's reply
    // the client switches DCID but keeps the old keys) would decrypt to garbage.
    uint8_t dcid[20];
    uint8_t dcid_len;
};

/* Decrypts a client Initial and adds its CRYPTO frames; false if it is not one. */
bool quic_add_initial(struct quic_crypto *qc, const uint8_t *pkt, size_t len);

/* The SNI of the ClientHello collected so far, lower case: its length, or 0 if not (yet) there. */
int quic_crypto_sni(const struct quic_crypto *qc, char *out, size_t size);

/* Remembers the addresses of a DNS response under the name that was asked for. */
void dns_cache_feed(const uint8_t *msg, size_t len);

/* The latest name the address was resolved from, or null. */
const char *dns_cache_get(const union sockaddr_u *addr);

#define DNS_NAMES 4

/* All names (up to DNS_NAMES, latest first) the address was resolved from; their count. A CDN
 * address serves many names. */
int dns_cache_names(const union sockaddr_u *addr, const char *names[DNS_NAMES]);

/* Whether a host name is plain enough to log and match: [a-z0-9._-] only. */
bool host_name_ok(const char *name, size_t len);

void dns_cache_reset(void);

#endif
