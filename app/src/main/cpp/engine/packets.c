#include "packets.h"

#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <stdbool.h>
#include <string.h>
#include <strings.h>

#ifdef _WIN32
    #include <winsock2.h>
#else
    #include <arpa/inet.h>
#endif


char tls_data[517] = {
    "\x16\x03\x01\x02\x00\x01\x00\x01\xfc\x03\x03\x03\x5f"
    "\x6f\x2c\xed\x13\x22\xf8\xdc\xb2\xf2\x60\x48\x2d\x72"
    "\x66\x6f\x57\xdd\x13\x9d\x1b\x37\xdc\xfa\x36\x2e\xba"
    "\xf9\x92\x99\x3a\x20\xf9\xdf\x0c\x2e\x8a\x55\x89\x82"
    "\x31\x63\x1a\xef\xa8\xbe\x08\x58\xa7\xa3\x5a\x18\xd3"
    "\x96\x5f\x04\x5c\xb4\x62\xaf\x89\xd7\x0f\x8b\x00\x3e"
    "\x13\x02\x13\x03\x13\x01\xc0\x2c\xc0\x30\x00\x9f\xcc"
    "\xa9\xcc\xa8\xcc\xaa\xc0\x2b\xc0\x2f\x00\x9e\xc0\x24"
    "\xc0\x28\x00\x6b\xc0\x23\xc0\x27\x00\x67\xc0\x0a\xc0"
    "\x14\x00\x39\xc0\x09\xc0\x13\x00\x33\x00\x9d\x00\x9c"
    "\x00\x3d\x00\x3c\x00\x35\x00\x2f\x00\xff\x01\x00\x01"
    "\x75\x00\x00\x00\x16\x00\x14\x00\x00\x11\x77\x77\x77"
    "\x2e\x77\x69\x6b\x69\x70\x65\x64\x69\x61\x2e\x6f\x72"
    "\x67\x00\x0b\x00\x04\x03\x00\x01\x02\x00\x0a\x00\x16"
    "\x00\x14\x00\x1d\x00\x17\x00\x1e\x00\x19\x00\x18\x01"
    "\x00\x01\x01\x01\x02\x01\x03\x01\x04\x00\x10\x00\x0e"
    "\x00\x0c\x02\x68\x32\x08\x68\x74\x74\x70\x2f\x31\x2e"
    "\x31\x00\x16\x00\x00\x00\x17\x00\x00\x00\x31\x00\x00"
    "\x00\x0d\x00\x2a\x00\x28\x04\x03\x05\x03\x06\x03\x08"
    "\x07\x08\x08\x08\x09\x08\x0a\x08\x0b\x08\x04\x08\x05"
    "\x08\x06\x04\x01\x05\x01\x06\x01\x03\x03\x03\x01\x03"
    "\x02\x04\x02\x05\x02\x06\x02\x00\x2b\x00\x09\x08\x03"
    "\x04\x03\x03\x03\x02\x03\x01\x00\x2d\x00\x02\x01\x01"
    "\x00\x33\x00\x26\x00\x24\x00\x1d\x00\x20\x11\x8c\xb8"
    "\x8c\xe8\x8a\x08\x90\x1e\xee\x19\xd9\xdd\xe8\xd4\x06"
    "\xb1\xd1\xe2\xab\xe0\x16\x63\xd6\xdc\xda\x84\xa4\xb8"
    "\x4b\xfb\x0e\x00\x15\x00\xac\x00\x00\x00\x00\x00\x00"
};

char http_data[43] __attribute__((nonstring)) = {
    "GET / HTTP/1.1\r\n"
    "Host: www.wikipedia.org\r\n\r\n"
};

char udp_data[64] = { 0 };


static char *strncasestr(const char *a, size_t as, const char *b, size_t bs)
{
    for (const char *p = a; ; p++) {
        p = memchr(p, *b, as - (p - a));
        if (!p) {
            return 0;
        }
        if ((p + bs) > (a + as)) {
            return 0;
        }
        if (!strncasecmp(p, b, bs)) {
            return (char *)p;
        }
    }
    return 0;
}


static size_t find_tls_ext_offset(uint16_t type, 
        const char *data, size_t size, size_t skip) 
{
    if (size <= (skip + 2)) {
        return 0;
    }
    uint16_t ext_len = ANTOHS(data, skip);
    skip += 2;
    
    if (ext_len < (size - skip)) {
        size = ext_len + skip;
    }
    while ((skip + 4) < size) {
        uint16_t epyt = ANTOHS(data, skip);
        if (type == epyt) {
            return skip;
        }
        uint16_t len = ANTOHS(data, skip + 2);
        skip += (len + 4);
    }
    return 0;
}


