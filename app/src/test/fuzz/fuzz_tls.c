/*
 * libFuzzer target: TLS ClientHello handling with the client's own bytes (--fake-tls-mod orig,
 * --fake-sni, --tlsrec, tpws markers). Every buffer is allocated to its exact size, so that
 * AddressSanitizer catches any access past it.
 */
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "packets.h"
#include "tpws.h"

static const char *hosts[] = { "a", "example.com", "a-much-longer-host-name.example.org", "" };

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size)
{
    if (size < 3) return 0;
    uint8_t ctl = data[0];
    int16_t delta = (int16_t) (data[1] << 8 | data[2]) % 2048;
    data += 3;
    size -= 3;
    ssize_t n = size;

    char *hs = 0;
    is_tls_chello((const char *) data, size);
    parse_tls((const char *) data, size, &hs);
    is_tls_shello((const char *) data, size);
    neq_tls_sid((const char *) data, size, (const char *) data, size);
    for (int m = MK_METHOD; m <= MK_SNIEXT; m++) {
        tpws_marker_pos(m, (const char *) data, size);
    }

    // change_tls_sni: buffer of max(n, nn) bytes, as both callers in desync.c allocate it.
    ssize_t nn = n + delta;
    if (nn > 0) {
        ssize_t cap = n > nn ? n : nn;
        char *buf = malloc(cap ? cap : 1);
        if (buf) {
            memset(buf, 0, cap);
            memcpy(buf, data, size);
            change_tls_sni(hosts[ctl & 3], buf, n, nn);
            free(buf);
        }
    }

    // randomize_tls on exactly n bytes.
    char *buf = malloc(size ? size : 1);
    if (buf) {
        memcpy(buf, data, size);
        randomize_tls(buf, n);
        free(buf);
    }

    // part_tls: room for one more record header, as desync's tlsrec keeps it.
    size_t room = 5 + (ctl >> 4);
    buf = malloc(size + room);
    if (buf) {
        memcpy(buf, data, size);
        long pos = (long) (delta < 0 ? -delta : delta);
        part_tls(buf, size + room, n, pos % (size + 1));
        free(buf);
    }
    return 0;
}
