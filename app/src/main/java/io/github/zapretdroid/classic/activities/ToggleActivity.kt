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
import io.github.zapretdroid.classic.utility.shellSplit

class ToggleActivity : Activity() {

    companion object {
        private const val TAG = "ToggleServiceActivity"

        private val LISTEN_OPTIONS = setOf("-i", "--ip", "-p", "--port", "-E", "--transparent", "-D", "--daemon", "-w", "--pidfile")

        /**
         * ZapretDroid: this activity is exported for automation apps, so any app can send a
         * strategy. A file path (--fake-data /data/..., -H /...) would let it send the app's
         * private files to the network, and listen options would open the proxy elsewhere:
         * such strategies are refused; {fake:name} and {list:name} still work.
         */
        fun isSafeStrategy(strategy: String): Boolean =
            shellSplit(strategy).none { arg ->
                '/' in arg || arg.substringBefore('=') in LISTEN_OPTIONS ||
                    LISTEN_OPTIONS.any { it.length == 2 && arg.startsWith(it) && arg.length > 2 && !arg.startsWith("--") }
            }
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
        if (strategy != null && !isSafeStrategy(strategy)) {
            Log.w(TAG, "Strategy from another app rejected: file paths and listen options are not accepted")
            return false
        }
        if (strategy != null && strategy != current) {
            prefs.edit(commit = true) { putString("byedpi_cmd_args", strategy) }
            Log.i(TAG, "Strategy updated to: $strategy")
            return true
        }
        return false
    }
}