static size_t find_ext_block(const char *data, size_t size)
{
    if (size < 44) {
        return 0;
    }
    uint8_t sid_len = data[43];
    if (size < (44lu + sid_len + 2)) {
        return 0;
    }
    uint16_t cip_len = ANTOHS(data, 44 + sid_len);

    size_t skip = 44 + sid_len + 2 + cip_len + 2;
    return skip > size ? 0 : skip;
}


static int merge_tls_records(char *buffer, ssize_t n)
{
    if (n < 9) { // ZapretDroid: the header writes below reach byte 8
        return 0;
    }
    uint16_t full_sz = 0;
    uint16_t r_sz = ANTOHS(buffer, 3);
    int i = 0;
    
    while (1) {
        full_sz += r_sz;
        if (5 + full_sz > n - 5
                || buffer[5 + full_sz] != *buffer) {
            break;
        }
        r_sz = ANTOHS(buffer, 5 + full_sz + 3);

        // ZapretDroid: an empty record does not move full_sz on, and the memmove below leaves
        // the last bytes in place, so records of length 0 looped forever (the client's own
        // ClientHello with --fake-tls-mod orig: any app could freeze the engine).
        if (!r_sz || full_sz + 10 + r_sz > n) {
            break;
        }
        memmove(buffer + 5 + full_sz, 
            buffer + 10 + full_sz, n - (10 + full_sz));
        i++;
    }
    SHTONA(buffer, 3, full_sz);
    SHTONA(buffer, 7, full_sz - 4);
    return i * 5;
}


static void copy_name(char *out, const char *name, size_t out_len)
{
    for (size_t i = 0; i < out_len; i++) {
        switch (name[i]) {
        case '*':;
            int r = rand() % (10 + 'z' - 'a' + 1);
            out[i] = (r < 10 ? '0' : ('a' - 10)) + r;
            break;
        case '?':
            out[i] = 'a' + (rand() % ('z' - 'a' + 1));
            break;
        case '#':
            out[i] = '0' + (rand() % 10);
            break;
        default:
            out[i] = name[i];
        }
    }
}


static int remove_ks_group(char *buffer,
        ssize_t n, size_t skip, uint16_t group)
{
    ssize_t ks_offs = find_tls_ext_offset(0x0033, buffer, n, skip);
    if (!ks_offs || ks_offs + 6 >= n) {
        return 0;
    }
    int ks_sz = ANTOHS(buffer, ks_offs + 2);
    if (ks_offs + 4 + ks_sz > n) {
        return 0;
    }
    ssize_t g_offs = ks_offs + 4 + 2;
    while (g_offs + 4 < ks_offs + 4 + ks_sz) {
        uint16_t g_sz = ANTOHS(buffer, g_offs + 2);
        if (g_offs + 4 + g_sz > n) { // ZapretDroid: the group's own end, not ks_offs
            return 0;
        }
        uint16_t g_tp = ANTOHS(buffer, g_offs);
        if (g_tp == group) {
            ssize_t g_end = g_offs + 4 + g_sz;
            
            memmove(buffer + g_offs, buffer + g_end, n - g_end);
            SHTONA(buffer, ks_offs + 2, ks_sz - (4 + g_sz));
            SHTONA(buffer, ks_offs + 4, ks_sz - (4 + g_sz) - 2);
            return 4 + g_sz;
        }
        g_offs += 4 + g_sz;
    }
    return 0;
}


static int remove_tls_ext(char *buffer, 
        ssize_t n, size_t skip, uint16_t type)
{
    ssize_t ext_offs = find_tls_ext_offset(type, buffer, n, skip);
    if (!ext_offs) {
        return 0;
    }
    uint16_t ext_sz = ANTOHS(buffer, ext_offs + 2);
    ssize_t ext_end = ext_offs + 4 + ext_sz;
    if (ext_end > n) {
        return 0;
    }
    memmove(buffer + ext_offs, buffer + ext_end, n - ext_end);
    return ext_sz + 4;
}


