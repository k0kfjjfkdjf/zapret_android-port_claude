package io.github.zapretdroid.desktop

import io.github.zapretdroid.core.ArgTemplates
import io.github.zapretdroid.core.EngineCommand
import io.github.zapretdroid.core.PresetFile
import io.github.zapretdroid.core.Script
import io.github.zapretdroid.test.ProbeResult
import io.github.zapretdroid.test.SiteProbe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** A preset for the picker: the file's command line with the app's Russian names. */
data class Preset(val id: String, val title: String, val about: String, val args: String)

object Presets {
    private val NAMES = mapOf(
        "auto" to ("Адаптивная" to "Disorder; если соединение похоже на заблокированное — разбиение TLS-записей, затем фейки с SNI google.com"),
        "disorder" to ("Disorder" to "Отправляет первый байт не по порядку"),
        "split_sni" to ("Разбиение по SNI" to "Разбивает запрос внутри имени домена"),
        "oob_sni" to ("OOB в SNI" to "Вставляет внеполосный байт в имя домена"),
        "tlsrec" to ("TLS-записи" to "Делит ClientHello на две TLS-записи, при сбое — disorder"),
        "fake" to ("Фейковый пакет" to "Сначала обманка с низким TTL; TTL может понадобиться подобрать"),
        "quic" to ("TCP + QUIC/UDP" to "Disorder и TLS-записи для TCP; фейковый QUIC Initial перед датаграммами UDP 443"),
        "discord" to ("Discord и звонки" to "TCP как выше; для UDP — фейки под протокол потока (голос Discord, STUN, QUIC)"),
        "discord_termux" to ("Discord (как в zapret-termux)" to "Разрез ClientHello в 7 точках с disorder и OOB; UDP-фейки на голосовых портах"),
        "fake_google" to ("Фейковый ClientHello (google.com)" to "Обманка с настоящим ClientHello google.com, TTL 8"),
        "tpws" to ("В стиле tpws (zapret)" to "HTTP: разбиение после метода, hostcase, hostdot; TLS: midsld и TLS-запись на SNI"),
        "byebyedpi" to ("Как в ByeByeDPI" to "Командная строка ByeByeDPI по умолчанию"),
    )

    fun load(): List<Preset> = runCatching { PresetFile.parse(Paths.presets.readText()) }.getOrDefault(emptyList()).map { e ->
        val (title, about) = NAMES[e.id] ?: ((e.name ?: e.id) to (e.about ?: ""))
        Preset(e.id, e.name ?: title, e.about ?: about, e.args)
    }
}

/** The references of a strategy on this machine: proxies from the settings, Telegram's ranges. */
class DesktopRefs(private val proxies: () -> List<Proxy>) : ArgTemplates.Refs {
    override fun uid(pkg: String): Int? = null
    override fun proxy(name: String): String? = proxies().firstOrNull { it.name == name }?.address?.trim()?.takeIf { it.isNotEmpty() }?.let { "socks5://$it" }
    override fun ipset(name: String): String? = if (name == "telegram") Paths.telegramIps.absolutePath else null
}

fun buildCommand(text: String, network: String): EngineCommand.Built =
    EngineCommand.build(text, Script.Env(network), Paths.fakeDir, Paths.listDir)

/** The engine as a child process: a SOCKS5 proxy on 127.0.0.1:[port]. */
class EngineRunner {
    sealed class State {
        object Stopped : State()
        data class Running(val port: Int, val args: List<String>) : State()
        data class Failed(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Stopped)
    val state: StateFlow<State> = _state

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    @Volatile private var process: Process? = null

    fun append(line: String) {
        val l = _log.value
        _log.value = (if (l.size >= MAX_LINES) l.drop(l.size - MAX_LINES + 1) else l) + line
    }

    fun clearLog() {
        _log.value = emptyList()
    }

    @Synchronized
    fun start(engine: File, port: Int, args: List<String>) {
        stop()
        if (!portFree(port)) {
            _state.value = State.Failed("порт $port уже занят: другая программа или ещё один запуск")
            return
        }
        val cmd = listOf(engine.absolutePath, "-i", "127.0.0.1", "-p", port.toString(), "--conn-log") + args
        append("$ ${cmd.joinToString(" ")}")
        val p = try {
            ProcessBuilder(cmd).redirectErrorStream(true).start()
        } catch (e: Exception) {
            _state.value = State.Failed("не запустился: ${e.message}")
            return
        }
        process = p
        _state.value = State.Running(port, args)
        thread(name = "engine-log", isDaemon = true) {
            p.inputStream.bufferedReader().forEachLine { append(it) }
            val code = p.waitFor()
            synchronized(this) {
                if (process === p) {
                    process = null
                    _state.value = State.Failed("движок завершился с кодом $code — подробности в журнале")
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        val p = process ?: return
        process = null
        p.destroy()
        if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
        append("— остановлен —")
        _state.value = State.Stopped
    }

    companion object {
        private const val MAX_LINES = 3000

        fun portFree(port: Int): Boolean = runCatching { ServerSocket(port, 1, java.net.InetAddress.getByName("127.0.0.1")).close() }.isSuccess
    }
}

/** One row of the check: the site without bypass and through the strategy. */
data class CheckRow(val host: String, val direct: ProbeResult? = null, val bypass: ProbeResult? = null)

object Checker {
    val DEFAULT_TARGETS = listOf(
        "www.google.com", "www.youtube.com", "youtubei.googleapis.com", "redirector.googlevideo.com",
        "discord.com", "gateway.discord.gg", "cdn.discordapp.com", "media.discordapp.net",
    )

    /**
     * Probes every target through an engine without desync (the network as it is) and through one
     * with [args]; [update] gets the rows as they fill. Blocking: call off the UI thread.
     */
    fun run(engine: File, args: List<String>, targets: List<String>, log: (String) -> Unit, update: (List<CheckRow>) -> Unit) {
        val rows = targets.map { CheckRow(it) }.toMutableList()
        update(rows.toList())
        val plainPort = freePort()
        val bypassPort = freePort()
        val plain = launch(engine, plainPort, emptyList(), log)
        val bypass = launch(engine, bypassPort, args, log)
        try {
            for ((i, host) in targets.withIndex()) {
                rows[i] = rows[i].copy(direct = SiteProbe.probe(plainPort, host, null, TIMEOUT_MS))
                update(rows.toList())
                rows[i] = rows[i].copy(bypass = SiteProbe.probe(bypassPort, host, null, TIMEOUT_MS))
                update(rows.toList())
                log("check: ${rows[i].host}: без обхода ${rows[i].direct?.summary()}; со стратегией ${rows[i].bypass?.summary()}")
            }
        } finally {
            plain.destroy()
            bypass.destroy()
        }
    }

    private const val TIMEOUT_MS = 8000

    private fun launch(engine: File, port: Int, args: List<String>, log: (String) -> Unit): Process {
        val p = ProcessBuilder(listOf(engine.absolutePath, "-i", "127.0.0.1", "-p", port.toString()) + args)
            .redirectErrorStream(true).start()
        thread(isDaemon = true) { p.inputStream.bufferedReader().forEachLine { log("check $port | $it") } }
        repeat(50) {
            if (!p.isAlive) throw IllegalStateException("движок проверки завершился с кодом ${p.exitValue()}")
            if (runCatching { Socket().use { s -> s.connect(InetSocketAddress("127.0.0.1", port), 200) } }.isSuccess) return p
            Thread.sleep(100)
        }
        p.destroy()
        throw IllegalStateException("движок проверки не слушает порт $port")
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}
