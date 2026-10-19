package io.github.zapretdroid.test

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.zapretdroid.MainActivity
import io.github.zapretdroid.R
import io.github.zapretdroid.core.AppLog
import kotlin.concurrent.thread

/**
 * Foreground service that keeps the app process alive while a long test runs (deep check,
 * strategy tester), so Android does not kill it in the background. It shows the progress and
 * stops by itself when no test is running.
 */
class TestService : Service() {
    @Volatile
    private var watching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            DeepCheck.cancel()
            StrategyTester.cancel()
            AutoFixTester.cancel()
        }
        startForegroundCompat(text())
        if (!watching) {
            watching = true
            thread(name = "test-service") {
                // Give the test a moment to set its running flag.
                Thread.sleep(1000)
                while (busy()) {
                    getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(text()))
                    Thread.sleep(3000)
                }
                watching = false
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun busy() = DeepCheck.running || StrategyTester.running || AutoFixTester.running || ScriptSearch.running

    private fun text(): String = when {
        DeepCheck.running -> {
            val (s, sTotal) = DeepCheck.strategyProgress
            val (d, dTotal) = DeepCheck.domainProgress
            getString(R.string.deep_notification, s, sTotal, d, dTotal)
        }
        StrategyTester.running -> {
            val (done, total) = StrategyTester.progress
            getString(R.string.test_notification, done, total)
        }
        ScriptSearch.running -> ScriptSearch.current
        AutoFixTester.running -> {
            val (done, total) = AutoFixTester.progress
            getString(R.string.test_notification, done, total)
        }
        else -> getString(R.string.test_notification_idle)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, TestService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.test_notification_title))
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startForegroundCompat(text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.test_notification_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification(text))
        }
    }

    companion object {
        private const val CHANNEL_ID = "tests"
        private const val NOTIFICATION_ID = 2
        private const val ACTION_STOP = "io.github.zapretdroid.TEST_STOP"

        /** Starts the service; call right after a test has started. */
        fun keepAlive(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, TestService::class.java)) }
                .onFailure { AppLog.w("test service did not start: ${it.message}", source = "test") }
        }
    }
}