static int resize_ech_ext(char *buffer, 
        ssize_t n, ssize_t cap, size_t skip, int inc)
{
    ssize_t ech_offs = find_tls_ext_offset(0xfe0d, buffer, n, skip);
    if (!ech_offs) {
        return 0;
    }
    uint16_t ech_sz = ANTOHS(buffer, ech_offs + 2);
    ssize_t ech_end = ech_offs + 4 + ech_sz;
    
    if (ech_sz < 12 || ech_end > n) {
        return 0;
    }
    uint16_t enc_sz = ANTOHS(buffer, ech_offs + 4 + 6);
    ssize_t pay_offs = ech_offs + 4 + 8 + enc_sz;
    // ZapretDroid: lengths from the ClientHello are checked before use (with --fake-tls-mod
    // orig it is any app's own first packet); moves stay inside the buffer ([cap] bytes).
    if (8 + enc_sz + 2 > ech_sz) {
        return 0;
    }
    uint16_t pay_sz = ech_sz - (8 + enc_sz + 2);
    
    if (pay_offs + 2 > n) {
        return 0;
    }
    if (pay_sz < -inc) {
        inc = -pay_sz;
    }
    if (ech_end + inc > cap) {
        return 0;
    }
    // The rest of the message, [ech_end, n), moves by inc: within the buffer, and nothing
    // when the extension is the last one (it was n - (ech_end + inc): negative there, and
    // past the buffer when shrinking).
    ssize_t len = n - ech_end;
    if (len > cap - (ech_end + inc)) len = cap - (ech_end + inc);
    SHTONA(buffer, ech_offs + 2, ech_sz + inc);
    SHTONA(buffer, pay_offs, pay_sz + inc);
    
    if (len > 0) {
        memmove(buffer + ech_end + inc, buffer + ech_end, len);
    }
    return inc;
}


static void resize_sni(char *buffer, ssize_t n, ssize_t cap,
        ssize_t sni_offs, ssize_t sni_sz, ssize_t new_sz)
{
    SHTONA(buffer, sni_offs + 2, new_sz + 5);
    SHTONA(buffer, sni_offs + 4, new_sz + 3);
    SHTONA(buffer, sni_offs + 7, new_sz);
    
    ssize_t sni_end = sni_offs + 4 + sni_sz;
    ssize_t to = sni_end + new_sz - (sni_sz - 5);
    // ZapretDroid: a longer name moves the rest right; nothing is written past the buffer.
    ssize_t len = n - sni_end;
    if (len > cap - to) len = cap - to;
    if (len > 0) {
        memmove(buffer + to, buffer + sni_end, len);
    }
}


int change_tls_sni(const char *host, char *buffer, ssize_t n, ssize_t nn)
{
    // ZapretDroid: with --fake-tls-mod orig the buffer is the client's own ClientHello, so
    // its lengths are checked against the buffer, which holds max(n, nn) bytes (both callers),
    // before anything is written.
    ssize_t cap = n > nn ? n : nn;
    if (n < 9) {
        return -1;
    }
    int avail = merge_tls_records(buffer, n);
    avail += (nn - n);
    
    // In int before it goes into 16 bits: a wrapped size would move writes before the buffer.
    int full_sz = ANTOHS(buffer, 3) + avail;
    if (full_sz < 0 || full_sz > 0xffff) {
        return -1;
    }
    uint16_t r_sz = full_sz;
    
    size_t skip = find_ext_block(buffer, n);
    if (!skip || (ssize_t) skip + 2 > cap) {
        return -1;
    }
    ssize_t sni_offs = find_tls_ext_offset(0x00, buffer, n, skip);
    if (!sni_offs) {
        return -1;
    }
    uint16_t new_sz = strlen(host);
    uint16_t sni_sz = ANTOHS(buffer, sni_offs + 2);
    
    if (sni_offs + 4 + sni_sz > n) {
        return -1;
    }
    int diff = (int )new_sz - (sni_sz - 5);
    avail -= diff;
    
    if (sni_offs + 9 + new_sz > cap) {
        return -1;
    }
    if (diff < 0 && avail > 0) {
        resize_sni(buffer, n, cap, sni_offs, sni_sz, new_sz);
        diff = 0;
    }
    if (avail) {
        avail -= resize_ech_ext(buffer, n, cap, skip, avail);
    }
    if (avail < -50) {
        avail += remove_ks_group(buffer, n, skip, 0x11ec);
    }
    static const uint16_t exts[] = { 
        0x0015, // padding
        0x0031, // post_handshake_auth
        0x0010, // ALPN
        0x001c, // record_size_limit
        0x0023, // session_ticket
        0x0005, // status_request
        0x0022, // delegated_credentials
        0x0012, // signed_certificate_timestamp
        0x001b, // compress_certificate
        0
    };
    for (const uint16_t *e = exts; avail && avail < 4; e++) {
        if (!*e) {
            return -1;
        }
        avail += remove_tls_ext(buffer, n, skip, *e);
    }
    if (!(sni_offs = find_tls_ext_offset(0x00, buffer, n, skip))) {
        return -1;
    }
    if (sni_offs + 9 + new_sz > cap) {
        return -1;
    }
    if (diff) {
        resize_sni(buffer, n, cap, sni_offs, sni_sz, new_sz);
    }
    copy_name(buffer + sni_offs + 9, host, new_sz);
    
    if (avail > 0) {
        avail -= resize_ech_ext(buffer, n, cap, skip, avail);
    }
    if (avail >= 4 && (avail > r_sz || 5 + (ssize_t) r_sz > cap)) {
        // ZapretDroid: the padding would start before the record or end past the buffer (a
        // record that claims more than the buffer holds, a hello split over segments).
        return -1;
    }
    if (avail >= 4) {
        SHTONA(buffer, 5 + r_sz - avail, 0x0015);
        SHTONA(buffer, 5 + r_sz - avail + 2, avail - 4);
        memset(buffer + 5 + r_sz - avail + 4, 0, avail - 4);
    }
    SHTONA(buffer, 3, r_sz);
    SHTONA(buffer, 7, r_sz - 4);
    SHTONA(buffer, skip, 5 + r_sz - skip - 2);
    return 0;
}


