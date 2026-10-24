#ifndef CIADPI_ERROR_H
#define CIADPI_ERROR_H

#include <stdio.h>
#include <stdarg.h>
#include <errno.h>
#include <string.h>

#ifdef _WIN32
    #include <winsock2.h>
#endif
#ifdef ANDROID_APP
    #include <android/log.h>
#endif

#include "params.h"

/*
 * ZapretDroid: a name from the client (SNI, Host header, SOCKS domain) for the log: lower case,
 * anything but [a-z0-9.-_:[]] as '?'. The app's log readers (@on rules, the connection monitor,
 * auto-fix) read lines like "conn 1: block detected ... on bank.com": raw, a name with a line
 * break let any app forge them.
 */
static inline void log_safe(char *out, size_t size, const char *in, size_t len)
{
    size_t i = 0;
    for (; i < len && i + 1 < size; i++) {
        unsigned char c = (unsigned char) in[i];
        if (c >= 'A' && c <= 'Z') c += 32;
        int ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
            || c == '.' || c == '-' || c == '_' || c == ':' || c == '[' || c == ']';
        out[i] = ok ? (char) c : '?';
    }
    out[i] = 0;
}

/* Where diagnostics go; the Android app points it at the session log, stderr otherwise. */
extern FILE *engine_log;
#define ENGINE_LOG (engine_log ? engine_log : stderr)

#ifdef _WIN32
#define get_e() \
    unie(WSAGetLastError())
#else
#define get_e() \
    errno
#endif

#ifdef _WIN32
    #define uniperror(str) \
        fprintf(stderr, "%s: %ld\n", str, GetLastError())
#else
    #ifdef ANDROID_APP
    #define uniperror(str) \
        __android_log_print(ANDROID_LOG_ERROR, "proxy", \
            "%s: %s\n", str, strerror(errno))
    #else
    #define uniperror(str) \
        fprintf(ENGINE_LOG, "%s: %s\n", str, strerror(errno))
    #endif
#endif

static int unie(int e)
{
    #ifdef _WIN32
    switch (e) {
        case WSAEWOULDBLOCK:
            return EAGAIN;
        case WSAETIMEDOUT:
            return ETIMEDOUT;
        case WSAENETUNREACH:
            return ENETUNREACH;
        case WSAEHOSTUNREACH:
            return EHOSTUNREACH;
        case WSAECONNREFUSED:
            return ECONNREFUSED;
        case WSAECONNRESET:
            return ECONNRESET;
    }
    #endif
    return e;
}

#ifdef ANDROID_APP
    #define LOG_E ANDROID_LOG_ERROR
    #define LOG_S ANDROID_LOG_DEBUG
    #define LOG_L ANDROID_LOG_VERBOSE
    #define LOG(s, str, ...) \
        __android_log_print(s, "proxy", str, ##__VA_ARGS__)
    #define LOG_ENABLED 1
#else
    #define LOG_E -1
    #define LOG_S 1
    #define LOG_L 2
    static void LOG(int s, const char *str, ...) {
        if (params.debug >= s) {
            va_list args;
            va_start(args, str);
            vfprintf(ENGINE_LOG, str, args);
            va_end(args);
        }
    }
    #define LOG_ENABLED (params.debug >= LOG_S)
#endif

#define INIT_ADDR_STR(dst) \
    char ADDR_STR[INET6_ADDRSTRLEN]; \
    const char *p = 0; \
    if (dst.sa.sa_family == AF_INET) \
        p = inet_ntop(AF_INET, &dst.in.sin_addr, ADDR_STR, sizeof(ADDR_STR)); \
    else \
        p = inet_ntop(AF_INET6, &dst.in6.sin6_addr, ADDR_STR, sizeof(ADDR_STR)); \
    if (!p) uniperror("inet_ntop");

#endif

#define INIT_HEX_STR(b, s) \
    char HEX_STR[s * 2 + 1]; \
    HEX_STR[sizeof(HEX_STR) - 1] = 0; \
    do { \
        ssize_t i; \
        for (i = 0; i + 4 <= s; i += 4) \
            snprintf(HEX_STR + i * 2, sizeof(HEX_STR) - i * 2, \
                "%02x%02x%02x%02x", b[i],b[i+1],b[i+2],b[i+3]); \
        for (; i < s; i++) \
            snprintf(HEX_STR + i * 2, sizeof(HEX_STR) - i * 2, "%02x", b[i]); \
    } while (0);
