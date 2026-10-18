package io.github.zapretdroid.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.github.zapretdroid.BuildConfig
import java.io.File
import java.io.IOException

/**
 * The Tor module: a separate APK (project module :tor) for those who want Tor without a second
 * app. Downloaded from the project's releases (checked against SHA256SUMS.txt, installed by
 * Android's installer), used only if signed with this app's key. It runs Tor with the chosen
 * bridges as a SOCKS5 proxy at the address of the proxy group, and reports its state back.
 */
object TorModule {
    // The protocol with the module (tor/…/TorModuleService.kt).
    private const val SERVICE = "io.github.zapretdroid.tor.TorModuleService"
    private const val ACTION_START = "io.github.zapretdroid.tor.START"
    private const val ACTION_STOP = "io.github.zapretdroid.tor.STOP"
    private const val ACTION_STATUS = "io.github.zapretdroid.tor.STATUS"
    private const val ACTION_STATUS_BROADCAST = "io.github.zapretdroid.TOR_STATUS"
    private val ASSET = Regex("byezapret-tor-(.+)-(armeabi-v7a|arm64-v8a|x86|x86_64)\\.apk")

    /** Bridges: built-in ones of a transport (Tor Browser's defaults, inside the module) or the user's lines. */
    enum class Bridges(val id: String) { NONE("none"), SNOWFLAKE("snowflake"), OBFS4("obfs4"), WEBTUNNEL("webtunnel"), MEEK("meek_lite"), CUSTOM("custom") }

    class Status(val state: String, val progress: Int, val message: String, val socks: String, val version: String)

    @Volatile
    var status: Status? = null
        private set

    private val listeners = mutableSetOf<(Status) -> Unit>()

    /** The module of this build type: io.github.zapretdroid.tor(.debug). */
    val packageName: String
        get() = BuildConfig.APPLICATION_ID.let { if (it.endsWith(".debug")) it.removeSuffix(".debug") + ".tor.debug" else "$it.tor" }

    fun installedVersion(context: Context): String? = runCatching {
        context.packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull()

    /** Installed and signed with this app's key (anything else under the name is not used). */
    fun isTrusted(context: Context): Boolean = installedVersion(context) != null && runCatching {
        context.packageManager.checkSignatures(context.packageName, packageName) == PackageManager.SIGNATURE_MATCH
    }.getOrDefault(false)

    private fun intent(context: Context, action: String) = Intent(action)
        .setClassName(packageName, SERVICE)
        .putExtra("reply_to", context.packageName)

    /** The bridge lines to send for the settings. */
    fun bridgeLines(settings: Settings): ArrayList<String> = ArrayList(
        when (val mode = Bridges.entries.firstOrNull { it.id == settings.torBridges } ?: Bridges.NONE) {
            Bridges.NONE -> emptyList()
            Bridges.CUSTOM -> settings.torBridgesCustom.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            else -> listOf("builtin:${mode.id}")
        },
    )

    /** Starts (or restarts with new settings) Tor in the module; the SOCKS port is that of the proxy address. */
    fun start(context: Context, settings: Settings): Boolean {
        if (!isTrusted(context)) return false
        val port = runCatching { TorProxy.socketAddress(settings.proxyAddress).port }.getOrDefault(9050)
        val i = intent(context, ACTION_START).putExtra("socks_port", port).putStringArrayListExtra("bridges", bridgeLines(settings))
        return runCatching {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }.onFailure { AppLog.w("Tor module not started: ${it.message}") }.isSuccess
    }

    fun stop(context: Context) {
        if (!isTrusted(context)) return
        runCatching { context.startService(intent(context, ACTION_STOP)) }
    }

    fun requestStatus(context: Context) {
        if (!isTrusted(context)) return
        runCatching { context.startService(intent(context, ACTION_STATUS)) }
    }

    /** Status broadcasts from the module, accepted only from holders of the signature permission. */
    fun register(context: Context, listener: (Status) -> Unit): () -> Unit {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val s = Status(
                    i.getStringExtra("state") ?: "OFF", i.getIntExtra("progress", 0), i.getStringExtra("message").orEmpty(),
                    i.getStringExtra("socks").orEmpty(), i.getStringExtra("version").orEmpty(),
                )
                if (s.state != status?.state || s.message != status?.message) AppLog.i("Tor module: ${s.state} ${s.progress}% ${s.message}")
                status = s
                synchronized(listeners) { listeners.toList() }.forEach { it(s) }
            }
        }
        synchronized(listeners) { listeners += listener }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(ACTION_STATUS_BROADCAST), "${context.packageName}.permission.CONTROL_TOR", null,
            ContextCompat.RECEIVER_EXPORTED,
        )
        return {
            synchronized(listeners) { listeners -= listener }
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    /** The module APK for this device in a release, as (version, name, url). */
    fun assetFor(release: Updater.Release): Triple<String, String, String>? {
        val byAbi = release.assets.mapNotNull { (name, url) -> ASSET.matchEntire(name)?.let { Triple(it.groupValues[2], it.groupValues[1], name to url) } }
        val abi = Build.SUPPORTED_ABIS.firstOrNull { a -> byAbi.any { it.first == a } } ?: return null
        val (_, version, asset) = byAbi.first { it.first == abi }
        return Triple(version, asset.first, asset.second)
    }

    /** The newest release carrying the module, with its asset for this device, or null. Network. */
    fun latest(): Pair<Updater.Release, Triple<String, String, String>>? =
        Updater.releases().firstNotNullOfOrNull { r -> assetFor(r)?.let { r to it } }

    /** Whether [available] differs from the installed module (versions are compared without the -debug suffix). */
    fun isUpdate(context: Context, available: String): Boolean =
        installedVersion(context)?.removeSuffix("-debug") != available

    fun download(context: Context, release: Updater.Release, asset: Triple<String, String, String>, progress: (Int) -> Unit): File =
        Updater.downloadAsset(context, release, asset.second, asset.third, progress).also {
            if (it.length() < 1_000_000) throw IOException("${asset.second} is too small")
        }
}
