package io.github.zapretdroid.core

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

enum class AppFilterMode { ALL, ONLY_SELECTED, EXCEPT_SELECTED }

/** How much the engines write to the session log. */
enum class LogLevel(val byedpiDebug: Int, val hevLevel: String) {
    NORMAL(0, "warn"),
    DEBUG(1, "info"),
    VERBOSE(2, "debug"),
}

/**
 * User settings. The VPN service runs in a separate process that is started fresh
 * for every session, so values are written with commit() and read on start.
 */
class Settings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var strategyId: String
        get() = prefs.getString(KEY_STRATEGY, Strategies.presets.first().id)!!
        set(value) {
            logChange("strategyId", strategyId, value)
            prefs.edit(commit = true) { putString(KEY_STRATEGY, value) }
        }

    var customArgs: String
        get() = prefs.getString(KEY_CUSTOM_ARGS, Strategies.presets.first().args)!!
        // Typed character by character: the settings screen logs the final value when it closes.
        set(value) {
            prefs.edit(commit = true) { putString(KEY_CUSTOM_ARGS, value) }
        }

    var dns: String
        get() = prefs.getString(KEY_DNS, DEFAULT_DNS)!!
        // Typed character by character: the settings screen logs the final value when it closes.
        set(value) {
            prefs.edit(commit = true) { putString(KEY_DNS, value) }
        }

    var ipv6: Boolean
        get() = prefs.getBoolean(KEY_IPV6, false)
        set(value) {
            logChange("ipv6", ipv6, value)
            prefs.edit(commit = true) { putBoolean(KEY_IPV6, value) }
        }

    var appFilterMode: AppFilterMode
        get() = prefs.getString(KEY_APP_FILTER, null)
            ?.let { runCatching { AppFilterMode.valueOf(it) }.getOrNull() } ?: AppFilterMode.ALL
        set(value) {
            logChange("appFilterMode", appFilterMode, value)
            prefs.edit(commit = true) { putString(KEY_APP_FILTER, value.name) }
        }

    var selectedApps: Set<String>
        get() = prefs.getStringSet(KEY_APPS, emptySet())!!.toSet()
        set(value) {
            logChange("selectedApps", selectedApps, value)
            prefs.edit(commit = true) { putStringSet(KEY_APPS, value) }
        }

    var logLevel: LogLevel
        get() = prefs.getString(KEY_LOG_LEVEL, null)
            ?.let { runCatching { LogLevel.valueOf(it) }.getOrNull() } ?: LogLevel.NORMAL
        set(value) {
            logChange("logLevel", logLevel, value)
            prefs.edit(commit = true) { putString(KEY_LOG_LEVEL, value.name) }
        }

    var hostScope: HostScope
        get() = prefs.getString(KEY_HOST_SCOPE, null)
            ?.let { runCatching { HostScope.valueOf(it) }.getOrNull() } ?: HostScope.ALL
        set(value) {
            logChange("hostScope", hostScope, value)
            prefs.edit(commit = true) { putString(KEY_HOST_SCOPE, value.name) }
        }

    var hostList: String
        get() = prefs.getString(KEY_HOST_LIST, "")!!
        set(value) {
            logChange("hostList", hostList, value)
            prefs.edit(commit = true) { putString(KEY_HOST_LIST, value) }
        }

    var autohost: Boolean
        get() = prefs.getBoolean(KEY_AUTOHOST, false)
        set(value) {
            logChange("autohost", autohost, value)
            prefs.edit(commit = true) { putBoolean(KEY_AUTOHOST, value) }
        }

    /** Package whose domains are being collected: while set, the VPN routes only that app. */
    var collectApp: String
        get() = prefs.getString(KEY_COLLECT_APP, "")!!
        set(value) {
            logChange("collectApp", collectApp, value)
            prefs.edit(commit = true) { putString(KEY_COLLECT_APP, value) }
        }

    /** Plain DNS through the engine, or the app's encrypted resolver (see SecureDns). */
    var dnsMode: DnsMode
        get() = prefs.getString(KEY_DNS_MODE, null)?.let { runCatching { DnsMode.valueOf(it) }.getOrNull() } ?: DnsMode.PLAIN
        set(value) {
            logChange("dnsMode", dnsMode, value)
            prefs.edit(commit = true) { putString(KEY_DNS_MODE, value.name) }
        }

    /** Encrypted DNS server: a preset id (cloudflare, google…) or https://… / tls://… of one's own. */
    var dnsProvider: String
        get() = prefs.getString(KEY_DNS_PROVIDER, SecureDns.providers.first().id)!!
        // Typed character by character: the settings screen logs the final value when it closes.
        set(value) {
            prefs.edit(commit = true) { putString(KEY_DNS_PROVIDER, value) }
        }

    /** DNSCrypt mode: the chosen servers (name to sdns:// stamp), in the order chosen. */
    var dnsServers: List<Pair<String, String>>
        get() = prefs.getString(KEY_DNS_SERVERS, "")!!.lines().mapNotNull { line ->
            line.trim().split(' ', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }
        set(value) {
            // Only what changed: the whole list grows long when many servers are ticked.
            val old = dnsServers.map { it.first }
            val new = value.map { it.first }
            if (old != new) AppLog.i("setting dnsServers: +${(new - old.toSet()).ifEmpty { listOf("-") }} -${(old - new.toSet()).ifEmpty { listOf("-") }} (${new.size} chosen)")
            prefs.edit(commit = true) { putString(KEY_DNS_SERVERS, value.joinToString("\n") { "${it.first} ${it.second}" }) }
        }

    /** DNS filter categories (DnsFilter.Category ids) blocked by the app's resolver. */
    var dnsBlock: Set<String>
        get() = prefs.getStringSet(KEY_DNS_BLOCK, emptySet())!!.toSet()
        set(value) {
            logChange("dnsBlock", dnsBlock.sorted(), value.sorted())
            prefs.edit(commit = true) { putStringSet(KEY_DNS_BLOCK, value) }
        }

    /** Listed domains (host list [proxyList]) through a SOCKS5 proxy, e.g. Tor from Orbot (see TorProxy). */
    var proxyEnabled: Boolean
        get() = prefs.getBoolean(KEY_PROXY_ENABLED, false)
        set(value) {
            logChange("proxyEnabled", proxyEnabled, value)
            prefs.edit(commit = true) { putBoolean(KEY_PROXY_ENABLED, value) }
        }

    var proxyAddress: String
        get() = prefs.getString(KEY_PROXY_ADDRESS, TorProxy.DEFAULT_PROXY)!!
        // Typed character by character: the settings screen logs the final value when it closes.
        set(value) {
            prefs.edit(commit = true) { putString(KEY_PROXY_ADDRESS, value) }
        }

    /** The proxy relays UDP (SOCKS5 UDP ASSOCIATE): the listed sites' QUIC and calls go through it too. Tor cannot. */
    var proxyUdp: Boolean
        get() = prefs.getBoolean(KEY_PROXY_UDP, false)
        set(value) {
            logChange("proxyUdp", proxyUdp, value)
            prefs.edit(commit = true) { putBoolean(KEY_PROXY_UDP, value) }
        }

    /** Telegram (its sites and its address ranges) through the proxy too. */
    var proxyTelegram: Boolean
        get() = prefs.getBoolean(KEY_PROXY_TELEGRAM, false)
        set(value) {
            logChange("proxyTelegram", proxyTelegram, value)
            prefs.edit(commit = true) { putBoolean(KEY_PROXY_TELEGRAM, value) }
        }

    /** Tor from the module (if installed and trusted) rather than Orbot / InviZible. */
    var useTorModule: Boolean
        get() = prefs.getBoolean(KEY_USE_TOR_MODULE, true)
        set(value) {
            logChange("useTorModule", useTorModule, value)
            prefs.edit(commit = true) { putBoolean(KEY_USE_TOR_MODULE, value) }
        }

    /** The module's bridges: a TorModule.Bridges id. */
    var torBridges: String
        get() = prefs.getString(KEY_TOR_BRIDGES, "none")!!
        set(value) {
            logChange("torBridges", torBridges, value)
            prefs.edit(commit = true) { putString(KEY_TOR_BRIDGES, value) }
        }

    /** The user's own bridge lines ("obfs4 1.2.3.4:443 FINGERPRINT cert=… iat-mode=0", one per line). */
    var torBridgesCustom: String
        get() = prefs.getString(KEY_TOR_BRIDGES_CUSTOM, "")!!
        // Typed character by character: not logged (bridges are best kept out of shared logs).
        set(value) {
            prefs.edit(commit = true) { putString(KEY_TOR_BRIDGES_CUSTOM, value) }
        }

    var proxyList: String
        get() = prefs.getString(KEY_PROXY_LIST, TorProxy.LIST)!!
        set(value) {
            logChange("proxyList", proxyList, value)
            prefs.edit(commit = true) { putString(KEY_PROXY_LIST, value) }
        }

    /** Every settings change goes to the journal, whichever screen made it. */
    private fun logChange(name: String, old: Any, new: Any) {
        if (old == new) return
        val text = when {
            old is Set<*> && new is Set<*> ->
                "added ${(new - old).sortedBy { it.toString() }}, removed ${(old - new).sortedBy { it.toString() }}"
            else -> "'$old' -> '$new'"
        }
        AppLog.i("setting $name: $text")
    }

    /** The ByeDPI arguments of the active strategy, without listen address options. */
    val strategyArgs: String
        get() = Strategies.byId(strategyId)?.args ?: customArgs

    companion object {
        const val DEFAULT_DNS = "8.8.8.8"

        /** DNS setting value for the network's own DNS servers (usually the router). */
        const val SYSTEM_DNS = "system"
        private const val KEY_STRATEGY = "strategy"
        private const val KEY_CUSTOM_ARGS = "custom_args"
        private const val KEY_DNS = "dns"
        private const val KEY_IPV6 = "ipv6"
        private const val KEY_APP_FILTER = "app_filter"
        private const val KEY_APPS = "apps"
        private const val KEY_LOG_LEVEL = "log_level"
        private const val KEY_HOST_SCOPE = "host_scope"
        private const val KEY_HOST_LIST = "host_list"
        private const val KEY_AUTOHOST = "autohost"
        private const val KEY_COLLECT_APP = "collect_app"
        private const val KEY_DNS_MODE = "dns_mode"
        private const val KEY_DNS_PROVIDER = "dns_provider"
        private const val KEY_PROXY_ENABLED = "proxy_enabled"
        private const val KEY_PROXY_ADDRESS = "proxy_address"
        private const val KEY_PROXY_LIST = "proxy_list"
        private const val KEY_PROXY_UDP = "proxy_udp"
        private const val KEY_PROXY_TELEGRAM = "proxy_telegram"
        private const val KEY_USE_TOR_MODULE = "use_tor_module"
        private const val KEY_TOR_BRIDGES = "tor_bridges"
        private const val KEY_TOR_BRIDGES_CUSTOM = "tor_bridges_custom"
        private const val KEY_DNS_BLOCK = "dns_block"
        private const val KEY_DNS_SERVERS = "dns_servers"
    }
}
