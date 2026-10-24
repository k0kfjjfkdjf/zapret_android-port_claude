#include "extend.h"

#ifdef _WIN32
    #include <ws2tcpip.h>
    
    #ifndef TCP_MAXRT
    #define TCP_MAXRT 5
    #endif
#else
    #include <arpa/inet.h>
    #include <netinet/tcp.h>
    #include <sys/un.h>
    #include <sys/time.h>
#endif

#include <stdlib.h>
#include <errno.h>
#ifndef _WIN32
#include <fcntl.h>
#endif
#include <string.h>
#include <stdio.h>
#include <time.h>
#include <assert.h>

#include "proxy.h"
#include "error.h"
#include "params.h"

#include "desync.h"
#include "packets.h"
#include "udphost.h"

static bool dest_equ(const union sockaddr_u *a, const union sockaddr_u *b);


static int set_timeout(int fd, unsigned int s)
{
    #ifdef __linux__
    if (setsockopt(fd, IPPROTO_TCP,
            TCP_USER_TIMEOUT, (char *)&s, sizeof(s))) {
        uniperror("setsockopt TCP_USER_TIMEOUT");
        return -1;
    }
    #else
    #ifdef _WIN32
    unsigned int sec = s / 1e3;
    if (setsockopt(fd, IPPROTO_TCP,
            TCP_MAXRT, (char *)&sec, sizeof(sec))) {
        uniperror("setsockopt TCP_MAXRT");
        return -1;
    }
    #endif
    #endif
    return 0;
}


static ssize_t serialize_addr(const union sockaddr_u *dst,
        struct cache_key *out)
{
    out->port = dst->in.sin_port;
    out->family = dst->sa.sa_family;
    static const ssize_t c = offsetof(struct cache_key, ip.v4);
    
    if (dst->sa.sa_family == AF_INET) {
        out->ip.v4 = dst->in.sin_addr;
        return c + sizeof(out->ip.v4);
    } 
    else {
        out->ip.v6 = dst->in6.sin6_addr;
        return c + sizeof(out->ip.v6);
    }
}


static int eq_bitlen(const char *a, const char *b, int n)
{
    int by = 0, bi = 0;
    for (; by < n && a[by] == b[by]; by++) {
    }
    if (by != n) {
        for (; bi < 8 
            && (a[by] >> bi) != (b[by] >> bi); bi++) {}
    }
    return by * 8 + 8 - bi;
}


static struct elem_i *cache_get_in(struct mphdr *pool, const union sockaddr_u *dst)
{
    struct cache_key key = { 0 };
    int len = serialize_addr(dst, &key);
    
    struct elem_i *val = mem_get(pool, (char *)&key, len * 8);
    if (!val) {
        return 0;
    }
    struct desync_params *dp = val->dp;
    
    time_t t = time(0);
    if (dp->cache_ttl && t > val->time + dp->cache_ttl) {
        LOG(LOG_S, "time=%jd, now=%jd, ignore\n", (intmax_t)val->time, (intmax_t)t);
        // ZapretDroid: deleted by the stored key and its length in bits; the length in bytes
        // matched nothing, so expired entries stayed (and were saved) for ever.
        mem_delete(pool, val->main.data, val->main.len);
        params.auto_cache_dirty = 1;
        return 0;
    }
    return val;
}


static struct elem_i *cache_get(const union sockaddr_u *dst)
{
    return cache_get_in(params.mempool, dst);
}


static struct elem_i *cache_add_in(struct mphdr *pool,
        const union sockaddr_u *dst, char **host, int host_len)
{
    struct cache_key key = { 0 };
    int bytes = serialize_addr(dst, &key);
    int cmp_len = bytes * 8;
    time_t t = time(0);
    
    if (dst->sa.sa_family == AF_INET && params.cache_pre) {
        struct elem_i *val = mem_get(
            pool, (char *)&key, cmp_len - params.cache_pre);
        if (val) {
            cmp_len = eq_bitlen((char *)&key, val->main.data, bytes);
        }
    }
    struct cache_key *data = calloc(1, bytes);
    if (!data) {
        return 0;
    }
    memcpy(data, &key, bytes);
    
    struct elem_i *val = mem_add(pool, (char *)data, cmp_len, sizeof(struct elem_i));
    if (!val) {
        uniperror("mem_add");
        free(data);
        return 0;
    }
    val->time = t;
    if (!val->extra && *host) {
        val->extra_len = host_len;
        val->extra = *host;
        *host = 0;
    }
    return val;
}


static struct elem_i *cache_add(
        const union sockaddr_u *dst, char **host, int host_len)
{
    return cache_add_in(params.mempool, dst, host, host_len);
}


static int on_socks_recv(struct poolhd *pool, struct eval *val, int t)
{
    struct s5_req r = { 0 };
    
    ssize_t n = recv(val->fd, (char *)&r, sizeof(r), 0);
    if (n < 2) {
        uniperror("socks recv");
        return (val->after_conn_cb)(pool, val, POLLERR);
    }
    if (r.ver != S_VER5 || r.cmd != S_ER_OK) {
        LOG(LOG_E, "socks answer: %d\n", r.cmd);
        return (val->after_conn_cb)(pool, val, POLLERR);
    }
    if (val->conn_state != FLAG_S5) {
        r.cmd = S_CMD_CONN;
        int len = s5_set_addr((char *)&r, sizeof(r), &val->addr, 0);
        // ZapretDroid: the host name (SNI or Host) when known, so that the proxy resolves it
        // (Tor: at the exit, past any spoofed DNS here); the address otherwise.
        char named[4 + 1 + 255 + 2];
        struct eval *client = val->pair;
        char *host = 0;
        int hlen = 0;
        // The client's first data is already here (connections to a proxy group wait for it).
        if (client && client->buff && client->buff->lock > 0) {
            if (!(hlen = parse_tls(client->buff->data, client->buff->lock, &host))) {
                hlen = parse_http(client->buff->data, client->buff->lock, &host, 0);
            }
        }
        if (hlen <= 0 || !host) {
            const char *name = dns_cache_get(&val->addr);
            host = (char *) name;
            hlen = name ? (int) strlen(name) : 0;
        }
        if (host && hlen > 0 && hlen <= 255 && host_name_ok(host, hlen)) {
            named[0] = S_VER5;
            named[1] = S_CMD_CONN;
            named[2] = 0;
            named[3] = 3; // domain name
            named[4] = (char) hlen;
            memcpy(named + 5, host, hlen);
            memcpy(named + 5 + hlen, &val->addr.in.sin_port, 2);
            len = 5 + hlen + 2;
            if (send(val->fd, named, len, 0) < 0) {
                uniperror("socks send");
                return (val->after_conn_cb)(pool, val, POLLERR);
            }
            val->conn_state = FLAG_S5;
            return 0;
        }
        
        if (send(val->fd, (char *)&r, len, 0) < 0) {
            uniperror("socks send");
            return (val->after_conn_cb)(pool, val, POLLERR);
        }
        val->conn_state = FLAG_S5;
        return 0;
    }
    val->cb = val->after_conn_cb;
    
    return (val->cb)(pool, val, POLLOUT);
}

static int on_socks_conn(struct poolhd *pool, struct eval *val, int t)
{
    static const char data[3] = { S_VER5, 0x01, 0x00 };
    
    // ZapretDroid: the proxy did not accept the connection; on_connect reads why and logs it.
    if (t & POLLERR) {
        return (val->after_conn_cb)(pool, val, t);
    }
    if (send(val->fd, (char *)data, sizeof(data), 0) < 0) {
        uniperror("socks send");
        return (val->after_conn_cb)(pool, val, t);
    }
    if (mod_etype(pool, val, POLLIN)) {
        uniperror("mod_etype");
        return -1;
    }
    val->cb = &on_socks_recv;
    return 0;
}


int socket_mod(int fd)
{
    if (params.custom_ttl) {
        if (setttl(fd, params.def_ttl) < 0) {
            return -1;
        }
    }
    if (params.protect_path) {
        return protect(fd, params.protect_path);
    }
    return 0;
}


