package io.github.zapretdroid.test

import io.github.zapretdroid.core.ArgTemplates
import io.github.zapretdroid.core.ByeDpi
import io.github.zapretdroid.core.splitArgs
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/** Runs the ByeDPI engine as a local SOCKS5 proxy in this process for tests. */
object TestEngine {
    /** Chrome's ClientHello with the post-quantum key share, from zapret2's fake payloads. */
    const val BROWSER_HELLO = "tls_clienthello_vk_com_kyber.bin"

    class Failed(val exitCode: Int) : Exception("engine did not start (code $exitCode)")

    /**
     * Starts the engine with [strategy] on 127.0.0.1:[port], runs [block] while it listens and
     * stops it. Throws [Failed] if the engine does not start listening within 3 seconds.
     * The engine has global state, so runs from the tester and the self-check take turns.
     */
    @Synchronized
    fun <T> with(strategy: String, port: Int, fakeDir: File, listDir: File, connLog: Boolean = true, block: () -> T): T {
        if (SiteProbe.browserHello == null) {
            SiteProbe.browserHello = runCatching { File(fakeDir, BROWSER_HELLO).readBytes() }.getOrNull()
        }
        val args = listOf("ciadpi", "--ip", "127.0.0.1", "--port", port.toString()) +
            (if (connLog) listOf("--conn-log") else emptyList()) +
            ArgTemplates.resolve(splitArgs(strategy), fakeDir, listDir)
        val engine = ByeDpi()
        var exitCode = Int.MIN_VALUE
        val engineThread = thread(name = "test-engine") { exitCode = engine.startProxy(args.toTypedArray()) }
        try {
            if (!awaitListening(engineThread, port)) throw Failed(exitCode)
            return block()
        } finally {
            engine.stopProxy()
            engineThread.join(3000)
        }
    }

    private fun awaitListening(engineThread: Thread, port: Int): Boolean {
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline && engineThread.isAlive) {
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200) }
                return true
            } catch (_: Exception) {
                Thread.sleep(30)
            }
        }
        return false
    }
}
