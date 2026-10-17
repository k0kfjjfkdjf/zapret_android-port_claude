package io.github.zapretdroid.classic.services

import android.content.Context
import io.github.zapretdroid.classic.data.Configuration
import io.github.zapretdroid.classic.data.Mode
import io.github.zapretdroid.core.AppLog
import io.github.zapretdroid.core.ArgTemplates
import io.github.zapretdroid.core.ByeDpi
import io.github.zapretdroid.core.FakePayloads
import io.github.zapretdroid.core.HostLists
import io.github.zapretdroid.core.NativeLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * ZapretDroid: the engine for ByeByeDPI's proxy test, run in this process like ZapretDroid's own
 * tester (ByeByeDPI 1.8 talks to an engine in a child process). Same calls the test service uses.
 */
class NativeEngine(context: Context, @Suppress("unused") val mode: Mode, @Suppress("unused") val foreground: Boolean = true) {
    private val app = context.applicationContext
    private var engine: ByeDpi? = null
    private var engineThread: Thread? = null
    private val exited = CompletableDeferred<Int>()

    suspend fun start(configuration: Configuration) = withContext(Dispatchers.IO) {
        NativeLog.start(AppLog.file.absolutePath, 8L * 1024 * 1024)
        val fakeDir = FakePayloads.install(app)
        val listDir = HostLists.dir(app)
        val rest = if (configuration.args.firstOrNull() == "ciadpi") configuration.args.drop(1) else configuration.args
        val args = listOf("ciadpi") + ArgTemplates.resolve(rest, fakeDir, listDir)
        AppLog.i("ByeByeDPI test engine: ${args.joinToString(" ")}", source = "test")
        val current = ByeDpi()
        engine = current
        engineThread = thread(name = "classic-test-engine") { exited.complete(current.startProxy(args.toTypedArray())) }
        val host = when (configuration.host) {
            "0.0.0.0" -> "127.0.0.1"
            "::", "[::]" -> "::1"
            else -> configuration.host
        }
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            if (exited.isCompleted) throw IllegalStateException("engine exited, see the log")
            try {
                Socket().use { it.connect(InetSocketAddress(host, configuration.port), 200) }
                return@withContext
            } catch (_: Exception) {
                delay(30)
            }
        }
        throw IllegalStateException("engine did not start listening on $host:${configuration.port}")
    }

    suspend fun awaitExit(): Int = exited.await()

    suspend fun stop() = withContext(Dispatchers.IO) {
        engine?.stopProxy()
        engineThread?.join(3000)
        engine = null
    }
}
