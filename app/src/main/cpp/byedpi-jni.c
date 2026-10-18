/*
 * JNI bridge for the ByeDPI engine.
 *
 * ByeDPI is a regular command line program, so it is driven through its main():
 * startProxy() blocks the calling thread until the proxy exits, stopProxy() shuts
 * down the listening socket, which makes the event loop return.
 *
 * ByeDPI keeps its state in globals: one engine can run at a time per process, and
 * engine_reset() restores the globals before every run.
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
void engine_reset(void);
extern int server_fd;
extern int engine_stop_requested;

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static int running;

/*
 * --apps: the engine asks who owns a connection; answered by ByeDpi.ownerUid() in Kotlin
 * (ConnectivityManager.getConnectionOwnerUid). The engine runs on the startProxy() thread,
 * so its JNIEnv is valid for every call.
 */
extern int (*owner_uid_hook)(int proto, const char *src_ip, int src_port, const char *dst_ip, int dst_port);
static JNIEnv *hook_env;
static jclass hook_class;
static jmethodID hook_method;

static int jni_owner_uid(int proto, const char *src_ip, int src_port, const char *dst_ip, int dst_port)
{
    JNIEnv *env = hook_env;
    if (!env || !hook_class || !hook_method) {
        return -1;
    }
    jstring s = (*env)->NewStringUTF(env, src_ip);
    jstring d = (*env)->NewStringUTF(env, dst_ip);
    int uid = -1;
    if (s && d) {
        uid = (*env)->CallStaticIntMethod(env, hook_class, hook_method, proto, s, src_port, d, dst_port);
    }
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        uid = -1;
    }
    if (s) (*env)->DeleteLocalRef(env, s);
    if (d) (*env)->DeleteLocalRef(env, d);
    return uid;
}

int __wrap_daemon(int nochdir, int noclose)
{
    errno = EPERM;
    return -1;
}

JNIEXPORT jint JNICALL
Java_io_github_zapretdroid_core_ByeDpi_startProxy(JNIEnv *env, jobject thiz, jobjectArray args)
{
    pthread_mutex_lock(&lock);
    if (running) {
        pthread_mutex_unlock(&lock);
        __android_log_print(ANDROID_LOG_ERROR, TAG, "engine is already running in this process");
        return -1;
    }
    running = 1;
    server_fd = -1;
    engine_stop_requested = 0;
    engine_reset();
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

    jclass cls = (*env)->GetObjectClass(env, thiz);
    hook_method = cls ? (*env)->GetStaticMethodID(env, cls, "ownerUid", "(ILjava/lang/String;ILjava/lang/String;I)I") : NULL;
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        hook_method = NULL;
    }
    hook_class = cls && hook_method ? (*env)->NewGlobalRef(env, cls) : NULL;
    hook_env = env;
    owner_uid_hook = hook_class ? jni_owner_uid : NULL;

    __android_log_print(ANDROID_LOG_INFO, TAG, "starting byedpi with %d args", argc);
    result = main(argc, argv);
    __android_log_print(ANDROID_LOG_INFO, TAG, "byedpi exited with %d", result);

    owner_uid_hook = NULL;
    hook_env = NULL;
    if (hook_class) {
        (*env)->DeleteGlobalRef(env, hook_class);
        hook_class = NULL;
    }
    if (cls) (*env)->DeleteLocalRef(env, cls);

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
    if (running) {
        // Before listen() the flag stops the engine as soon as it would start serving.
        __atomic_store_n(&engine_stop_requested, 1, __ATOMIC_SEQ_CST);
        int fd = __atomic_load_n(&server_fd, __ATOMIC_SEQ_CST);
        if (fd >= 0) {
            shutdown(fd, SHUT_RDWR);
        }
    }
    pthread_mutex_unlock(&lock);
}
