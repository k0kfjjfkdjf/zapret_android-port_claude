#ifndef PARAMS_H
#define PARAMS_H

#include <stdint.h>
#include <stdio.h>
#include <stdbool.h>
#include <assert.h>

#ifdef _WIN32
    #include <ws2tcpip.h>
#else
    #include <arpa/inet.h>
    #include <netinet/in.h>
    #include <unistd.h>
    #include <sys/socket.h>
#endif

#include "mpool.h"

#if defined(__linux__) || defined(_WIN32)
#define FAKE_SUPPORT 1
#define TIMEOUT_SUPPORT 1
#endif

#define OFFSET_END 1
#define OFFSET_MID 2
#define OFFSET_RAND 4
#define OFFSET_SNI 8
#define OFFSET_HOST 16
#define OFFSET_START 32

#define DETECT_HTTP_LOCAT 1
#define DETECT_TLS_ERR 2
#define DETECT_TORST 8
#define DETECT_CONNECT 16
// ZapretDroid --auto=udp: the first destination of a UDP flow did not answer
#define DETECT_UDP 32

#define AUTO_ONRECONN 1
#define AUTO_NORECONN 2
#define AUTO_SORT 4

#define MODE_SOCKS5 1
#define MODE_SOCKS4 2
#define MODE_HTTP 4
#define MODE_TRANSPARENT 8
#define MODE_SHADOWSOCKS 16
#define MODE_RAWTLS 32
#define MODE_TCP 64
#define MODE_SOCKS (MODE_SOCKS4 | MODE_SOCKS5)

#define OUT_SUPPORT (MODE_SOCKS5 | MODE_TCP)

#define FM_RAND 1
#define FM_ORIG 2

enum demode {
    DESYNC_NONE,
    DESYNC_SPLIT,
    DESYNC_DISORDER,
    DESYNC_OOB,
    DESYNC_DISOOB,
    DESYNC_FAKE
};

#ifdef STR_MODE
static const char *demode_str[] = {
    "DESYNC_NONE",
    "DESYNC_SPLIT",
    "DESYNC_DISORDER",
    "DESYNC_OOB",
    "DESYNC_DISOOB",
    "DESYNC_FAKE"
};
#endif

union sockaddr_u {
    struct sockaddr sa;
    struct sockaddr_in in;
    struct sockaddr_in6 in6;
};

struct part {
    int m;
    int flag;
    long pos;
    int r, s;
};

struct packet {
     ssize_t size;
     char  *data;
     ssize_t off;
};

struct desync_params {
    int ttl;
    bool md5sig;
    struct packet fake_data;
    // UDP fakes chosen by the protocol of the flow (--udp-proto-fake), else fake_data. A
    // directory gives several fakes used in turn (QUIC Initials with different SNIs, say).
    // Indexed by udp_proto - 1: quic, discord, stun.
    struct packet *proto_fakes[3];
    int proto_fakes_n[3];
    unsigned int proto_fakes_next[3];
    // --udp-desync N[:K]: fakes before the first N datagrams, then before every K-th
    int udp_first, udp_every;
    // ZapretDroid --quic-split <marker|N>: a client's first QUIC Initial carries its ClientHello
    // in two CRYPTO frames, the part from this point first (see quic_split_initial).
    bool quic_split;
    int quic_split_marker;
    long quic_split_off;
    int udp_fake_count;
    struct part fake_offset;
    int fake_sni_count;
    const char **fake_sni_list;
    int fake_mod;
    int fake_tls_size;
    bool drop_sack;
    char oob_char[2];
    
    int parts_n;
    struct part *parts;
    
    int mod_http;
    char hostspell[5];
    int hostpad;
    int tlsrec_n;
    struct part *tlsrec;
    uint8_t tlsminor;
    bool tlsminor_set;
    
    int proto;
    short detect;
    short auto_level;
    struct mphdr *hosts;
    struct mphdr *ipset;
    uint16_t pf[2];
    int rounds[2];
    
    uint16_t out_type;
    // ZapretDroid --udp-proxy: with -C socks5://…, the group's UDP also goes through the proxy
    // (its UDP ASSOCIATE); without it UDP of a -C group is dropped (Tor carries TCP only).
    bool udp_proxy;
    // ZapretDroid: while the proxy refuses connections, one log line per burst (see proxy_refused).
    long long refused_ms;
    int refused_n;
    // ZapretDroid --apps: the group applies only to connections of these apps (Android uids),
    // looked up from the client's source address (see conev.h, app_src).
    int *apps;
    int apps_n;
    // ZapretDroid --slot: the group's host list is filled while the engine runs (slot_assign).
    char *slot;
    union sockaddr_u out_addr;
    
    int _optind;
    int id;
    uint64_t bit;
    int fail_count;
    int pri;
    const char *str;
    long cache_ttl;
    const char *cache_file;
    
    struct desync_params *prev;
    struct desync_params *next;
};

struct params {
    int dp_n;
    struct desync_params *dp;
    uint64_t dp_full_mask;
    int await_int;
    bool wait_send;
    int def_ttl;
    bool custom_ttl;
    
    bool tfo;
    unsigned int timeout, ptimeout;
    int to_count_lim;
    int to_bytes_lim;
    char cache_pre;
    int mode;
    bool ipv6;
    bool resolve;
    bool udp;
    bool delay_conn;
    bool auto_reconnect;
    int max_open;
    int debug;
    size_t bfsize;
    union sockaddr_u baddr;
    union sockaddr_u laddr;
    struct mphdr *mempool;
    // ZapretDroid: the --auto cache of UDP flows (--auto=udp), apart from TCP's: the same
    // address and port (443) is another flow there, with groups of its own.
    struct mphdr *udp_cache;
    bool udp_auto;
    
    char **need_free;
    int need_free_n;
    
    const char *protect_path;
    const char *autohost_log;
    // ZapretDroid --auto-cache: file where the --auto cache (address -> group) is kept across
    // restarts; saved at exit and at most every AUTO_CACHE_SAVE_S seconds after a change.
    const char *auto_cache;
    bool auto_cache_dirty;
    long long auto_cache_saved;
    bool conn_log;
    // --mss: TCP maximum segment size of outgoing connections (as tpws --mss): both sides then
    // send small segments
    int mss;
    // ZapretDroid --names-dns: DNS servers whose answers name addresses for host lists (the
    // VPN's DNS); without it, answers from any server to a query the engine relayed count.
    union sockaddr_u names_dns[4];
    int names_dns_n;
    // ZapretDroid --dns-redirect <ip>=<ip>:<port>: UDP DNS to the first address (port 53) goes
    // to the app's resolver at the second (DNS over HTTPS/TLS); its answers come back as if
    // from the first.
    bool dns_redir;
    union sockaddr_u dns_redir_from, dns_redir_to;
    bool daemonize;
    const char *pid_file;
    int pid_fd;
};

extern struct params params;

extern struct packet fake_tls;
extern struct packet fake_http;
extern struct packet fake_udp;

#define ASSERT(exp) \
    char t[(exp) ? 1 : -1];
#endif
