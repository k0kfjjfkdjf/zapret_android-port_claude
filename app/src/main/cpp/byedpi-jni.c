/*
 * JNI bridge for the ByeDPI engine.
 *
 * ByeDPI is a regular command line program, so it is driven through its main():
 * startProxy() blocks the calling thread until the proxy exits, stopProxy() shuts
 * down the listening socket, which makes the event loop return.
 *
 * ByeDPI keeps its state in globals, so the engine can be started only once per
 * process. The VPN service runs in its own process and exits after every session.
 */
#include <errno.h>
#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>

#include <android/log.h>

#define TAG "zapretdroid"

int main(int argc, char **argv);
extern int server_fd;

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static int used;
static int running;

int __wrap_daemon(int nochdir, int noclose)
{
    errno = EPERM;
    return -1;
}

JNIEXPORT jint JNICALL
Java_io_github_zapretdroid_core_ByeDpi_startProxy(JNIEnv *env, jobject thiz, jobjectArray args)
{
    pthread_mutex_lock(&lock);
    if (used) {
        pthread_mutex_unlock(&lock);
        __android_log_print(ANDROID_LOG_ERROR, TAG, "engine already used in this process");
        return -1;
    }
    used = 1;
    server_fd = -1;
    running = 1;
    pthread_mutex_unlock(&lock);

    int argc = (*env)->GetArrayLength(env, args);
    char **argv = calloc(argc + 1, sizeof(char *));
    int result = -1;
    if (!argv) {
        goto out;
    }
    for (int i = 0; i < argc; i++) {
        jstring arg = (jstring) (*env)->GetObjectArrayElement(env, args, i);
        const char *str = arg ? (*env)->GetStringUTFChars(env, arg, NULL) : NULL;
        if (!str) {
            goto out;
        }
        argv[i] = strdup(str);
        (*env)->ReleaseStringUTFChars(env, arg, str);
        (*env)->DeleteLocalRef(env, arg);
        if (!argv[i]) {
            goto out;
        }
    }

    __android_log_print(ANDROID_LOG_INFO, TAG, "starting byedpi with %d args", argc);
    result = main(argc, argv);
    __android_log_print(ANDROID_LOG_INFO, TAG, "byedpi exited with %d", result);

out:
    if (argv) {
        for (int i = 0; i < argc; i++) {
            free(argv[i]);
        }
        free(argv);
    }
    pthread_mutex_lock(&lock);
    running = 0;
    pthread_mutex_unlock(&lock);
    return result;
}

JNIEXPORT void JNICALL
Java_io_github_zapretdroid_core_ByeDpi_stopProxy(JNIEnv *env, jobject thiz)
{
    pthread_mutex_lock(&lock);
    if (running && server_fd >= 0) {
        shutdown(server_fd, SHUT_RDWR);
    }
    pthread_mutex_unlock(&lock);
}