static int reconnect(struct poolhd *pool, struct eval *val)
{
    assert(val->flag == FLAG_CONN);
    struct eval *client = val->pair;
    
    if (client->sq_buff) {
        if (!client->buff) {
            client->buff = buff_pop(pool, client->sq_buff->size);
        }
        client->buff->lock = client->sq_buff->lock;
        memcpy(client->buff->data, client->sq_buff->data, client->buff->lock);
        
        client->buff->offset = 0;
    }
    client->round_sent = 0;
    client->part_sent = 0;
    
    if (connect_hook(pool, client, &val->addr, &on_connect)) {
        return -1;
    }
    val->pair = 0;
    del_event(pool, val);
    return 0;
}


pthread_mutex_t slot_lock = PTHREAD_MUTEX_INITIALIZER;

static bool host_in(struct mphdr *hosts, const char *host, int len)
{
    bool dynamic = hosts->flags & MF_DYNAMIC;
    if (dynamic) pthread_mutex_lock(&slot_lock);
    struct elem *v = mem_get(hosts, host, len);
    bool in = v && v->len <= len;
    if (dynamic) pthread_mutex_unlock(&slot_lock);
    return in;
}


int slot_assign(const char *host, const char *slot)
{
    int len = strlen(host);
    if (len <= 0 || len > 255) {
        return -1;
    }
    pthread_mutex_lock(&slot_lock);
    int found = slot ? -1 : 0;
    for (struct desync_params *dp = params.dp; dp; dp = dp->next) {
        if (!dp->slot) {
            continue;
        }
        mem_delete(dp->hosts, host, len);
        if (slot && !strcmp(dp->slot, slot)) {
            char *copy = strdup(host);
            if (copy && mem_add(dp->hosts, copy, len, sizeof(struct elem))) {
                found = 0;
            }
        }
    }
    pthread_mutex_unlock(&slot_lock);
    if (found == 0) {
        LOG(LOG_S, "slot: %s -> %s\n", host, slot ? slot : "none");
    }
    return found;
}


/* ZapretDroid: whether any name the address was resolved from (a CDN address has several) is listed. */
static bool names_in(struct mphdr *hosts, const union sockaddr_u *dst)
{
    const char *names[DNS_NAMES];
    int n = dns_cache_names(dst, names);
    for (int i = 0; i < n; i++) {
        if (host_in(hosts, names[i], strlen(names[i]))) return 1;
    }
    return 0;
}


static bool check_host(
        struct mphdr *hosts, const char *buffer, ssize_t n, const union sockaddr_u *dst)
{
    char *host = 0;
    int len;
    if (!(len = parse_tls(buffer, n, &host))) {
        len = parse_http(buffer, n, &host, 0);
    }
    assert(len == 0 || host != 0);
    if (len <= 0) {
        // ZapretDroid: no SNI or Host (another protocol): the names the address came from.
        return dst && names_in(hosts, dst);
    }
    return host_in(hosts, host, len);
}


static bool check_ip(
        struct mphdr *ipset, const union sockaddr_u *addr)
{
    // ZapretDroid: with a v6 listener (phones) IPv4 targets arrive as ::ffff:a.b.c.d and
    // would never match the IPv4 entries of the list.
    union sockaddr_u a = *addr, *dst = &a;
    map_fix(dst, 0);
    
    int len = sizeof(dst->in.sin_addr);
    const char *data = (const char *)&dst->in.sin_addr;
    
    if (dst->sa.sa_family == AF_INET6) {
        len = sizeof(dst->in6.sin6_addr);
        data = (const char *)&dst->in6.sin6_addr;
    }
    if (mem_get(ipset, data, len * 8)) {
        return 1;
    }
    return 0;
}


static bool check_proto_tcp(int proto, const char *buffer, ssize_t n)
{
    if (!(proto & ~IS_IPV4)) {
        return 1;
    }
    else if ((proto & IS_HTTP) && 
            is_http(buffer, n)) {
        return 1;
    }
    else if ((proto & IS_HTTPS) && 
            is_tls_chello(buffer, n)) {
        return 1;
    }
    return 0;
}


static bool check_l34(struct desync_params *dp, int st, const union sockaddr_u *dst)
{
    if ((dp->proto & IS_UDP) && (st != SOCK_DGRAM)) {
        return 0;
    }
    if ((dp->proto & IS_TCP) && (st != SOCK_STREAM)) {
        return 0;
    }
    if (dp->proto & IS_IPV4) {
        static const char *pat = "\0\0\0\0\0\0\0\0\0\0\xff\xff";
        
        if (dst->sa.sa_family != AF_INET 
                && memcmp(&dst->in6.sin6_addr, pat, 12)) {
            return 0;
        }
    }
    // pf[] and sin_port are in network byte order; compare in host order so that ranges work.
    if (dp->pf[0] && 
            (ntohs(dst->in.sin_port) < ntohs(dp->pf[0]) 
                || ntohs(dst->in.sin_port) > ntohs(dp->pf[1]))) {
        return 0;
    }
    if (dp->ipset && !check_ip(dp->ipset, dst)) {
        return 0;
    }
    return 1;
}


int (*owner_uid_hook)(int proto, const char *src_ip, int src_port, const char *dst_ip, int dst_port);


/*
 * ZapretDroid --apps: whether the connection of [client] belongs to one of the group's apps.
 * The uid is asked once per connection (association for UDP), only when a group needs it.
 */
static bool check_app(struct desync_params *dp, struct eval *client, int proto, const union sockaddr_u *dst)
{
    if (!dp->apps_n) {
        return 1;
    }
    if (!client) {
        return 0;
    }
    if (!client->app_uid_known) {
        client->app_uid_known = 1;
        client->app_uid = -1;
        if (client->has_app_src && owner_uid_hook) {
            union sockaddr_u s = client->app_src, d = *dst;
            map_fix(&s, 0);
            map_fix(&d, 0);
            char sb[INET6_ADDRSTRLEN] = "", db[INET6_ADDRSTRLEN] = "";
            inet_ntop(s.sa.sa_family, s.sa.sa_family == AF_INET6 ? (void *)&s.in6.sin6_addr : (void *)&s.in.sin_addr, sb, sizeof(sb));
            inet_ntop(d.sa.sa_family, d.sa.sa_family == AF_INET6 ? (void *)&d.in6.sin6_addr : (void *)&d.in.sin_addr, db, sizeof(db));
            client->app_uid = owner_uid_hook(proto, sb, ntohs(s.in.sin_port), db, ntohs(d.in.sin_port));
        }
        if (params.conn_log) {
            fprintf(ENGINE_LOG, "conn %d: app uid %d\n", client->fd, client->app_uid);
        }
    }
    for (int i = 0; i < dp->apps_n; i++) {
        if (dp->apps[i] == client->app_uid) {
            return 1;
        }
    }
    return 0;
}


static bool check_round(const int *nr, int r)
{
    return (!nr[1] && r <= 1) || (r >= nr[0] && r <= nr[1]);
}


static void swop_groups(struct desync_params *dpc, struct desync_params *dpn)
{
    LOG(LOG_S, "swop: %d <-> %d\n", dpc->id, dpn->id);
    
    struct desync_params dpc_cp = *dpc;
    dpc->next = dpn->next;
    dpc->prev = dpn->prev;
    
    dpn->prev = dpc_cp.prev;
    dpn->next = dpc_cp.next;
    
    if (dpn->prev) 
        dpn->prev->next = dpn;
    
    if (dpc->next)
        dpc->next->prev = dpc;
    
    if (dpc_cp.next != dpn) {
        dpn->next->prev = dpn;
        dpc->prev->next = dpc;
    } 
    else {
        dpc->prev = dpn;
        dpn->next = dpc;
    }
    dpc->detect = dpn->detect;
    dpc->auto_level = dpn->auto_level;
    dpn->detect = dpc_cp.detect;
    dpn->auto_level = dpc_cp.auto_level;
    
    if (params.dp == dpc) params.dp = dpn;
}


/*
 * autohostlist (zapret tpws): every detected block is appended to --autohost-log as
 * "<unix time> <host> <reason>", so the app can show which sites still fail.
 */
