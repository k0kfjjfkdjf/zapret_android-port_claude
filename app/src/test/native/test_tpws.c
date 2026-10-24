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
#include "udphost.h"
#include "extend.h"
#include "mpool.h"
#include "proxy.h"
#include "conev.h"

#include <arpa/inet.h>
#include <unistd.h>

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

static size_t read_fake(const char *dir, const char *name, uint8_t *buf, size_t size)
{
    char path[1024];
    snprintf(path, sizeof(path), "%s/%s", dir, name);
    FILE *f = fopen(path, "rb");
    if (!f) return 0;
    size_t n = fread(buf, 1, size, f);
    fclose(f);
    return n;
}

/* SNI of QUIC Initials, also when Chrome's ClientHello with Kyber spans two (in either order). */
static void test_quic_sni(const char *fakes)
{
    struct { const char *first, *second, *sni; } cases[] = {
        { "quic_initial_www_google_com.bin", 0, "www.google.com" },
        { "quic_initial_facebook_com_quiche.bin", 0, "facebook.com" },
        { "quic2_example_com.bin", 0, "example.com" },
        { "quic_initial_rutracker_org_kyber_1.bin", "quic_initial_rutracker_org_kyber_2.bin", "rutracker.org" },
        { "quic_initial_rr1---sn-xguxaxjvh-n8me_googlevideo_com_kyber_2.bin",
          "quic_initial_rr1---sn-xguxaxjvh-n8me_googlevideo_com_kyber_1.bin", "rr1---sn-xguxaxjvh-n8me.googlevideo.com" },
    };
    static struct quic_crypto qc;
    uint8_t buf[4096];
    char sni[256];
    for (size_t i = 0; i < sizeof(cases) / sizeof(*cases); i++) {
        memset(&qc, 0, sizeof(qc));
        size_t n = read_fake(fakes, cases[i].first, buf, sizeof(buf));
        CHECK(quic_add_initial(&qc, buf, n));
        int len = quic_crypto_sni(&qc, sni, sizeof(sni));
        if (cases[i].second) {
            CHECK_EQ(len, 0);
            n = read_fake(fakes, cases[i].second, buf, sizeof(buf));
            CHECK(quic_add_initial(&qc, buf, n));
            len = quic_crypto_sni(&qc, sni, sizeof(sni));
        }
        CHECK_EQ(len, (long) strlen(cases[i].sni));
        CHECK(len && !strcmp(sni, cases[i].sni));
    }
    memset(&qc, 0, sizeof(qc));
    size_t n = read_fake(fakes, "quic_short_header.bin", buf, sizeof(buf));
    CHECK(!quic_add_initial(&qc, buf, n));
    n = read_fake(fakes, "stun.bin", buf, sizeof(buf));
    CHECK(!quic_add_initial(&qc, buf, n));
}

/* DNS answers name the addresses: the question's name, also through a CNAME. */
static void test_dns_cache(void)
{
    static const uint8_t answer[] = {
        0x12, 0x34, 0x81, 0x80, 0, 1, 0, 2, 0, 0, 0, 0,
        // question: C-FRA06.Discord.Media A IN
        7, 'C', '-', 'F', 'R', 'A', '0', '6', 7, 'D', 'i', 's', 'c', 'o', 'r', 'd', 5, 'M', 'e', 'd', 'i', 'a', 0,
        0, 1, 0, 1,
        // CNAME to x.y
        0xc0, 12, 0, 5, 0, 1, 0, 0, 0, 60, 0, 5, 1, 'x', 1, 'y', 0,
        // x.y A 162.159.130.234
        0xc0, 51, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 162, 159, 130, 234,
    };
    dns_cache_reset();
    dns_cache_feed(answer, sizeof(answer));
    union sockaddr_u a = {0};
    a.in.sin_family = AF_INET;
    memcpy(&a.in.sin_addr, (const uint8_t[]) {162, 159, 130, 234}, 4);
    const char *name = dns_cache_get(&a);
    CHECK(name && !strcmp(name, "c-fra06.discord.media"));
    // IPv4-mapped IPv6, as the engine sees addresses on an IPv6 socket
    union sockaddr_u m = {0};
    m.in6.sin6_family = AF_INET6;
    memcpy(&m.in6.sin6_addr, (const uint8_t[]) {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, 162, 159, 130, 234}, 16);
    name = dns_cache_get(&m);
    CHECK(name && !strcmp(name, "c-fra06.discord.media"));
    memcpy(&a.in.sin_addr, (const uint8_t[]) {1, 2, 3, 4}, 4);
    CHECK(!dns_cache_get(&a));
    // A query (QR not set) and a truncated message change nothing
    uint8_t query[sizeof(answer)];
    memcpy(query, answer, sizeof(answer));
    query[2] = 0x01;
    query[sizeof(query) - 1] = 1;
    dns_cache_feed(query, sizeof(query));
    dns_cache_feed(answer, 40);
    memcpy(&a.in.sin_addr, (const uint8_t[]) {162, 159, 130, 1}, 4);
    CHECK(!dns_cache_get(&a));
    dns_cache_reset();
}

