#include "mpool.h"

#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <assert.h>


static int bit_cmp(const struct elem *p, const struct elem *q)
{
    int len = q->len < p->len ? q->len : p->len;
    int df = len % 8, bytes = len / 8;
    int cmp = memcmp(p->data, q->data, bytes);
    
    if (cmp || !df) {
        return cmp;
    }
    uint8_t c1 = p->data[bytes] >> (8 - df);
    uint8_t c2 = q->data[bytes] >> (8 - df);
    if (c1 != c2) {
        if (c1 < c2) return -1;
        else return 1;
    }
    return 0;
}


static int byte_cmp(const struct elem *p, const struct elem *q)
{
    if (p->len != q->len) {
        return p->len < q->len ? -1 : 1;
    }
    return memcmp(p->data, q->data, p->len);
}


static int host_cmp(const struct elem *p, const struct elem *q)
{
    int len = q->len < p->len ? q->len : p->len;
    char *pd = p->data + p->len, *qd = q->data + q->len;
    
    while (len-- > 0) {
        if (*--pd != *--qd) {
            return *pd < *qd ? -1 : 1;
        }
    }
    if (p->len == q->len 
            || (p->len > q->len ? pd[-1] : qd[-1]) == '.')
        return 0;
    
    return p->len > q->len ? 1 : -1;
}


static int scmp(const struct elem *p, const struct elem *q)
{
    switch (p->cmp_type) {
    case CMP_BITS:
        return bit_cmp(p, q);
    case CMP_HOST:
        return host_cmp(p, q);
    default:
        return byte_cmp(p, q);
    }
}

KAVL_INIT(my, struct elem, head, scmp)


struct mphdr *mem_pool(unsigned short flags, unsigned char cmp_type)
{
    struct mphdr *hdr = calloc(1, sizeof(struct mphdr));
    if (hdr) {
        hdr->flags = flags;
        hdr->cmp_type = cmp_type;
    }
    return hdr;
}


void *mem_get(const struct mphdr *hdr, const char *str, int len)
{
    struct elem temp = { 
        .cmp_type = hdr->cmp_type,
        .len = len, .data = (char *)str 
    };
    return kavl_find(my, hdr->root, &temp, 0);
}


static void destroy_elem(struct mphdr *hdr, struct elem *e)
{
    if (!(hdr->flags & MF_STATIC)) {
        free(e->data);
    }
    if (hdr->flags & MF_EXTRA) {
        free(((struct elem_ex *)e)->extra);
    }
    free(e);
}


void *mem_add(struct mphdr *hdr, char *str, int len, size_t struct_size)
{
    struct elem *v, *e = calloc(1, struct_size);
    if (!e) {
        return 0;
    }
    e->len = len;
    e->cmp_type = hdr->cmp_type;
    e->data = str;
    
    v = kavl_insert(my, &hdr->root, e, 0);
    while (e != v && e->len < v->len) {
        mem_delete(hdr, v->data, v->len);
        v = kavl_insert(my, &hdr->root, e, 0);
    }
    if (e != v) {
        destroy_elem(hdr, e);
    }
    else hdr->count++;
    return v;
}


void mem_delete(struct mphdr *hdr, const char *str, int len)
{
    struct elem temp = { 
        .cmp_type = hdr->cmp_type,
        .len = len, .data = (char *)str 
    };
    struct elem *e = kavl_erase(my, &hdr->root, &temp, 0);
    if (!e) {
        return;
    }
    destroy_elem(hdr, e);
    hdr->count--;
}


void mem_destroy(struct mphdr *hdr)
{
    while (hdr && hdr->root) {
        struct elem *e = kavl_erase_first(my, &hdr->root);
        if (!e) {
            break;
        }
        destroy_elem(hdr, e);
    }
    free(hdr);
}