static const char *detect_name(int type)
{
    return (type & DETECT_TORST) ? "torst"
        : (type & DETECT_HTTP_LOCAT) ? "redirect"
        : (type & DETECT_TLS_ERR) ? "ssl_err"
        : (type & DETECT_CONNECT) ? "connect"
        : (type & DETECT_UDP) ? "udp" : "unknown";
}


static void autohost_record(const char *host, int len, int type)
{
    if (!params.autohost_log || !host || len <= 0) {
        return;
    }
    const char *reason = detect_name(type);
    FILE *f = fopen(params.autohost_log, "a");
    if (!f) {
        return;
    }
    fprintf(f, "%ld %.*s %s\n", (long) time(0), len, host, reason);
    fclose(f);
}


int on_trigger(int type, struct poolhd *pool, struct eval *val, bool client_alive)
{
    struct eval *lav = val->pair;
    autohost_record(lav->host, lav->host_len, type);
    
    bool before_req = !lav->recv_count && !val->recv_count;
    
    bool can_reconn = ((lav->sq_buff || before_req) && client_alive);
    INIT_ADDR_STR((val->addr));
    
    struct elem_i *cache = cache_add(&val->addr, &lav->host, lav->host_len);
    if (!cache) {
        return -1;
    }
    lav->dp->fail_count++;
    lav->dp_mask |= lav->dp->bit;
    lav->detect = type;
    
    uint64_t uncheked = lav->dp_mask;
    struct desync_params *dp = params.dp, *next = 0;
    
    for (; dp; dp = dp->next) {
        if (!uncheked && !dp->detect) {
            break;
        }
        if (!(dp->bit & lav->dp_mask)
                && (!dp->detect || (dp->detect & type))
                && (!(dp->auto_level & AUTO_ONRECONN) || can_reconn)) {
            next = dp;
            break;
        }
        uncheked &= ~dp->bit;
        lav->dp_mask |= dp->bit;
    }
    if (!(lav->dp->bit & cache->dp_mask)) 
    {
        if (next && (next->auto_level & AUTO_SORT)
                && lav->dp->pri > next->pri
                && !(lav->dp->bit & uncheked)) {
            swop_groups(lav->dp, next);
        }
        lav->dp->pri++;
    }
    if (params.conn_log && !lav->quiet_fail) {
        const char *h = lav->log_host[0] ? lav->log_host : "-";
        if (next) {
            fprintf(ENGINE_LOG, "conn %d: block detected (%s) on %s (%s) with group %d, trying group %d%s\n",
                lav->fd, detect_name(type), h, ADDR_STR, lav->dp->id, next->id,
                (!(next->auto_level & AUTO_NORECONN) && can_reconn) ? ", reconnecting" : "");
        }
        else {
            fprintf(ENGINE_LOG, "conn %d: block detected (%s) on %s (%s) with group %d, no group left\n",
                lav->fd, detect_name(type), h, ADDR_STR, lav->dp->id);
        }
    }
    lav->quiet_fail = 0;
    if (!next) {
        LOG(LOG_S, "unreach ip: %s\n", ADDR_STR);
        cache->dp_mask = 0;
        cache->detect = 0;
        cache->dp = params.dp;
        auto_cache_changed();
        return -1;
    }
    LOG(LOG_S, "save: ip=%s, id=%d\n", ADDR_STR, next->id);
    
    cache->dp_mask |= lav->dp_mask;
    cache->detect = lav->detect;
    cache->dp = dp;
    auto_cache_changed();
    
    if (!(next->auto_level & AUTO_NORECONN) && can_reconn) {
        return reconnect(pool, val);
    }
    return -1;
}


static int on_torst(struct poolhd *pool, struct eval *val)
{
    if (on_trigger(DETECT_TORST, pool, val, 1) == 0) {
        return 0;
    }
    struct linger l = { .l_onoff = 1 };
    if (setsockopt(val->pair->fd, SOL_SOCKET,
            SO_LINGER, (char *)&l, sizeof(l)) < 0) {
        uniperror("setsockopt SO_LINGER");
    }
    return -1;
}


static int on_fin(struct poolhd *pool, struct eval *val)
{
    bool is_client = val->flag != FLAG_CONN;
    
    if (is_client) {
        val = val->pair;
    }
    if (!val || !(val->pair->mark && val->round_count <= 1)) {
        return -1;
    }
    if (on_trigger(DETECT_TLS_ERR, pool, val, !is_client) == 0) {
        return 0;
    }
    return -1;
}


int on_timeout(struct poolhd *pool, struct eval *val) {
    if (++val->to_count >= params.to_count_lim) {
        return on_torst(pool, val);
    }
    set_timer(pool, val, params.ptimeout);
    return 0;
}


/* Repeated refusals of a group's proxy within this time are counted, not logged. */
#define REFUSED_QUIET_MS 10000

/*
 * ZapretDroid: a proxy that is not running (Tor off) refuses every connection of its group;
 * instead of a failure and a block line per connection, one line per burst with a count.
 * Returns true when the connection went to the group's proxy and was refused.
 */
bool proxy_refused(struct eval *remote, int error)
{
    struct eval *client = remote->pair;
    struct desync_params *dp = client ? client->dp : 0;
    if (!dp || !dp->out_type || error != ECONNREFUSED) {
        return 0;
    }
    client->quiet_fail = 1;
    if (!params.conn_log) {
        return 1;
    }
    long long now = mono_ms();
    if (dp->refused_ms && now - dp->refused_ms < REFUSED_QUIET_MS) {
        dp->refused_n++;
        return 1;
    }
    INIT_ADDR_STR((dp->out_addr));
    if (dp->refused_n) {
        fprintf(ENGINE_LOG, "group %d: proxy %s:%d still refuses connections (%d more since the last line)\n",
            dp->id, ADDR_STR, ntohs(dp->out_addr.in.sin_port), dp->refused_n);
    }
    else {
        fprintf(ENGINE_LOG, "group %d: proxy %s:%d refuses connections: is it running? "
            "Repeats within %d s are counted, not logged\n",
            dp->id, ADDR_STR, ntohs(dp->out_addr.in.sin_port), REFUSED_QUIET_MS / 1000);
    }
    dp->refused_ms = now;
    dp->refused_n = 0;
    return 1;
}


int on_connerr(struct poolhd *pool, struct eval *val)
{
    return on_trigger(DETECT_CONNECT, pool, val, 1);
}


static int on_response(struct poolhd *pool, struct eval *val, 
        const char *resp, ssize_t sn)
{
    struct desync_params *dp = params.dp;
    
    char *req = val->pair->sq_buff->data;
    ssize_t qn = val->pair->sq_buff->size;
    
    val->pair->dp_mask |= val->pair->dp->bit;
    
    for (; dp; dp = dp->next) {
        if (dp->bit & val->pair->dp_mask) {
            continue;
        }
        if ((dp->detect & DETECT_HTTP_LOCAT)
                && is_http_redirect(req, qn, resp, sn)) {
            break;
        }
        else if ((dp->detect & DETECT_TLS_ERR)
                && ((is_tls_chello(req, qn) && !is_tls_shello(resp, sn))
                    || neq_tls_sid(req, qn, resp, sn))) {
            break;
        }
    }
    if (dp) {
        return on_trigger(dp->detect, pool, val, 1);
    }
    return -1;
}


static int try_set_tls_timer(struct poolhd *pool, 
        struct eval *val, const char *buff, ssize_t n)
{
    ssize_t pos = 0;
    if (params.to_bytes_lim 
            && val->recv_count > params.to_bytes_lim) {
        remove_timer(pool, val);
        return -1;
    }
    do {
        if (val->tls_rec_pos < 5) {
            val->tls_rec[val->tls_rec_pos] = buff[pos];
            val->tls_rec_pos++; pos++;
            continue;
        }
        else if (val->tls_rec_pos == 5) {
            val->tls_rec_size = ANTOHS(val->tls_rec, 3) + 5;
            uint8_t t = val->tls_rec[0];
            
            if (t < 0x14 || t > 0x18) {
                LOG(LOG_E, "unknown record type: 0x%x\n", t);
                return -1;
            }
        }
        else if (val->tls_rec_pos == val->tls_rec_size) {
            val->tls_rec_pos = 0;
            val->tls_rec_size = 0;
            continue;
        }
        ssize_t end = val->tls_rec_size - val->tls_rec_pos;
        if (end <= 0)  {
            LOG(LOG_E, "invalid record size: %zd\n", val->tls_rec_size);
            return -1;
        }
        if (pos + end > n) end = n - pos;
        
        val->tls_rec_pos += end; pos += end;
    } while (pos < n);
    