int engine_main(int argc, char **argv);
void engine_reset(void);
void clear_params(char *line, char **argv);
extern int engine_stop_requested;

/* A stop requested before the engine listens makes it return instead of serving forever. */
static void test_early_stop(void)
{
    char *args[] = { "ciadpi", "-i", "127.0.0.1", "-p", "10997", 0 };
    engine_reset();
    engine_stop_requested = 1;
    CHECK_EQ(engine_main(5, args), 0);
    engine_stop_requested = 0;
    engine_reset();
}

/* --dns-redirect and --names-dns: accepted forms and rejected ones. */
static void test_dns_options(void)
{
    struct { const char *opt, *val; int ok; } cases[] = {
        { "--dns-redirect", "10.10.10.53=127.0.0.1:5353", 1 },
        { "--dns-redirect", "10.10.10.53=127.0.0.1", 0 },     // no port
        { "--dns-redirect", "10.10.10.53", 0 },
        { "--dns-redirect", "host=127.0.0.1:53", 0 },
        { "--names-dns", "8.8.8.8,2001:4860::8888", 1 },
        { "--names-dns", "8.8.8.8,not-an-ip", 0 },
    };
    for (size_t i = 0; i < sizeof(cases) / sizeof(*cases); i++) {
        char *args[] = { "ciadpi", (char *) cases[i].opt, (char *) cases[i].val, 0 };
        engine_reset();
        int failed = parse_args(3, args) != 0;
        clear_params(0, 0);
        if (failed == cases[i].ok) {
            failures++;
            fprintf(stderr, "%s %s: expected %s\n", cases[i].opt, cases[i].val, cases[i].ok ? "accepted" : "rejected");
        }
    }
    engine_reset();
}

/* --slot groups: hosts moved between them at run time; --slot with --hosts is refused. */
static void test_slots(void)
{
    char *args[] = { "ciadpi", "--slot", "a", "--disorder", "1", "-A", "none", "--slot", "b", "--split", "1", 0 };
    engine_reset();
    CHECK_EQ(parse_args(11, args), 0);
    struct desync_params *a = params.dp, *b = a ? a->next : 0;
    CHECK(a && b && a->slot && b->slot);
    if (a && b) {
        CHECK_EQ(slot_assign("example.com", "a"), 0);
        CHECK(mem_get(a->hosts, "example.com", 11) != 0);
        CHECK(mem_get(b->hosts, "example.com", 11) == 0);
        CHECK_EQ(slot_assign("example.com", "b"), 0);
        CHECK(mem_get(a->hosts, "example.com", 11) == 0);
        CHECK(mem_get(b->hosts, "example.com", 11) != 0);
        CHECK_EQ(slot_assign("example.com", "nope"), -1);
        CHECK(mem_get(b->hosts, "example.com", 11) == 0);
        CHECK_EQ(slot_assign("example.com", "a"), 0);
        CHECK_EQ(slot_assign("example.com", 0), 0);
        CHECK(mem_get(a->hosts, "example.com", 11) == 0);
    }
    clear_params(0, 0);
    char *both[] = { "ciadpi", "--slot", "a", "--hosts", "/dev/null", 0 };
    engine_reset();
    CHECK(parse_args(5, both) != 0);
    clear_params(0, 0);
    engine_reset();
}

/*
 * --udp-proxy: replies from the proxy's relay are recognised however recvfrom names the sender.
 * On a dual-stack socket (phones) an IPv4 relay comes back as ::ffff:a.b.c.d.
 */
static void test_ups_relay(void)
{
    struct udp_upstream u = { 0 };
    struct eval remote = { 0 };
    remote.ups = &u;
    u.state = 3;
    u.relay.in.sin_family = AF_INET;
    u.relay.in.sin_port = htons(40000);
    inet_pton(AF_INET, "127.0.0.1", &u.relay.in.sin_addr);

    union sockaddr_u from = u.relay;
    CHECK(ups_is_relay(&remote, &from));
    map_fix(&from, 6); // as a dual-stack socket reports it
    CHECK_EQ(from.sa.sa_family, AF_INET6);
    CHECK(ups_is_relay(&remote, &from));
    from.in6.sin6_port = htons(40001);
    CHECK(!ups_is_relay(&remote, &from));
    from = u.relay;
    inet_pton(AF_INET, "127.0.0.2", &from.in.sin_addr);
    CHECK(!ups_is_relay(&remote, &from));
    u.state = 2; // no relay yet
    CHECK(!ups_is_relay(&remote, &u.relay));
}

