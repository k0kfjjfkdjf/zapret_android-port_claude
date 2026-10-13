/*
 * Host tests for the zapret features in the engine (tpws.c) and their option parsing.
 * Run with ./run.sh.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>

#include "params.h"
#include "packets.h"
#include "tpws.h"

int parse_offset(struct part *part, const char *str);
int parse_args(int argc, char **argv);

static int failures;

#define CHECK(cond) do { \
    if (!(cond)) { failures++; fprintf(stderr, "%s:%d: CHECK failed: %s\n", __FILE__, __LINE__, #cond); } \
} while (0)

#define CHECK_EQ(a, b) do { \
    long _a = (long) (a), _b = (long) (b); \
    if (_a != _b) { failures++; fprintf(stderr, "%s:%d: %s == %ld, expected %ld\n", __FILE__, __LINE__, #a, _a, _b); } \
} while (0)

static const char REQ[] = "GET / HTTP/1.1\r\nHost: www.example.com\r\nAccept: */*\r\n\r\n";

static void mod(int flags, const char *spell, int pad, const char *expected)
{
    char buf[512];
    ssize_t n = sizeof(REQ) - 1;
    memcpy(buf, REQ, n);
    tpws_mod_http(buf, sizeof(buf), &n, flags, spell, pad);
    if ((size_t) n != strlen(expected) || memcmp(buf, expected, n)) {
        failures++;
        fprintf(stderr, "mod 0x%x: got \"%.*s\"\n", flags, (int) n, buf);
    }
}

static void test_http_mods(void)
{
    mod(MH_HOSTDOT, 0, 0, "GET / HTTP/1.1\r\nHost: www.example.com.\r\nAccept: */*\r\n\r\n");
    mod(MH_HOSTTAB, 0, 0, "GET / HTTP/1.1\r\nHost: www.example.com\t\r\nAccept: */*\r\n\r\n");
    mod(MH_HOSTNOSPACE, 0, 0, "GET / HTTP/1.1\r\nHost:www.example.com\r\nAccept: */*\r\n\r\n");
    mod(MH_HOSTCASE, 0, 0, "GET / HTTP/1.1\r\nhost: www.example.com\r\nAccept: */*\r\n\r\n");
    mod(MH_HOSTCASE, "HoSt", 0, "GET / HTTP/1.1\r\nHoSt: www.example.com\r\nAccept: */*\r\n\r\n");
    mod(MH_METHODSPACE, 0, 0, "GET  / HTTP/1.1\r\nHost: www.example.com\r\nAccept: */*\r\n\r\n");
    mod(MH_METHODEOL, 0, 0, "\r\nGET / HTTP/1.1\r\nHost: www.example.com\r\nAccept: */*\r\n\r\n");
    mod(MH_UNIXEOL, 0, 0, "GET / HTTP/1.1\nHost: www.example.com\nAccept: */*\n\n");
    mod(MH_UNIXEOL | MH_METHODEOL, 0, 0, "\nGET / HTTP/1.1\nHost: www.example.com\nAccept: */*\n\n");

    // hostpad inserts dummy headers of exactly the requested size before Host:
    char buf[512];
    ssize_t n = sizeof(REQ) - 1;
    memcpy(buf, REQ, n);
    tpws_mod_http(buf, sizeof(buf), &n, MH_HOSTPAD, 0, 40);
    CHECK_EQ(n, (long) sizeof(REQ) - 1 + 40);
    CHECK(!memcmp(buf, "GET / HTTP/1.1\r\n", 16));
    CHECK(!memcmp(buf + 16 + 40, "Host: www.example.com\r\n", 23));
    CHECK(buf[16 + 5] == ':' && buf[16 + 40 - 2] == '\r' && buf[16 + 40 - 1] == '\n');

    // Growing modifications respect the buffer size.
    char small[sizeof(REQ) - 1];
    n = sizeof(REQ) - 1;
    memcpy(small, REQ, n);
    tpws_mod_http(small, sizeof(small), &n, MH_HOSTDOT | MH_METHODEOL | MH_HOSTPAD, 0, 100);
    CHECK_EQ(n, (long) sizeof(small));
}

