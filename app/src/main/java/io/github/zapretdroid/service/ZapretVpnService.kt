package io.github.zapretdroid.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.core.app.NotificationCompat
import io.github.zapretdroid.MainActivity
import io.github.zapretdroid.R
import io.github.zapretdroid.core.AppFilterMode
import io.github.zapretdroid.core.AppLog
import io.github.zapretdroid.core.ArgTemplates
import io.github.zapretdroid.core.ByeDpi
import io.github.zapretdroid.core.Diagnostics
import io.github.zapretdroid.core.FakePayloads
import io.github.zapretdroid.core.AutoHost
import io.github.zapretdroid.core.HostLists
import io.github.zapretdroid.core.HostScope
import io.github.zapretdroid.core.StrategyArgs
import io.github.zapretdroid.core.StrategyGroups
import io.github.zapretdroid.core.NativeLog
import io.github.zapretdroid.core.Settings
import io.github.zapretdroid.core.TProxyService
import io.github.zapretdroid.core.isIpLiteral
import io.github.zapretdroid.core.splitArgs
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Routes device traffic through the TUN interface into hev-socks5-tunnel, which hands every
 * TCP connection and UDP flow to the local ByeDPI proxy. ByeDPI applies the desync strategy and
 * talks to the network through ordinary sockets. The app itself is excluded from the VPN, so
 * those sockets bypass the tunnel without protect().
 *
 * Runs in the ":engine" process, which exits when the session ends: both native libraries keep
 * global state that cannot be reinitialised within one process.
 */
