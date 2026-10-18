package io.github.zapretdroid.tor

import IPtProxy.Controller
import IPtProxy.IPtProxy
import IPtProxy.OnTransportEvents
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.torproject.jni.TorService
import java.io.File

/**
 * Runs Tor for ByeZapret: a SOCKS5 proxy on 127.0.0.1 (the port ByeZapret asks for), with
 * bridges through the pluggable transports of IPtProxy when ByeZapret sends bridge lines.
 * Only apps signed with ByeZapret's key may start it (signature permission), and status goes
 * back only to such an app.
 */
class TorModuleService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var tor: TorService? = null
    private var bound = false
    private var controller: Controller? = null
    private var replyTo: String? = null
    private var socks = ""

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            tor = (service as? TorService.LocalBinder)?.service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            tor = null
        }
    }

    private val torStatus = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                TorService.ACTION_STATUS -> when (intent.getStringExtra(TorService.EXTRA_STATUS)) {
                    TorService.STATUS_ON -> report(STATE_STARTING, progress, "Tor started, building circuits")
                    TorService.STATUS_OFF -> if (state != STATE_ERROR) report(STATE_OFF, 0, "Tor stopped")
                }
                TorService.ACTION_ERROR -> report(STATE_ERROR, progress, intent.getStringExtra(Intent.EXTRA_TEXT) ?: "Tor error")
            }
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            val t = tor
            if (t != null && (state == STATE_STARTING || state == STATE_ON)) {
                val phase = runCatching { t.getInfo("status/bootstrap-phase") }.getOrNull().orEmpty()
                val pct = Regex("PROGRESS=(\\d+)").find(phase)?.groupValues?.get(1)?.toIntOrNull()
                val summary = Regex("SUMMARY=\"([^\"]*)\"").find(phase)?.groupValues?.get(1)
                if (pct != null && (pct != progress || summary != message)) {
                    report(if (pct >= 100) STATE_ON else STATE_STARTING, pct, summary ?: phase)
                }
            }
            if (state == STATE_STARTING || state == STATE_ON) main.postDelayed(this, if (state == STATE_ON) 10_000 else 1_500)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        LocalBroadcastManager.getInstance(this).registerReceiver(torStatus, IntentFilter().apply {
            addAction(TorService.ACTION_STATUS)
            addAction(TorService.ACTION_ERROR)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_REPLY_TO)?.takeIf { sameSigner(it) }?.let { replyTo = it }
        when (intent?.action) {
            ACTION_START -> {
                foreground(getString(R.string.notification_starting))
                stopTor()
                startTor(intent.getIntExtra(EXTRA_SOCKS_PORT, 9050), intent.getStringArrayListExtra(EXTRA_BRIDGES).orEmpty())
            }
            ACTION_STOP -> {
                stopTor()
                report(STATE_OFF, 0, "stopped by ByeZapret")
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> report(state, progress, message)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopTor()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(torStatus)
        super.onDestroy()
    }

    private fun startTor(port: Int, rawBridges: List<String>) {
        socks = "127.0.0.1:$port"
        report(STATE_STARTING, 0, "starting")
        try {
            // "builtin:<transport>": Tor Browser's default bridges of that transport (assets).
            val bridges = rawBridges.flatMap { line ->
                if (line.startsWith("builtin:")) builtin(line.removePrefix("builtin:")) else listOf(line)
            }.map { it.trim().removePrefix("Bridge ").trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            val transports = bridges.map { it.substringBefore(' ') }.filter { it in TRANSPORTS }.distinct()
            val torrc = StringBuilder()
            torrc.append("SocksPort 127.0.0.1:$port\n")
            torrc.append("ClientOnly 1\nAvoidDiskWrites 1\nSafeSocks 0\n")
            torrc.append("Log notice file ${File(filesDir, "tor.log").absolutePath}\n")
            if (bridges.isNotEmpty()) {
                if (transports.isNotEmpty()) {
                    val pt = IPtProxy.newController(File(filesDir, "pt").apply { mkdirs() }.absolutePath, true, false, "INFO", ptEvents)
                    controller = pt
                    for (t in transports) {
                        pt.start(t, "")
                        torrc.append("ClientTransportPlugin $t socks5 127.0.0.1:${pt.port(t)}\n")
                    }
                }
                torrc.append("UseBridges 1\n")
                bridges.forEach { torrc.append("Bridge ").append(withDefaults(it)).append('\n') }
            }
            TorService.getTorrc(this).writeText(torrc.toString())
            File(filesDir, "tor.log").delete()
            bound = bindService(Intent(this, TorService::class.java), connection, Context.BIND_AUTO_CREATE)
            if (!bound) throw IllegalStateException("cannot start TorService")
            Log.i(TAG, "Tor starting: SOCKS $socks, ${bridges.size} bridges (${transports.joinToString().ifEmpty { "direct" }})")
            main.removeCallbacks(poll)
            main.postDelayed(poll, 1_500)
        } catch (e: Exception) {
            Log.e(TAG, "Tor start failed", e)
            report(STATE_ERROR, 0, "${e.javaClass.simpleName}: ${e.message}")
            stopTor()
        }
    }

    private fun stopTor() {
        main.removeCallbacks(poll)
        if (bound) {
            runCatching { unbindService(connection) }
            bound = false
        }
        runCatching { stopService(Intent(this, TorService::class.java)) }
        tor = null
        controller?.let { c -> TRANSPORTS.forEach { runCatching { c.stop(it) } } }
        controller = null
    }

    private val ptEvents = object : OnTransportEvents {
        override fun connected(transport: String?) {
            Log.i(TAG, "transport $transport connected")
        }

        override fun error(transport: String?, e: Exception?) {
            Log.w(TAG, "transport $transport error: ${e?.message}")
            main.post { report(state, progress, "$transport: ${e?.message}") }
        }

        override fun stopped(transport: String?, e: Exception?) {
            Log.i(TAG, "transport $transport stopped${e?.message?.let { ": $it" } ?: ""}")
        }
    }

    private fun builtin(transport: String): List<String> =
        assets.open("bridges_default.txt").bufferedReader().use { r -> r.readLines().filter { it.startsWith("$transport ") } }

    /** Snowflake lines from Tor Browser's defaults carry no rendezvous: the CDN77 one, as InviZible Pro adds. */
    private fun withDefaults(bridge: String): String {
        if (!bridge.startsWith("snowflake ")) return bridge
        val b = StringBuilder(bridge)
        if (" url=" !in bridge) b.append(" url=https://1098762253.rsc.cdn77.org/")
        if (" front=" !in bridge && " fronts=" !in bridge) b.append(" fronts=docs.plesk.com,maxst.icons8.com,app.datapacket.com")
        if (" utls-imitate=" !in bridge) b.append(" utls-imitate=hellorandomizedalpn")
        if (" ice=" !in bridge) {
            val ice = STUN.map { "stun:$it" }.toMutableList()
            // Tor passes the arguments in the SOCKS handshake: at most 510 bytes.
            while (b.length + 5 + ice.joinToString(",").length > 510 && ice.size > 1) ice.removeAt(ice.size - 1)
            b.append(" ice=").append(ice.joinToString(","))
        }
        return b.toString()
    }

    private fun report(newState: String, newProgress: Int, newMessage: String) {
        state = newState
        progress = newProgress
        message = newMessage
        val text = when (newState) {
            STATE_ON -> getString(R.string.notification_on, socks)
            STATE_STARTING -> getString(R.string.notification_bootstrap, newProgress)
            else -> null
        }
        if (text != null) foreground(text)
        val tail = if (newState == STATE_ERROR) logTail() else ""
        replyTo?.let { pkg ->
            sendBroadcast(
                Intent(ACTION_STATUS_BROADCAST).setPackage(pkg)
                    .putExtra(EXTRA_STATE, newState)
                    .putExtra(EXTRA_PROGRESS, newProgress)
                    .putExtra(EXTRA_MESSAGE, newMessage + tail)
                    .putExtra(EXTRA_SOCKS, socks)
                    .putExtra(EXTRA_VERSION, "${BuildConfig.VERSION_NAME} (Tor ${TorService.VERSION_NAME})"),
                controlPermission(),
            )
        }
    }

    private fun logTail(): String = runCatching {
        File(filesDir, "tor.log").readLines().takeLast(5).joinToString("\n", prefix = "\n")
    }.getOrDefault("")

    private fun foreground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel), NotificationManager.IMPORTANCE_LOW))
        }
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tor)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(this, 1, n, if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
    }

    private fun sameSigner(pkg: String) =
        runCatching { packageManager.checkSignatures(packageName, pkg) == PackageManager.SIGNATURE_MATCH }.getOrDefault(false)

    private fun controlPermission(): String =
        packageManager.getServiceInfo(ComponentName(this, TorModuleService::class.java), 0).permission

    companion object {
        private const val TAG = "ByeZapretTor"
        private const val CHANNEL = "tor"

        // The protocol with ByeZapret (core/TorModule.kt there).
        const val ACTION_START = "io.github.zapretdroid.tor.START"
        const val ACTION_STOP = "io.github.zapretdroid.tor.STOP"
        const val ACTION_STATUS = "io.github.zapretdroid.tor.STATUS"
        const val ACTION_STATUS_BROADCAST = "io.github.zapretdroid.TOR_STATUS"
        const val EXTRA_SOCKS_PORT = "socks_port"
        const val EXTRA_BRIDGES = "bridges"
        const val EXTRA_REPLY_TO = "reply_to"
        const val EXTRA_STATE = "state"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_SOCKS = "socks"
        const val EXTRA_VERSION = "version"
        const val STATE_OFF = "OFF"
        const val STATE_STARTING = "STARTING"
        const val STATE_ON = "ON"
        const val STATE_ERROR = "ERROR"

        val TRANSPORTS = listOf(IPtProxy.Obfs4, IPtProxy.MeekLite, IPtProxy.Webtunnel, IPtProxy.Snowflake)
        private val STUN = listOf(
            "stun.nextcloud.com:443", "stun.sipgate.net:10000", "stun.epygi.com:3478", "stun.uls.co.za:3478",
            "stun.voipgate.com:3478", "stun.bethesda.net:3478", "stun.mixvoip.com:3478", "stun.antisip.com:3478",
        )

        @Volatile
        var state = STATE_OFF
            private set

        @Volatile
        var progress = 0
            private set

        @Volatile
        var message = ""
            private set
    }
}
