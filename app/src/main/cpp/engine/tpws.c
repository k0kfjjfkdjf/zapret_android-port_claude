#include "tpws.h"

#include <ctype.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <strings.h>

#include "packets.h"

static const struct {
    const char *name;
    int marker;
} markers[] = {
    { "method", MK_METHOD },
    { "endhost", MK_ENDHOST },
    { "host", MK_HOST },
    { "midsld", MK_MIDSLD },
    { "endsld", MK_ENDSLD },
    { "sld", MK_SLD },
    { "sniext", MK_SNIEXT },
};

int tpws_parse_marker(const char *str, int *marker, long *offset)
{
    for (size_t i = 0; i < sizeof(markers) / sizeof(*markers); i++) {
        size_t len = strlen(markers[i].name);
        if (strncmp(str, markers[i].name, len)) {
            continue;
        }
        const char *rest = str + len;
        long off = 0;
        if (*rest == '+' || *rest == '-') {
            char *end;
            off = strtol(rest, &end, 10);
            if (end == rest + 1 || *end) {
                return -1;
            }
        }
        else if (*rest) {
            continue;
        }
        *marker = markers[i].marker;
        *offset = off;
        return 0;
    }
    return -1;
}


/* "Host:" header of an HTTP request (pointer to 'H'), as tpws HttpFindHost. */
static char *find_host_header(char *buf, size_t n)
{
    for (char *p = buf; p + 6 <= buf + n; p++) {
        p = memchr(p, '\n', buf + n - p);
        if (!p || p + 6 > buf + n) {
            return 0;
        }
        if (!strncasecmp(p + 1, "Host:", 5)) {
            return p + 1;
        }
    }
    return 0;
}


/* Second-level domain inside a host name (zapret FindNLD with level 2). */
static int find_sld(const char *host, size_t len, const char **sld, size_t *sld_len)
{
    const char *end = host + len;
    const char *p2 = end;
    for (p2--; p2 > host && *p2 != '.'; p2--);
    if (p2 <= host) {
        return 0;
    }
    const char *p1 = p2 - 1;
    for (; p1 > host && *p1 != '.'; p1--);
    if (*p1 == '.') p1++;
    *sld = p1;
    *sld_len = p2 - p1;
    return 1;
}


/* Offset of the SNI extension data (after its type and length) in a ClientHello. */
static long sni_ext_pos(const char *buf, size_t n)
{
    char *host = 0;
    if (!parse_tls(buf, n, &host)) {
        return -1;
    }
    // server_name: type(2) len(2) list_len(2) name_type(1) name_len(2) name
    return host - buf - 5;
}


long tpws_marker_pos(int marker, const char *buf, size_t n)
{
    char *host = 0;
    int host_len = 0;
    if (marker == MK_METHOD) {
        // As tpws: skip up to two line breaks left by methodeol, then expect "METHOD ".
        size_t p = 0;
        if (p < n && (buf[p] == '\n' || buf[p] == '\r')) p++;
        if (p < n && (buf[p] == '\n' || buf[p] == '\r')) p++;
        if (!is_http(buf + p, n - p)) {
            return -1;
        }
        return p;
    }
    if (marker == MK_SNIEXT) {
        return sni_ext_pos(buf, n);
    }
    if (!(host_len = parse_tls(buf, n, &host))) {
        // HTTP as in tpws: the Host: value up to the end of the line. Searched directly
        // rather than with parse_http(), which rejects requests modified by methodeol.
        char *hdr = find_host_header((char *) buf, n);
        if (!hdr) {
            return -1;
        }
        host = hdr + 5;
        if (host < buf + n && (*host == ' ' || *host == '\t')) host++;
        char *eol = host;
        while (eol < buf + n && *eol != '\r' && *eol != '\n') eol++;
        host_len = eol - host;
    }
    if (host_len <= 0 || !host) {
        return -1;
    }
    long host_pos = host - buf;
    const char *sld;
    size_t sld_len;

    switch (marker) {
        case MK_HOST:
            return host_pos;
        case MK_ENDHOST:
            return host_pos + host_len;
        case MK_SLD:
        case MK_MIDSLD:
        case MK_ENDSLD:
            // Ignore a trailing dot or tab added by hostdot/hosttab, so the SLD stays the SLD.
            while (host_len > 0 && (host[host_len - 1] == '.' || isspace((unsigned char) host[host_len - 1]))) {
                host_len--;
            }
            if (!find_sld(host, host_len, &sld, &sld_len)) {
                return -1;
            }
            if (marker == MK_SLD) return sld - buf;
            if (marker == MK_ENDSLD) return sld - buf + sld_len;
            return sld - buf + (sld_len == 1 ? 1 : sld_len / 2);
    }
    return -1;
}


