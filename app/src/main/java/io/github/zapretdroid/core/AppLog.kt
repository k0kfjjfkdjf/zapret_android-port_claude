package io.github.zapretdroid.core

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Session log shared by the UI and engine processes. The engine also captures native output
 * into the same file (NativeLog), so a session log has app events, ByeDPI and hev lines
 * interleaved in time order. Each line: "MM-dd HH:mm:ss.SSS source | message".
 */
object AppLog {
    private const val TAG = "ZapretDroid"
    private lateinit var dir: File

    val current: File get() = File(dir, "session.log")
    val previous: File get() = File(dir, "previous.log")

    fun init(context: Context) {
        dir = File(context.filesDir, "logs").apply { mkdirs() }
    }

    /** Starts a new session log; the last one is kept as [previous]. */
    @Synchronized
    fun rotate() {
        if (current.exists()) {
            previous.delete()
            current.renameTo(previous)
        }
    }

    @Synchronized
    fun clear() {
        current.delete()
        previous.delete()
    }

    fun i(message: String) = write(Log.INFO, message, null)

    fun w(message: String, error: Throwable? = null) = write(Log.WARN, message, error)

    fun e(message: String, error: Throwable? = null) = write(Log.ERROR, message, error)

    @Synchronized
    private fun write(priority: Int, message: String, error: Throwable?) {
        Log.println(priority, TAG, if (error != null) "$message\n${Log.getStackTraceString(error)}" else message)
        if (!::dir.isInitialized) return
        val level = when (priority) {
            Log.ERROR -> "E/"
            Log.WARN -> "W/"
            else -> ""
        }
        val text = buildString {
            val now = System.currentTimeMillis()
            (level + message).lines().forEach { append(formatLine(now, "app", it)).append('\n') }
            error?.stackTraceToString()?.trimEnd()?.lines()?.forEach { append(formatLine(now, "app", "  $it")).append('\n') }
        }
        runCatching { FileOutputStream(current, true).use { it.write(text.toByteArray()) } }
    }

    fun formatLine(time: Long, source: String, message: String): String =
        "${SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(time))} $source | $message"

    /** The last lines of [file] that fit in [maxBytes]; a partial first line is dropped. */
    fun readTail(file: File, maxBytes: Int = 256 * 1024): List<String> {
        if (!file.exists()) return emptyList()
        return RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            val start = (length - maxBytes).coerceAtLeast(0)
            val bytes = ByteArray((length - start).toInt())
            raf.seek(start)
            raf.readFully(bytes)
            val lines = String(bytes, Charsets.UTF_8).split('\n')
            val complete = if (start > 0) lines.drop(1) else lines
            complete.filter { it.isNotEmpty() }
        }
    }
}
