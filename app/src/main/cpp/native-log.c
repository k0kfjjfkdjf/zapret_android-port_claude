/*
 * Captures stdout/stderr of the native engines into the session log file.
 *
 * ByeDPI prints its diagnostics to stderr (verbosity set with --debug) and
 * hev-socks5-tunnel is configured with "log-file: stderr", so both end up in a pipe.
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
    ssize_t n = writev(log_fd, iov, sizeof(iov) / sizeof(*iov));
    __android_log_print(ANDROID_LOG_INFO, src, "%.*s", (int) len, msg);

    if (n > 0) {
        log_written += n;
    }
    if (log_limit > 0 && log_written > log_limit) {
        // Verbose logging can produce a lot of output; start over instead of filling the disk.
        if (ftruncate(log_fd, 0) == 0) {
            static const char note[] = "--- log truncated: size limit reached ---\n";
            log_written = write(log_fd, note, sizeof(note) - 1);
        }
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

JNIEXPORT jboolean JNICALL
Java_io_github_zapretdroid_core_NativeLog_start(JNIEnv *env, jobject thiz, jstring path, jlong limit)
{
    if (log_fd >= 0) {
        return JNI_TRUE;
    }
    const char *cpath = (*env)->GetStringUTFChars(env, path, NULL);
    if (!cpath) {
        return JNI_FALSE;
    }
    int fd = open(cpath, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600);
    (*env)->ReleaseStringUTFChars(env, path, cpath);
    if (fd < 0) {
        return JNI_FALSE;
    }

    int pipefd[2];
    if (pipe(pipefd) < 0) {
        close(fd);
        return JNI_FALSE;
    }
    log_fd = fd;
    log_limit = limit;
    log_written = lseek(fd, 0, SEEK_END);

    fflush(stdout);
    fflush(stderr);
    dup2(pipefd[1], STDOUT_FILENO);
    dup2(pipefd[1], STDERR_FILENO);
    if (pipefd[1] != STDOUT_FILENO && pipefd[1] != STDERR_FILENO) {
        close(pipefd[1]);
    }
    setvbuf(stdout, NULL, _IOLBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);

    pthread_t thread;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    int res = pthread_create(&thread, &attr, reader, (void *) (intptr_t) pipefd[0]);
    pthread_attr_destroy(&attr);
    return res == 0 ? JNI_TRUE : JNI_FALSE;
}
