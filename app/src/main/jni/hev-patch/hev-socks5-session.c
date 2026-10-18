/*
 ============================================================================
 Name        : hev-socks5-session.c
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2017 - 2023 hev
 Description : Socks5 Session
 ============================================================================
 */

#include <stdio.h>
#include <string.h>

#include <lwip/ip_addr.h>
#include <lwip/tcp.h>
#include <lwip/udp.h>
#include <hev-task-mutex.h>

#include "hev-utils.h"
#include "hev-logger.h"
#include "hev-config.h"
#include "hev-socks5-client.h"

#include "hev-socks5-session.h"
#include "hev-socks5-session-tcp.h"
#include "hev-socks5-session-udp.h"

/*
 * ByeZapret: with the user name "@src" in the config, each session authenticates with the
 * address its app's connection came from ("10.10.10.2:41234"), so that the proxy (the ByeZapret
 * engine) can ask Android which app owns the connection and route it by app.
 */
static int
session_source (HevSocks5Session *self, char *buf, size_t size)
{
    HevObjectClass *klass = HEV_OBJECT (self)->klass;
    HevTaskMutex *mutex;
    ip_addr_t ip;
    u16_t port;
    char ipbuf[48];
    int ok = 0;

    if (klass == HEV_SOCKS5_SESSION_TCP_TYPE) {
        HevSocks5SessionTCP *tcp = HEV_SOCKS5_SESSION_TCP (self);
        mutex = tcp->mutex;
        hev_task_mutex_lock (mutex);
        if (tcp->pcb) {
            ip_addr_copy (ip, tcp->pcb->remote_ip);
            port = tcp->pcb->remote_port;
            ok = 1;
        }
        hev_task_mutex_unlock (mutex);
    } else if (klass == HEV_SOCKS5_SESSION_UDP_TYPE) {
        HevSocks5SessionUDP *udp = HEV_SOCKS5_SESSION_UDP (self);
        mutex = udp->mutex;
        hev_task_mutex_lock (mutex);
        if (udp->pcb) {
            ip_addr_copy (ip, udp->pcb->remote_ip);
            port = udp->pcb->remote_port;
            ok = 1;
        }
        hev_task_mutex_unlock (mutex);
    }
    if (!ok || !ipaddr_ntoa_r (&ip, ipbuf, sizeof (ipbuf)))
        return -1;
    snprintf (buf, size, IP_IS_V6_VAL (ip) ? "[%s]:%u" : "%s:%u", ipbuf, port);
    return 0;
}

void
hev_socks5_session_run (HevSocks5Session *self)
{
    HevSocks5SessionIface *iface;
    HevConfigServer *srv;
    int res;

    LOG_D ("%p socks5 session run", self);

    srv = hev_config_get_socks5_server ();

    res = hev_socks5_client_connect (HEV_SOCKS5_CLIENT (self), srv->addr,
                                     srv->port);
    if (res < 0) {
        LOG_I ("%p socks5 session connect", self);
        return;
    }

    char source[64];
    if (srv->user && srv->pass && !strcmp (srv->user, "@src")) {
        if (session_source (self, source, sizeof (source)) == 0)
            hev_socks5_client_set_auth (HEV_SOCKS5_CLIENT (self), source,
                                        srv->pass);
    } else if (srv->user && srv->pass) {
        hev_socks5_client_set_auth (HEV_SOCKS5_CLIENT (self), srv->user,
                                    srv->pass);
        LOG_D ("%p socks5 client auth %s:%s", self, srv->user, srv->pass);
    }

    res = hev_socks5_client_handshake (HEV_SOCKS5_CLIENT (self), srv->pipeline);
    if (res < 0) {
        LOG_I ("%p socks5 session handshake", self);
        return;
    }

    iface = HEV_OBJECT_GET_IFACE (self, HEV_SOCKS5_SESSION_TYPE);
    iface->splicer (self);
}

void
hev_socks5_session_terminate (HevSocks5Session *self)
{
    HevSocks5SessionIface *iface;

    LOG_D ("%p socks5 session terminate", self);

    iface = HEV_OBJECT_GET_IFACE (self, HEV_SOCKS5_SESSION_TYPE);
    hev_socks5_set_timeout (HEV_SOCKS5 (self), 0);
    hev_task_wakeup (iface->get_task (self));
}

void
hev_socks5_session_set_task (HevSocks5Session *self, HevTask *task)
{
    HevSocks5SessionIface *iface;

    iface = HEV_OBJECT_GET_IFACE (self, HEV_SOCKS5_SESSION_TYPE);
    iface->set_task (self, task);
}

HevListNode *
hev_socks5_session_get_node (HevSocks5Session *self)
{
    HevSocks5SessionIface *iface;

    iface = HEV_OBJECT_GET_IFACE (self, HEV_SOCKS5_SESSION_TYPE);
    return iface->get_node (self);
}

int
hev_socks5_session_bind (HevSocks5 *self, int fd, const struct sockaddr *dest)
{
    HevConfigServer *srv;
    unsigned int mark;

    LOG_D ("%p socks5 session bind", self);

    srv = hev_config_get_socks5_server ();
    mark = srv->mark;

    if (mark) {
        int res;

        res = set_sock_mark (fd, mark);
        if (res < 0)
            return -1;
    }

    set_sock_tcp_fastopen (fd, srv->fastopen);

    return 0;
}

void *
hev_socks5_session_iface (void)
{
    static char type;

    return &type;
}
