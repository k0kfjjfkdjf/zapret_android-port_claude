package io.github.zapretdroid.test

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.zapretdroid.core.AppLog
import io.github.zapretdroid.core.FakePayloads
import io.github.zapretdroid.core.HostLists
import io.github.zapretdroid.core.NativeLog
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.BitSet
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

/** Settings of a deep check run. */
data class DeepConfig(
    val domains: List<String>,
    val strategies: List<String>,
    val timeoutMs: Int = 5000,
    val attempts: Int = 1,
    val parallel: Int = 16,
    val delayMs: Int = 0,
    val tls: TlsMode = TlsMode.ANY,
    val dns: String,
    val sources: String = "",
)

/** One strategy's outcome: which domains it opened on every attempt, and why the others failed. */
class DeepResult(val index: Int, val args: String, val opened: BitSet, val reasons: Map<Char, Int>) {
    val count get() = opened.cardinality()
}

/**
 * Long strategy check without stages: every strategy against every domain of the chosen lists,
 * with the tester's probes. Results go to disk as they come (results.tsv, then matrix.csv:
 * domain x strategy, one reason code per attempt), so a run over thousands of domains can take
 * hours without holding every probe in memory. A domain counts as opened by a strategy only if
 * all its attempts succeed. The data is what the multi-strategy builder works from.
 */
object DeepCheck {
    var running by mutableStateOf(false)
        private set
    var strategyProgress by mutableStateOf(0 to 0)
        private set
    var domainProgress by mutableStateOf(0 to 0)
        private set
    var currentArgs by mutableStateOf("")
        private set
    var startedAt by mutableStateOf(0L)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    /** Domains actually tested (resolved), in matrix order. */
    var domains by mutableStateOf(emptyList<String>())
        private set
    var baseline by mutableStateOf<DeepResult?>(null)
        private set
    var results by mutableStateOf(emptyList<DeepResult>())
        private set
    var runDir by mutableStateOf<File?>(null)
        private set

    @Volatile
    private var cancelled = false

    private const val PORT = 10930
    private const val READ_LIMIT = 32 * 1024

    fun cancel() {
        AppLog.i("deep check: stop requested", source = "test")
        cancelled = true
    }

    fun start(context: Context, config: DeepConfig) {
        if (running) return
        val app = context.applicationContext
        running = true
        cancelled = false
        message = null
        baseline = null
        results = emptyList()
        domains = emptyList()
        startedAt = System.currentTimeMillis()
        strategyProgress = 0 to config.strategies.size + 1
        domainProgress = 0 to config.domains.size
        TestService.keepAlive(app)
        thread(name = "deep-check") {
            try {
                run(app, config)
            } catch (e: Throwable) {
                AppLog.e("deep check failed", e, source = "test")
                message = e.message ?: e.toString()
            } finally {
                currentArgs = ""
                running = false
            }
        }
    }

    private fun run(context: Context, config: DeepConfig) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = File(context.filesDir, "deep/$stamp").apply { mkdirs() }
        runDir = dir
        NativeLog.start(AppLog.file.absolutePath, 8L * 1024 * 1024)
        AppLog.i(
            "deep check: ${config.domains.size} domains, ${config.strategies.size} strategies, ${config.attempts} attempt(s), " +
                "timeout ${config.timeoutMs} ms, ${config.parallel} parallel, delay ${config.delayMs} ms, TLS ${config.tls}, dir ${dir.path}",
            source = "test",
        )
        File(dir, "config.txt").writeText(
            buildString {
                appendLine("sources: ${config.sources}")
                appendLine("domains: ${config.domains.size}, strategies: ${config.strategies.size}, attempts: ${config.attempts}")
                appendLine("timeout: ${config.timeoutMs} ms, parallel: ${config.parallel}, delay: ${config.delayMs} ms, TLS: ${config.tls}, DNS: ${config.dns}")
                appendLine("body read limit: ${READ_LIMIT / 1024} KB")
            },
        )
        val all = listOf("") + config.strategies
        File(dir, "strategies.txt").writeText(all.withIndex().joinToString("\n", postfix = "\n") { (i, s) -> "$i\t${s.ifEmpty { "(without bypass)" }}" })

