package io.github.zapretdroid.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** The strategy script (see [Script]) compiled for this phone: network type, blocks switched off, saved strategies. */
object StrategyScript {
    /** "wifi", "mobile" or "other", of the app's default network (the app is outside the VPN: the real one). */
    fun networkType(context: Context): String {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return "other"
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return "other"
        return typeOf(caps)
    }

    fun typeOf(caps: NetworkCapabilities): String = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "wifi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
        else -> "other"
    }

    fun env(context: Context, settings: Settings, network: String = networkType(context)) = Script.Env(
        network = network,
        disabledBlocks = settings.disabledBlocks,
        include = { name -> SavedStrategies.all(context).firstOrNull { it.name == name }?.args },
        autofix = autofixCandidates(settings),
    )

    /** "-> autofix": the presets, then the main proxy and the other proxies. */
    fun autofixCandidates(settings: Settings): List<String> =
        (Strategies.presets.map { it.args } + buildList {
            if (TorProxy.isValid(settings.proxyAddress)) add("-C {proxy}" + if (settings.proxyUdp) " --udp-proxy" else "")
            settings.extraProxies.filter { TorProxy.isValid(it.address) }.forEach { add("-C {proxy:${it.name}}" + if (it.udp) " --udp-proxy" else "") }
        }).distinct()

    fun compile(context: Context, settings: Settings, text: String = settings.strategyArgs, network: String = networkType(context)): Script.Result =
        Script.compile(text, env(context, settings, network))

    /** The command line the engine gets; throws with the first script error. */
    fun text(context: Context, settings: Settings, text: String = settings.strategyArgs, network: String = networkType(context)): String {
        val r = compile(context, settings, text, network)
        r.errors.firstOrNull()?.let { e ->
            throw IllegalStateException(if (e.line > 0) "strategy line ${e.line}: ${e.message}" else "strategy: ${e.message}")
        }
        return r.text
    }
}
