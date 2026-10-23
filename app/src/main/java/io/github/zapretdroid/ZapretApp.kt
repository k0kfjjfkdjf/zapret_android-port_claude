package io.github.zapretdroid

import android.app.Application
import android.os.Build
import android.os.Process
import android.util.Log
import io.github.zapretdroid.core.AppLog
import io.github.zapretdroid.core.AppRefs
import io.github.zapretdroid.core.ArgTemplates
import io.github.zapretdroid.core.Strategies
import io.github.zapretdroid.service.VpnStatus
import java.io.File

class ZapretApp : Application() {

    private fun processName(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            runCatching { File("/proc/self/cmdline").readText().trimEnd('\u0000') }.getOrDefault("")
        }

    override fun onCreate() {
        super.onCreate()
        AppLog.console = { priority, message -> Log.println(priority, "ByeZapret", message) }
        AppLog.init(File(filesDir, "logs"), processName().endsWith(":engine"))
        ArgTemplates.refs = AppRefs(this)
        Strategies.init(this)
        val vpnRunning = VpnStatus.isServiceRunning(this)
        // The VPN process trims right before a session; the UI process only when no session writes.
        if (!vpnRunning && !AppLog.inEngineProcess) AppLog.trimIfLarge()
        AppLog.i(
            "process started: ${if (AppLog.inEngineProcess) "vpn" else "ui"}, " +
                "ByeZapret ${BuildConfig.VERSION_NAME}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
                "${Build.MANUFACTURER} ${Build.MODEL}" + if (!AppLog.inEngineProcess) ", vpn running: $vpnRunning" else "",
        )
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            AppLog.e("crash in thread ${thread.name} (pid ${Process.myPid()})", error)
            default?.uncaughtException(thread, error)
        }
    }
}
