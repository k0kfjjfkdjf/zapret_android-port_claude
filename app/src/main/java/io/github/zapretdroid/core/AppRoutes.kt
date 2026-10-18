package io.github.zapretdroid.core

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress

/**
 * Routing by app: chosen apps go wholly through the proxy (Tor or the user's own) or wholly
 * direct (no desync, no proxy), whatever the host lists say. hev-socks5-tunnel gives the engine
 * each connection's source address (SOCKS5 user name "@src"), the engine asks [ownerUid] once
 * per connection and picks the group of the app (--apps).
 */
object AppRoutes {
    enum class Route(val id: String) { PROXY("proxy"), DIRECT("direct") }

    /** Engine groups in front of all others: direct apps first, then apps through the proxy. */
    fun groups(context: Context, routes: Map<String, Route>, proxyAddress: String, udp: Boolean): List<String> {
        fun uids(route: Route) = routes.filterValues { it == route }.keys.mapNotNull { pkg ->
            runCatching { context.packageManager.getApplicationInfo(pkg, 0).uid }.getOrNull()
        }.distinct()
        val direct = uids(Route.DIRECT)
        val proxied = if (TorProxy.isValid(proxyAddress)) uids(Route.PROXY) else emptyList()
        return buildList {
            if (direct.isNotEmpty()) addAll(listOf("--apps", direct.joinToString(","), "--auto=none"))
            if (proxied.isNotEmpty()) {
                addAll(listOf("--apps", proxied.joinToString(","), "-C", "socks5://$proxyAddress"))
                if (udp) add("--udp-proxy")
                add("--auto=none")
            }
        }
    }

    /** The owner of a connection from [local] (the app's side on the tun) to [remote]; -1 if unknown. */
    fun ownerUid(context: Context, proto: Int, local: InetSocketAddress, remote: InetSocketAddress): Int =
        if (Build.VERSION.SDK_INT >= 29) {
            context.getSystemService(ConnectivityManager::class.java)?.getConnectionOwnerUid(proto, local, remote) ?: -1
        } else {
            procOwner(proto, local)
        }

    /** Android 7–9: /proc/net is readable, the socket bound to [local] names its uid. */
    private fun procOwner(proto: Int, local: InetSocketAddress): Int {
        val port = "%04X".format(local.port)
        val v4 = (local.address as? Inet4Address)?.address?.reversed()?.joinToString("") { "%02X".format(it) }
        val files = if (proto == 6) listOf("/proc/net/tcp6", "/proc/net/tcp") else listOf("/proc/net/udp6", "/proc/net/udp")
        for (path in files) {
            val lines = runCatching { File(path).readLines() }.getOrNull() ?: continue
            for (line in lines.drop(1)) {
                val cols = line.trim().split(Regex("\\s+"))
                if (cols.size < 8) continue
                val (ip, p) = cols[1].split(':').takeIf { it.size == 2 } ?: continue
                if (p != port || (v4 != null && !ip.endsWith(v4))) continue
                return cols[7].toIntOrNull() ?: continue
            }
        }
        return -1
    }
}
