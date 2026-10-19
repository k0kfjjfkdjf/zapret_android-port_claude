package io.github.zapretdroid.core

import android.content.Context

/** The command-line references ({app:…}, {proxy[:name]}, {ipset:telegram}) on this device. */
class AppRefs(context: Context) : ArgTemplates.Refs {
    private val context = context.applicationContext

    override fun uid(pkg: String): Int? =
        runCatching { context.packageManager.getApplicationInfo(pkg, 0).uid }.getOrNull()

    override fun proxy(name: String): String? {
        val settings = Settings(context)
        val address = if (name.isEmpty()) settings.proxyAddress else settings.extraProxies.firstOrNull { it.name == name }?.address ?: return null
        return address.trim().takeIf { TorProxy.isValid(it) }?.let { "socks5://$it" }
    }

    override fun ipset(name: String): String? =
        if (name == TorProxy.TELEGRAM_LIST) TorProxy.telegramFiles(context).absolutePath else null
}
