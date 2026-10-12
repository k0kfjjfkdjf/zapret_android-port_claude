package io.github.zapretdroid.core

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The app journal: one file with everything that happens in the app. The UI process (source
 * "ui"), the VPN process ("vpn"), the strategy tester ("test") and the native engines ("byedpi",
 * "hev", captured by NativeLog) all append to it. Each line: "MM-dd HH:mm:ss.SSS source | message".
 *
 * The file is trimmed to its most recent part when it grows too large, only at moments when no
 * VPN session holds it open (app start without a session, or right before a session starts).
 */
object AppLog {
    private const val TAG = "ZapretDroid"
    private const val TRIM_ABOVE = 4L * 1024 * 1024
    private const val KEEP = 2 * 1024 * 1024

    private lateinit var dir: File
    private var source = "ui"

    /** True in the VPN process (":engine"). */
    var inEngineProcess = false
        private set

    val file: File get() = File(dir, "app.log")

    fun init(context: Context) {
        dir = File(context.filesDir, "logs").apply { mkdirs() }
        // Files of versions before the single journal.
        File(dir, "session.log").delete()
        File(dir, "previous.log").delete()
        inEngineProcess = processName().endsWith(":engine")
        source = if (inEngineProcess) "vpn" else "ui"
    }

    private fun processName(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            runCatching { File("/proc/self/cmdline").readText().trimEnd('\u0000') }.getOrDefault("")
        }

    /** Keeps the last part of the journal if it is too large. Not while a session writes to it. */
    @Synchronized
    fun trimIfLarge() {
        val f = file
        if (!f.exists() || f.length() <= TRIM_ABOVE) return
        runCatching {
            val tail = readTailBytes(f, KEEP)
            val start = tail.indexOf('\n'.code.toByte()) + 1
            val tmp = File(dir, "app.log.tmp")
            tmp.outputStream().use {
                it.write("--- older entries removed ---\n".toByteArray())
                it.write(tail, start, tail.size - start)
            }
            tmp.renameTo(f)
        }
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    fun i(message: String, source: String? = null) = write(Log.INFO, message, null, source)

    fun w(message: String, error: Throwable? = null, source: String? = null) = write(Log.WARN, message, error, source)

    fun e(message: String, error: Throwable? = null, source: String? = null) = write(Log.ERROR, message, error, source)

    @Synchronized
    private fun write(priority: Int, message: String, error: Throwable?, sourceOverride: String?) {
        Log.println(priority, TAG, if (error != null) "$message\n${Log.getStackTraceString(error)}" else message)
        if (!::dir.isInitialized) return
        val src = sourceOverride ?: source
        val level = when (priority) {
            Log.ERROR -> "E/"
            Log.WARN -> "W/"
            else -> ""
        }
        val text = buildString {
            val now = System.currentTimeMillis()
            (level + message).lines().forEach { append(formatLine(now, src, it)).append('\n') }
            error?.stackTraceToString()?.trimEnd()?.lines()?.forEach { append(formatLine(now, src, "  $it")).append('\n') }
        }
        runCatching { FileOutputStream(file, true).use { it.write(text.toByteArray()) } }
    }

    fun formatLine(time: Long, source: String, message: String): String =
        "${SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(time))} $source | $message"

    /** The last lines of [file] that fit in [maxBytes]; a partial first line is dropped. */
    fun readTail(file: File, maxBytes: Int = 512 * 1024): List<String> {
        if (!file.exists()) return emptyList()
        val bytes = readTailBytes(file, maxBytes)
        val lines = String(bytes, Charsets.UTF_8).split('\n')
        val complete = if (file.length() > bytes.size) lines.drop(1) else lines
        return complete.filter { it.isNotEmpty() }
    }

    private fun readTailBytes(file: File, maxBytes: Int): ByteArray = RandomAccessFile(file, "r").use { raf ->
        val length = raf.length()
        val start = (length - maxBytes).coerceAtLeast(0)
        ByteArray((length - start).toInt()).also {
            raf.seek(start)
            raf.readFully(it)
        }
    }
}
