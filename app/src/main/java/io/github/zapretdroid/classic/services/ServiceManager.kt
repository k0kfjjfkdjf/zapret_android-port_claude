package io.github.zapretdroid.classic.services

import android.app.Application
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.zapretdroid.R
import io.github.zapretdroid.classic.ClassicBridge
import io.github.zapretdroid.classic.data.AppStatus
import io.github.zapretdroid.classic.data.FAILED_BROADCAST
import io.github.zapretdroid.classic.data.Mode
import io.github.zapretdroid.classic.data.PAUSE_ACTION
import io.github.zapretdroid.classic.data.RESTART_ACTION
import io.github.zapretdroid.classic.data.RESUME_ACTION
import io.github.zapretdroid.classic.data.SENDER
import io.github.zapretdroid.classic.data.STARTED_BROADCAST
import io.github.zapretdroid.classic.data.START_ACTION
import io.github.zapretdroid.classic.data.STOPPED_BROADCAST
import io.github.zapretdroid.classic.data.STOP_ACTION
import io.github.zapretdroid.classic.data.Sender
import io.github.zapretdroid.classic.utility.createPauseNotification
import io.github.zapretdroid.classic.utility.registerNotificationChannel
import io.github.zapretdroid.core.AppLog
import io.github.zapretdroid.service.VpnState
import io.github.zapretdroid.service.VpnStatus
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ZapretDroid: ByeByeDPI's service manager with the same calls, on ZapretDroid's services. VPN
 * mode runs ZapretDroid's VPN service (its engine, logging, host lists, monitor), proxy mode runs
 * [ClassicProxyService]; the classic settings are written to ZapretDroid's before every start
 * (see [ClassicBridge]). Status comes from those services' broadcasts and is republished as
 * ByeByeDPI's STARTED/STOPPED/FAILED broadcasts, which the classic screens listen to.
 */
object ServiceManager {
    private const val TAG = "ServiceManager"
    private const val PAUSE_NOTIFICATION_ID = 6
    private const val START_TIMEOUT_MS = 8_000L
    private const val STOP_TIMEOUT_MS = 6_000L

    /** The engine process exits after a session; a new one needs a moment. */
    private const val RESTART_PAUSE_MS = 1_000L

    private val scope = CoroutineScope(
        Dispatchers.Main.immediate + SupervisorJob() + CoroutineExceptionHandler { _, error ->
            Log.e(TAG, "Service command failed", error)
        },
    )
    private val mutex = Mutex()
    private val state = MutableStateFlow(AppStatus.Halted to Mode.VPN)
    private var application: Application? = null

