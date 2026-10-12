#ifndef TPWS_H
#define TPWS_H

/*
 * Features ported from zapret's tpws: HTTP request modifications and position markers.
 */

#include <stddef.h>
#include <sys/types.h>

/* HTTP modifications (extend MH_* from packets.h). */
#define MH_HOSTCASE    0x0008
#define MH_HOSTDOT     0x0010
#define MH_HOSTTAB     0x0020
#define MH_HOSTNOSPACE 0x0040
#define MH_METHODSPACE 0x0080
#define MH_METHODEOL   0x0100
#define MH_UNIXEOL     0x0200
#define MH_HOSTPAD     0x0400
#define MH_TPWS_MASK   0x07f8

/* Position markers: a part with OFFSET_MARKER keeps the marker in the bits above MARKER_SHIFT. */
#define OFFSET_MARKER 64
#define MARKER_SHIFT 8

enum marker {
    MK_METHOD = 1,
    MK_HOST,
    MK_ENDHOST,
    MK_SLD,
    MK_MIDSLD,
    MK_ENDSLD,
    MK_SNIEXT,
};

/* Parses "name[+N|-N]"; returns 0 and fills marker/offset, or -1 if str is not a marker. */
int tpws_parse_marker(const char *str, int *marker, long *offset);

/* Position of the marker in a TLS ClientHello or HTTP request, -1 if it does not apply. */
long tpws_marker_pos(int marker, const char *buf, size_t n);

/*
 * Parses one -M token. Returns 0 if it is a tpws modification (flags, hostspell and hostpad
 * are updated), 1 if it is not one, -1 if it is malformed.
 */
int tpws_parse_http_mod(const char *token, size_t len, int *flags, char hostspell[5], int *hostpad);

/* Applies the tpws HTTP modifications in tpws order; the request may grow up to bfsize. */
void tpws_mod_http(char *buf, size_t bfsize, ssize_t *n, int flags, const char *hostspell, int hostpad);

#endif