int tpws_parse_http_mod(const char *token, size_t len, int *flags, char hostspell[5], int *hostpad)
{
    static const struct {
        const char *name;
        int flag;
    } mods[] = {
        { "hostcase", MH_HOSTCASE },
        { "hostdot", MH_HOSTDOT },
        { "hosttab", MH_HOSTTAB },
        { "hostnospace", MH_HOSTNOSPACE },
        { "methodspace", MH_METHODSPACE },
        { "methodeol", MH_METHODEOL },
        { "unixeol", MH_UNIXEOL },
    };
    for (size_t i = 0; i < sizeof(mods) / sizeof(*mods); i++) {
        if (strlen(mods[i].name) == len && !strncmp(token, mods[i].name, len)) {
            *flags |= mods[i].flag;
            return 0;
        }
    }
    if (len == 14 && !strncmp(token, "hostspell=", 10)) {
        memcpy(hostspell, token + 10, 4);
        hostspell[4] = 0;
        *flags |= MH_HOSTCASE;
        return 0;
    }
    if (len > 8 && !strncmp(token, "hostpad=", 8)) {
        char num[16] = { 0 };
        if (len - 8 >= sizeof(num)) {
            return -1;
        }
        memcpy(num, token + 8, len - 8);
        char *end;
        long v = strtol(num, &end, 10);
        if (*end || v <= 0 || v > 65536) {
            return -1;
        }
        *hostpad = (int) v;
        *flags |= MH_HOSTPAD;
        return 0;
    }
    if (!strncmp(token, "host", 4) || !strncmp(token, "method", 6) || !strncmp(token, "unix", 4)) {
        return -1;
    }
    return 1;
}


static void insert_at(char *buf, ssize_t *n, char *at, const char *data, size_t len)
{
    memmove(at + len, at, buf + *n - at);
    memcpy(at, data, len);
    *n += len;
}


/* Ported from tpws tamper_out(), HTTP branch; the same order of operations. */
void tpws_mod_http(char *buf, size_t bfsize, ssize_t *n, int flags, const char *hostspell, int hostpad)
{
    char *p, *host;
    int unixeol = flags & MH_UNIXEOL;

    if (unixeol) {
        char *prev = buf;
        p = buf;
        while ((p = memmem(p, buf + *n - p, "\r\n", 2))) {
            *p = '\n';
            p++;
            memmove(p, p + 1, buf + *n - p - 1);
            (*n)--;
            if (prev == p - 1) {
                break; // end of headers
            }
            prev = p;
        }
    }
    if ((flags & MH_METHODEOL) && (size_t) (*n + 2) <= bfsize) {
        insert_at(buf, n, buf, unixeol ? "\n" : "\r\n", unixeol ? 1 : 2);
    }
    if ((flags & MH_METHODSPACE) && (size_t) *n < bfsize) {
        p = buf;
        while (p < buf + *n && (*p == '\r' || *p == '\n')) p++;
        while (p < buf + *n && *p >= 'A' && *p <= 'Z') p++;
        if (p < buf + *n && *p == ' ') {
            insert_at(buf, n, p, " ", 1);
        }
    }
    if ((flags & (MH_HOSTDOT | MH_HOSTTAB)) && (size_t) *n < bfsize && (host = find_host_header(buf, *n))) {
        p = host + 5;
        while (p < buf + *n && *p != '\r' && *p != '\n') p++;
        if (p < buf + *n) {
            insert_at(buf, n, p, (flags & MH_HOSTDOT) ? "." : "\t", 1);
        }
    }
    if ((flags & MH_HOSTNOSPACE) && (host = find_host_header(buf, *n))
            && host + 5 < buf + *n && host[5] == ' ') {
        p = host + 6;
        memmove(p - 1, p, buf + *n - p);
        (*n)--;
    }
    if ((flags & MH_HOSTCASE) && (host = find_host_header(buf, *n))) {
        memcpy(host, hostspell && hostspell[0] ? hostspell : "host", 4);
    }
    if ((flags & MH_HOSTPAD) && (host = find_host_header(buf, *n))) {
        // Dummy headers "xNNNN: aaaa..." before Host: totalling hostpad bytes.
        size_t hsize = unixeol ? 8 : 9;
        size_t pad = (size_t) hostpad < hsize ? hsize : (size_t) hostpad;
        if (hsize + *n > bfsize) {
            return;
        }
        if (pad + *n > bfsize) {
            pad = bfsize - *n;
        }
        memmove(host + pad, host, buf + *n - host);
        *n += pad;
        p = host;
        while (pad >= hsize) {
            size_t padsize = pad > hsize ? pad - hsize : 0;
            if (padsize > 2048) padsize = 2048;
            if (pad - padsize - hsize < hsize) padsize += pad - padsize - hsize;
            char s[8];
            snprintf(s, sizeof(s), "%c%04x: ", 'a' + rand() % 26, rand() & 0xffff);
            memcpy(p, s, 7);
            p += 7;
            memset(p, 'a' + rand() % 26, padsize);
            p += padsize;
            if (!unixeol) *p++ = '\r';
            *p++ = '\n';
            pad -= hsize + padsize;
        }
    }
}
