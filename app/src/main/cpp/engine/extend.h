#ifndef EXTEND_H
#define EXTEND_H

#include <stddef.h>

#include "proxy.h"
#include "params.h"

int socket_mod(int fd);

int connect_hook(struct poolhd *pool, struct eval *val, 
        const union sockaddr_u *dst, evcb_t next);
        
ssize_t tcp_send_hook(struct poolhd *pool, 
        struct eval *remote, struct buffer *buff, ssize_t *n, bool *wait);
        
ssize_t tcp_recv_hook(struct poolhd *pool, 
        struct eval *val, struct buffer *buff);
        
/* Accounts a datagram received from the server of a UDP flow. */
void udp_stat_in(struct eval *remote, ssize_t n);

void udp_reply_hook(struct eval *val, const char *buffer, ssize_t n, const union sockaddr_u *src);

bool ups_is_relay(struct eval *remote, const union sockaddr_u *from);

/* ZapretDroid --auto=udp: a datagram from [src] came back on the remote socket of a flow. */
void udp_reply_from(struct eval *remote, const union sockaddr_u *src);

/*
 * Sends one datagram of the client to [dst]. Returns the bytes sent, -1 if the datagram could
 * not be sent (errno says why: lost like any datagram, the association goes on), or
 * UDP_NO_GROUP if no group takes the association's first destination (it is closed).
 */
#define UDP_NO_GROUP (-2)

ssize_t udp_hook(struct poolhd *pool, struct eval *val,
        char *buffer, ssize_t n, const union sockaddr_u *dst);
    
static bool check_l34(struct desync_params *dp, int st, const union sockaddr_u *dst);

int on_connerr(struct poolhd *pool, struct eval *val);

bool proxy_refused(struct eval *remote, int error);

#include <pthread.h>

/* ZapretDroid: host lists of --slot groups change while the engine runs. */
extern pthread_mutex_t slot_lock;

/*
 * Moves [host] to the --slot group named [slot] (out of every other slot group), or out of all
 * of them if [slot] is NULL. Callable from any thread. 0 on success, -1 if there is no such slot.
 */
int slot_assign(const char *host, const char *slot);

/*
 * ZapretDroid --apps: the host's lookup of the app (uid) owning a connection from src_ip:src_port
 * to dst_ip:dst_port (proto: IPPROTO_TCP or IPPROTO_UDP); -1 if unknown. Unset: no app groups match.
 */
extern int (*owner_uid_hook)(int proto, const char *src_ip, int src_port, const char *dst_ip, int dst_port);

/* ZapretDroid --auto-cache: load at start, save (force: now; else if changed and due). */
void auto_cache_load(void);
void auto_cache_save(bool force);
void auto_cache_changed(void);

int on_timeout(struct poolhd *pool, struct eval *val);

#ifdef __linux__
static int protect(int conn_fd, const char *path);
#else
#define protect(fd, path) 0
#endif

#endif
