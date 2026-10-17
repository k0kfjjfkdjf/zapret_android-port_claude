package io.github.zapretdroid

import android.app.Application
import android.os.Build
import android.os.Process
import io.github.zapretdroid.core.AppLog
import io.github.zapretdroid.service.VpnStatus

class ZapretApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
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