static void test_markers_http(void)
{
    const char *r = REQ;
    size_t n = sizeof(REQ) - 1;
    long host = strstr(r, "www.") - r;
    CHECK_EQ(tpws_marker_pos(MK_METHOD, r, n), 0);
    CHECK_EQ(tpws_marker_pos(MK_HOST, r, n), host);
    CHECK_EQ(tpws_marker_pos(MK_ENDHOST, r, n), host + 15);
    CHECK_EQ(tpws_marker_pos(MK_SLD, r, n), host + 4);
    CHECK_EQ(tpws_marker_pos(MK_MIDSLD, r, n), host + 4 + 3);
    CHECK_EQ(tpws_marker_pos(MK_ENDSLD, r, n), host + 4 + 7);
    CHECK_EQ(tpws_marker_pos(MK_SNIEXT, r, n), -1);

    // After methodeol the method marker skips the inserted line break.
    char buf[256];
    ssize_t m = n;
    memcpy(buf, r, n);
    tpws_mod_http(buf, sizeof(buf), &m, MH_METHODEOL, 0, 0);
    CHECK_EQ(tpws_marker_pos(MK_METHOD, buf, m), 2);
    CHECK_EQ(tpws_marker_pos(MK_HOST, buf, m), host + 2);

    // hostdot: endhost includes the dot (as tpws), the SLD markers ignore it.
    m = n;
    memcpy(buf, r, n);
    tpws_mod_http(buf, sizeof(buf), &m, MH_HOSTDOT, 0, 0);
    CHECK_EQ(tpws_marker_pos(MK_ENDHOST, buf, m), host + 16);
    CHECK_EQ(tpws_marker_pos(MK_MIDSLD, buf, m), host + 4 + 3);

    const char *one = "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n";
    CHECK_EQ(tpws_marker_pos(MK_SLD, one, strlen(one)), -1);
}

static void test_markers_tls(const char *path)
{
    FILE *f = fopen(path, "rb");
    CHECK(f != 0);
    if (!f) return;
    char buf[4096];
    size_t n = fread(buf, 1, sizeof(buf), f);
    fclose(f);

    char *host = 0;
    int len = parse_tls(buf, n, &host);
    CHECK_EQ(len, 14); // www.google.com
    long h = host - buf;
    CHECK(!memcmp(buf + h, "www.google.com", 14));
    CHECK_EQ(tpws_marker_pos(MK_HOST, buf, n), h);
    CHECK_EQ(tpws_marker_pos(MK_ENDHOST, buf, n), h + 14);
    CHECK_EQ(tpws_marker_pos(MK_SLD, buf, n), h + 4);
    CHECK_EQ(tpws_marker_pos(MK_MIDSLD, buf, n), h + 4 + 3);
    CHECK_EQ(tpws_marker_pos(MK_ENDSLD, buf, n), h + 4 + 6);
    // SNI extension data starts with the 2-byte server name list length.
    long ext = tpws_marker_pos(MK_SNIEXT, buf, n);
    CHECK_EQ(ext, h - 5);
    CHECK_EQ(((unsigned char) buf[ext - 4] << 8) | (unsigned char) buf[ext - 3], 0); // type server_name
    CHECK_EQ(tpws_marker_pos(MK_METHOD, buf, n), -1);
}