bool is_tls_chello(const char *buffer, size_t bsize)
{
    return (bsize > 5 &&
        ANTOHS(buffer, 0) == 0x1603 &&
        buffer[5] == 0x01);
}


int parse_tls(const char *buffer, size_t bsize, char **hs)
{
    if (!is_tls_chello(buffer, bsize)) {
        return 0;
    }
    size_t skip = find_ext_block(buffer, bsize);
    if (!skip) {
        return 0;
    }
    size_t sni_offs = find_tls_ext_offset(0x00, buffer, bsize, skip);
    
    if (!sni_offs || (sni_offs + 12) >= bsize) {
        return 0;
    }
    uint16_t len = ANTOHS(buffer, sni_offs + 7);
    
    if ((sni_offs + 9 + len) > bsize) {
        return 0;
    }
    *hs = (char *)&buffer[sni_offs + 9];
    return len;
}


bool is_http(const char *buffer, size_t bsize)
{
    if (bsize < 16 || *buffer > 'T' || *buffer < 'C') {
        return 0;
    }
    const char *methods[] = {
        "HEAD", "GET", "POST", "PUT", "DELETE",
        "OPTIONS", "CONNECT", "TRACE", "PATCH", 0
    };
    for (const char **m = methods; *m; m++) {
        if (strncmp(buffer, *m, strlen(*m)) == 0) {
            return 1;
        }
    }
    return 0;
}

    
int parse_http(const char *buffer, size_t bsize, char **hs, uint16_t *port)
{
    const char *host = buffer, *l_end;
    const char *buff_end = buffer + bsize;
    
    if (!is_http(buffer, bsize)) {
        return 0;
    }
    if (!(host = strncasestr(buffer, bsize, "\nHost:", 6))) {
        return 0;
    }
    host += 6;
    for (; host < buff_end && *host == ' '; host++);
    
    if (!(l_end = memchr(host, '\n', buff_end - host))) {
        return 0;
    }
    // ZapretDroid: the trims stop at the value's start. An empty value ("Host: \r\n") walked
    // back past it into "Host:" and returned a negative length (memmove of -2 in mod_http).
    for (; l_end > host && isspace((unsigned char) *(l_end - 1)); l_end--);
    if (l_end <= host) {
        return 0;
    }

    const char *h_end = l_end - 1;
    while (h_end > host && isdigit((unsigned char) *--h_end));

    if (*h_end != ':') {
        if (port) *port = 80;
        h_end = l_end;
    }
    else if (port) {
        // ZapretDroid: digits counted by hand. strtol skips leading white space, so with a
        // value of ":" alone ("Host: :\r\n") it ran over "\r\n" and past the buffer.
        long i = 0;
        const char *d = h_end + 1;
        for (; d < l_end && isdigit((unsigned char) *d) && i <= 0xffff; d++) {
            i = i * 10 + (*d - '0');
        }
        if (i <= 0 || d != l_end || i > 0xffff)
            return 0;
        *port = i;
    }
    if (*host == '[') {
        if (h_end - host < 2 || *--h_end != ']')
            return 0;
        host++;
    }
    if (h_end <= host) {
        return 0;
    }
    *hs = (char *)host;
    return h_end - host;
}