    private fun attach(context: Context): Application {
        val app = context.applicationContext as Application
        if (application == null) {
            application = app
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val mode = if (intent.action == ClassicProxyService.ACTION_STATUS) Mode.Proxy else Mode.VPN
                    onStatus(mode, VpnStatus.parseState(intent), intent.getStringExtra(VpnStatus.EXTRA_ERROR))
                }
            }
            val filter = IntentFilter(VpnStatus.ACTION).apply { addAction(ClassicProxyService.ACTION_STATUS) }
            ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        return app
    }

    private fun onStatus(mode: Mode, vpnState: VpnState, error: String?) {
        when (vpnState) {
            VpnState.RUNNING -> publish(mode, AppStatus.Running, STARTED_BROADCAST)
            VpnState.STOPPED -> if (state.value.first == AppStatus.Running || error != null) {
                publish(mode, AppStatus.Halted, if (error != null) FAILED_BROADCAST else STOPPED_BROADCAST)
            }
            else -> Unit
        }
    }

    private fun runningMode(app: Application): Mode? = when {
        VpnStatus.isServiceRunning(app) -> Mode.VPN
        ClassicProxyService.isRunning(app) -> Mode.Proxy
        else -> null
    }

    fun start(context: Context, mode: Mode) {
        val app = attach(context)
        scope.launch { waitStart(app, mode) }
    }

    suspend fun waitStart(context: Context, mode: Mode): Boolean = withContext(Dispatchers.Main.immediate) {
        val app = attach(context)
        mutex.withLock {
            if (TestService.isRunning) return@withLock false
            val running = runningMode(app)
            if (running == mode) return@withLock true
            if (running != null) {
                stopServices(app)
                awaitStopped(app)
                delay(RESTART_PAUSE_MS)
            }
            ClassicBridge.toZapret(app)
            (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(PAUSE_NOTIFICATION_ID)
            AppLog.i("ByeByeDPI interface: start in ${mode.name} mode")
            when (mode) {
                Mode.VPN -> VpnStatus.start(app)
                Mode.Proxy -> ClassicProxyService.start(app)
            }
            withTimeoutOrNull(START_TIMEOUT_MS) { state.first { it.first == AppStatus.Running && it.second == mode } } != null
        }
    }

    fun stop() {
        scope.launch { waitStop() }
    }

    suspend fun waitStop() = withContext(Dispatchers.Main.immediate) {
        val app = application ?: return@withContext
        (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(PAUSE_NOTIFICATION_ID)
        mutex.withLock {
            if (runningMode(app) == null) {
                refresh(app)
                return@withLock
            }
            stopServices(app)
            awaitStopped(app)
        }
    }

    private fun stopServices(app: Application) {
        VpnStatus.stop(app)
        ClassicProxyService.stop(app)
    }

    private suspend fun awaitStopped(app: Application) {
        withTimeoutOrNull(STOP_TIMEOUT_MS) {
            while (runningMode(app) != null) delay(100)
        }
        refresh(app)
    }

    fun restart(context: Context, mode: Mode) {
        val app = attach(context)
        scope.launch {
            if (TestService.isRunning) return@launch
            waitStop()
            delay(RESTART_PAUSE_MS)
            waitStart(app, mode)
        }
    }

    /** Re-reads which service runs (the screens call it on resume). */
    fun refresh(context: Context) {
        val app = attach(context)
        val running = runningMode(app)
        when {
            running != null && state.value != (AppStatus.Running to running) -> publish(running, AppStatus.Running, STARTED_BROADCAST)
            running == null && state.value.first == AppStatus.Running -> publish(state.value.second, AppStatus.Halted, STOPPED_BROADCAST)
        }
    }

    suspend fun handleAction(context: Context, action: String, mode: Mode, sourcePid: Int = 0) {
        val app = attach(context)
        withContext(Dispatchers.Main.immediate) {
            if (TestService.isRunning) return@withContext
            when (action) {
                START_ACTION, RESUME_ACTION, RESTART_ACTION -> {
                    if (mode == Mode.VPN && VpnService.prepare(app) != null) return@withContext
                    if (action == RESTART_ACTION) {
                        waitStop()
                        delay(RESTART_PAUSE_MS)
                    }
                    waitStart(app, mode)
                }
                STOP_ACTION, PAUSE_ACTION -> {
                    val activeMode = runningMode(app) ?: mode
                    waitStop()
                    if (action == PAUSE_ACTION && !TestService.isRunning) {
                        val notifications = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        val channel = if (activeMode == Mode.VPN) "ByeDPIVpn" else "ByeDPI Proxy"
                        val name = if (activeMode == Mode.VPN) R.string.bb_vpn_channel_name else R.string.bb_proxy_channel_name
                        registerNotificationChannel(app, channel, name)
                        notifications.notify(
                            PAUSE_NOTIFICATION_ID,
                            createPauseNotification(app, channel, R.string.bb_notification_title, R.string.bb_service_paused_text, activeMode),
                        )
                    }
                }
            }
        }
    }

    private fun publish(mode: Mode, status: AppStatus, action: String) {
        state.value = status to mode
        setStatus(status, mode)
        val sender = if (mode == Mode.VPN) Sender.VPN else Sender.Proxy
        application?.let {
            it.sendBroadcast(Intent(action).setPackage(it.packageName).putExtra(SENDER, sender.ordinal))
        }
    }
}
