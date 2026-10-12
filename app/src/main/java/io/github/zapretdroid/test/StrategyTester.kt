package io.github.zapretdroid.test

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.zapretdroid.core.AppLog
import io.github.zapretdroid.core.ArgTemplates
import io.github.zapretdroid.core.ByeDpi
import io.github.zapretdroid.core.FakePayloads
import io.github.zapretdroid.core.HostLists
import io.github.zapretdroid.core.NativeLog
import io.github.zapretdroid.core.splitArgs
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.concurrent.thread

data class StrategyResult(val args: String, val probes: List<ProbeResult>) {
    /** Successful requests out of all (sites x attempts). */
    val passed get() = probes.count { it.ok }
    val averageMillis get() = probes.filter { it.ok }.map { it.totalMs }.average().takeIf { !it.isNaN() }?.toLong() ?: 0L

    /** Per site: its attempts, in the order the sites were given. */
    val bySite: Map<String, List<ProbeResult>> get() = probes.groupBy { it.host }
}

/**
 * Tries strategies one by one against a list of sites, the way zapret's blockcheck does, but
 * through the ByeDPI engine as a local SOCKS proxy in the app process. The app is excluded from
 * the VPN, so the tests reach the network directly even while the VPN is on.
 *
 * State is held in Compose snapshot state, so the screen follows the run and the run survives
 * leaving the screen.
 */
object StrategyTester {
    var running by mutableStateOf(false)
        private set
    var progress by mutableStateOf(0 to 0)
        private set
    var currentArgs by mutableStateOf("")
        private set
    var baseline by mutableStateOf<StrategyResult?>(null)
        private set
    var results by mutableStateOf(emptyList<StrategyResult>())
        private set
    var message by mutableStateOf<String?>(null)
        private set

    @Volatile
    private var cancelled = false

    private const val PORT = 10900
    private const val TIMEOUT_MS = 5000
    private const val PARALLEL = 8
    private const val TEST_LOG_LIMIT = 8L * 1024 * 1024

    fun start(context: Context, hosts: List<String>, candidates: List<String>, dns: String, attempts: Int = 1) {
        if (running) return
        val app = context.applicationContext
        running = true
        cancelled = false
        baseline = null
        results = emptyList()
        message = null
        progress = 0 to candidates.size + 1
        thread(name = "strategy-test") {
            try {
                run(app, hosts, candidates, dns, attempts.coerceIn(1, 5))
            } catch (e: Throwable) {
                AppLog.e("strategy test failed", e, source = "test")
                message = e.message ?: e.toString()
            } finally {
                running = false
                currentArgs = ""
            }
        }
    }

    fun cancel() {
        AppLog.i("strategy test: stop requested", source = "test")
        cancelled = true
    }

    private fun run(context: Context, hosts: List<String>, candidates: List<String>, dns: String, attempts: Int) {
        AppLog.i(
            "strategy test: ${candidates.size} strategies, $attempts attempt(s) per site, timeout ${TIMEOUT_MS} ms, " +
                "DNS $dns, sites: ${hosts.joinToString()}",
            source = "test",
        )
        // Engine output of the test proxy (connections, groups, detected blocks) goes to the journal too.
        if (NativeLog.start(AppLog.file.absolutePath, TEST_LOG_LIMIT) == null) {
            AppLog.w("strategy test: engine output is not captured", source = "test")
        }
        val addresses = resolveAll(hosts, dns)
        val fakeDir = FakePayloads.install(context)
        val listDir = HostLists.dir(context)
        val pool = Executors.newFixedThreadPool(PARALLEL)
        try {
            val base = test("", hosts, addresses, attempts, pool, fakeDir, listDir)
            baseline = base
            AppLog.i("strategy test: result without bypass ${base.passed}/${base.probes.size}", source = "test")
            progress = 1 to candidates.size + 1
            for ((index, candidate) in candidates.withIndex()) {
                if (cancelled) break
                val result = test(candidate, hosts, addresses, attempts, pool, fakeDir, listDir)
                results = (results + result).sortedWith(compareByDescending<StrategyResult> { it.passed }.thenBy { it.averageMillis })
                AppLog.i("strategy test: result ${result.passed}/${result.probes.size} for $candidate", source = "test")
                progress = index + 2 to candidates.size + 1
            }
        } finally {
            pool.shutdownNow()
        }
        AppLog.i("strategy test: ${if (cancelled) "cancelled" else "finished"}", source = "test")
    }

    /** Resolves names through the configured DNS server; falls back to the system resolver. */
    private fun resolveAll(hosts: List<String>, dns: String): Map<String, InetAddress?> {
        val server = runCatching { InetAddress.getByName(dns) }.getOrNull()
        return hosts.associateWith { host ->
            val viaServer = server?.let { s ->
                runCatching { DnsClient.resolve(host, s).firstOrNull() }
                    .onFailure { AppLog.w("strategy test: DNS $dns failed for $host: ${it.message}", source = "test") }
                    .getOrNull()
            }
            viaServer ?: runCatching { InetAddress.getAllByName(host).firstOrNull { it is Inet4Address } }.getOrNull()
        }.also { map -> AppLog.i("strategy test: resolved " + map.entries.joinToString { "${it.key}=${it.value?.hostAddress}" }, source = "test") }
    }

    private fun test(
        candidate: String,
        hosts: List<String>,
        addresses: Map<String, InetAddress?>,
        attempts: Int,
        pool: java.util.concurrent.ExecutorService,
        fakeDir: java.io.File,
        listDir: java.io.File,
    ): StrategyResult {
        currentArgs = candidate
        AppLog.i("strategy test: --- ${candidate.ifEmpty { "without bypass" }} ---", source = "test")
        val args = listOf("ciadpi", "--ip", "127.0.0.1", "--port", PORT.toString(), "--conn-log") +
            ArgTemplates.resolve(splitArgs(candidate), fakeDir, listDir)
        val engine = ByeDpi()
        var exitCode = Int.MIN_VALUE
        val engineThread = thread(name = "test-engine") { exitCode = engine.startProxy(args.toTypedArray()) }
        try {
            if (!awaitListening(engineThread)) {
                AppLog.e("strategy test: engine did not start for '$candidate' (code $exitCode)", source = "test")
                return StrategyResult(candidate, hosts.map {
                    ProbeResult(it, null, false, "engine did not start (code $exitCode)", ProbeStage.PROXY, totalMs = 0)
                })
            }
            val probes = mutableListOf<ProbeResult>()
            repeat(attempts) { attempt ->
                val tasks = hosts.map { host ->
                    Callable { SiteProbe.probe(PORT, host, addresses[host], TIMEOUT_MS) }
                }
                pool.invokeAll(tasks).map { it.get() }.forEach { probe ->
                    val prefix = if (attempts > 1) "attempt ${attempt + 1}: " else ""
                    if (probe.ok) AppLog.i("strategy test: $prefix${probe.summary()}", source = "test")
                    else AppLog.w("strategy test: $prefix${probe.summary()}", source = "test")
                    probes += probe
                }
            }
            return StrategyResult(candidate, probes)
        } finally {
            engine.stopProxy()
            engineThread.join(3000)
        }
    }

    private fun awaitListening(engineThread: Thread): Boolean {
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline && engineThread.isAlive) {
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", PORT), 200) }
                return true
            } catch (_: Exception) {
                Thread.sleep(30)
            }
        }
        return false
    }
}