void dump_cache(struct mphdr *hdr, FILE *out, struct desync_params *dp)
{
    if (!hdr->root) {
        return;
    }
    kavl_itr_t(my) itr;
    kavl_itr_first(my, hdr->root, &itr);
    do {
        struct elem_i *p = (struct elem_i *)kavl_at(&itr);
        struct cache_key *key = (struct cache_key *)p->main.data;
        
        if (p->dp != dp) {
            continue;
        }
        char ADDR_STR[INET6_ADDRSTRLEN];
        if (key->family == AF_INET)
            inet_ntop(AF_INET, &key->ip.v4, ADDR_STR, sizeof(ADDR_STR));
        else
            inet_ntop(AF_INET6, &key->ip.v6, ADDR_STR, sizeof(ADDR_STR));
        
        int bitlen = p->main.len - offsetof(struct cache_key, ip.v4) * 8;
        fprintf(out, "0 %s %d %d %jd %.*s\n", 
            ADDR_STR, bitlen, ntohs(key->port),
            (intmax_t)p->time, p->extra_len ? p->extra_len : 1, p->extra ? p->extra : "-");
    } 
    while (kavl_itr_next(my, &itr));
    fflush(out);
}


void load_cache(struct mphdr *hdr, FILE *in, struct desync_params *dp)
{
    for (int i = 0; ; i++) {
        char addr_str[INET6_ADDRSTRLEN] = { 0 };
        char host[256] = { 0 };
        
        int bitlen;
        uint16_t port;
        time_t cache_time;
        
        int c = fscanf(in, "0 %39s %d %hu %jd %255s\n", 
            addr_str, &bitlen, &port, &cache_time, host);
        if (c < 1) {
            return;
        }
        // ZapretDroid: a short line left cache_time and host unset, and a negative or too
        // long bit length went into the key as it was (or overflowed when added to below).
        if (c != 5 || bitlen < 0 || bitlen > 128) {
            continue;
        }
        struct cache_key key = { 0 };
        int key_size = offsetof(struct cache_key, ip.v4);
        bitlen += key_size * 8;
        
        if (inet_pton(AF_INET, addr_str, &key.ip.v4) <= 0) {
            if (inet_pton(AF_INET6, addr_str, &key.ip.v6) <= 0) {
                continue;
            } else {
                key.family = AF_INET6;
                key_size += sizeof(key.ip.v6);
            }
        }
        else {
            key.family = AF_INET;
            key_size += sizeof(key.ip.v4);
        }
        if (key_size * 8 < bitlen) {
            continue;
        }
        key.port = htons(port);
        
        struct cache_key *data = calloc(1, key_size);
        if (!data) {
            return;
        }
        memcpy(data, &key, key_size);
        
        struct elem_i *e = mem_add(hdr, (char *)data, bitlen, sizeof(struct elem_i));
        if (!e) {
            free(data);
            return;
        }
        e->time = cache_time;
        e->extra_len = strlen(host);
        e->dp = dp;
        
        if (e->extra_len > 1) {
            e->extra = malloc(e->extra_len + 1);
            memcpy(e->extra, host, e->extra_len + 1);
        }
    }
}



/*
 * ZapretDroid --auto-cache: the whole cache in one file, every entry with its group, so that
 * what --auto learnt survives a restart of the engine (the app's process is killed at will).
 * Line: "<t|u> <group id> <dp_mask hex> <detect> <ip> <bits> <port> <time> <host|->" (t: the
 * TCP cache, u: UDP's), after a header "zdcache 1 <groups>" that ties the file to a strategy
 * with that many groups (written by the caller).
 */
