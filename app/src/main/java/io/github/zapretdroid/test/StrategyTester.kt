package io.github.zapretdroid.test

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.zapretdroid.core.AppLog
import io.github.zapretdroid.core.FakePayloads
import io.github.zapretdroid.core.HostLists
import io.github.zapretdroid.core.NativeLog
import java.net.Inet4Address
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.concurrent.thread

data class StrategyResult(val args: String, val probes: List<ProbeResult>) {
    /** Successful requests out of all (sites x attempts). */
    val passed get() = probes.count { it.ok }
    val averageMillis get() = probes.filter { it.ok }.map { it.totalMs }.average().takeIf { !it.isNaN() }?.toLong() ?: 0L

    /** Per site: its attempts, in the order the sites were given. */
    val bySite: Map<String, List<ProbeResult>> get() = probes.groupBy { it.host }

    /** Failure reasons and how often they happened, e.g. "r×3 f×1". */
    val reasons: String
        get() = probes.filter { !it.ok }.groupingBy { it.reason }.eachCount().entries
            .sortedByDescending { it.value }.joinToString(" ") { "${it.key}×${it.value}" }
}

/**
 * Tries strategies one by one against a list of sites, the way zapret's blockcheck does, but
 * through the ByeDPI engine as a local SOCKS proxy in the app process. The app is excluded from
 * the VPN, so the tests reach the network directly even while the VPN is on. In UDP mode the
 * sites are checked over QUIC and STUN servers with a Binding request (see [UdpProbe]).
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

    /** Report of the last run: settings, every probe and the ranking. */
    var report by mutableStateOf<java.io.File?>(null)
        private set
    private val reportLines = mutableListOf<String>()

    @Volatile
    private var cancelled = false

    private const val PORT = 10900
    private const val TIMEOUT_MS = 5000
    private const val PARALLEL = 8
    private const val TEST_LOG_LIMIT = 8L * 1024 * 1024

    fun start(
        context: Context,
        hosts: List<String>,
        candidates: List<String>,
        dns: String,
        attempts: Int = 1,
        udp: Boolean = false,
        tls: List<TlsMode> = listOf(TlsMode.ANY),
    ) {
        if (running) return
        val app = context.applicationContext
        running = true
        cancelled = false
        baseline = null
        results = emptyList()
        message = null
        report = null
        synchronized(reportLines) { reportLines.clear() }
        progress = 0 to candidates.size + 1
        TestService.keepAlive(app)
        thread(name = "strategy-test") {
            try {
                run(app, hosts, candidates, dns, attempts.coerceIn(1, 5), udp, tls.ifEmpty { listOf(TlsMode.ANY) })
            } catch (e: Throwable) {
                AppLog.e("strategy test failed", e, source = "test")
                note("ERROR: $e")
                message = e.message ?: e.toString()
            } finally {
                writeReport(app)
                running = false
                currentArgs = ""
            }
        }
    }

    fun cancel() {
        AppLog.i("strategy test: stop requested", source = "test")
        cancelled = true
    }

    /** A line for both the journal and the run report. */
    private fun log(line: String, warn: Boolean = false) {
        if (warn) AppLog.w("strategy test: $line", source = "test") else AppLog.i("strategy test: $line", source = "test")
        note(line)
    }

    private fun note(line: String) {
        val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        synchronized(reportLines) { reportLines += "$stamp $line" }
    }

    private fun run(context: Context, hosts: List<String>, candidates: List<String>, dns: String, attempts: Int, udp: Boolean, tls: List<TlsMode>) {
        val udpTargets = if (udp) TestPlan.udpTargets(hosts) else emptyList()
        log(
            "${if (udp) "UDP" else "TCP"} mode, ${candidates.size} strategies, $attempts attempt(s) per target, " +
                "timeout ${TIMEOUT_MS} ms, DNS $dns, " +
                if (udp) "targets: ${udpTargets.joinToString { it.label }}" else "TLS: ${tls.joinToString { it.name }}, sites: ${hosts.joinToString()}",
        )
        log("reason codes: + opened, r reset, t timeout, f freeze or short body, c no TCP, m wrong certificate, p test proxy, x other")
        // Engine output of the test proxy (connections, groups, detected blocks) goes to the journal too.
        if (NativeLog.start(AppLog.file.absolutePath, TEST_LOG_LIMIT) == null) {
            AppLog.w("strategy test: engine output is not captured", source = "test")
        }
        val addresses = resolveAll((hosts.map { ProbeTarget.parse(it).host } + udpTargets.map { it.host }).distinct(), dns)
        val fakeDir = FakePayloads.install(context)
        val listDir = HostLists.dir(context)
        val pool = Executors.newFixedThreadPool(PARALLEL)
        try {
            val base = test("", hosts, tls, udpTargets, addresses, attempts, pool, fakeDir, listDir)
            baseline = base
            log("result without bypass ${base.passed}/${base.probes.size}")
            progress = 1 to candidates.size + 1
            for ((index, candidate) in candidates.withIndex()) {
                if (cancelled) break
                val result = test(candidate, hosts, tls, udpTargets, addresses, attempts, pool, fakeDir, listDir)
                results = (results + result).sortedWith(compareByDescending<StrategyResult> { it.passed }.thenBy { it.averageMillis })
                log("result ${result.passed}/${result.probes.size} for $candidate")
                progress = index + 2 to candidates.size + 1
            }
        } finally {
            pool.shutdownNow()
        }
        log(if (cancelled) "cancelled" else "finished")
    }

    /** Writes the run report next to the journal: the log of this run and the final ranking. */
    private fun writeReport(context: Context) {
        runCatching {
            val file = java.io.File(AppLog.file.parentFile, "test-report.txt")
            file.bufferedWriter().use { out ->
                out.appendLine("ZapretDroid strategy test, " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                out.appendLine("Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}), ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                out.appendLine()
                out.appendLine("Codes per target, one per attempt: + opened, r reset, t timeout, f freeze or short body,")
                out.appendLine("c no TCP connection, m wrong certificate, p test proxy, x other")
                out.appendLine()
                out.appendLine("== Ranking ==")
                (listOfNotNull(baseline) + results).forEach { r ->
                    out.appendLine("${r.passed}/${r.probes.size}, avg ${r.averageMillis} ms" +
                        (if (r.reasons.isEmpty()) "" else ", ${r.reasons}") + ": ${r.args.ifEmpty { "(without bypass)" }}")
                    r.bySite.forEach { (target, probes) ->
                        out.appendLine("    ${probes.joinToString("") { it.reason.toString() }}  $target")
                    }
                }
                out.appendLine()
                out.appendLine("== Log ==")
                synchronized(reportLines) { reportLines.forEach { out.appendLine(it) } }
            }
            report = file
        }.onFailure { AppLog.e("strategy test: report not written", it, source = "test") }
    }

    /** Resolves names through the configured DNS server; falls back to the system resolver. */
    private fun resolveAll(hosts: List<String>, dns: String): Map<String, InetAddress?> {
        val server = runCatching { InetAddress.getByName(dns) }.getOrNull()
        return hosts.associateWith { host ->
            val viaServer = server?.let { s ->
                runCatching { DnsClient.resolve(host, s).firstOrNull() }
                    .onFailure { log("DNS $dns failed for $host: ${it.message}", warn = true) }
                    .getOrNull()
            }
            viaServer ?: runCatching { InetAddress.getAllByName(host).firstOrNull { it is Inet4Address } }.getOrNull()
        }.also { map -> log("resolved " + map.entries.joinToString { "${it.key}=${it.value?.hostAddress}" }) }
    }

    private fun test(
        candidate: String,
        hosts: List<String>,
        tls: List<TlsMode>,
        udpTargets: List<UdpTarget>,
        addresses: Map<String, InetAddress?>,
        attempts: Int,
        pool: java.util.concurrent.ExecutorService,
        fakeDir: java.io.File,
        listDir: java.io.File,
    ): StrategyResult {
        currentArgs = candidate
        log("--- ${candidate.ifEmpty { "without bypass" }} ---")
        val names = if (udpTargets.isEmpty()) hosts.flatMap { h -> tls.map { h + if (it == TlsMode.ANY) "" else " [${it.label}]" } } else udpTargets.map { it.label }
        return try {
            TestEngine.with(candidate, PORT, fakeDir, listDir) {
                val probes = mutableListOf<ProbeResult>()
                repeat(attempts) { attempt ->
                    val tasks = if (udpTargets.isEmpty()) {
                        hosts.flatMap { spec ->
                            val address = addresses[ProbeTarget.parse(spec).host]
                            tls.map { mode -> Callable { SiteProbe.probe(PORT, spec, address, TIMEOUT_MS, mode) } }
                        }
                    } else {
                        udpTargets.map { target -> Callable { UdpProbe.probe(PORT, target, addresses[target.host], TIMEOUT_MS) } }
                    }
                    pool.invokeAll(tasks).map { it.get() }.forEach { probe ->
                        val prefix = if (attempts > 1) "attempt ${attempt + 1}: " else ""
                        log(prefix + probe.summary(), warn = !probe.ok)
                        probes += probe
                    }
                }
                StrategyResult(candidate, probes)
            }
        } catch (e: TestEngine.Failed) {
            log("engine did not start for '$candidate' (code ${e.exitCode})", warn = true)
            StrategyResult(candidate, names.map {
                ProbeResult(it, null, false, e.message!!, ProbeStage.PROXY, totalMs = 0)
            })
        }
    }
}
