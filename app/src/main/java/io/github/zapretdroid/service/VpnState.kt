package io.github.zapretdroid.service

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.github.zapretdroid.core.AppLog

enum class VpnState { STOPPED, STARTING, RUNNING, STOPPING }

/** Status messages sent by [ZapretVpnService] (engine process) to the UI process. */
object VpnStatus {
    const val ACTION = "io.github.zapretdroid.STATUS"
    const val EXTRA_STATE = "state"
    const val EXTRA_ERROR = "error"
    const val EXTRA_TX_BYTES = "tx"
    const val EXTRA_RX_BYTES = "rx"

    fun send(context: Context, state: VpnState, error: String? = null, tx: Long = 0, rx: Long = 0) {
        context.sendBroadcast(
            Intent(ACTION)
                .setPackage(context.packageName)
                .putExtra(EXTRA_STATE, state.name)
                .putExtra(EXTRA_ERROR, error)
                .putExtra(EXTRA_TX_BYTES, tx)
                .putExtra(EXTRA_RX_BYTES, rx),
        )
    }

    fun parseState(intent: Intent): VpnState =
        intent.getStringExtra(EXTRA_STATE)?.let { runCatching { VpnState.valueOf(it) }.getOrNull() }
            ?: VpnState.STOPPED

    /** Whether the VPN service is alive; used when the UI starts and has missed broadcasts. */
    @Suppress("DEPRECATION")
    fun isServiceRunning(context: Context): Boolean {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return false
        // getRunningServices() still reports the caller's own services.
        return manager.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == ZapretVpnService::class.java.name && it.started
        }
    }

    fun start(context: Context) {
        AppLog.i("starting VPN service")
        val intent = Intent(context, ZapretVpnService::class.java).setAction(ZapretVpnService.ACTION_START)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        AppLog.i("stopping VPN service")
        val intent = Intent(context, ZapretVpnService::class.java).setAction(ZapretVpnService.ACTION_STOP)
        // Fails only if the service is not running, in which case there is nothing to stop.
        runCatching { context.startService(intent) }
    }
}