    if (params.ptimeout) {
        remove_timer(pool, val);
        if (val->tls_rec_pos != val->tls_rec_size) {
            set_timer(pool, val, params.ptimeout);
        }
    }
    return 0;
}


static inline void free_first_req(struct poolhd *pool, struct eval *client)
{
    buff_push(pool, client->sq_buff);
    client->sq_buff = 0;
}


static void save_hostname(struct eval *client, const char *buffer, ssize_t n)
{
    if (client->host) {
        return;
    }
    char *host = 0;
    int len = parse_tls(buffer, n, &host);
    if (!len) {
        if (!(len = parse_http(buffer, n, &host, 0))) {
            return;
        }
    }
    if (!(client->host = malloc(len))) {
        return;
    }
    memcpy(client->host, host, len);
    client->host_len = len;
}


static struct desync_params *find_dp(struct eval *client, 
        const char *buff, ssize_t n, const union sockaddr_u *dst)
{
    if (!buff && client->buff) {
        buff = client->buff->data;
        n = client->buff->lock;
    }
    struct desync_params *dp = client->dp, *init_dp = dp;
    
    for (; ; dp = dp->next) {
        if (!dp) dp = params.dp;
        if (client->dp_mask == params.dp_full_mask) {
            break;
        }
        if (!(dp->bit & client->dp_mask) 
                && (dp == init_dp || !dp->detect || (client->detect & dp->detect))
                && (check_l34(dp, SOCK_STREAM, dst))
                && check_app(dp, client, IPPROTO_TCP, dst)
                && (!dp->proto || !buff || check_proto_tcp(dp->proto, buff, n))
                && (!dp->hosts || !buff || check_host(dp->hosts, buff, n, dst))) {
            return dp;
        }
        client->dp_mask |= dp->bit;
    }
    return 0;
}


static int recv_and_connect(struct poolhd *pool, struct eval *val, int t)
{
    if (!(val->buff = buff_pop(pool, params.bfsize))) {
        return -1;
    }
    val->buff->lock = tcp_recv_hook(pool, val, val->buff);
    if (val->buff->lock <= 0) {
        return -1;
    }
    return connect_hook(pool, val, &val->addr, &on_connect);
}


int connect_hook(struct poolhd *pool, struct eval *val, 
        const union sockaddr_u *dst, evcb_t next)
{
    if (!val->dp) {
        struct elem_i *e = cache_get(dst);
        if (e) {
            val->dp_mask = e->dp_mask;
            val->detect = e->detect;
            val->dp = e->dp;
        }
    }
    struct desync_params *dp = find_dp(val, 0, 0, dst);
    if (!dp) {
        LOG(LOG_E, "drop connection\n");
        return -1;
    }
    val->dp = dp;
    
    if ((dp->hosts || dp->proto) && !val->buff && params.delay_conn) {
        if (resp_error(val->fd, 0, val->flag) < 0) {
            uniperror("send");
            return -1;
        }
        val->cb = &recv_and_connect;
        val->addr = *dst;
        return 0;
    }
    if (dp->out_type) {
        int e = create_conn(pool, val, &dp->out_addr, 
            dp->out_type == MODE_SOCKS5 ? &on_socks_conn : next);
        if (!e) {
            val->pair->after_conn_cb = next;
            val->pair->addr = *dst;
        }
        return e;
    }
    return create_conn(pool, val, dst, next);
}


static int setup_conn(struct eval *client, const char *buffer, ssize_t n)
{
    struct desync_params *dp = find_dp(client, buffer, n, &client->pair->addr);
    if (!dp) {
        LOG(LOG_E, "drop connection\n");
        return -1;
    }
    save_hostname(client, buffer, n);
    client->mark = is_tls_chello(buffer, n);
    client->dp = dp;
    if (params.conn_log) {
        const union sockaddr_u *dst = &client->pair->addr;
        INIT_ADDR_STR((*dst));
        if (client->host) {
            snprintf(client->log_host, sizeof(client->log_host), "%.*s", client->host_len, client->host);
        }
        else if (!client->log_host[0]) {
            // ZapretDroid: without SNI or Host, the name the address was resolved from.
            const char *name = dns_cache_get(dst);
            snprintf(client->log_host, sizeof(client->log_host), "%s", name ? name : ADDR_STR);
        }
        fprintf(ENGINE_LOG, "conn %d: %s -> %s:%d, %s, group %d\n", client->fd,
            client->log_host, ADDR_STR, ntohs(dst->in.sin_port),
            client->mark ? "TLS" : is_http(buffer, n) ? "HTTP" : "other", dp->id);
    }
    
    if (params.timeout 
            && set_timeout(client->pair->fd, params.timeout)) {
        return -1;
    }
    if (pre_desync(client->pair->fd, client->dp)) {
        return -1;
    }
    return 0;
}


static int cancel_setup(struct eval *remote)
{
    if (post_desync(remote->fd, remote->pair->dp)) {
        return -1;
    }
    return 0;
}


static int handle_err(struct poolhd *pool, struct eval *val, int e)
{
    switch (e) {
        case ECONNRESET:
        case ECONNREFUSED:
        case ETIMEDOUT: 
        case EHOSTUNREACH:
            if (val->flag == FLAG_CONN)
                return on_torst(pool, val);
            else
                return on_fin(pool, val);
    }
    return -1;
}


ssize_t tcp_send_hook(struct poolhd *pool, 
        struct eval *remote, struct buffer *buff, ssize_t *n, bool *wait)
{
    ssize_t sn = -1;
    int skip = remote->flag != FLAG_CONN; 
    size_t off = buff->offset;
    
    if (!skip) {
        struct eval *client = remote->pair;
    
        if (client->recv_count == *n 
                && setup_conn(client, buff->data, *n) < 0) {
            return -1;
        }
        int r = client->round_count;
        if (!check_round(client->dp->rounds, r)) {
            skip = 1;
        }
        else {
            LOG(LOG_S, "desync TCP: group=%d, round=%d, fd=%d\n", client->dp->id, r, remote->fd);
            sn = desync(pool, remote, buff, n, wait);
        }
    }
    if (skip) {
        sn = send(remote->fd, buff->data + off, *n - off, 0);
        if (sn < 0 && get_e() == EAGAIN) {
            return 0;
        }
    }
    if (sn < 0) {
        uniperror("send");
        return handle_err(pool, remote, get_e());
    }
    remote->pair->round_sent += sn;
    return sn;
}


ssize_t tcp_recv_hook(struct poolhd *pool, 
        struct eval *val, struct buffer *buff)
{
    ssize_t n = recv(val->fd, buff->data, buff->size, 0);
    if (n < 1) {
        if (!n) {
            return on_fin(pool, val);
        }
        if (get_e() == EAGAIN) {
            return 0;
        }
        uniperror("recv");
        return handle_err(pool, val, get_e());
    }
    val->recv_count += n;
    
    if (val->round_sent == 0) {
        val->round_count++;
        if (val->pair) {
            val->pair->round_sent = 0;
            val->pair->part_sent = 0;
        }
    }
    if (val->flag == FLAG_CONN && !val->round_sent) {
        int *nr = val->pair->dp->rounds;
        
        if (check_round(nr, val->round_count)
                && !check_round(nr, val->round_count + 1)
                && cancel_setup(val)) {
            return -1;
        }
    }
    //
    if (val->flag == FLAG_CONN) {
        if (val->pair->mark 
                && try_set_tls_timer(pool, val, buff->data, n)) {
            val->pair->mark = 0;
        }
        if (val->to_count >= 0 && params.timeout 
                && (params.to_bytes_lim && val->recv_count > params.to_bytes_lim)) {
            set_timeout(val->fd, 0);
            val->to_count = -1;
        }
        //
        if (val->pair->sq_buff) {
            if (on_response(pool, val, buff->data, n) == 0) {
                return 0;
            }
            free_first_req(pool, val->pair);
        }
    }
    else if (params.auto_reconnect
            && (val->sq_buff || val->recv_count == n))
    {
        if (!val->sq_buff) {
            if (!(val->sq_buff = buff_pop(pool, buff->size))) {
                return -1;
            }
        }
        val->sq_buff->lock += n;
        
        if ((size_t )val->sq_buff->lock >= val->sq_buff->size) {
            free_first_req(pool, val);
        }
        else {
            memcpy(val->sq_buff->data + val->sq_buff->lock - n, buff->data, n);
        }
    }
    return n;
}


