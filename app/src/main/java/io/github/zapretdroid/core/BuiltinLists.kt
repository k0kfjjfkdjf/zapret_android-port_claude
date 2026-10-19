package io.github.zapretdroid.core

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The lists that come with the app (proxy, telegram, exclude and Telegram's address ranges) are
 * also kept up to date from the repository, without an app update. A downloaded copy replaces
 * the bundled one; a host list the user changed is left alone (it is told apart by the hash of
 * what was last put there).
 */
object BuiltinLists {
    /** The repository's default branch (raw.githubusercontent resolves HEAD). */
    private const val BASE = "https://raw.githubusercontent.com/${Updater.REPO}/HEAD/app/src/main/assets/"
    const val TELEGRAM_IPS = "telegram-ip.txt"
    private const val MAX_SIZE = 4 * 1024 * 1024

    val HOST_LISTS = listOf(TorProxy.LIST, TorProxy.TELEGRAM_LIST, TorProxy.EXCLUDE_LIST)

    enum class Status { UPDATED, SAME, KEPT_EDITED, FAILED }

    data class Result(val name: String, val status: Status, val detail: String)

    private val CIDR = Regex("""^[0-9a-fA-F:.]+/\d{1,3}$""")

    private fun dir(context: Context) = File(context.filesDir, "builtin").apply { mkdirs() }

    private fun hostListPath(name: String) = "lists/$name.txt"

    /** The newest default of [path]: the downloaded copy, else the one in the APK. */
    fun text(context: Context, path: String): String {
        val downloaded = File(dir(context), path.replace('/', '_'))
        if (downloaded.exists()) return downloaded.readText()
        return context.assets.open(path).bufferedReader().use { it.readText() }
    }

    /** Creates the host list [name] from its default once; the user edits it afterwards. */
    fun ensure(context: Context, name: String) {
        if (HostLists.file(context, name).exists()) return
        put(context, name, text(context, hostListPath(name)))
    }

    /** Telegram's address ranges as a file for --ipset. */
    fun telegramIps(context: Context): File {
        val file = File(context.filesDir, TELEGRAM_IPS)
        file.writeText(text(context, TELEGRAM_IPS))
        return file
    }

    private fun put(context: Context, name: String, text: String) {
        HostLists.save(context, name, text)
        marker(context, name).writeText(sha256(HostLists.file(context, name).readBytes()))
    }

    private fun marker(context: Context, name: String) = File(HostLists.dir(context), "$name.builtin")

    /**
     * True when the user changed the list since it was last put there. Lists made before the
     * marker existed count as unchanged when they equal the bundled default.
     */
    fun edited(context: Context, name: String): Boolean {
        val file = HostLists.file(context, name)
        if (!file.exists()) return false
        val current = sha256(file.readBytes())
        val mark = marker(context, name)
        if (mark.exists()) return mark.readText().trim() != current
        val bundled = context.assets.open(hostListPath(name)).bufferedReader().use { it.readText() }
        return HostLists.normalize(bundled).hosts != HostLists.normalize(file.readText()).hosts
    }

    /** Lines of a Telegram ranges file that are not CIDRs (comments and blanks aside). */
    fun badCidrs(text: String): List<String> =
        text.lineSequence().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() && !CIDR.matches(it) }.toList()

    fun cidrCount(text: String) = text.lineSequence().count { it.substringBefore('#').trim().isNotEmpty() }

    /**
     * Downloads every built-in list and, if [apply], saves the new defaults and refreshes the host
     * lists the user has not changed. Network; never throws, a failure is a [Status.FAILED] row.
     */
    fun update(context: Context, apply: Boolean = true): List<Result> {
        val results = HOST_LISTS.map { name ->
            runCatching {
                val path = hostListPath(name)
                val fresh = String(get(BASE + path), Charsets.UTF_8)
                val hosts = HostLists.normalize(fresh).hosts
                if (hosts.isEmpty()) throw IOException("no domains in the downloaded list")
                val local = HostLists.file(context, name)
                val same = local.exists() && HostLists.normalize(local.readText()).hosts == hosts
                when {
                    same -> Result(name, Status.SAME, "${hosts.size} domains")
                    edited(context, name) -> {
                        if (apply) saveDefault(context, path, fresh)
                        Result(name, Status.KEPT_EDITED, "${hosts.size} domains upstream; yours is changed and kept")
                    }
                    else -> {
                        if (apply) {
                            saveDefault(context, path, fresh)
                            put(context, name, fresh)
                        }
                        Result(name, Status.UPDATED, "${hosts.size} domains")
                    }
                }
            }.getOrElse { Result(name, Status.FAILED, it.message ?: it.javaClass.simpleName) }
        }
        val ips = runCatching {
            val fresh = String(get(BASE + TELEGRAM_IPS), Charsets.UTF_8)
            val bad = badCidrs(fresh)
            if (bad.isNotEmpty()) throw IOException("not a range: ${bad.first()}")
            val count = cidrCount(fresh)
            if (count == 0) throw IOException("no ranges")
            val same = badCidrs(text(context, TELEGRAM_IPS)).isEmpty() && text(context, TELEGRAM_IPS).trim() == fresh.trim()
            if (!same && apply) saveDefault(context, TELEGRAM_IPS, fresh)
            Result(TELEGRAM_IPS, if (same) Status.SAME else Status.UPDATED, "$count ranges")
        }.getOrElse { Result(TELEGRAM_IPS, Status.FAILED, it.message ?: it.javaClass.simpleName) }
        val all = results + ips
        if (apply) AppLog.i("built-in lists: " + all.joinToString { "${it.name} ${it.status.name.lowercase()} (${it.detail})" })
        return all
    }

    private fun saveDefault(context: Context, path: String, text: String) {
        val file = File(dir(context), path.replace('/', '_'))
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) throw IOException("cannot save $path")
    }

    private fun get(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.size > MAX_SIZE) throw IOException("too large")
            return bytes
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
