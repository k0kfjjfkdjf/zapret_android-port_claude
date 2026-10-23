package io.github.zapretdroid.classic

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import io.github.zapretdroid.classic.activities.MainActivity
import io.github.zapretdroid.classic.core.ByeDpiProxyCmdPreferences
import io.github.zapretdroid.classic.core.ByeDpiProxyPreferences
import io.github.zapretdroid.classic.core.ByeDpiProxyUIPreferences
import io.github.zapretdroid.classic.utility.getPreferences
import io.github.zapretdroid.classic.utility.getStringNotNull
import io.github.zapretdroid.core.AppFilterMode
import io.github.zapretdroid.classic.utility.DomainListUtils
import io.github.zapretdroid.classic.utility.getCmdArgs
import io.github.zapretdroid.classic.utility.getCmdEnable
import io.github.zapretdroid.core.AppLog
import io.github.zapretdroid.core.ClassicCommand
import io.github.zapretdroid.core.HostLists
import io.github.zapretdroid.core.Settings
import io.github.zapretdroid.core.Strategies
import io.github.zapretdroid.core.isIpLiteral

/**
 * Keeps the ByeByeDPI interface and ZapretDroid on one set of settings. The classic screens keep
 * their own preferences (as in ByeByeDPI); before the engine starts from them, their command line
 * (or UI editor), DNS, IPv6 and app list are written to ZapretDroid's settings, so the same VPN
 * service and engine run with them, and every ZapretDroid option ({fake:...}, {list:...}, tpws
 * markers, --mss, UDP protocol fakes...) works in their command line. Opening the classic
 * interface copies ZapretDroid's current strategy into their command line the other way.
 */
object ClassicBridge {
    private const val PREFS = "ui"
    private const val KEY_CLASSIC = "classic"
    /** The command line last handed between the two interfaces: unchanged means nothing to take over. */
    private const val KEY_SYNCED = "classic_synced_cmd"

    /** Whether the launcher should open the ByeByeDPI interface. */
    fun isClassic(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CLASSIC, false)

    fun setClassic(context: Context, classic: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_CLASSIC, classic) }
        AppLog.i("interface: ${if (classic) "ByeByeDPI" else "ByeZapret"}")
    }

    /** The engine arguments the classic settings produce, without the program name and listen address. */
    fun classicArgs(context: Context): List<String> {
        val args = when (val p = ByeDpiProxyPreferences.fromSharedPreferences(context.getPreferences(), context)) {
            is ByeDpiProxyCmdPreferences -> p.args.toList()
            is ByeDpiProxyUIPreferences -> p.uiargs.toList()
        }
        return stripListen(args.drop(1))
    }

    /** Removes --ip/--port (-i/-p) from engine arguments: the VPN service sets its own. */
    fun stripListen(args: List<String>): List<String> = ClassicCommand.stripListen(args)

    fun quote(arg: String): String = ClassicCommand.quote(arg)

    /**
     * Classic settings -> ZapretDroid settings, before a start through the classic code (its
     * interface, its tile and shortcuts, automation, boot).
     *
     * The command line is taken over only when it changed since the two sides last exchanged it
     * (edited in the classic interface, or sent by another app: [force]). An unchanged one is the
     * strategy ByeZapret already has, preset or script, which stays as it is; it used to be
     * flattened into one line and stored as a custom strategy at every start, which cut it at its
     * first comment. When it changed, its lines, comments and directives are kept.
     * DNS, IPv6 and the app list follow only from the classic interface itself: a start from the
     * tile or at boot in ByeZapret's interface must not reset them to ByeByeDPI's defaults.
     */
    fun toZapret(context: Context, force: Boolean = false) {
        val prefs = context.getPreferences()
        val ui = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val settings = Settings(context)
        val classic = isClassic(context)
        if (prefs.getCmdEnable()) {
            val raw = prefs.getCmdArgs()
            val synced = ui.getString(KEY_SYNCED, null)
            if (raw != synced && (classic || synced != null || force)) {
                val args = ClassicCommand.toZapret(
                    ClassicCommand.expandLists(raw, { classicDomains(context, it) }, { HostLists.file(context, it).isFile }),
                )
                if (args.isNotBlank()) setCustom(settings, args)
                ui.edit { putString(KEY_SYNCED, raw) }
            }
        } else if (classic) {
            val args = classicArgs(context).joinToString(" ") { quote(it) }
            if (args.isNotBlank()) setCustom(settings, args)
        }
        if (!classic) return
        prefs.getStringNotNull("dns_ip", "").trim().takeIf { isIpLiteral(it) }?.let { if (settings.dns != it) settings.dns = it }
        val ipv6 = prefs.getBoolean("ipv6_enable", false)
        if (settings.ipv6 != ipv6) settings.ipv6 = ipv6
        val mode = when (prefs.getStringNotNull("applist_type", "disable")) {
            "whitelist" -> AppFilterMode.ONLY_SELECTED
            "blacklist" -> AppFilterMode.EXCEPT_SELECTED
            else -> AppFilterMode.ALL
        }
        if (settings.appFilterMode != mode) settings.appFilterMode = mode
        val apps = prefs.getStringSet("selected_apps", emptySet()).orEmpty()
        if (settings.selectedApps != apps) settings.selectedApps = apps
    }

    private fun setCustom(settings: Settings, args: String) {
        if (settings.strategyId != Strategies.CUSTOM || settings.customArgs != args) {
            AppLog.i("ByeByeDPI interface: strategy '$args'")
        }
        settings.customArgs = args
        settings.strategyId = Strategies.CUSTOM
    }

    private fun classicDomains(context: Context, name: String): List<String>? =
        DomainListUtils.getLists(context).find { it.name.equals(name, ignoreCase = true) }?.domains

    /** Switches to the ByeByeDPI interface with ZapretDroid's current settings. */
    fun open(context: Context) {
        setClassic(context, true)
        fromZapret(context)
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
    }

    /** Switches back to the ZapretDroid interface, taking the classic settings along. */
    fun close(activity: Activity) {
        toZapret(activity)
        setClassic(activity, false)
        activity.startActivity(
            Intent(activity, io.github.zapretdroid.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        activity.finish()
    }

    /** ZapretDroid settings -> classic settings, when the classic interface opens. */
    fun fromZapret(context: Context) {
        val settings = Settings(context)
        val prefs = context.getPreferences()
        val cmd = prefs.getBoolean("byedpi_enable_cmd_settings", true)
        prefs.edit {
            // The UI editor cannot show an arbitrary command line: only the command line follows.
            if (cmd) {
                putString("byedpi_cmd_args", settings.strategyArgs)
            }
            if (isIpLiteral(settings.dns)) putString("dns_ip", settings.dns)
            putBoolean("ipv6_enable", settings.ipv6)
            putString(
                "applist_type",
                when (settings.appFilterMode) {
                    AppFilterMode.ONLY_SELECTED -> "whitelist"
                    AppFilterMode.EXCEPT_SELECTED -> "blacklist"
                    AppFilterMode.ALL -> "disable"
                },
            )
            putStringSet("selected_apps", settings.selectedApps)
        }
        if (cmd) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY_SYNCED, settings.strategyArgs) }
        }
    }
}