/* Inbound gaps above this are counted as stalls: audible in a call, visible in a stream. */
#define UDP_STALL_MS 300
#define UDP_REPORT_MS 10000

void udp_flow_report(struct eval *remote, const char *state)
{
    INIT_ADDR_STR((remote->addr));
    long long now = mono_ms();
    char reply[32] = "";
    if (remote->udp_in_pkts) {
        snprintf(reply, sizeof(reply), ", first reply %lld ms", remote->udp_first_reply_ms);
    }
    char retries[64] = "";
    if (remote->udp_retries) {
        snprintf(retries, sizeof(retries), ", retries %u", remote->udp_retries);
    }
    if (remote->udp_send_errors) {
        size_t used = strlen(retries);
        snprintf(retries + used, sizeof(retries) - used, ", not sent %u", remote->udp_send_errors);
    }
    fprintf(ENGINE_LOG, "udp %d %s: -> %s:%d %s, out %u pkts/%lld B, in %u pkts/%lld B, fakes %u%s%s, "
        "max gap out %lld ms, in %lld ms, stalls %u, %lld s%s\n",
        remote->fd, state, ADDR_STR, ntohs(remote->addr.in.sin_port), udp_proto_name(remote->udp_proto),
        remote->udp_out_pkts, remote->udp_out_bytes, remote->udp_in_pkts, remote->udp_in_bytes,
        remote->udp_fakes, reply, retries, remote->udp_max_out_gap, remote->udp_max_in_gap, remote->udp_stalls,
        (now - remote->udp_first_ms) / 1000,
        remote->udp_out_pkts && !remote->udp_in_pkts ? "  <-- NO REPLY" : "");
    remote->udp_last_report = now;
}


static void udp_maybe_report(struct eval *remote, long long now)
{
    if (params.conn_log && now - remote->udp_last_report >= UDP_REPORT_MS) {
        udp_flow_report(remote, "running");
    }
}


void udp_stat_in(struct eval *remote, ssize_t n)
{
    long long now = mono_ms();
    if (remote->udp_last_in) {
        long long gap = now - remote->udp_last_in;
        if (gap > remote->udp_max_in_gap) remote->udp_max_in_gap = gap;
        if (gap > UDP_STALL_MS) remote->udp_stalls++;
    }
    remote->udp_last_in = now;
    if (!remote->udp_in_pkts) {
        // How long the flow waited for the server: long waits are what makes a call "connect" slowly.
        remote->udp_first_reply_ms = now - remote->udp_first_ms;
        if (params.conn_log) {
            fprintf(ENGINE_LOG, "udp %d: first reply after %lld ms (%u datagrams and %u fakes sent before it)\n",
                remote->fd, remote->udp_first_reply_ms, remote->udp_out_pkts, remote->udp_fakes);
        }
    }
    remote->udp_in_pkts++;
    remote->udp_in_bytes += n;
    udp_maybe_report(remote, now);
}


/*
 * ZapretDroid: the host of a UDP flow from its first datagram, so that groups with host lists
 * (-H) apply to UDP too: the SNI of a QUIC Initial, else the name the address was resolved
 * from, else for Discord's IP discovery discord.media (its voice servers' domain). Returns
 * where the name came from, or null. A QUIC flow whose Initial ends before the SNI keeps its
 * CRYPTO data for the next Initial (Chrome splits large ClientHellos).
 */
static const char *udp_flow_host(struct eval *val, const char *buffer, ssize_t n,
        const union sockaddr_u *dst, int proto, char *host, size_t size, bool keep_quic)
{
    if (proto == UDP_PROTO_QUIC) {
        struct quic_crypto *qc = calloc(1, sizeof(*qc));
        if (qc && quic_add_initial(qc, (const uint8_t *) buffer, n)) {
            if (quic_crypto_sni(qc, host, size)) {
                free(qc);
                return "sni";
            }
            if (keep_quic) {
                val->udp_quic = qc;
                qc = 0;
            }
        }
        free(qc);
    }
    const char *name = dns_cache_get(dst);
    if (name && strlen(name) < size) {
        strcpy(host, name);
        return "dns";
    }
    if (proto == UDP_PROTO_DISCORD) {
        snprintf(host, size, "discord.media");
        return "discord ip discovery";
    }
    return 0;
}


#define UDP_DESTS 8

/* Same address and port. */
static bool dest_equ(const union sockaddr_u *a, const union sockaddr_u *b)
{
    if (a->sa.sa_family != b->sa.sa_family || a->in.sin_port != b->in.sin_port) {
        return 0;
    }
    if (a->sa.sa_family == AF_INET) {
        return !memcmp(&a->in.sin_addr, &b->in.sin_addr, sizeof(a->in.sin_addr));
    }
    return !memcmp(&a->in6.sin6_addr, &b->in6.sin6_addr, sizeof(a->in6.sin6_addr));
}

struct udp_dest {
    union sockaddr_u addr;
    struct desync_params *dp;
    unsigned int pkts;
};


/* The first group for a UDP flow: groups with host lists only when the host is known and listed
 * (with by_dns, any of the names the address was resolved from). */
static struct desync_params *udp_find_dp(struct eval *owner, const union sockaddr_u *dst, const char *host, bool by_dns)
{
    for (struct desync_params *dp = params.dp; dp; dp = dp->next) {
        if (dp->detect || !check_l34(dp, SOCK_DGRAM, dst) || !check_app(dp, owner, IPPROTO_UDP, dst)) {
            continue;
        }
        if (dp->hosts && !(by_dns ? names_in(dp->hosts, dst) : host && host_in(dp->hosts, host, strlen(host)))) {
            continue;
        }
        return dp;
    }
    return 0;
}


static bool is_dns(const char *source)
{
    return source && !strcmp(source, "dns");
}


/*
 * ZapretDroid: DNS answers relayed to the client name the addresses for udp_flow_host and TCP.
 * Only an answer to a query relayed on the same association (its ID), and with --names-dns
 * only from those servers: otherwise any app could send itself "answers" from its own server
 * and choose the group for other apps' addresses.
 */
void udp_reply_hook(struct eval *val, const char *buffer, ssize_t n, const union sockaddr_u *src)
{
    if (n < 12 || ntohs(src->in.sin_port) != 53 || !val->dns_ids_n) {
        return;
    }
    if (params.names_dns_n) {
        bool known = 0;
        for (int i = 0; i < params.names_dns_n && !known; i++) {
            const union sockaddr_u *d = &params.names_dns[i];
            union sockaddr_u s = *src;
            map_fix(&s, 0);
            known = d->sa.sa_family == s.sa.sa_family && (d->sa.sa_family == AF_INET
                ? !memcmp(&d->in.sin_addr, &s.in.sin_addr, 4)
                : !memcmp(&d->in6.sin6_addr, &s.in6.sin6_addr, 16));
        }
        if (!known) return;
    }
    uint16_t id = (uint16_t) ((uint8_t) buffer[0] << 8 | (uint8_t) buffer[1]);
    for (int i = 0; i < val->dns_ids_n; i++) {
        if (val->dns_ids[i] == id) {
            val->dns_ids[i] = val->dns_ids[--val->dns_ids_n];
            dns_cache_feed((const uint8_t *) buffer, n);
            return;
        }
    }
}


/*
 * ZapretDroid: UDP through the group's upstream SOCKS5 proxy (--udp-proxy): one UDP ASSOCIATE
 * per association, set up on a non-blocking control connection; datagrams go to the proxy's
 * relay with a SOCKS5 UDP header (RFC 1928 7), replies are unwrapped in on_udp_tunnel.
 */
