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
