package io.github.zapretdroid.core

import android.content.Context
import android.content.Intent
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Listed domains through a SOCKS5 proxy, normally Tor from Orbot or InviZible Pro running in
 * proxy mode (Android allows one VPN: ours). For services that refuse Russian addresses
 * themselves (ChatGPT, Gemini…), where DPI bypass cannot help: the site sees the proxy's exit.
 * The engine sends the host name to the proxy (resolved at the exit) and drops the group's UDP
 * (Tor carries TCP only), so apps fall back from QUIC to TCP; with the user's own proxy that
 * relays UDP (sing-box, Xray, NekoBox… in SOCKS mode) the UDP goes through it too ([udp]).
 */
/** A named SOCKS5 proxy besides the main one (Settings.proxyAddress): Tor, the user's sing-box… */
data class ProxyEntry(val name: String, val address: String, val udp: Boolean)

object TorProxy {
    const val DEFAULT_PROXY = "127.0.0.1:9050"
    const val LIST = "proxy"

    /** Apps that provide Tor as a local SOCKS5 proxy. */
    enum class Provider(val title: String, val packages: List<String>) {
        ORBOT("Orbot", listOf("org.torproject.android")),
        INVIZIBLE("InviZible Pro", listOf("pan.alexander.tordnscrypt.stable", "pan.alexander.tordnscrypt", "pan.alexander.tordnscrypt.gp")),
    }

    /**
     * Apps that run a local SOCKS5 proxy for us: Termux (zapret-termux: tpws on 127.0.0.1:1080 with
     * UDP), sing-box, NekoBox, v2rayNG, Hiddify. Their own connections must go around our VPN, or
     * they would loop back into the engine.
     */
    val LOCAL_PROXY_APPS = listOf(
        "com.termux", "io.nekohasekai.sfa", "moe.nb4a", "io.nekohasekai.sagernet", "com.v2ray.ang", "app.hiddify.com",
    )

    /** zapret-termux (branch termux-zapret-udp-script of this repository): its tpws SOCKS5. */
    val ZAPRET_TERMUX = ProxyEntry("zapret-termux", "127.0.0.1:1080", true)

    /** Installed providers with the package found. */
    fun installed(context: Context): List<Pair<Provider, String>> = Provider.entries.mapNotNull { p ->
        p.packages.firstOrNull { pkg -> context.packageManager.getLaunchIntentForPackage(pkg) != null }?.let { p to it }
    }

    /** Whether something accepts connections at [address] ("host:port"). */
    fun reachable(address: String, timeoutMs: Int = 500): Boolean = runCatching {
        Socket().use { it.connect(socketAddress(address), timeoutMs) }
        true
    }.getOrDefault(false)

    fun socketAddress(address: String): InetSocketAddress {
        val host = address.substringBeforeLast(':').trim().removePrefix("[").removeSuffix("]")
        val port = address.substringAfterLast(':').trim().toInt()
        return InetSocketAddress(host, port)
    }

    /** host:port with an IP address: the engine takes no host names for -C. */
    fun isValid(address: String) = runCatching {
        val host = address.substringBeforeLast(':').trim().removePrefix("[").removeSuffix("]")
        val port = address.substringAfterLast(':').trim().toInt()
        val v4 = host.split('.').let { p -> p.size == 4 && p.all { it.toIntOrNull() in 0..255 && it.isNotEmpty() } }
        val v6 = ':' in host && host.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '.' }
        port in 1..65535 && (v4 || v6)
    }.getOrDefault(false)

    /** Asks Orbot to start Tor (its public intent); InviZible has none, it is started by hand. */
    fun requestStart(context: Context) {
        if (installed(context).none { it.first == Provider.ORBOT }) return
        runCatching {
            context.sendBroadcast(
                Intent("org.torproject.android.intent.action.START")
                    .setPackage("org.torproject.android")
                    .putExtra("org.torproject.android.intent.extra.PACKAGE_NAME", context.packageName),
            )
        }
    }

    /** The default list (assets/lists/proxy.txt), created once; the user can edit it afterwards. */
    fun ensureList(context: Context) {
        if (HostLists.file(context, LIST).exists()) return
        val text = context.assets.open("lists/$LIST.txt").bufferedReader().use { it.readText() }
        HostLists.save(context, LIST, text)
    }

    /** The engine group in front of the strategy: listed hosts through the proxy, UDP too if [udp]. */
    fun group(listPath: String, address: String, udp: Boolean = false): List<String> =
        listOf("--hosts", listPath, "-C", "socks5://$address") + (if (udp) listOf("--udp-proxy") else emptyList()) + "--auto=none"

    const val TELEGRAM_LIST = "telegram"

    /**
     * Telegram through the proxy: its sites by name (list "telegram", editable) and the app by
     * its address ranges (core.telegram.org/resources/cidr.txt), since the app connects to IPs
     * and sends no name to match. Calls are UDP: they need a proxy that relays UDP ([udp]).
     */
    fun telegramGroups(context: Context, address: String, udp: Boolean): List<String> {
        if (!HostLists.file(context, TELEGRAM_LIST).exists()) {
            HostLists.save(context, TELEGRAM_LIST, context.assets.open("lists/$TELEGRAM_LIST.txt").bufferedReader().use { it.readText() })
        }
        val ips = java.io.File(context.filesDir, "telegram-ip.txt")
        ips.writeBytes(context.assets.open("telegram-ip.txt").use { it.readBytes() })
        val upstream = listOf("-C", "socks5://$address") + (if (udp) listOf("--udp-proxy") else emptyList()) + "--auto=none"
        return listOf("--hosts", HostLists.file(context, TELEGRAM_LIST).absolutePath) + upstream + listOf("--ipset", ips.absolutePath) + upstream
    }

    /** A Telegram server (DC 2) for the self-check. */
    const val TELEGRAM_CHECK_IP = "149.154.167.51"
}