static size_t ups_header(char *out, const union sockaddr_u *dst)
{
    out[0] = out[1] = out[2] = 0;
    if (dst->sa.sa_family == AF_INET6) {
        out[3] = S_ATP_I6;
        memcpy(out + 4, &dst->in6.sin6_addr, 16);
        memcpy(out + 20, &dst->in6.sin6_port, 2);
        return 22;
    }
    out[3] = S_ATP_I4;
    memcpy(out + 4, &dst->in.sin_addr, 4);
    memcpy(out + 8, &dst->in.sin_port, 2);
    return 10;
}


static void ups_fail(struct eval *remote, const char *why)
{
    struct udp_upstream *u = remote->ups;
    if (!u || u->state < 0) return;
    u->state = -1;
    if (params.conn_log) {
        fprintf(ENGINE_LOG, "udp %d: proxy UDP failed: %s\n", remote->fd, why);
    }
}


static int on_ups_ctrl(struct poolhd *pool, struct eval *ctrl, int et)
{
    struct eval *remote = ctrl->ups_owner;
    struct udp_upstream *u = remote ? remote->ups : 0;
    if (!u) {
        return -1;
    }
    char buf[64];
    switch (u->state) {
    case 0: {
        int err = 0;
        socklen_t len = sizeof(err);
        if (getsockopt(ctrl->fd, SOL_SOCKET, SO_ERROR, (char *) &err, &len) || err) {
            ups_fail(remote, "cannot connect to the proxy");
            return -1;
        }
        static const char hello[3] = { S_VER5, 1, 0 };
        if (send(ctrl->fd, hello, sizeof(hello), 0) != sizeof(hello) || mod_etype(pool, ctrl, POLLIN)) {
            ups_fail(remote, "send");
            return -1;
        }
        u->state = 1;
        return 0;
    }
    case 1: {
        ssize_t n = recv(ctrl->fd, buf, 2, 0);
        if (n != 2 || buf[0] != S_VER5 || buf[1] != 0) {
            ups_fail(remote, "the proxy wants authentication or is not SOCKS5");
            return -1;
        }
        // UDP ASSOCIATE, client address unknown (0.0.0.0:0).
        static const char req[10] = { S_VER5, 3, 0, S_ATP_I4, 0, 0, 0, 0, 0, 0 };
        if (send(ctrl->fd, req, sizeof(req), 0) != sizeof(req)) {
            ups_fail(remote, "send");
            return -1;
        }
        u->state = 2;
        return 0;
    }
    case 2: {
        ssize_t n = recv(ctrl->fd, buf, sizeof(buf), 0);
        if (n < 10 || buf[0] != S_VER5 || buf[1] != 0) {
            ups_fail(remote, n >= 2 ? "the proxy refused UDP ASSOCIATE" : "no reply from the proxy");
            return -1;
        }
        union sockaddr_u relay = u->proxy;
        if (buf[3] == S_ATP_I4 && n >= 10) {
            relay.in.sin_family = AF_INET;
            memcpy(&relay.in.sin_addr, buf + 4, 4);
            memcpy(&relay.in.sin_port, buf + 8, 2);
            // 0.0.0.0: the relay is at the proxy's own address.
            if (!relay.in.sin_addr.s_addr && u->proxy.sa.sa_family == AF_INET) {
                relay.in.sin_addr = u->proxy.in.sin_addr;
            }
        }
        else if (buf[3] == S_ATP_I6 && n >= 22) {
            relay.in6.sin6_family = AF_INET6;
            memcpy(&relay.in6.sin6_addr, buf + 4, 16);
            memcpy(&relay.in6.sin6_port, buf + 20, 2);
        }
        else {
            ups_fail(remote, "unexpected relay address");
            return -1;
        }
        u->relay = relay;
        u->state = 3;
        if (params.conn_log) {
            INIT_ADDR_STR((relay));
            fprintf(ENGINE_LOG, "udp %d: through the proxy, relay %s:%d, %d queued\n",
                remote->fd, ADDR_STR, ntohs(relay.in.sin_port), u->queued);
        }
        for (int i = 0; i < u->queued; i++) {
            sendto(remote->fd, u->queue[i], u->queue_len[i], 0, &u->relay.sa, SA_SIZE(&u->relay));
            free(u->queue[i]);
        }
        u->queued = 0;
        return 0;
    }
    default: {
        // Ready: the association lasts while this connection does.
        ssize_t n = recv(ctrl->fd, buf, sizeof(buf), 0);
        if (n <= 0 || (et & (POLLHUP | POLLERR))) {
            ups_fail(remote, "the proxy closed the association");
            return -1;
        }
        return 0;
    }
    }
}


static int ups_start(struct poolhd *pool, struct eval *remote, struct desync_params *dp)
{
    struct udp_upstream *u = calloc(1, sizeof(*u));
    if (!u) {
        return -1;
    }
    u->proxy = dp->out_addr;
    remote->ups = u;
    int fd = socket(u->proxy.sa.sa_family, SOCK_STREAM, 0);
    if (fd < 0) {
        ups_fail(remote, "socket");
        return -1;
    }
    #ifdef __linux__
    fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK);
    #endif
    if (socket_mod(fd) < 0
            || (connect(fd, &u->proxy.sa, SA_SIZE(&u->proxy)) < 0 && get_e() != EINPROGRESS)) {
        close(fd);
        ups_fail(remote, "cannot connect to the proxy");
        return -1;
    }
    struct eval *ctrl = add_event(pool, &on_ups_ctrl, fd, POLLOUT);
    if (!ctrl) {
        close(fd);
        ups_fail(remote, "event pool full");
        return -1;
    }
    ctrl->ups_owner = remote;
    u->ctrl = ctrl;
    return 0;
}


/* Sends or queues one datagram through the proxy; always "sent" for the caller. */
static ssize_t ups_dgram(struct poolhd *pool, struct eval *remote, struct desync_params *dp,
        const union sockaddr_u *dst, const char *buffer, ssize_t n)
{
    if (!remote->ups && ups_start(pool, remote, dp) < 0) {
        return n;
    }
    struct udp_upstream *u = remote->ups;
    if (u->state < 0) {
        return n;
    }
    char *pkt = malloc(22 + n);
    if (!pkt) {
        return n;
    }
    size_t hl = ups_header(pkt, dst);
    memcpy(pkt + hl, buffer, n);
    if (u->state == 3) {
        sendto(remote->fd, pkt, hl + n, 0, &u->relay.sa, SA_SIZE(&u->relay));
        free(pkt);
    }
    else if (u->queued < UPS_QUEUE) {
        u->queue[u->queued] = pkt;
        u->queue_len[u->queued++] = hl + n;
    }
    else {
        free(pkt);
    }
    return n;
}


/* Whether [from] is the relay of the association: then the datagram carries a SOCKS5 header. */
bool ups_is_relay(struct eval *remote, const union sockaddr_u *from)
{
    struct udp_upstream *u = remote->ups;
    // IPv4-mapped-aware: on a dual-stack socket (the default bind address on phones) recvfrom
    // gives ::ffff:a.b.c.d while the proxy names its relay in IPv4.
    return u && u->state == 3 && same_addr(from, &u->relay, 1);
}



/* ZapretDroid --auto=udp: a flow's first destination that does not answer within UDP_AUTO_MS,
 * after at least UDP_AUTO_PKTS datagrams of its current group, moves to the next group with
 * "udp" detection (QUIC repeats its Initial, Discord its IP discovery: the repeats then carry
 * the new group's desync). The choice is kept in the UDP cache for later flows. */
#define UDP_AUTO_MS 1000
#define UDP_AUTO_PKTS 2

void udp_reply_from(struct eval *remote, const union sockaddr_u *src)
{
    if (remote->addr.in.sin_port && same_addr(src, &remote->addr, 1)) {
        remote->udp_first_in++;
    }
}


