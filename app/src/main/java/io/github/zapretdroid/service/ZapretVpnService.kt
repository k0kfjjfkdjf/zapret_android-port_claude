package io.github.zapretdroid.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.zapretdroid.MainActivity
import io.github.zapretdroid.R
import io.github.zapretdroid.core.AppFilterMode
import io.github.zapretdroid.core.ByeDpi
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stop(null)
            return START_NOT_STICKY
        }
        // ACTION_START from the UI or "android.net.VpnService" from always-on VPN.
        startForegroundCompat()
        if (state == VpnState.STOPPED && !used) {
            used = true
            setState(VpnState.STARTING)
            thread(name = "vpn-start") { startSession() }
        } else {
            setState(state)
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
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
            val args = buildList {
                add("ciadpi")
                add("--ip"); add(PROXY_HOST)
                add("--port"); add(PROXY_PORT.toString())
                addAll(splitArgs(settings.strategyArgs))
            }
            Log.i(TAG, "byedpi args: ${args.joinToString(" ")}")
            val proxy = ByeDpi()
            engine = proxy
            engineThread = thread(name = "byedpi") {
                val code = proxy.startProxy(args.toTypedArray())
                if (state == VpnState.STARTING || state == VpnState.RUNNING) {
                    handler.post { stop(getString(R.string.error_engine_exited, code)) }
                }
            }
            if (!awaitProxy()) {
                throw IllegalStateException(getString(R.string.error_engine_start))
            }
            if (state != VpnState.STARTING) return

            val fd = buildTunnel(settings).establish()
                ?: throw IllegalStateException(getString(R.string.error_vpn_permission))
            tun = fd

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
                |  log-level: warn
                |""".trimMargin(),
            )
            if (!TProxyService.TProxyStartService(config.absolutePath, fd.fd)) {
                throw IllegalStateException(getString(R.string.error_tunnel_start))
            }
            handler.post {
                if (state == VpnState.STARTING) {
                    setState(VpnState.RUNNING)
                    handler.postDelayed(statsTick, STATS_INTERVAL_MS)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "failed to start", e)
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
        if (settings.ipv6 || !dns.contains(':')) builder.addDnsServer(dns)

        val selected = settings.selectedApps.filter { it != packageName && isInstalled(it) }
        val mode = settings.appFilterMode
        if (mode == AppFilterMode.ONLY_SELECTED && selected.isNotEmpty()) {
            selected.forEach { builder.addAllowedApplication(it) }
        } else {
            // An empty allow-list would route every app including this one, so "only selected"
            // with nothing selected falls back to routing all apps.
            builder.addDisallowedApplication(packageName)
            if (mode == AppFilterMode.EXCEPT_SELECTED) {
                selected.forEach { builder.addDisallowedApplication(it) }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
        return builder
    }

    private val statsTick = object : Runnable {
        override fun run() {
            if (state != VpnState.RUNNING) return
            val stats = runCatching { TProxyService.TProxyGetStats() }.getOrNull()
            VpnStatus.send(this@ZapretVpnService, state, tx = stats?.getOrNull(1) ?: 0, rx = stats?.getOrNull(3) ?: 0)
            handler.postDelayed(this, STATS_INTERVAL_MS)
        }
    }

    /** Stops the session on the main thread; [error] is shown in the UI. */
    private fun stop(error: String?) {
        if (state == VpnState.STOPPING) return
        val wasActive = state != VpnState.STOPPED
        setState(VpnState.STOPPING)
        handler.removeCallbacks(statsTick)
        thread(name = "vpn-stop") {
            if (wasActive) teardown()
            handler.post {
                state = VpnState.STOPPED
                VpnStatus.send(this, VpnState.STOPPED, error)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                // Native state is per process: exit so the next session starts clean.
                handler.postDelayed({ Process.killProcess(Process.myPid()) }, EXIT_DELAY_MS)
            }
        }
    }

    private fun teardown() {
        runCatching { TProxyService.TProxyStopService() }
        runCatching { tun?.close() }
        tun = null
        engine?.stopProxy()
        engineThread?.join(STOP_TIMEOUT_MS)
        engine = null
        engineThread = null
    }

    private fun setState(newState: VpnState) {
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
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_running))
            .setContentIntent(mainActivityIntent())
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

    private fun mainActivityIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun isInstalled(pkg: String): Boolean = try {
        packageManager.getApplicationInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    companion object {
        const val ACTION_START = "io.github.zapretdroid.START"
        const val ACTION_STOP = "io.github.zapretdroid.STOP"

        private const val TAG = "ZapretVpnService"
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
