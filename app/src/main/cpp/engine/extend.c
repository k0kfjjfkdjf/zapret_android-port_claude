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


static struct elem_i *cache_get(const union sockaddr_u *dst)
{
    struct cache_key key = { 0 };
    int len = serialize_addr(dst, &key);
    
    struct elem_i *val = mem_get(params.mempool, (char *)&key, len * 8);
    if (!val) {
        return 0;
    }
    struct desync_params *dp = val->dp;
    
    time_t t = time(0);
    if (dp->cache_ttl && t > val->time + dp->cache_ttl) {
        LOG(LOG_S, "time=%jd, now=%jd, ignore\n", (intmax_t)val->time, (intmax_t)t);
        mem_delete(params.mempool, (char *)&key, len);
        return 0;
    }
    return val;
}


static struct elem_i *cache_add(
        const union sockaddr_u *dst, char **host, int host_len)
{
    struct cache_key key = { 0 };
    int bytes = serialize_addr(dst, &key);
    int cmp_len = bytes * 8;
    time_t t = time(0);
    
    if (dst->sa.sa_family == AF_INET && params.cache_pre) {
        struct elem_i *val = mem_get(
            params.mempool, (char *)&key, cmp_len - params.cache_pre);
        if (val) {
            cmp_len = eq_bitlen((char *)&key, val->main.data, bytes);
        }
    }
    struct cache_key *data = calloc(1, bytes);
    if (!data) {
        return 0;
    }
    memcpy(data, &key, bytes);
    
    struct elem_i *val = mem_add(params.mempool, (char *)data, cmp_len, sizeof(struct elem_i));
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


static bool host_in(struct mphdr *hosts, const char *host, int len)
{
    struct elem *v = mem_get(hosts, host, len);
    return v && v->len <= len;
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
        struct mphdr *ipset, const union sockaddr_u *dst)
{
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
        : (type & DETECT_CONNECT) ? "connect" : "unknown";
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
    if (params.conn_log) {
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
    if (!next) {
        LOG(LOG_S, "unreach ip: %s\n", ADDR_STR);
        cache->dp_mask = 0;
        cache->detect = 0;
        cache->dp = params.dp;
        return -1;
    }
    LOG(LOG_S, "save: ip=%s, id=%d\n", ADDR_STR, next->id);
    
    cache->dp_mask |= lav->dp_mask;
    cache->detect = lav->detect;
    cache->dp = dp;
    
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
    char retries[32] = "";
    if (remote->udp_retries) {
        snprintf(retries, sizeof(retries), ", retries %u", remote->udp_retries);
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
static struct desync_params *udp_find_dp(const union sockaddr_u *dst, const char *host, bool by_dns)
{
    for (struct desync_params *dp = params.dp; dp; dp = dp->next) {
        if (dp->detect || !check_l34(dp, SOCK_DGRAM, dst)) {
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


ssize_t udp_hook(struct eval *val, 
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
    if (params.dns_redir && dest_equ(dst, &params.dns_redir_from)) {
        ssize_t sent = sendto(val->fd, buffer, n, 0, &params.dns_redir_to.sa, SA_SIZE(&params.dns_redir_to));
        udp_maybe_report(val, now);
        return sent;
    }
    
    // The first destination of the association keeps its group on the client side (pair->dp);
    // other destinations of the same app socket get theirs in val->udp_dests.
    bool first = dest_equ(dst, &val->addr);
    struct desync_params *dp = 0;
    unsigned int dindex = index - val->udp_dp_base;
    if (first) {
        dp = pair->dp;
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
            struct desync_params *found = udp_find_dp(dst, source ? host : 0, is_dns(source));
            if (!found) {
                return -1;
            }
            // Beyond UDP_DESTS destinations the oldest entry makes room.
            d = &val->udp_dests[val->udp_dests_n < UDP_DESTS ? val->udp_dests_n++ : (int) (index % UDP_DESTS)];
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
        if (!(dp = udp_find_dp(dst, source ? host : 0, is_dns(source)))) {
            return -1;
        }
        pair->dp = dp;
        if (params.conn_log) {
            INIT_ADDR_STR((*dst));
            fprintf(ENGINE_LOG, "udp %d: -> %s:%d, %s, host %s (%s), group %d\n", val->fd,
                ADDR_STR, ntohs(dst->in.sin_port), udp_proto_name(proto),
                source ? host : "unknown",
                source ? source : val->udp_quic ? "SNI in a later Initial" : "no SNI, not in DNS answers", dp->id);
        }
    }
    else if (first && val->udp_quic && proto == UDP_PROTO_QUIC) {
        // The rest of the ClientHello: the SNI now decides the group for the following datagrams.
        char host[256];
        quic_add_initial(val->udp_quic, (const uint8_t *) buffer, n);
        int len = quic_crypto_sni(val->udp_quic, host, sizeof(host));
        if (len || index >= 3) {
            free(val->udp_quic);
            val->udp_quic = 0;
        }
        struct desync_params *found = len ? udp_find_dp(dst, host, 0) : 0;
        if (found && found != dp) {
            if (params.conn_log) {
                fprintf(ENGINE_LOG, "udp %d: host %s (sni in Initial %u), group %d -> %d\n",
                    val->fd, host, index + 1, dp->id, found->id);
            }
            pair->dp = dp = found;
            // The new group counts --udp-desync from this datagram, so its fakes go before it.
            val->udp_dp_base = index;
            dindex = 0;
        }
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