static struct desync_params *udp_auto_next(struct eval *owner, struct eval *val,
        const union sockaddr_u *dst)
{
    const char *host = val->udp_host[0] ? val->udp_host : 0;
    for (struct desync_params *dp = params.dp; dp; dp = dp->next) {
        if ((dp->bit & val->udp_auto_mask) || !(dp->detect & DETECT_UDP)) {
            continue;
        }
        if (!check_l34(dp, SOCK_DGRAM, dst) || !check_app(dp, owner, IPPROTO_UDP, dst)) {
            continue;
        }
        if (dp->hosts && !(host ? host_in(dp->hosts, host, strlen(host)) : names_in(dp->hosts, dst))) {
            continue;
        }
        return dp;
    }
    return 0;
}


/* The cached group of a new flow to [dst], if it still applies to this flow. */
static struct desync_params *udp_auto_cached(struct eval *owner, struct eval *val,
        const union sockaddr_u *dst)
{
    if (!params.udp_auto || !params.udp_cache) {
        return 0;
    }
    struct elem_i *e = cache_get_in(params.udp_cache, dst);
    if (!e || !e->dp || !(e->dp->detect & DETECT_UDP)
            || !check_l34(e->dp, SOCK_DGRAM, dst) || !check_app(e->dp, owner, IPPROTO_UDP, dst)) {
        return 0;
    }
    val->udp_auto_mask = e->dp_mask;
    val->udp_auto_cached = 1;
    return e->dp;
}


static void udp_set_host(struct eval *val, const char *host)
{
    if (host && strlen(host) < sizeof(val->udp_host)) {
        strcpy(val->udp_host, host);
    }
}


/* Called before a datagram to the first destination; returns the group for it. */
static struct desync_params *udp_auto_check(struct eval *owner, struct eval *val,
        const union sockaddr_u *dst, struct desync_params *dp, unsigned int dindex, long long now)
{
    if (!params.udp_auto || val->udp_auto_done || val->udp_first_in || dp->out_type
            || ntohs(dst->in.sin_port) == 53
            || dindex < UDP_AUTO_PKTS || now - val->udp_group_ms < UDP_AUTO_MS) {
        return dp;
    }
    val->udp_auto_mask |= dp->bit;
    struct desync_params *next = udp_auto_next(owner, val, dst);
    const char *h = val->udp_host[0] ? val->udp_host : "-";
    autohost_record(val->udp_host, strlen(val->udp_host), DETECT_UDP);
    dp->fail_count++;
    INIT_ADDR_STR((*dst));
    if (!next) {
        val->udp_auto_done = 1;
        if (params.conn_log) {
            fprintf(ENGINE_LOG, "udp %d: block detected (udp) on %s (%s) with group %d, no group left\n",
                val->fd, h, ADDR_STR, dp->id);
        }
        // Nothing worked: the next flow starts afresh.
        struct elem_i *e = cache_get_in(params.udp_cache, dst);
        if (e) {
            mem_delete(params.udp_cache, e->main.data, e->main.len);
            auto_cache_changed();
        }
        return dp;
    }
    if (params.conn_log) {
        fprintf(ENGINE_LOG, "udp %d: block detected (udp) on %s (%s) with group %d, trying group %d\n",
            val->fd, h, ADDR_STR, dp->id, next->id);
    }
    char *host = val->udp_host[0] ? strdup(val->udp_host) : 0;
    struct elem_i *e = cache_add_in(params.udp_cache, dst, &host, host ? (int) strlen(host) : 0);
    free(host); // left when the entry already had its host
    if (e) {
        e->dp = next;
        e->dp_mask = val->udp_auto_mask;
        e->detect = DETECT_UDP;
        auto_cache_changed();
    }
    val->udp_group_ms = now;
    return next;
}


ssize_t udp_hook(struct poolhd *pool, struct eval *val, 
        char *buffer, ssize_t n, const union sockaddr_u *dst)
{
    struct eval *pair = val->pair->pair;
    int r = pair->round_count;
    
    // val is the remote socket of the association: the flow's statistics live there.
    long long now = mono_ms();
    int proto = udp_detect_proto(buffer, n);
    unsigned int index = val->udp_out_pkts;
    if (!index) {
        val->udp_proto = proto;
        val->udp_first_ms = now;
        val->udp_last_report = now;
    }
    else {
        long long gap = now - val->udp_last_out;
        if (gap > val->udp_max_out_gap) val->udp_max_out_gap = gap;
        // The client repeats IP discovery when it gets no answer: each retry costs the user seconds
        // of "Connecting...".
        if (proto == UDP_PROTO_DISCORD && !val->udp_in_pkts) {
            val->udp_retries++;
            if (params.conn_log) {
                fprintf(ENGINE_LOG, "udp %d: discord IP discovery RETRY #%u after %lld ms without a reply\n",
                    val->fd, val->udp_retries, now - val->udp_first_ms);
            }
        }
    }
    val->udp_last_out = now;
    val->udp_out_pkts++;
    val->udp_out_bytes += n;
    if (n >= 12 && ntohs(dst->in.sin_port) == 53) {
        uint16_t id = (uint16_t) ((uint8_t) buffer[0] << 8 | (uint8_t) buffer[1]);
        if (val->dns_ids_n < 4) val->dns_ids[val->dns_ids_n++] = id;
        else val->dns_ids[index % 4] = id;
    }
    // ZapretDroid: DNS for the VPN's own DNS address goes to the app's encrypted resolver,
    // untouched by any group (see --dns-redirect).
    if (params.dns_redir && same_addr(dst, &params.dns_redir_from, 1)) {
        // The resolver's address in the form of this socket (IPv4-mapped on a dual-stack one).
        union sockaddr_u to = params.dns_redir_to;
        map_fix(&to, dst->sa.sa_family == AF_INET6 ? 6 : 0);
        ssize_t sent = sendto(val->fd, buffer, n, 0, &to.sa, SA_SIZE(&to));
        udp_maybe_report(val, now);
        return sent;
    }
    
    // The first destination of the association keeps its group on the client side (pair->dp);
    // other destinations of the same app socket get theirs in val->udp_dests.
    bool first = dest_equ(dst, &val->addr);
    struct desync_params *dp = 0;
    // Datagram index within the destination's group: --udp-desync counts the datagrams to this
    // destination only, not those the same socket sends elsewhere.
    unsigned int dindex = 0, first_index = 0;
    if (first) {
        dp = pair->dp;
        first_index = val->udp_first_out++;
        dindex = first_index - val->udp_dp_base;
    }
    else {
        struct udp_dest *d = 0;
        for (int i = 0; i < val->udp_dests_n; i++) {
            if (dest_equ(dst, &val->udp_dests[i].addr)) d = &val->udp_dests[i];
        }
        if (!d) {
            if (!val->udp_dests && !(val->udp_dests = calloc(UDP_DESTS, sizeof(*val->udp_dests)))) {
                return -1;
            }
            char host[256];
            const char *source = udp_flow_host(val, buffer, n, dst, proto, host, sizeof(host), 0);
            struct desync_params *found = udp_find_dp(pair, dst, source ? host : 0, is_dns(source));
            if (!found) {
                // No group for this destination: its datagram is dropped, the association (and the
                // destinations the socket already talks to) goes on.
                if (params.conn_log) {
                    INIT_ADDR_STR((*dst));
                    fprintf(ENGINE_LOG, "udp %d: -> %s:%d dropped, no group for another destination of the socket\n",
                        val->fd, ADDR_STR, ntohs(dst->in.sin_port));
                }
                udp_maybe_report(val, now);
                return n;
            }
            // Beyond UDP_DESTS destinations the oldest one makes room.
            if (val->udp_dests_n == UDP_DESTS) {
                memmove(val->udp_dests, val->udp_dests + 1, (UDP_DESTS - 1) * sizeof(*val->udp_dests));
                val->udp_dests_n--;
            }
            d = &val->udp_dests[val->udp_dests_n++];
            d->addr = *dst;
            d->dp = found;
            d->pkts = 0;
            if (params.conn_log) {
                INIT_ADDR_STR((*dst));
                fprintf(ENGINE_LOG, "udp %d: -> %s:%d, %s, host %s (%s), group %d, another destination of the socket\n",
                    val->fd, ADDR_STR, ntohs(dst->in.sin_port), udp_proto_name(proto),
                    source ? host : "unknown", source ? source : "no SNI, not in DNS answers", found->id);
            }
        }
        dp = d->dp;
        dindex = d->pkts++;
    }
    if (first && !dp) {
        char host[256];
        const char *source = udp_flow_host(val, buffer, n, dst, proto, host, sizeof(host), 1);
        if (source) udp_set_host(val, host);
        // ZapretDroid --auto=udp: a group that got through to this address before comes first.
        if (!(dp = udp_auto_cached(pair, val, dst))
                && !(dp = udp_find_dp(pair, dst, source ? host : 0, is_dns(source)))) {
            return UDP_NO_GROUP;
        }
        pair->dp = dp;
        val->udp_group_ms = now;
        if (params.conn_log) {
            INIT_ADDR_STR((*dst));
            fprintf(ENGINE_LOG, "udp %d: -> %s:%d, %s, host %s (%s), group %d%s\n", val->fd,
                ADDR_STR, ntohs(dst->in.sin_port), udp_proto_name(proto),
                source ? host : "unknown",
                source ? source : val->udp_quic ? "SNI in a later Initial" : "no SNI, not in DNS answers", dp->id,
                val->udp_auto_cached ? ", from the auto cache" : "");
        }
    }
    else if (first && val->udp_quic && proto == UDP_PROTO_QUIC) {
        // The rest of the ClientHello: the SNI now decides the group for the following datagrams.
        char host[256];
        quic_add_initial(val->udp_quic, (const uint8_t *) buffer, n);
        int len = quic_crypto_sni(val->udp_quic, host, sizeof(host));
        if (len || first_index >= 3) {
            free(val->udp_quic);
            val->udp_quic = 0;
        }
        if (len) udp_set_host(val, host);
        // A group from the auto cache stays: it was chosen for this address after a block.
        struct desync_params *found = len && !val->udp_auto_cached ? udp_find_dp(pair, dst, host, 0) : 0;
        if (found && found != dp) {
            if (params.conn_log) {
                fprintf(ENGINE_LOG, "udp %d: host %s (sni in Initial %u), group %d -> %d\n",
                    val->fd, host, first_index + 1, dp->id, found->id);
            }
            pair->dp = dp = found;
            // The new group counts --udp-desync from this datagram, so its fakes go before it.
            val->udp_dp_base = first_index;
            dindex = 0;
            val->udp_group_ms = now;
        }
    }
    if (first) {
        struct desync_params *next = udp_auto_check(pair, val, dst, dp, dindex, now);
        if (next != dp) {
            pair->dp = dp = next;
            val->udp_dp_base = first_index;
            dindex = 0;
        }
    }
    // ZapretDroid: a group that connects through a proxy (-C, e.g. Tor) carries TCP only: its
    // UDP (QUIC) is dropped, so that browsers and apps fall back to TCP through the proxy.
    if (dp->out_type) {
        if (dp->udp_proxy && dp->out_type == MODE_SOCKS5) {
            ssize_t sent = ups_dgram(pool, val, dp, dst, buffer, n);
            udp_maybe_report(val, now);
            return sent;
        }
        if (!dindex && params.conn_log) {
            fprintf(ENGINE_LOG, "udp %d: dropped, group %d goes through a proxy (TCP only)\n", val->fd, dp->id);
        }
        udp_maybe_report(val, now);
        return n;
    }
    // --udp-desync N[:K] counts datagrams of the flow; otherwise rounds (request/response) apply.
    bool desync = dp->udp_first > 0
        ? (dindex < (unsigned int) dp->udp_first || (dp->udp_every > 0 && dindex % dp->udp_every == 0))
        : check_round(dp->rounds, r);
    ssize_t ret;
    if (!desync) {
        ret = sendto(val->fd, buffer, n, 0, &dst->sa, SA_SIZE(dst));
    }
    else {
        LOG(LOG_S, "desync UDP: group=%d, round=%d, datagram=%u, fd=%d\n", dp->id, r, dindex, val->fd);
        // A datagram of an unknown protocol in a flow that started as, say, Discord (RTP after
        // IP discovery) still gets the flow's fake.
        ret = desync_udp(val->fd, buffer, n, &dst->sa, dp,
            proto ? proto : val->udp_proto, &val->udp_fakes);
    }
    udp_maybe_report(val, now);
    return ret;
}


