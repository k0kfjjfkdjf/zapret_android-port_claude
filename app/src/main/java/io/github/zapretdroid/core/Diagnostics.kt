package io.github.zapretdroid.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import io.github.zapretdroid.BuildConfig

/** Environment summary written at the top of every session log. */
object Diagnostics {
    fun report(context: Context, settings: Settings): List<String> = buildList {
        add("ZapretDroid ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE}), ${context.packageName}")
        add("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}, ABI ${Build.SUPPORTED_ABIS.joinToString()}")
        add("strategy: ${settings.strategyId}")
        add("args: ${settings.strategyArgs}")
        add("dns: ${settings.dns}, ipv6: ${settings.ipv6}, log level: ${settings.logLevel}")
        add("hosts: ${settings.hostScope}" + (if (settings.hostScope != HostScope.ALL) " list '${settings.hostList}'" else "") + ", autohost: ${settings.autohost}")
        add("apps: ${settings.appFilterMode}" + if (settings.appFilterMode != AppFilterMode.ALL) " ${settings.selectedApps.sorted()}" else "")
        addAll(network(context))
    }

    private fun network(context: Context): List<String> = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return listOf("network: unknown")
        val network = manager.activeNetwork ?: return listOf("network: none")
        val caps = manager.getNetworkCapabilities(network)
        val transports = buildList {
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("wifi")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("cellular")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("ethernet")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("vpn")
        }
        val link = manager.getLinkProperties(network)
        buildList {
            add("network: ${transports.ifEmpty { listOf("other") }.joinToString("+")}, validated: ${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}")
            add("system dns: ${link?.dnsServers?.joinToString { it.hostAddress ?: "?" }}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && link != null) {
                add("private dns: active=${link.isPrivateDnsActive}, server=${link.privateDnsServerName ?: "auto"}")
            }
        }
    }.getOrElse { listOf("network: error ${it.message}") }
}
