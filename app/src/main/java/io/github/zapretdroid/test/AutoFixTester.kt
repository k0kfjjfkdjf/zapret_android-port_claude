package io.github.zapretdroid.test

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.zapretdroid.core.AppLog
import io.github.zapretdroid.core.FakePayloads
import io.github.zapretdroid.core.HostLists
import io.github.zapretdroid.core.Settings
import io.github.zapretdroid.core.Strategies
import io.github.zapretdroid.core.TorProxy
import java.net.Inet4Address
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** A way to open a site: a strategy, or a proxy ([proxy] is its name, "" for the main one). */
data class FixCandidate(val label: String, val args: String, val proxy: String? = null)

/** What the auto-fix found for a site: the first candidate that loaded it, else one that got an answer. */
data class FixResult(val host: String, val loaded: FixCandidate?, val answered: FixCandidate?, val tried: Int, val note: String? = null)

/**
 * The auto-fix: sites that did not open in the VPN session (blocks, freezes after 16-20 KB) are
 * tried with strategy after strategy and then through the proxies, in the app process (outside
 * the VPN) with a test engine. One engine run per candidate checks all the sites still without a
 * fix at once. Nothing is changed until the person applies a result (see core.AutoFix).
 */
object AutoFixTester {
    private const val PORT = 10930
    private const val TIMEOUT_MS = 6000
    private const val MAX_HOSTS = 12

    var running by mutableStateOf(false)
        private set
    var progress by mutableStateOf(0 to 0)
        private set
    var current by mutableStateOf("")
        private set
    var results by mutableStateOf(emptyList<FixResult>())
        private set

    @Volatile
    private var cancelled = false

    /** Strategies first (no proxy needed, faster), the proxies last. */
    fun candidates(settings: Settings): List<FixCandidate> {
        val strategies = (Strategies.presets.map { it.args } + EXTRA)
            .distinct()
            .map { FixCandidate(it, it) }
        val proxies = buildList {
            if (TorProxy.isValid(settings.proxyAddress)) add(proxyCandidate("", settings.proxyAddress.trim(), settings.proxyUdp))
            settings.extraProxies.filter { TorProxy.isValid(it.address) }.forEach { add(proxyCandidate(it.name, it.address.trim(), it.udp)) }
        }
        // First without bypass: a site that opens so failed only for a moment.
        return listOf(FixCandidate(NONE, "")) + strategies + proxies
    }

    /** The label of the candidate without bypass. */
    const val NONE = "without bypass"

    private fun proxyCandidate(name: String, address: String, udp: Boolean) = FixCandidate(
        label = "proxy ${name.ifEmpty { address }}",
        args = "-C socks5://$address" + if (udp) " --udp-proxy" else "",
        proxy = name,
    )

    /** Splits and fakes that open sites on many Russian networks, beyond the presets. */
    private val EXTRA = listOf(
        "--disorder 1",
        "--disorder 1 --tlsrec 1+s",
        "--disorder 1 --disorder midsld --tlsrec sniext+1",
        "--oob 3",
        "--split 1 --disorder sniext+1 --tlsrec sniext",
        "--fake -1 --ttl 6 --fake-tls-mod rand,orig --fake-sni www.google.com",
        "--fake -1 --ttl 8 --fake-tls-mod rand,orig --fake-sni www.google.com",
        "--disorder sniext+1 --fake -1 --ttl 6 --fake-tls-mod rand,orig --fake-sni www.google.com",
    )

    fun start(context: Context, settings: Settings, hosts: List<String>) {
        if (running || hosts.isEmpty()) return
        val app = context.applicationContext
        val sites = hosts.distinct().take(MAX_HOSTS)
        val candidates = candidates(settings)
        val dns = TestDns.spec(settings)
        running = true
        cancelled = false
        results = emptyList()
        progress = 0 to candidates.size
        TestService.keepAlive(app)
        thread(name = "auto-fix") {
            try {
                run(app, sites, candidates, dns)
            } catch (e: Throwable) {
                AppLog.e("auto-fix failed", e, source = "test")
            } finally {
                running = false
                current = ""
            }
        }
    }

    fun cancel() {
        cancelled = true
    }

    private fun run(context: Context, hosts: List<String>, candidates: List<FixCandidate>, dns: String) {
        val fakeDir = FakePayloads.install(context)
        val listDir = HostLists.dir(context)
        AppLog.i("auto-fix: ${hosts.size} site(s) ${hosts}, ${candidates.size} candidates", source = "test")
        val addresses = hosts.associateWith { host ->
            runCatching { TestDns.resolve(host, dns).address?.takeIf { it is Inet4Address } }.getOrNull()
        }
        val loaded = mutableMapOf<String, FixCandidate>()
        val answered = mutableMapOf<String, FixCandidate>()
        val tried = mutableMapOf<String, Int>()
        val pool = Executors.newFixedThreadPool(hosts.size.coerceAtMost(6))
        try {
            candidates.forEachIndexed { index, candidate ->
                if (cancelled) return@forEachIndexed
                val left = hosts.filter { it !in loaded && addresses[it] != null }
                if (left.isEmpty()) return@forEachIndexed
                current = candidate.label
                progress = index to candidates.size
                val probes = runCatching {
                    TestEngine.with(candidate.args, PORT, fakeDir, listDir, connLog = false) {
                        pool.invokeAll(left.map { host -> Callable { SiteProbe.probe(PORT, host, addresses[host], TIMEOUT_MS) } }).map { it.get() }
                    }
                }.getOrElse {
                    AppLog.w("auto-fix: ${candidate.label}: ${it.message}", source = "test")
                    return@forEachIndexed
                }
                left.zip(probes).forEach { (host, r) ->
                    tried[host] = (tried[host] ?: 0) + 1
                    if (r.ok) loaded[host] = candidate
                    else if (r.responded && host !in answered) answered[host] = candidate
                }
                val found = left.filter { loaded[it] == candidate }
                if (found.isNotEmpty()) AppLog.i("auto-fix: ${candidate.label} opens ${found}", source = "test")
                results = hosts.map { FixResult(it, loaded[it], answered[it], tried[it] ?: 0, if (addresses[it] == null) "no address" else null) }
            }
        } finally {
            pool.shutdownNow()
        }
        progress = candidates.size to candidates.size
        results = hosts.map { FixResult(it, loaded[it], answered[it], tried[it] ?: 0, if (addresses[it] == null) "no address" else null) }
        AppLog.i(
            "auto-fix done: " + results.joinToString { r -> "${r.host}: ${r.loaded?.label ?: r.answered?.let { "answered with ${it.label}" } ?: "nothing"}" },
            source = "test",
        )
    }
}