#ifdef __linux__
static int protect(int conn_fd, const char *path)
{
    struct sockaddr_un sa;
    sa.sun_family = AF_UNIX;
    strcpy(sa.sun_path, path);
    
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        uniperror("socket");  
        return -1;
    }
    struct timeval tv = { .tv_sec = 1 };
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
    
    int err = connect(fd, (struct sockaddr *)&sa, sizeof(sa));
    if (err) {
        uniperror("connect");
        close(fd);
        return -1;
    }
    char buf[CMSG_SPACE(sizeof(fd))] = { 0 };
    struct iovec io = { .iov_base = "1", .iov_len = 1 };
    struct msghdr msg = { .msg_iov = &io };
    
    msg.msg_iovlen = 1;
    msg.msg_control = buf;
    msg.msg_controllen = sizeof(buf);

    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
    cmsg->cmsg_level = SOL_SOCKET;
    cmsg->cmsg_type = SCM_RIGHTS;
    cmsg->cmsg_len = CMSG_LEN(sizeof(conn_fd));

    *((int *)CMSG_DATA(cmsg)) = conn_fd;
    msg.msg_controllen = CMSG_SPACE(sizeof(conn_fd));

    if (sendmsg(fd, &msg, 0) < 0) {
        uniperror("sendmsg");
        close(fd);
        return -1;
    }
    if (recv(fd, buf, 1, 0) < 1) {
        uniperror("recv");
        close(fd);
        return -1;
    }
    close(fd);
    return 0;
}
#endif


/* ZapretDroid --auto-cache */

#define AUTO_CACHE_SAVE_S 15

static struct desync_params *group_by_id(int id)
{
    for (struct desync_params *dp = params.dp; dp; dp = dp->next) {
        if (dp->id == id) return dp;
    }
    return 0;
}


void auto_cache_load(void)
{
    if (!params.auto_cache || !params.mempool) {
        return;
    }
    FILE *f = fopen(params.auto_cache, "r");
    if (!f) {
        return; // first run
    }
    int n = load_cache_all(params.mempool, params.udp_cache, f, params.dp_n, group_by_id);
    fclose(f);
    if (n < 0) {
        LOG(LOG_S, "auto cache: %s is for another strategy, not loaded\n", params.auto_cache);
    }
    else if (params.conn_log) {
        fprintf(ENGINE_LOG, "auto cache: %d address(es) loaded\n", n);
    }
    params.auto_cache_saved = time(0);
}


void auto_cache_save(bool force)
{
    if (!params.auto_cache || !params.mempool) {
        return;
    }
    time_t now = time(0);
    if (!force && (!params.auto_cache_dirty || now - params.auto_cache_saved < AUTO_CACHE_SAVE_S)) {
        return;
    }
    // Written beside the file and renamed over it: a process killed while writing leaves the
    // previous cache, not half of the new one.
    size_t len = strlen(params.auto_cache);
    char *tmp = malloc(len + 5);
    if (!tmp) {
        return;
    }
    memcpy(tmp, params.auto_cache, len);
    memcpy(tmp + len, ".tmp", 5);
    FILE *f = fopen(tmp, "w");
    if (!f) {
        uniperror("auto cache fopen");
        free(tmp);
        return;
    }
    fprintf(f, "zdcache 1 %d\n", params.dp_n);
    dump_cache_all(params.mempool, f, 't');
    dump_cache_all(params.udp_cache, f, 'u');
    bool ok = !ferror(f);
    ok = !fclose(f) && ok;
    if (!ok || rename(tmp, params.auto_cache)) {
        uniperror("auto cache write");
        remove(tmp);
    }
    else {
        params.auto_cache_dirty = 0;
        params.auto_cache_saved = now;
    }
    free(tmp);
}


void auto_cache_changed(void)
{
    params.auto_cache_dirty = 1;
    auto_cache_save(0);
}
