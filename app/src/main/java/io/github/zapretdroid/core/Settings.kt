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
        set(value) = prefs.edit(commit = true) { putString(KEY_STRATEGY, value) }

    var customArgs: String
        get() = prefs.getString(KEY_CUSTOM_ARGS, Strategies.presets.first().args)!!
        set(value) = prefs.edit(commit = true) { putString(KEY_CUSTOM_ARGS, value) }

    var dns: String
        get() = prefs.getString(KEY_DNS, DEFAULT_DNS)!!
        set(value) = prefs.edit(commit = true) { putString(KEY_DNS, value) }

    var ipv6: Boolean
        get() = prefs.getBoolean(KEY_IPV6, false)
        set(value) = prefs.edit(commit = true) { putBoolean(KEY_IPV6, value) }

    var appFilterMode: AppFilterMode
        get() = prefs.getString(KEY_APP_FILTER, null)
            ?.let { runCatching { AppFilterMode.valueOf(it) }.getOrNull() } ?: AppFilterMode.ALL
        set(value) = prefs.edit(commit = true) { putString(KEY_APP_FILTER, value.name) }

    var selectedApps: Set<String>
        get() = prefs.getStringSet(KEY_APPS, emptySet())!!.toSet()
        set(value) = prefs.edit(commit = true) { putStringSet(KEY_APPS, value) }

    var logLevel: LogLevel
        get() = prefs.getString(KEY_LOG_LEVEL, null)
            ?.let { runCatching { LogLevel.valueOf(it) }.getOrNull() } ?: LogLevel.NORMAL
        set(value) = prefs.edit(commit = true) { putString(KEY_LOG_LEVEL, value.name) }

    /** The ByeDPI arguments of the active strategy, without listen address options. */
    val strategyArgs: String
        get() = Strategies.byId(strategyId)?.args ?: customArgs

    companion object {
        const val DEFAULT_DNS = "8.8.8.8"
        private const val KEY_STRATEGY = "strategy"
        private const val KEY_CUSTOM_ARGS = "custom_args"
        private const val KEY_DNS = "dns"
        private const val KEY_IPV6 = "ipv6"
        private const val KEY_APP_FILTER = "app_filter"
        private const val KEY_APPS = "apps"
        private const val KEY_LOG_LEVEL = "log_level"
    }
}
