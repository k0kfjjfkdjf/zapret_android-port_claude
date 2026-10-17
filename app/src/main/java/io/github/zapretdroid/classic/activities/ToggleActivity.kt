package io.github.zapretdroid.classic.activities

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import androidx.core.content.edit
import io.github.zapretdroid.classic.data.AppStatus
import io.github.zapretdroid.classic.data.Mode
import io.github.zapretdroid.classic.data.RESTART_ACTION
import io.github.zapretdroid.classic.data.START_ACTION
import io.github.zapretdroid.classic.data.STOP_ACTION
import io.github.zapretdroid.classic.receiver.ServiceActionReceiver
import io.github.zapretdroid.classic.services.ServiceManager
import io.github.zapretdroid.classic.services.appStatus
import io.github.zapretdroid.classic.utility.getCmdArgs
import io.github.zapretdroid.classic.utility.getPreferences
import io.github.zapretdroid.classic.utility.mode

class ToggleActivity : Activity() {

    companion object {
        private const val TAG = "ToggleServiceActivity"
    }

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ServiceManager.refresh(this)

        prefs = getPreferences()
        val strategy = intent.getStringExtra("strategy")
        val updated = updateStrategy(strategy)

        val onlyUpdate = intent.getBooleanExtra("only_update", false)
        val onlyStart = intent.getBooleanExtra("only_start", false)
        val onlyStop = intent.getBooleanExtra("only_stop", false)

        when {
            onlyUpdate -> {
                Log.i(TAG, "Only update strategy")
            }
            onlyStart -> {
                val (status) = appStatus
                if (status == AppStatus.Halted) {
                    startService()
                } else {
                    Log.i(TAG, "Service already running")
                }
            }
            onlyStop -> {
                val (status) = appStatus
                if (status == AppStatus.Running) {
                    stopService()
                } else {
                    Log.i(TAG, "Service already stopped")
                }
            }
            else -> {
                toggleService(updated)
            }
        }

        finish()
    }

    private fun startService() {
        val mode = prefs.mode()

        if (mode == Mode.VPN && VpnService.prepare(this) != null) {
            return
        }

        sendServiceAction(START_ACTION, mode)
        Log.i(TAG, "Toggle service start")
    }

    private fun restartService() {
        val mode = prefs.mode()

        if (mode == Mode.VPN && VpnService.prepare(this) != null) {
            return
        }

        sendServiceAction(RESTART_ACTION, mode)
        Log.i(TAG, "Toggle service start")
    }

    private fun stopService() {
        sendServiceAction(STOP_ACTION, appStatus.second)
        Log.i(TAG, "Toggle service stop")
    }

    private fun sendServiceAction(action: String, mode: Mode) {
        sendBroadcast(Intent(this, ServiceActionReceiver::class.java).setAction(action)
            .putExtra(ServiceActionReceiver.EXTRA_MODE, mode.name))
    }

    private fun toggleService(restart: Boolean) {
        val (status) = appStatus
        when (status) {
            AppStatus.Halted -> {
                startService()
            }
            AppStatus.Running -> {
                if (restart) {
                    restartService()
                } else {
                    stopService()
                }
            }
        }
    }

    private fun updateStrategy(strategy: String?): Boolean {
        val current = prefs.getCmdArgs()
        if (strategy != null && strategy != current) {
            prefs.edit(commit = true) { putString("byedpi_cmd_args", strategy) }
            Log.i(TAG, "Strategy updated to: $strategy")
            return true
        }
        return false
    }
}
