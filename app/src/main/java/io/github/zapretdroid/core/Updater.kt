package io.github.zapretdroid.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.core.content.FileProvider
import androidx.core.content.edit
import io.github.zapretdroid.BuildConfig
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Updates from the project's GitHub releases (pre-releases included, as all builds so far are):
 * the newest version above the installed one, its APK for this device's ABI, checked against
 * the release's SHA256SUMS.txt, then handed to Android's installer (which always asks). Builds
 * from 0.9.3 on share one signing key, so they install over each other.
 */
object Updater {
    const val REPO = "k0kfjjfkdjf/zapret_android-port_claude"
    private const val API = "https://api.github.com/repos/$REPO/releases?per_page=15"
    private const val PREFS = "ui"
    private const val KEY_LAST_CHECK = "update_last_check"
    private const val KEY_AUTO = "update_auto"
    private const val KEY_SKIPPED = "update_skipped"
    private const val CHECK_EVERY_MS = 12 * 60 * 60 * 1000L

    class Release(
        val version: String,
        val tag: String,
        val notes: String,
        val page: String,
        /** File name -> download URL. */
        val assets: Map<String, String>,
    )

    /** "0.9.4-debug" -> [0, 9, 4]; non-numeric parts end the version. */
    fun parse(version: String): List<Int> =
        version.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: return@map -1 }.takeWhile { it >= 0 }

    fun newer(a: String, b: String): Boolean {
        val x = parse(a)
        val y = parse(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /** The newest release above the installed version, or null. Network: call off the main thread. */
    fun check(context: Context): Release? {
        prefs(context).edit { putLong(KEY_LAST_CHECK, System.currentTimeMillis()) }
        val json = get(API).toString(Charsets.UTF_8)
        val releases = parseReleases(json)
        val best = releases.maxWithOrNull { a, b -> if (newer(a.version, b.version)) 1 else if (newer(b.version, a.version)) -1 else 0 }
        return best?.takeIf { newer(it.version, BuildConfig.VERSION_NAME) }
    }

    fun parseReleases(json: String): List<Release> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { arr.getJSONObject(it) }.filter { !it.optBoolean("draft") }.map { o ->
            val assets = o.optJSONArray("assets") ?: JSONArray()
            Release(
                version = o.getString("tag_name").removePrefix("v"),
                tag = o.getString("tag_name"),
                notes = o.optString("body"),
                page = o.optString("html_url"),
                assets = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .associate { it.getString("name") to it.getString("browser_download_url") },
            )
        }
    }

    /** Whether to check now: automatic checks on, and none in the last 12 hours. */
    fun due(context: Context): Boolean =
        isAuto(context) && System.currentTimeMillis() - prefs(context).getLong(KEY_LAST_CHECK, 0) > CHECK_EVERY_MS

    fun isAuto(context: Context) = prefs(context).getBoolean(KEY_AUTO, true)

    fun setAuto(context: Context, auto: Boolean) = prefs(context).edit { putBoolean(KEY_AUTO, auto) }

    /** A version the user chose "later" for is not offered again automatically. */
    fun isSkipped(context: Context, version: String) = prefs(context).getString(KEY_SKIPPED, null) == version

    fun skip(context: Context, version: String) = prefs(context).edit { putString(KEY_SKIPPED, version) }

    /** The APK for this device: its first ABI, else the universal one. */
    fun apkFor(release: Release): Pair<String, String>? {
        val names = Build.SUPPORTED_ABIS.map { "zapretdroid-${release.version}-$it.apk" } + "zapretdroid-${release.version}-universal.apk"
        return names.firstNotNullOfOrNull { name -> release.assets[name]?.let { name to it } }
    }

    /** Downloads the APK and checks it against SHA256SUMS.txt; the file, ready to install. */
    fun download(context: Context, release: Release, progress: (Int) -> Unit = {}): File {
        val (name, url) = apkFor(release) ?: throw IOException("no APK for this device in release ${release.tag}")
        val sums = release.assets["SHA256SUMS.txt"]?.let { get(it).toString(Charsets.UTF_8) }
            ?: throw IOException("release ${release.tag} has no SHA256SUMS.txt")
        val expected = sums.lineSequence().map { it.trim().split(Regex("\\s+")) }
            .firstOrNull { it.size >= 2 && it.last().removePrefix("*") == name }?.first()?.lowercase()
            ?: throw IOException("$name is not in SHA256SUMS.txt")
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, name)
        val digest = MessageDigest.getInstance("SHA-256")
        open(url).let { conn ->
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (total > 0) progress((done * 100 / total).toInt())
                    }
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            file.delete()
            throw IOException("checksum of $name does not match SHA256SUMS.txt")
        }
        AppLog.i("update: ${release.version} downloaded and verified ($name, ${file.length()} B)")
        return file
    }

    /** Whether Android lets this app start installs (Android 8+: a per-app switch). */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** The system screen where the user allows installs from this app. */
    fun installPermissionIntent(context: Context): Intent =
        Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** Opens Android's installer for the downloaded APK. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.logs", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun open(url: String): HttpURLConnection {
        var current = url
        // GitHub sends asset downloads on to its CDN: follow redirects (also https -> https hosts).
        repeat(5) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "ByeZapret/${BuildConfig.VERSION_NAME}")
            conn.setRequestProperty("Accept", if (current.startsWith("https://api.github.com")) "application/vnd.github+json" else "*/*")
            val code = conn.responseCode
            if (code in 300..399) {
                current = conn.getHeaderField("Location") ?: throw IOException("redirect without Location")
                conn.disconnect()
                return@repeat
            }
            if (code != 200) {
                conn.disconnect()
                throw IOException("HTTP $code from ${URL(current).host}")
            }
            return conn
        }
        throw IOException("too many redirects")
    }

    private fun get(url: String): ByteArray = open(url).let { conn -> conn.inputStream.use { it.readBytes() } }
}
