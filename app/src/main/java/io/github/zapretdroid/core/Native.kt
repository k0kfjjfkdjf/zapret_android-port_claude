package io.github.zapretdroid.core

/** ByeDPI engine (local SOCKS5 proxy that applies the desync strategy). */
class ByeDpi {
    /** Runs the proxy with command line [args] and blocks until it exits. */
    external fun startProxy(args: Array<String>): Int

    /** Makes a running [startProxy] call return. */
    external fun stopProxy()

    companion object {
        init {
            System.loadLibrary("byedpi")
        }
    }
}

/** Collects the output of the native engines (ByeDPI's log, hev's log file) into the journal (see native-log.c). */
object NativeLog {
    init {
        System.loadLibrary("byedpi")
    }

    /**
     * Starts capturing into [path], or points a running capture at the current [path] file. After
     * [limitBytes] of engine output since this call, further lines go to logcat only. Returns
     * the path that hev-socks5-tunnel should use as its log file, or null on failure.
     */
    @JvmStatic
    external fun start(path: String, limitBytes: Long): String?
}

/**
 * hev-socks5-tunnel: moves packets between the TUN interface and the SOCKS5 proxy.
 * The natives are registered by the library's JNI_OnLoad for this exact class name.
 */
object TProxyService {
    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    @Suppress("FunctionName")
    external fun TProxyStartService(configPath: String, fd: Int): Boolean

    @Suppress("FunctionName")
    external fun TProxyStopService(): Boolean

    @Suppress("FunctionName")
    external fun TProxyIsRunning(): Boolean

    /** tx packets, tx bytes, rx packets, rx bytes. */
    @Suppress("FunctionName")
    external fun TProxyGetStats(): LongArray?
}
