package io.github.zapretdroid.test

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.zapretdroid.core.AppLog
import io.github.zapretdroid.core.FakePayloads
import io.github.zapretdroid.core.HostLists
import io.github.zapretdroid.core.Script
import io.github.zapretdroid.core.ScriptCache
import io.github.zapretdroid.core.Settings
import io.github.zapretdroid.service.VpnStatus
import io.github.zapretdroid.service.ZapretVpnService
import java.net.Inet4Address
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * The script's @try search: in the app's main process (the VPN's engine runs in its own), each
 * variant of a block is tried on its sites with a test engine outside the VPN; the first that
 * loads every site wins, else the one that did best. The winner goes back to the VPN service,
 * which keeps it per network type and moves the sites to its slot while the engine runs.
 */
object ScriptSearch {
    private const val PORT = 10940
    private const val TIMEOUT_MS = 6000

    var running by mutableStateOf(false)
        private set
    var current by mutableStateOf("")
        private set

    /** A block as it travels in an Intent: name, sites, variants. */
    fun encode(b: Script.TryBlock) =
        b.name + "\t" + b.sites.joinToString(",") + "\t" + b.variants.joinToString("\u0001") + "\t" + b.slots.joinToString("\u0001")

    private fun decode(s: String): Script.TryBlock? {
        val p = s.split('\t')
        if (p.size != 4) return null
        return Script.TryBlock(p[0], Script.TryKind.TRY, p[1].split(',').filter { it.isNotEmpty() }, p[2].split('\u0001'), p[3].split('\u0001'))
    }

    fun start(context: Context, network: String, blocks: List<Script.TryBlock>) {
        if (running || blocks.isEmpty()) return
        val app = context.applicationContext
        running = true
        TestService.keepAlive(app)
        thread(name = "script-search") {
            try {
                blocks.forEach { run(app, network, it) }
            } catch (e: Throwable) {
                AppLog.e("script search failed", e, source = "test")
            } finally {
                running = false
                current = ""
            }
        }
    }

    private fun run(context: Context, network: String, block: Script.TryBlock) {
        val fakeDir = FakePayloads.install(context)
        val listDir = HostLists.dir(context)
        val dns = TestDns.spec(Settings(context))
        val addresses = block.sites.associateWith { host ->
            runCatching { TestDns.resolve(host, dns).address?.takeIf { it is Inet4Address } }.getOrNull()
        }
        val sites = block.sites.filter { addresses[it] != null }
        if (sites.isEmpty()) {
            AppLog.w("script search ${block.name}: no address for ${block.sites}", source = "test")
            return
        }
        AppLog.i("script search ${block.name} ($network): ${block.variants.size} variants on $sites", source = "test")
        var best = 0
        var bestScore = -1
        val pool = Executors.newFixedThreadPool(sites.size.coerceAtMost(6))
        try {
            for ((i, variant) in block.variants.withIndex()) {
                current = "${block.name}: ${i + 1}/${block.variants.size}"
                val attempt = runCatching {
                    TestEngine.with(variant, PORT, fakeDir, listDir, connLog = false) {
                        pool.invokeAll(sites.map { host -> Callable { SiteProbe.probe(PORT, host, addresses[host], TIMEOUT_MS) } }).map { it.get() }
                    }
                }
                attempt.exceptionOrNull()?.let { AppLog.w("script search ${block.name} variant ${i + 1}: ${it.message}", source = "test") }
                val probes = attempt.getOrNull() ?: continue
                val score = probes.sumOf { (if (it.ok) 2 else 0) + (if (it.responded) 1 else 0) }
                AppLog.i("script search ${block.name} variant ${i + 1} [$variant]: " + probes.joinToString { "${it.host} ${if (it.ok) "loaded" else if (it.responded) "answered" else "failed"}" }, source = "test")
                if (score > bestScore) {
                    best = i
                    bestScore = score
                }
                if (probes.all { it.ok }) break
            }
        } finally {
            pool.shutdownNow()
        }
        if (bestScore <= 0) {
            AppLog.w("script search ${block.name}: no variant got an answer; keeping the current one", source = "test")
            return
        }
        AppLog.i("script search ${block.name}: variant ${best + 1} wins", source = "test")
        if (VpnStatus.isServiceRunning(context)) {
            runCatching {
                context.startService(
                    Intent(context, ZapretVpnService::class.java).setAction(ZapretVpnService.ACTION_SCRIPT_RESULT)
                        .putExtra("network", network).putExtra("name", block.name)
                        .putExtra("index", best).putExtra("hash", ScriptCache.hash(block)),
                )
            }.onFailure { AppLog.w("script search: cannot reach the VPN: ${it.message}", source = "test") }
        }
    }

    /** Started by the VPN service: runs the search in this (the main) process. */
    class Request : Service() {
        override fun onBind(intent: Intent?): IBinder? = null

        override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            val network = intent?.getStringExtra("network") ?: "other"
            val blocks = intent?.getStringArrayListExtra("blocks").orEmpty().mapNotNull(::decode)
            start(this, network, blocks)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        companion object {
            fun send(context: Context, network: String, blocks: List<Script.TryBlock>) {
                runCatching {
                    context.startService(
                        Intent(context, Request::class.java).putExtra("network", network)
                            .putStringArrayListExtra("blocks", ArrayList(blocks.map(::encode))),
                    )
                }.onFailure { AppLog.w("script search not started: ${it.message}") }
            }
        }
    }
}