static int get_http_code(const char *b, size_t n)
{
    if (n < 13 || strncmp(b, "HTTP/1.", 7)) {
        return 0;
    }
    if (!memchr(b + 12, '\n', n - 12)) {
        return 0;
    }
    // ZapretDroid: three digits read by hand. strtol skipped white space, newlines included,
    // and could run past the server's response.
    if (!isdigit((unsigned char) b[9]) || !isdigit((unsigned char) b[10])
            || !isdigit((unsigned char) b[11]) || !isspace((unsigned char) b[12])) {
        return 0;
    }
    int num = (b[9] - '0') * 100 + (b[10] - '0') * 10 + (b[11] - '0');
    if (num < 100 || num > 511) {
        return 0;
    }
    return num;
}


bool is_http_redirect(
        const char *req, size_t qn, const char *resp, size_t sn)
{
    char *host = 0, *location;
    int len = parse_http(req, qn, &host, 0);
    
    if (len <= 0 || sn < 29) {
        return 0;
    }
    int code = get_http_code(resp, sn);
    if (code > 308 || code < 300) {
        return 0;
    }
    if (!(location = strncasestr(resp, sn, "\nLocation:", 10))
            || ((location += 11) + 8) >= (resp + sn)) {
        return 0;
    }
    char *l_end = memchr(location, '\n', sn - (location - resp));
    if (!l_end) {
        return 0;
    }
    // ZapretDroid: the trim stops at the value's start. An empty value ("Location: \r\n", any
    // server or an injected 302) walked back past it and memchr below got a negative length.
    for (; l_end > location && isspace((unsigned char) *(l_end - 1)); l_end--);
    if (l_end <= location) {
        return 0;
    }
    
    if ((l_end - location) > 7) {
        if (!strncmp(location, "http://", 7)) {
            location += 7;
        }
        else if (!strncmp(location, "https://", 8)) {
            location += 8;
        }
    }
    char *le = memchr(location, '/', l_end - location);
    if (!le) le = l_end;
    char *he = host + len, *h = he;
    
    while (h != host && *(--h - 1) != '.');
    while (h != host && *(--h - 1) != '.');
    
    return ((le - location) < (he - h)) 
        || memcmp(le - (he - h), h, he - h) != 0;
}


bool neq_tls_sid(const char *req, size_t qn, const char *resp, size_t sn)
{
    if (qn < 75 || sn < 75) {
        return 0;
    }
    if (!is_tls_chello(req, qn)
            || ANTOHS(resp, 0) != 0x1603) {
        return 0;
    }
    uint8_t sid_len = req[43];
    size_t skip = 44 + sid_len + 3;
    
    if (!find_tls_ext_offset(0x2b, resp, sn, skip)) {
        return 0;
    }
    if (req[43] != resp[43]) {
        return 1;
    }
    return memcmp(req + 44, resp + 44, sid_len);
}


bool is_tls_shello(const char *buffer, size_t bsize)
{
    return (bsize > 5 &&
        ANTOHS(buffer, 0) == 0x1603 &&
        buffer[5] == 0x02);
}

/*
bool is_dns_req(char *buffer, size_t n)
{
    if (n < 12) {
        return 0;
    }
    return !memcmp(buffer + 2, "\1\0\0\1\0\0\0\0\0\0", 10);
}


bool is_quic_initial(char *buffer, size_t bsize)
{
    return (bsize > 64 && (buffer[0] & 0xc0) == 0xc0);
}
*/

/* QUIC long header Initial packet (v1 and drafts: type 0, v2 0x6b3343cf: type 1). */
static bool is_quic_initial_pkt(const uint8_t *d, size_t len)
{
    if (len < 7 || (d[0] & 0xc0) != 0xc0) {
        return 0;
    }
    uint32_t ver = ((uint32_t) d[1] << 24) | ((uint32_t) d[2] << 16) | ((uint32_t) d[3] << 8) | d[4];
    if (!ver) {
        return 0; // version negotiation
    }
    uint8_t type = (d[0] >> 4) & 3;
    return ver == 0x6b3343cf ? type == 1 : type == 0;
}

