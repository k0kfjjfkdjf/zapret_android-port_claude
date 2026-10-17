package io.github.zapretdroid.classic.services

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import androidx.core.content.ContextCompat
import io.github.zapretdroid.R
import io.github.zapretdroid.classic.core.ByeDpiProxyCmdPreferences
import io.github.zapretdroid.classic.core.ByeDpiProxyPreferences
import io.github.zapretdroid.classic.core.ByeDpiProxyUIPreferences
import io.github.zapretdroid.classic.data.Mode
import io.github.zapretdroid.classic.utility.createConnectionNotification
import io.github.zapretdroid.classic.utility.getPreferences
import io.github.zapretdroid.classic.utility.registerNotificationChannel
import io.github.zapretdroid.core.AppLog
import io.github.zapretdroid.core.ArgTemplates
import io.github.zapretdroid.core.ByeDpi
import io.github.zapretdroid.core.FakePayloads
import io.github.zapretdroid.core.HostLists
import io.github.zapretdroid.core.NativeLog
import io.github.zapretdroid.service.VpnState
import io.github.zapretdroid.service.VpnStatus
import kotlin.concurrent.thread

/**
 * ZapretDroid: ByeByeDPI's proxy mode, the engine as a SOCKS proxy on the address from the classic
 * settings, without a VPN. Runs in its own process because the engine's state is per process.
 */
class ClassicProxyService : Service() {
    private var engine: ByeDpi? = null
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopping = true
            engine?.stopProxy() ?: finish(null)
            return START_NOT_STICKY
        }
        if (engine != null) return START_NOT_STICKY
        registerNotificationChannel(this, CHANNEL, R.string.bb_proxy_channel_name)
        val notification = createConnectionNotification(this, CHANNEL, R.string.bb_notification_title, R.string.bb_proxy_notification_content, Mode.Proxy)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        NativeLog.start(AppLog.file.absolutePath, 8L * 1024 * 1024)
        val args = runCatching { buildArgs(intent?.getStringArrayExtra(EXTRA_ARGS).orEmpty().toList()) }.getOrElse {
            finish(it.message ?: it.toString())
            return START_NOT_STICKY
        }
        AppLog.i("ByeByeDPI proxy mode: ${args.joinToString(" ")}")
        val current = ByeDpi()
        engine = current
        thread(name = "classic-proxy") {
            Handler(Looper.getMainLooper()).postDelayed({ if (engine != null) send(VpnState.RUNNING, null) }, 300)
            val code = current.startProxy(args.toTypedArray())
            Handler(Looper.getMainLooper()).post {
                finish(if (code != 0 && !stopping) "engine exited with code $code" else null)
            }
        }
        return START_NOT_STICKY
    }

    private fun buildArgs(args: List<String>): List<String> {
        require(args.isNotEmpty()) { "no engine arguments" }
        val rest = ArgTemplates.resolve(args, FakePayloads.install(this), HostLists.dir(this))
        return listOf("ciadpi", "--conn-log") + rest
    }

    private fun finish(error: String?) {
        engine = null
        if (error != null) AppLog.e("ByeByeDPI proxy mode: $error")
        send(VpnState.STOPPED, error)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        // The engine's state is per process: exit so that the next start is clean.
        Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 500)
    }

    private fun send(state: VpnState, error: String?) {
        sendBroadcast(
            Intent(ACTION_STATUS).setPackage(packageName)
                .putExtra(VpnStatus.EXTRA_STATE, state.name)
                .putExtra(VpnStatus.EXTRA_ERROR, error),
        )
    }

    companion object {
        const val ACTION_STATUS = "io.github.zapretdroid.PROXY_STATUS"
        private const val ACTION_START = "io.github.zapretdroid.PROXY_START"
        private const val ACTION_STOP = "io.github.zapretdroid.PROXY_STOP"
        private const val EXTRA_ARGS = "args"
        private const val CHANNEL = "ByeDPI Proxy"
        private const val NOTIFICATION_ID = 5

        /**
         * Starts the proxy with the classic settings, read here because the service process
         * may not see preferences that were just written with apply().
         */
        fun start(context: Context) {
            val args = when (val p = ByeDpiProxyPreferences.fromSharedPreferences(context.getPreferences(), context)) {
                is ByeDpiProxyCmdPreferences -> p.args.toList()
                is ByeDpiProxyUIPreferences -> p.uiargs.toList()
            }
            val intent = Intent(context, ClassicProxyService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ARGS, args.drop(1).toTypedArray())
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            if (isRunning(context)) runCatching { context.startService(Intent(context, ClassicProxyService::class.java).setAction(ACTION_STOP)) }
        }

        @Suppress("DEPRECATION")
        fun isRunning(context: Context): Boolean {
            val manager = context.getSystemService(ActivityManager::class.java) ?: return false
            return manager.getRunningServices(Int.MAX_VALUE).any { it.service.className == ClassicProxyService::class.java.name && it.started }
        }
    }
}
