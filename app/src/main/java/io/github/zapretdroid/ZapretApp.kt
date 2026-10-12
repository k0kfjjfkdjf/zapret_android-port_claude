package io.github.zapretdroid

import android.app.Application
import android.os.Process
import io.github.zapretdroid.core.AppLog

class ZapretApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            AppLog.e("crash in thread ${thread.name} (pid ${Process.myPid()})", error)
            default?.uncaughtException(thread, error)
        }
    }
}
