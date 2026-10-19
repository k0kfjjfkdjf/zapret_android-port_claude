package io.github.zapretdroid.core

import android.content.Context
import io.github.zapretdroid.R

/**
 * The settings written out as one engine command line with references ({app:…}, {proxy:…},
 * {list:…}, {ipset:…}) and "# …" comments that name each block: what the VPN builds from the
 * settings, readable and editable, to keep as a strategy of one's own.
 */
object CommandLine {
    fun fromSettings(context: Context, settings: Settings): String {
        val out = StringBuilder()
        fun block(title: Int, vararg args: Any) {
            out.append("# === ").append(context.getString(title, *args)).append(" ===\n")
        }
        fun line(tokens: List<String>) {
            // One group per line: a line break before every group start reads like a list.
            val groups = StrategyArgs.groups(tokens).filter { it.isNotEmpty() }
            groups.forEach { out.append(it.joinToString(" ") { t -> quote(t) }).append('\n') }
        }
        fun proxyRef(name: String) = if (name.isEmpty()) "{proxy}" else "{proxy:$name}"

        if (settings.excludeEnabled) {
            block(R.string.cmd_block_exclude)
            line(listOf("--hosts", "{list:${TorProxy.EXCLUDE_LIST}}", "--auto=none"))
        }
        val routes = settings.appRoutes
        val direct = routes.filterValues { it == AppRoutes.Route.DIRECT }.keys.sorted()
        if (direct.isNotEmpty()) {
            block(R.string.cmd_block_direct)
            line(listOf("--apps", "{app:${direct.joinToString(",")}}", "--auto=none"))
        }
        val appProxies = settings.appProxies
        routes.filterValues { it == AppRoutes.Route.PROXY }.keys.sorted()
            .groupBy { appProxies[it].orEmpty() }
            .forEach { (name, pkgs) ->
                val proxy = settings.proxyByName(name)
                block(R.string.cmd_block_apps_proxy, name.ifEmpty { context.getString(R.string.proxies_main) })
                line(listOf("--apps", "{app:${pkgs.joinToString(",")}}", "-C", proxyRef(name)) +
                    (if (proxy.udp) listOf("--udp-proxy") else emptyList()) + "--auto=none")
            }
        AutoFix.rules(context, settings).forEach { rule ->
            block(R.string.cmd_block_autofix, rule.label)
            line(StrategyArgs.build(splitArgs(rule.args), HostScope.ONLY_LIST, "{list:${rule.list}}", null) + "--auto=none")
        }
        if (settings.proxyEnabled) {
            block(R.string.cmd_block_sites_proxy, settings.proxyList)
            line(listOf("--hosts", "{list:${settings.proxyList}}", "-C", "{proxy}") +
                (if (settings.proxyUdp) listOf("--udp-proxy") else emptyList()) + "--auto=none")
        }
        if (settings.proxyTelegram) {
            val name = settings.telegramProxy
            val udp = if (settings.proxyByName(name).udp) listOf("--udp-proxy") else emptyList()
            block(R.string.cmd_block_telegram, name.ifEmpty { context.getString(R.string.proxies_main) })
            line(listOf("--hosts", "{list:${TorProxy.TELEGRAM_LIST}}", "-C", proxyRef(name)) + udp + "--auto=none")
            line(listOf("--ipset", "{ipset:${TorProxy.TELEGRAM_LIST}}", "-C", proxyRef(name)) + udp + "--auto=none")
        }
        val strategies = settings.appStrategies
        routes.filterValues { it == AppRoutes.Route.STRATEGY }.keys.sorted().forEach { pkg ->
            val args = strategies[pkg]?.takeIf { it.isNotBlank() } ?: return@forEach
            block(R.string.cmd_block_app_strategy, pkg)
            line(forApp(splitArgs(args), "{app:$pkg}"))
        }
        block(R.string.cmd_block_main)
        val main = settings.strategyArgs.trim()
        when (settings.hostScope) {
            HostScope.ALL -> out.append(main).append('\n')
            else -> {
                out.append("# ").append(context.getString(R.string.cmd_scope_note, settings.hostList)).append('\n')
                line(StrategyArgs.build(splitArgs(main), settings.hostScope, "{list:${settings.hostList}}", null))
            }
        }
        return out.toString().trimEnd()
    }

    /** Like StrategyArgs.forApps, with an {app:…} reference instead of uids. */
    private fun forApp(strategy: List<String>, ref: String): List<String> = buildList {
        StrategyArgs.groups(strategy).filter { it.isNotEmpty() }.forEach { group ->
            if (StrategyArgs.isGroupStart(group[0])) {
                val head = if (group[0] == "-A" || group[0] == "--auto") 2 else 1
                addAll(group.take(head)); add("--apps"); add(ref); addAll(group.drop(head))
            } else {
                add("--apps"); add(ref); addAll(group)
            }
        }
        add("--auto=none")
    }

    private fun quote(t: String) =
        if (t.isEmpty() || t.any { it.isWhitespace() || it == '"' || it == '\'' || it == '#' }) "\"" + t.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" else t
}