        val pool = Executors.newFixedThreadPool(config.parallel.coerceIn(1, 64))
        try {
            val addresses = resolve(config, pool)
            val tested = config.domains.filter { addresses[it] != null }
            val unresolved = config.domains.filter { addresses[it] == null }
            domains = tested
            File(dir, "domains.txt").writeText(tested.joinToString("\n", postfix = "\n"))
            if (unresolved.isNotEmpty()) File(dir, "unresolved.txt").writeText(unresolved.joinToString("\n", postfix = "\n"))
            AppLog.i("deep check: ${tested.size} domains resolved, ${unresolved.size} not (left out)", source = "test")
            val fakeDir = FakePayloads.install(context)
            val listDir = HostLists.dir(context)
            File(dir, "results.tsv").bufferedWriter().use { out ->
                for ((index, args) in all.withIndex()) {
                    if (cancelled) break
                    if (index > 0 && config.delayMs > 0) Thread.sleep(config.delayMs.toLong())
                    val result = test(index, args, tested, addresses, config, pool, fakeDir, listDir, out)
                    if (index == 0) baseline = result
                    else results = (results + result).sortedByDescending { it.count }
                    AppLog.i("deep check: ${result.count}/${tested.size} opened by ${args.ifEmpty { "(without bypass)" }}" +
                        if (result.reasons.isEmpty()) "" else ", failures ${result.reasons}", source = "test")
                    strategyProgress = index + 1 to all.size
                }
            }
            writeMatrix(dir, tested, all.size)
            writeSummary(dir, tested)
        } finally {
            pool.shutdownNow()
        }
        AppLog.i("deep check: ${if (cancelled) "cancelled" else "finished"}, results in ${dir.path}", source = "test")
    }

    private fun resolve(config: DeepConfig, pool: java.util.concurrent.ExecutorService): Map<String, InetAddress?> {
        currentArgs = "DNS"
        val server = runCatching { InetAddress.getByName(config.dns) }.getOrNull()
        val done = AtomicInteger()
        val tasks = config.domains.map { spec ->
            Callable {
                val host = ProbeTarget.parse(spec).host
                val a = server?.let { s -> runCatching { DnsClient.resolve(host, s).firstOrNull() }.getOrNull() }
                    ?: runCatching { InetAddress.getAllByName(host).firstOrNull { it is Inet4Address } }.getOrNull()
                domainProgress = done.incrementAndGet() to config.domains.size
                spec to a
            }
        }
        return pool.invokeAll(tasks).associate { it.get() }
    }

    private fun test(
        index: Int,
        args: String,
        tested: List<String>,
        addresses: Map<String, InetAddress?>,
        config: DeepConfig,
        pool: java.util.concurrent.ExecutorService,
        fakeDir: File,
        listDir: File,
        out: java.io.Writer,
    ): DeepResult {
        currentArgs = args.ifEmpty { "(without bypass)" }
        domainProgress = 0 to tested.size
        val opened = BitSet(tested.size)
        val reasons = HashMap<Char, Int>()
        val codes = arrayOfNulls<String>(tested.size)
        try {
            TestEngine.with(args, PORT, fakeDir, listDir, connLog = false) {
                val done = AtomicInteger()
                val tasks = tested.withIndex().map { (i, spec) ->
                    Callable {
                        val code = StringBuilder()
                        repeat(config.attempts) {
                            if (cancelled) return@repeat
                            val r = SiteProbe.probe(PORT, spec, addresses[spec], config.timeoutMs, config.tls, READ_LIMIT)
                            code.append(r.reason)
                        }
                        codes[i] = code.toString()
                        domainProgress = done.incrementAndGet() to tested.size
                    }
                }
                pool.invokeAll(tasks)
            }
        } catch (e: TestEngine.Failed) {
            AppLog.w("deep check: engine did not start for '$args' (code ${e.exitCode})", source = "test")
            codes.fill("p".repeat(config.attempts))
        }
        tested.indices.forEach { i ->
            val code = codes[i].orEmpty()
            if (code.length == config.attempts && code.all { it == '+' }) opened.set(i)
            code.filter { it != '+' }.forEach { c -> reasons[c] = (reasons[c] ?: 0) + 1 }
            out.write("$index\t${tested[i]}\t$code\n")
        }
        out.flush()
        return DeepResult(index, args, opened, reasons)
    }

    /** Domain rows, strategy columns (s0 is without bypass), cells are reason codes per attempt. */
    private fun writeMatrix(dir: File, tested: List<String>, strategies: Int) {
        val cells = HashMap<String, Array<String>>()
        File(dir, "results.tsv").forEachLine { line ->
            val parts = line.split('\t')
            if (parts.size == 3) {
                val row = cells.getOrPut(parts[1]) { Array(strategies) { "" } }
                parts[0].toIntOrNull()?.takeIf { it < strategies }?.let { row[it] = parts[2] }
            }
        }
        File(dir, "matrix.csv").bufferedWriter().use { out ->
            out.write("domain;" + (0 until strategies).joinToString(";") { "s$it" } + "\n")
            tested.forEach { d -> out.write(d + ";" + (cells[d] ?: Array(strategies) { "" }).joinToString(";") + "\n") }
        }
    }

    private fun writeSummary(dir: File, tested: List<String>) {
        val ranked = results
        val base = baseline
        File(dir, "summary.txt").bufferedWriter().use { out ->
            out.appendLine("ZapretDroid deep check, ${tested.size} domains" + if (cancelled) " (stopped early)" else "")
            out.appendLine("codes: + opened, r reset, t timeout, f freeze or short body, c no TCP, m wrong certificate, p test proxy, x other")
            base?.let { out.appendLine("without bypass: ${it.count}/${tested.size}, failures ${it.reasons}") }
            out.appendLine()
            ranked.forEach { r -> out.appendLine("s${r.index}: ${r.count}/${tested.size}, failures ${r.reasons}: ${r.args}") }
            val union = BitSet().apply { ranked.forEach { or(it.opened) } }
            val never = tested.indices.filter { !union.get(it) && base?.opened?.get(it) != true }
            out.appendLine()
            out.appendLine("opened by at least one strategy: ${union.cardinality()}/${tested.size}")
            out.appendLine("not opened by any strategy (${never.size}), possibly dead or blocked by IP:")
            never.take(500).forEach { out.appendLine("  ${tested[it]}") }
        }
    }

    /** Packs the run's files into one zip for sharing. */
    fun zip(context: Context): File? {
        val dir = runDir ?: return null
        val zip = File(context.cacheDir, "deep-${dir.name}.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            dir.listFiles().orEmpty().filter { it.isFile }.forEach { f ->
                z.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        return zip
    }
}
