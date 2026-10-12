/*
 * Captures the diagnostics of the native engines into the session log file.
 *
 * ByeDPI prints its diagnostics to engine_log (verbosity set with --debug) and
 * hev-socks5-tunnel is configured with "log-file: /proc/self/fd/N", so both end up in a pipe.
 * A reader thread timestamps every line, tags its source and appends it to the log
 * file; lines are mirrored to logcat for adb users.
 */
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/uio.h>
#include <time.h>
#include <unistd.h>

#include <android/log.h>

static int log_fd = -1;
static long long log_limit;
static long long log_written;
static int limit_reached;

static void write_line(const char *src, const char *msg, size_t len)
{
    struct timespec now;
    struct tm tm;
    char ts[32];

    clock_gettime(CLOCK_REALTIME, &now);
    localtime_r(&now.tv_sec, &tm);
    int ts_len = snprintf(ts, sizeof(ts), "%02d-%02d %02d:%02d:%02d.%03ld ",
            tm.tm_mon + 1, tm.tm_mday, tm.tm_hour, tm.tm_min, tm.tm_sec, now.tv_nsec / 1000000);

    struct iovec iov[] = {
        { ts, (size_t) ts_len },
        { (void *) src, strlen(src) },
        { " | ", 3 },
        { (void *) msg, len },
        { "\n", 1 },
    };
    __android_log_print(ANDROID_LOG_INFO, src, "%.*s", (int) len, msg);
    if (limit_reached) {
        return;
    }
    ssize_t n = writev(log_fd, iov, sizeof(iov) / sizeof(*iov));

    if (n > 0) {
        log_written += n;
    }
    if (log_limit > 0 && log_written > log_limit && !limit_reached) {
        // The journal is shared with the app; stop adding engine output instead of growing it
        // without bound. Lines still go to logcat.
        static const char note[] = "--- engine output limit for this session reached, rest only in logcat ---\n";
        if (write(log_fd, note, sizeof(note) - 1) > 0) {}
        limit_reached = 1;
    }
}

/* hev-socks5-tunnel lines look like "[2026-01-02 03:04:05] [W] message". */
static int is_hev_line(const char *line, size_t len)
{
    return len >= 26 && line[0] == '[' && line[20] == ']' && line[22] == '[' && line[24] == ']';
}

static void handle_line(char *line, size_t len)
{
    while (len > 0 && (line[len - 1] == '\r' || line[len - 1] == '\n')) {
        len--;
    }
    if (len == 0) {
        return;
    }
    if (is_hev_line(line, len)) {
        // Drop hev's own second-resolution timestamp, keep the level tag.
        write_line("hev", line + 22, len - 22);
    } else {
        write_line("byedpi", line, len);
    }
}

static void *reader(void *arg)
{
    int fd = (int) (intptr_t) arg;
    char buf[8192];
    size_t used = 0;

    for (;;) {
        ssize_t n = read(fd, buf + used, sizeof(buf) - used);
        if (n < 0 && errno == EINTR) {
            continue;
        }
        if (n <= 0) {
            break;
        }
        used += n;

        size_t start = 0;
        for (size_t i = 0; i < used; i++) {
            if (buf[i] == '\n') {
                handle_line(buf + start, i - start);
                start = i + 1;
            }
        }
        if (start == 0 && used == sizeof(buf)) {
            // A line longer than the buffer: flush what we have.
            handle_line(buf, used);
            used = 0;
        } else {
            memmove(buf, buf + start, used - start);
            used -= start;
        }
    }
    if (used > 0) {
        handle_line(buf, used);
    }
    close(fd);
    return NULL;
}

/*
 * Starts capturing. The engines do not write to the process stdout/stderr (whose descriptors
 * may be closed or reused in an app process): ByeDPI writes to engine_log, hev-socks5-tunnel
 * opens the returned /proc/self/fd path. Both lead into the pipe read by the thread above.
 * Returns that path, or null if capturing could not start.
 */
extern FILE *engine_log;

JNIEXPORT jstring JNICALL
Java_io_github_zapretdroid_core_NativeLog_start(JNIEnv *env, jobject thiz, jstring path, jlong limit)
{
    static char hev_path[32];
    if (log_fd >= 0) {
        return (*env)->NewStringUTF(env, hev_path);
    }
    const char *cpath = (*env)->GetStringUTFChars(env, path, NULL);
    if (!cpath) {
        return NULL;
    }
    int fd = open(cpath, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600);
    (*env)->ReleaseStringUTFChars(env, path, cpath);
    if (fd < 0) {
        return NULL;
    }
    int pipefd[2];
    if (pipe(pipefd) < 0) {
        close(fd);
        return NULL;
    }
    FILE *out = fdopen(pipefd[1], "w");
    if (!out) {
        close(pipefd[0]);
        close(pipefd[1]);
        close(fd);
        return NULL;
    }
    setvbuf(out, NULL, _IOLBF, 0);

    log_fd = fd;
    log_limit = limit;
    log_written = 0; // counts this session's engine output only

    pthread_t thread;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    int res = pthread_create(&thread, &attr, reader, (void *) (intptr_t) pipefd[0]);
    pthread_attr_destroy(&attr);
    if (res != 0) {
        fclose(out);
        close(pipefd[0]);
        close(fd);
        log_fd = -1;
        return NULL;
    }
    engine_log = out;
    // Its presence in the session log shows that capture works.
    fprintf(engine_log, "native output capture started\n");
    snprintf(hev_path, sizeof(hev_path), "/proc/self/fd/%d", pipefd[1]);
    return (*env)->NewStringUTF(env, hev_path);
}
