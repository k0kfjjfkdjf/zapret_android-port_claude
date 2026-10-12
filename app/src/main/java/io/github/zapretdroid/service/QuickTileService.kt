package io.github.zapretdroid.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import io.github.zapretdroid.MainActivity
import io.github.zapretdroid.core.AppLog

class QuickTileService : TileService() {
    private var state = VpnState.STOPPED

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            state = VpnStatus.parseState(intent)
            updateTile()
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        ContextCompat.registerReceiver(
            this, receiver, IntentFilter(VpnStatus.ACTION), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        state = if (VpnStatus.isServiceRunning(this)) VpnState.RUNNING else VpnState.STOPPED
        updateTile()
    }

    override fun onStopListening() {
        runCatching { unregisterReceiver(receiver) }
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        AppLog.i("quick settings tile tapped (state $state)")
        when {
            state == VpnState.RUNNING || state == VpnState.STARTING -> VpnStatus.stop(this)
            VpnService.prepare(this) == null -> VpnStatus.start(this)
            // The VPN consent dialog needs an activity.
            else -> openApp()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_CONNECT, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        tile.state = when (state) {
            VpnState.RUNNING, VpnState.STARTING -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }
}
