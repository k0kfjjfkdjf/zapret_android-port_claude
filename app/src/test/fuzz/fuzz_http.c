/*
 * libFuzzer target: HTTP request parsing and the original and tpws request modifications
 * (-M), applied to the client's request in place with the room desync gives them.
 */
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "packets.h"
#include "tpws.h"

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size)
{
    if (size < 4) return 0;
    int flags = (data[0] << 8 | data[1]) & (MH_HMIX | MH_SPACE | MH_DMIX | MH_TPWS_MASK);
    int hostpad = data[2] % 64;
    size_t room = data[3];
    data += 4;
    size -= 4;

    char *hs = 0;
    uint16_t port = 0;
    is_http((const char *) data, size);
    parse_http((const char *) data, size, &hs, &port);
    is_http_redirect((const char *) data, size, (const char *) data, size);
    for (int m = MK_METHOD; m <= MK_SNIEXT; m++) {
        tpws_marker_pos(m, (const char *) data, size);
    }

    char *buf = malloc(size + room + 1);
    if (!buf) return 0;
    memcpy(buf, data, size);
    ssize_t n = size;
    // Same order as desync.c: the original in-place modifications first, on an HTTP request.
    if (is_http(buf, n)) {
        if (flags & ~MH_TPWS_MASK) {
            mod_http(buf, n, flags);
        }
        if (flags & MH_TPWS_MASK) {
            tpws_mod_http(buf, size + room, &n, flags, "hoSt", hostpad);
        }
    }
    free(buf);
    return 0;
}