/* Discord voice IP discovery request: type 1, length 70, 74 bytes in total. */
static bool is_discord_ip_discovery(const uint8_t *d, size_t len)
{
    return len == 74 && d[0] == 0 && d[1] == 1 && d[2] == 0 && d[3] == 70;
}

/* STUN: two top bits zero, magic cookie 0x2112A442, length matching. */
static bool is_stun(const uint8_t *d, size_t len)
{
    return len >= 20 && !(d[0] & 0xc0) && d[4] == 0x21 && d[5] == 0x12
        && d[6] == 0xa4 && d[7] == 0x42 && (((size_t) d[2] << 8 | d[3]) + 20) == len;
}

int udp_detect_proto(const char *data, size_t len)
{
    const uint8_t *d = (const uint8_t *) data;
    if (is_quic_initial_pkt(d, len)) return UDP_PROTO_QUIC;
    if (is_discord_ip_discovery(d, len)) return UDP_PROTO_DISCORD;
    if (is_stun(d, len)) return UDP_PROTO_STUN;
    return UDP_PROTO_UNKNOWN;
}

const char *udp_proto_name(int proto)
{
    switch (proto) {
        case UDP_PROTO_QUIC: return "quic";
        case UDP_PROTO_DISCORD: return "discord";
        case UDP_PROTO_STUN: return "stun";
        default: return "other";
    }
}


int mod_http(char *buffer, size_t bsize, int m)
{
    char *host = 0, *par;
    int hlen = parse_http(buffer, bsize, &host, 0);
    if (!hlen)
        return -1;
    for (par = host - 1; *par != ':'; par--) {}
    par -= 4;
    if (m & MH_HMIX) {
        par[0] = tolower((unsigned char) par[0]);
        par[1] = toupper((unsigned char) par[1]);
        par[3] = toupper((unsigned char) par[3]);
    }
    if (m & MH_DMIX) {
        for (int i = 0; i < hlen; i += 2) {
            host[i] = toupper((unsigned char)host[i]);
        }
    }
    if (m & MH_SPACE) {
        for (; !isspace((unsigned char) *(host + hlen)); hlen++) {}
        int sc = host - (par + 5);
        memmove(par + 5, host, hlen);
        memset(par + 5 + hlen, '\t', sc);
    }
    return 0;
}


int part_tls(char *buffer, size_t bsize, ssize_t n, long pos)
{
    if ((n < 3) || (bsize - n < 5) || 
            (pos < 0) || (pos + 5 > n)) {
        return 0;
    }
    uint16_t r_sz = ANTOHS(buffer, 3);
    if (r_sz < pos) {
        return n;
    }
    memmove(buffer + 5 + pos + 5, buffer + 5 + pos, n - (5 + pos));
    memcpy(buffer + 5 + pos, buffer, 3);
    
    SHTONA(buffer, 3, pos);
    SHTONA(buffer, 5 + pos + 3, r_sz - pos);
    return 5;
}


static void gen_rand_array(char *out, size_t len)
{
    for (; len; len--, out++) {
        uint8_t c = rand() % 256;
        *((uint8_t *)out) = c;
    }
}


void randomize_tls(char *buffer, ssize_t n)
{
    if (n < 44) {
        return;
    }
    uint8_t sid_len = buffer[43];
    if (n < (44l + sid_len + 2)) {
        return;
    }
    gen_rand_array(buffer + 11, 32);
    gen_rand_array(buffer + 44, sid_len);
    
    size_t skip = find_ext_block(buffer, n);
    if (!skip) {
        return;
    }
    ssize_t ks_offs = find_tls_ext_offset(0x0033, buffer, n, skip);
    if (!ks_offs || ks_offs + 6 >= n) {
        return;
    }
    int ks_sz = ANTOHS(buffer, ks_offs + 2);
    if (ks_offs + 4 + ks_sz > n) {
        return;
    }
    ssize_t g_offs = ks_offs + 4 + 2;
    while (g_offs + 4 < ks_offs + 4 + ks_sz) {
        uint16_t g_sz = ANTOHS(buffer, g_offs + 2);
        if (g_offs + 4 + g_sz > n) { // ZapretDroid: the group's own end, not ks_offs
            return;
        }
        gen_rand_array(buffer + g_offs + 4, g_sz);
        g_offs += 4 + g_sz;
    }
}
