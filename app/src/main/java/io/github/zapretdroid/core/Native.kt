package io.github.zapretdroid.core

import java.net.InetAddress
import java.net.InetSocketAddress

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

        /**
         * Moves [host] (lowercase, subdomains included) to the --slot group named [slot] of the
         * running engine, or out of every slot if [slot] is null: new connections to it use that
         * group at once, open ones keep theirs. 0 on success, -1 if the engine or the slot is missing.
         */
        @JvmStatic
        external fun assignSlot(host: String, slot: String?): Int

        /** Set by the VPN service: the uid of the app owning a connection (proto, source, destination), -1 if unknown. */
        @Volatile
        var ownerLookup: ((Int, InetSocketAddress, InetSocketAddress) -> Int)? = null

        /** Called by the engine (--apps groups) for each new connection or UDP association. */
        @JvmStatic
        fun ownerUid(proto: Int, srcIp: String, srcPort: Int, dstIp: String, dstPort: Int): Int = runCatching {
            // Literal addresses: no DNS lookup happens here.
            ownerLookup?.invoke(proto, InetSocketAddress(InetAddress.getByName(srcIp), srcPort), InetSocketAddress(InetAddress.getByName(dstIp), dstPort))
        }.getOrNull() ?: -1
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