/*
 * --fake-sni on real ClientHellos (Chrome's with Kyber too): the SNI is replaced, longer or
 * shorter, and the hello still parses, in place (nn = n, --fake-tls-mod orig) and grown from
 * a smaller fake into a bigger buffer.
 */
static void test_change_sni(const char *fakes)
{
    static const char *files[] = {
        "tls_clienthello_www_google_com.bin", "tls_clienthello_vk_com_kyber.bin",
        "tls_clienthello_rutracker_org_kyber.bin",
    };
    static const char *names[] = { "www.google.com", "a.co", "averyveryverylonghostname.example.com" };
    for (size_t i = 0; i < sizeof(files) / sizeof(*files); i++) {
        uint8_t orig[8192];
        size_t n = read_fake(fakes, files[i], orig, sizeof(orig));
        CHECK(n > 0);
        if (!n) continue;
        for (size_t j = 0; j < sizeof(names) / sizeof(*names); j++) {
            for (int grow = 0; grow < 2; grow++) {
                size_t nn = grow ? n + 64 : n;
                char *buf = malloc(nn);
                memcpy(buf, orig, n);
                int r = change_tls_sni(names[j], buf, n, nn);
                char *host = 0;
                int len = r ? 0 : parse_tls(buf, nn, &host);
                if (r || len != (int) strlen(names[j]) || memcmp(host, names[j], len)) {
                    failures++;
                    fprintf(stderr, "change_tls_sni(%s, %s, nn=%zu): %d, parsed %d\n", files[i], names[j], nn, r, len);
                }
                free(buf);
            }
        }
    }
}

/*
 * --fake-tls-mod orig with --fake-sni rewrites the client's own ClientHello. TLS records of
 * length 0 made merge_tls_records() loop forever (found by fuzzing): any app could freeze the
 * engine with such a first packet. The alarm turns a hang into a failure.
 */
static void test_empty_tls_records(void)
{
    char buf[200] = { 0 };
    for (int i = 0; i + 5 <= 50; i += 5) {
        memcpy(buf + i, "\x16\x03\x01\x00\x00", 5);
    }
    alarm(10);
    change_tls_sni("www.example.com", buf, 50, sizeof(buf));
    alarm(0);
}

/* HTTP parsing on buffers that end right after the request (no NUL behind them). */
static void test_http_parse(void)
{
    static const char *bad[] = {
        "GET / HTTP/1.1\r\nHost: :\r\n",
        "GET / HTTP/1.1\r\nHost: \r\n",
        "GET / HTTP/1.1\r\nHost: a:99999\r\n",
        "GET / HTTP/1.1\r\nHost: []:80\r\n",
    };
    for (size_t i = 0; i < sizeof(bad) / sizeof(*bad); i++) {
        size_t n = strlen(bad[i]);
        char *b = malloc(n); // exact size: no NUL after the request
        memcpy(b, bad[i], n);
        char *hs = 0;
        uint16_t port = 0;
        CHECK_EQ(parse_http(b, n, &hs, &port), 0);
        free(b);
    }
    const char ok[] = "GET / HTTP/1.1\r\nHost: example.com:8080\r\n";
    char *hs = 0;
    uint16_t port = 0;
    CHECK_EQ(parse_http(ok, sizeof(ok) - 1, &hs, &port), 11);
    CHECK_EQ(port, 8080);
    const char v6[] = "GET / HTTP/1.1\r\nHost: [::1]:81\r\n";
    CHECK_EQ(parse_http(v6, sizeof(v6) - 1, &hs, &port), 3);
    CHECK_EQ(port, 81);

    // A redirect answer that is only white space after "HTTP/1.1": nothing past it is read.
    const char req[] = "GET / HTTP/1.1\r\nHost: a.example.com\r\n\r\n";
    const char resp[] = "HTTP/1.1                     \n";
    char *r = malloc(sizeof(resp) - 1);
    memcpy(r, resp, sizeof(resp) - 1);
    CHECK(!is_http_redirect(req, sizeof(req) - 1, r, sizeof(resp) - 1));
    free(r);
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
    test_http_parse();
    if (argc > 2) test_udp_proto(argv[2]);
    if (argc > 2) test_quic_sni(argv[2]);
    test_dns_cache();
    test_early_stop();
    test_dns_options();
    test_slots();
    test_ups_relay();
    test_empty_tls_records();
    if (argc > 2) test_change_sni(argv[2]);
    if (failures) {
        fprintf(stderr, "%d check(s) failed\n", failures);
        return 1;
    }
    printf("native tests passed\n");
    return 0;
}