static void test_parsing(void)
{
    int mk; long off;
    CHECK(!tpws_parse_marker("midsld", &mk, &off) && mk == MK_MIDSLD && off == 0);
    CHECK(!tpws_parse_marker("host+1", &mk, &off) && mk == MK_HOST && off == 1);
    CHECK(!tpws_parse_marker("endhost-2", &mk, &off) && mk == MK_ENDHOST && off == -2);
    CHECK(!tpws_parse_marker("sniext+4", &mk, &off) && mk == MK_SNIEXT && off == 4);
    CHECK(!tpws_parse_marker("sld", &mk, &off) && mk == MK_SLD);
    CHECK(tpws_parse_marker("hostx", &mk, &off) < 0);
    CHECK(tpws_parse_marker("host+", &mk, &off) < 0);
    CHECK(tpws_parse_marker("bogus", &mk, &off) < 0);

    struct part p = { 0 };
    CHECK_EQ(parse_offset(&p, "midsld+1"), 0);
    CHECK_EQ(p.flag, OFFSET_MARKER | (MK_MIDSLD << MARKER_SHIFT));
    CHECK_EQ(p.pos, 1);
    memset(&p, 0, sizeof(p));
    CHECK_EQ(parse_offset(&p, "1+s"), 0); // the original syntax still works
    CHECK_EQ(p.flag, OFFSET_SNI);
    CHECK(parse_offset(&p, "nonsense") < 0);

    int flags = 0, pad = 0; char spell[5] = { 0 };
    CHECK_EQ(tpws_parse_http_mod("hostdot", 7, &flags, spell, &pad), 0);
    CHECK_EQ(tpws_parse_http_mod("hostpad=300", 11, &flags, spell, &pad), 0);
    CHECK_EQ(tpws_parse_http_mod("hostspell=HOst", 14, &flags, spell, &pad), 0);
    CHECK_EQ(flags, MH_HOSTDOT | MH_HOSTPAD | MH_HOSTCASE);
    CHECK_EQ(pad, 300);
    CHECK(!strcmp(spell, "HOst"));
    CHECK_EQ(tpws_parse_http_mod("h", 1, &flags, spell, &pad), 1);       // original hcsmix
    CHECK_EQ(tpws_parse_http_mod("hcsmix", 6, &flags, spell, &pad), 1);
    CHECK_EQ(tpws_parse_http_mod("hostpad=0", 9, &flags, spell, &pad), -1);
    CHECK_EQ(tpws_parse_http_mod("hostfoo", 7, &flags, spell, &pad), -1);
}

static void test_udp_proto(const char *fakes)
{
    char path[1024];
    char buf[2048];
    struct { const char *file; int proto; } cases[] = {
        { "quic_initial_www_google_com.bin", UDP_PROTO_QUIC },
        { "quic_initial_vk_com.bin", UDP_PROTO_QUIC },
        { "quic2_example_com.bin", UDP_PROTO_QUIC },
        { "discord-ip-discovery-with-port.bin", UDP_PROTO_DISCORD },
        { "stun.bin", UDP_PROTO_STUN },
        { "quic_short_header.bin", UDP_PROTO_UNKNOWN },
        { "dns.bin", UDP_PROTO_UNKNOWN },
        { "wireguard_initiation.bin", UDP_PROTO_UNKNOWN },
    };
    for (size_t i = 0; i < sizeof(cases) / sizeof(*cases); i++) {
        snprintf(path, sizeof(path), "%s/%s", fakes, cases[i].file);
        FILE *f = fopen(path, "rb");
        CHECK(f != 0);
        if (!f) continue;
        size_t n = fread(buf, 1, sizeof(buf), f);
        fclose(f);
        int got = udp_detect_proto(buf, n);
        if (got != cases[i].proto) {
            failures++;
            fprintf(stderr, "%s: detected %s, expected %s\n", cases[i].file, udp_proto_name(got), udp_proto_name(cases[i].proto));
        }
    }
    CHECK_EQ(udp_detect_proto("", 0), UDP_PROTO_UNKNOWN);
}

int main(int argc, char **argv)
{
    if (argc > 1 && !strcmp(argv[1], "--parse")) {
        // Checks that a strategy command line is accepted by the engine's option parser.
        argv[1] = "ciadpi";
        return parse_args(argc - 1, argv + 1) ? 1 : 0;
    }
    test_http_mods();
    test_markers_http();
    test_markers_tls(argc > 1 ? argv[1] : "tls_clienthello_www_google_com.bin");
    test_parsing();
    if (argc > 2) test_udp_proto(argv[2]);
    if (failures) {
        fprintf(stderr, "%d check(s) failed\n", failures);
        return 1;
    }
    printf("native tests passed\n");
    return 0;
}