class ZapretVpnService : VpnService() {
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var state = VpnState.STOPPED
    private var used = false
    private var tun: ParcelFileDescriptor? = null
    private var engine: ByeDpi? = null
    private var engineThread: Thread? = null
    private var startedAt = 0L
    private var paused = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AppLog.i("stop requested")
            stop(null)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_PAUSE) {
            AppLog.i("pause requested from the notification")
            paused = true
            stop(null)
            return START_NOT_STICKY
        }
        getSystemService(NotificationManager::class.java)?.cancel(PAUSED_NOTIFICATION_ID)
        // ACTION_START from the UI or "android.net.VpnService" from always-on VPN.
        startForegroundCompat()
        if (state == VpnState.STOPPED && !used) {
            used = true
            AppLog.trimIfLarge()
            AppLog.i("===== VPN session start (${intent?.action ?: "restart"}) =====")
            setState(VpnState.STARTING)
            thread(name = "vpn-start") { startSession() }
        } else {
            setState(state)
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        AppLog.w("VPN revoked by the system (another VPN started or permission withdrawn)")
        stop(null)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (state != VpnState.STOPPED) teardown()
        super.onDestroy()
    }

    private fun startSession() {
        val settings = Settings(this)
        try {
            Diagnostics.report(this, settings).forEach { AppLog.i(it) }
            val hevLog = NativeLog.start(AppLog.file.absolutePath, NATIVE_LOG_LIMIT)
            if (hevLog == null) AppLog.w("could not capture native engine output")
            val logLevel = settings.logLevel
            val args = buildList {
                add("ciadpi")
                add("--ip"); add(PROXY_HOST)
                add("--port"); add(PROXY_PORT.toString())
                // One line per connection (host, group, bytes) and per detected block at every level.
                add("--conn-log")
                if (logLevel.byedpiDebug > 0) {
                    add("--debug"); add(logLevel.byedpiDebug.toString())
                }
                addAll(resolveStrategy(settings))
            }
            AppLog.i("byedpi command: ${args.joinToString(" ") { quoteArg(it) }}")
            StrategyGroups.describe(args.drop(1)).forEach { AppLog.i(it) }
            val proxy = ByeDpi()
            engine = proxy
            engineThread = thread(name = "byedpi") {
                val code = proxy.startProxy(args.toTypedArray())
                AppLog.i("byedpi exited with code $code")
                if (state == VpnState.STARTING || state == VpnState.RUNNING) {
                    val message = withEngineOutput(getString(R.string.error_engine_exited, code))
                    handler.post { stop(message) }
                }
            }
            if (!awaitProxy()) {
                throw IllegalStateException(withEngineOutput(getString(R.string.error_engine_start)))
            }
            AppLog.i("byedpi is listening on $PROXY_HOST:$PROXY_PORT")
            if (state != VpnState.STARTING) return

            val fd = buildTunnel(settings).establish()
                ?: throw IllegalStateException(getString(R.string.error_vpn_permission))
            tun = fd
            AppLog.i("tun interface established")

            val config = File(filesDir, "hev.yml")
            config.writeText(
                """
                |tunnel:
                |  mtu: $MTU
                |socks5:
                |  port: $PROXY_PORT
                |  address: $PROXY_HOST
                |  udp: 'udp'
                |misc:
                |  task-stack-size: 81920
                |  log-file: ${hevLog ?: "null"}
                |  log-level: ${logLevel.hevLevel}
                |""".trimMargin(),
            )
            if (!TProxyService.TProxyStartService(config.absolutePath, fd.fd)) {
                throw IllegalStateException(getString(R.string.error_tunnel_start))
            }
            AppLog.i("tunnel started")
            handler.post {
                if (state == VpnState.STARTING) {
                    startedAt = System.currentTimeMillis()
                    AppLog.i("connected")
                    watchNetwork()
                    setState(VpnState.RUNNING)
                    handler.postDelayed(statsTick, STATS_INTERVAL_MS)
                }
            }
        } catch (e: Throwable) {
            AppLog.e("failed to start: ${e.message}", e)
            handler.post { stop(e.message ?: e.toString()) }
        }
    }

    /** Waits until ByeDPI accepts connections or exits. */
    private fun awaitProxy(): Boolean {
        val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (engineThread?.isAlive != true) return false
            try {
                Socket().use { it.connect(InetSocketAddress(PROXY_HOST, PROXY_PORT), 200) }
                return true
            } catch (_: Exception) {
                Thread.sleep(50)
            }
        }
        return false
    }

    private fun buildTunnel(settings: Settings): Builder {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(MTU)
            .addAddress("10.10.10.10", 32)
            .addRoute("0.0.0.0", 0)
            .setConfigureIntent(mainActivityIntent())
        if (settings.ipv6) {
            builder.addAddress("fd00:10:10::10", 128).addRoute("::", 0)
        }

        val dns = settings.dns.trim().takeIf { isIpLiteral(it) } ?: Settings.DEFAULT_DNS
        if (dns != settings.dns.trim()) AppLog.w("invalid DNS server '${settings.dns}', using $dns")
        if (settings.ipv6 || !dns.contains(':')) {
            builder.addDnsServer(dns)
        } else {
            AppLog.w("IPv6 DNS server $dns ignored because IPv6 is off")
        }

        val selected = settings.selectedApps.filter { it != packageName && isInstalled(it) }
        val mode = settings.appFilterMode
        if (mode != AppFilterMode.ALL) {
            (settings.selectedApps - selected.toSet() - packageName).forEach { AppLog.w("selected app not installed: $it") }
        }
        if (mode == AppFilterMode.ONLY_SELECTED && selected.isNotEmpty()) {
            selected.forEach { builder.addAllowedApplication(it) }
        } else {
            if (mode == AppFilterMode.ONLY_SELECTED) AppLog.w("no apps selected, routing all apps")
            // An empty allow-list would route every app including this one, so "only selected"
            // with nothing selected falls back to routing all apps.
            builder.addDisallowedApplication(packageName)
            if (mode == AppFilterMode.EXCEPT_SELECTED) {
                selected.forEach { builder.addDisallowedApplication(it) }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
        AppLog.i("vpn: mtu $MTU, dns $dns, ipv6 ${settings.ipv6}, apps $mode (${selected.size} selected)")
        return builder
    }

    private var lastLoggedTx = 0L
    private var lastLoggedRx = 0L
    private var ticks = 0

    private val statsTick = object : Runnable {
        override fun run() {
            if (state != VpnState.RUNNING) return
            val stats = runCatching { TProxyService.TProxyGetStats() }.getOrNull()
            if (stats != null && ++ticks % 60 == 0) {
                val tx = stats.getOrNull(1) ?: 0
                val rx = stats.getOrNull(3) ?: 0
                AppLog.i("traffic: last minute up ${tx - lastLoggedTx} B, down ${rx - lastLoggedRx} B; total up $tx B, down $rx B")
                lastLoggedTx = tx
                lastLoggedRx = rx
            }
            VpnStatus.send(this@ZapretVpnService, state, tx = stats?.getOrNull(1) ?: 0, rx = stats?.getOrNull(3) ?: 0)
            handler.postDelayed(this, STATS_INTERVAL_MS)
        }
    }

    /** Stops the session on the main thread; [error] is shown in the UI. */
    private fun stop(error: String?) {
        if (state == VpnState.STOPPING) return
        val wasActive = state != VpnState.STOPPED
        if (error != null) AppLog.e("stopping: $error")
        if (startedAt > 0) {
            val stats = runCatching { TProxyService.TProxyGetStats() }.getOrNull()
            val minutes = (System.currentTimeMillis() - startedAt) / 60_000.0
            AppLog.i(
                "session lasted %.1f min, sent %d bytes, received %d bytes"
                    .format(java.util.Locale.US, minutes, stats?.getOrNull(1) ?: 0L, stats?.getOrNull(3) ?: 0L),
            )
        }
        setState(VpnState.STOPPING)
        handler.removeCallbacks(statsTick)
        thread(name = "vpn-stop") {
            if (wasActive) teardown()
            handler.post {
                if (wasActive) AppLog.i("===== VPN session stopped =====")
                state = VpnState.STOPPED
                VpnStatus.send(this, VpnState.STOPPED, error)
                stopForeground(STOP_FOREGROUND_REMOVE)
                if (paused) showPausedNotification()
                stopSelf()
                // Native state is per process: exit so the next session starts clean.
                handler.postDelayed({ Process.killProcess(Process.myPid()) }, EXIT_DELAY_MS)
            }
        }
    }

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** Logs network changes during the session (Wi-Fi <-> mobile, loss), a common cause of drops. */
    private fun watchNetwork() {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var lastDescription: String? = null

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val description = buildList {
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
                }.ifEmpty { listOf("other") }.joinToString("+") +
                    ", validated: ${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}"
                if (description != lastDescription) {
                    lastDescription = description
                    AppLog.i("network: $description")
                }
            }

            override fun onLost(network: Network) {
                lastDescription = null
                AppLog.w("network lost")
            }
        }
        // The default network of this app: the app is outside the VPN, so it is the real one.
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
            .onFailure { AppLog.w("cannot watch network changes", it) }
    }

    private fun teardown() {
        networkCallback?.let { cb ->
            runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        }
        networkCallback = null
        runCatching { TProxyService.TProxyStopService() }
        runCatching { tun?.close() }
        tun = null
        engine?.stopProxy()
        engineThread?.join(STOP_TIMEOUT_MS)
        engine = null
        engineThread = null
    }

    private fun setState(newState: VpnState) {
        if (newState != state) AppLog.i("state: $state -> $newState")
        state = newState
        VpnStatus.send(this, newState)
    }

    private fun startForegroundCompat() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ZapretVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val pauseIntent = PendingIntent.getService(
            this, 4,
            Intent(this, ZapretVpnService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_running))
            .setContentIntent(mainActivityIntent())
            .addAction(0, getString(R.string.action_pause), pauseIntent)
            .addAction(0, getString(R.string.action_stop), stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** After "Pause": a notification that starts the VPN again with one tap. */
    private fun showPausedNotification() {
        val start = Intent(this, ZapretVpnService::class.java).setAction(ACTION_START)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val resume = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(this, 3, start, flags)
        } else {
            PendingIntent.getService(this, 3, start, flags)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_paused))
            .setContentIntent(mainActivityIntent())
            .addAction(0, getString(R.string.action_resume), resume)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(PAUSED_NOTIFICATION_ID, notification)
    }

    private fun mainActivityIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * Strategy arguments with {fake:...} and {list:...} replaced by file paths, limited to the
     * chosen host list and with the autohostlist recorder when enabled.
     */
    private fun resolveStrategy(settings: Settings): List<String> {
        val strategy = try {
            ArgTemplates.resolve(splitArgs(settings.strategyArgs), FakePayloads.install(this), HostLists.dir(this))
        } catch (e: ArgTemplates.UnknownReference) {
            val res = if (e.kind == "fake") R.string.error_unknown_fake else R.string.error_unknown_list
            throw IllegalStateException(getString(res, e.name))
        }
        val scope = settings.hostScope
        val listPath = if (scope == HostScope.ALL) {
            null
        } else {
            HostLists.file(this, settings.hostList).takeIf { settings.hostList.isNotEmpty() && it.isFile }?.absolutePath
                ?: throw IllegalStateException(getString(R.string.error_unknown_list, settings.hostList))
        }
        val autohostLog = if (settings.autohost) AutoHost.file(this).absolutePath else null
        return StrategyArgs.build(strategy, scope, listPath, autohostLog)
    }

    /** Appends the last line ByeDPI printed, which usually names the bad option or failed call. */
    private fun withEngineOutput(message: String): String {
        Thread.sleep(100) // let the log reader catch up with the engine's last words
        val line = runCatching { AppLog.readTail(AppLog.file, 16 * 1024) }.getOrNull()
            ?.lastOrNull { it.contains(" byedpi | ") }
            ?.substringAfter(" byedpi | ")
        return if (line != null) "$message\nByeDPI: $line" else message
    }

    /** Shell-style quoting so that the logged command can be copied into a terminal. */
    private fun quoteArg(arg: String): String =
        if (arg.isNotEmpty() && arg.none { it.isWhitespace() || it in "'\"\\$" }) arg
        else "'" + arg.replace("'", "'\\''") + "'"

    private fun isInstalled(pkg: String): Boolean = try {
        packageManager.getApplicationInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    companion object {
        const val ACTION_START = "io.github.zapretdroid.START"
        const val ACTION_STOP = "io.github.zapretdroid.STOP"
        const val ACTION_PAUSE = "io.github.zapretdroid.PAUSE"
        private const val PAUSED_NOTIFICATION_ID = 3

        private const val NATIVE_LOG_LIMIT = 8L * 1024 * 1024
        private const val CHANNEL_ID = "vpn"
        private const val NOTIFICATION_ID = 1
        private const val PROXY_HOST = "127.0.0.1"
        private const val PROXY_PORT = 10808
        private const val MTU = 8500
        private const val START_TIMEOUT_MS = 5_000L
        private const val STOP_TIMEOUT_MS = 2_000L
        private const val STATS_INTERVAL_MS = 1_000L
        private const val EXIT_DELAY_MS = 500L
    }
}