void dump_cache_all(struct mphdr *hdr, FILE *out, char tag)
{
    if (!hdr || !hdr->root) {
        return;
    }
    kavl_itr_t(my) itr;
    kavl_itr_first(my, hdr->root, &itr);
    do {
        struct elem_i *p = (struct elem_i *)kavl_at(&itr);
        struct cache_key *key = (struct cache_key *)p->main.data;
        if (!p->dp) {
            continue;
        }
        char addr[INET6_ADDRSTRLEN];
        if (key->family == AF_INET)
            inet_ntop(AF_INET, &key->ip.v4, addr, sizeof(addr));
        else
            inet_ntop(AF_INET6, &key->ip.v6, addr, sizeof(addr));
        int bitlen = p->main.len - offsetof(struct cache_key, ip.v4) * 8;
        // Host names with white space would break the line: written as "-".
        bool plain = p->extra && p->extra_len > 0 && p->extra_len < 256;
        for (unsigned int i = 0; plain && i < p->extra_len; i++) {
            unsigned char ch = p->extra[i];
            if (ch <= ' ' || ch >= 0x7f) plain = 0;
        }
        fprintf(out, "%c %d %jx %d %s %d %d %jd %.*s\n",
            tag, p->dp->id, (uintmax_t) p->dp_mask, p->detect, addr, bitlen, ntohs(key->port),
            (intmax_t) p->time, plain ? (int) p->extra_len : 1, plain ? p->extra : "-");
    }
    while (kavl_itr_next(my, &itr));
}


int load_cache_all(struct mphdr *tcp, struct mphdr *udp, FILE *in, int groups,
        struct desync_params *(*group)(int id), time_t min_time)
{
    uint64_t full_mask = groups >= 64 ? ~(uint64_t) 0 : ((uint64_t) 1 << groups) - 1;
    int version = 0, n = -1;
    if (fscanf(in, "zdcache %d %d\n", &version, &n) != 2 || version != 1) {
        return -1;
    }
    if (n != groups) {
        // Another strategy: its group numbers mean other groups here.
        return -1;
    }
    int loaded = 0;
    char line[512];
    while (fgets(line, sizeof(line), in)) {
        int id, detect, bitlen, port;
        uintmax_t mask;
        intmax_t cache_time;
        char tag = 0;
        char addr_str[INET6_ADDRSTRLEN] = { 0 };
        char host[256] = { 0 };
        if (sscanf(line, "%c %d %jx %d %45s %d %d %jd %255s",
                &tag, &id, &mask, &detect, addr_str, &bitlen, &port, &cache_time, host) != 9) {
            continue;
        }
        struct mphdr *hdr = tag == 't' ? tcp : tag == 'u' ? udp : 0;
        struct desync_params *dp = group(id);
        if (!hdr) {
            continue;
        }
        // A mask of every group is never saved (no group left resets it): a damaged line.
        if (!dp || (time_t) cache_time < min_time || bitlen < 0 || bitlen > 128 || port < 0 || port > 0xffff
                || ((uint64_t) mask & full_mask) == full_mask) {
            continue;
        }
        struct cache_key key = { 0 };
        int key_size = offsetof(struct cache_key, ip.v4);
        if (inet_pton(AF_INET, addr_str, &key.ip.v4) == 1) {
            key.family = AF_INET;
            key_size += sizeof(key.ip.v4);
        }
        else if (inet_pton(AF_INET6, addr_str, &key.ip.v6) == 1) {
            key.family = AF_INET6;
            key_size += sizeof(key.ip.v6);
        }
        else {
            continue;
        }
        bitlen += offsetof(struct cache_key, ip.v4) * 8;
        if (bitlen > key_size * 8) {
            continue;
        }
        key.port = htons(port);
        struct cache_key *data = calloc(1, key_size);
        if (!data) {
            break;
        }
        memcpy(data, &key, key_size);
        struct elem_i *e = mem_add(hdr, (char *)data, bitlen, sizeof(struct elem_i));
        if (!e) {
            free(data);
            break;
        }
        free(e->extra);
        e->extra = 0;
        e->extra_len = 0;
        e->time = (time_t) cache_time;
        e->dp = dp;
        // Only bits of existing groups: find_dp ends when the mask is full.
        e->dp_mask = (uint64_t) mask & full_mask;
        e->detect = detect;
        size_t hl = strlen(host);
        if (hl > 1 || (hl == 1 && host[0] != '-')) {
            e->extra = malloc(hl + 1);
            if (e->extra) {
                memcpy(e->extra, host, hl + 1);
                e->extra_len = hl;
            }
        }
        loaded++;
    }
    return loaded;
}
