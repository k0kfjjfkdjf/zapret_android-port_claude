package io.github.zapretdroid.core

import android.content.Context
import android.os.Build
import java.io.File

/**
 * InviZible Pro (github.com/Gedsh/InviZible): Tor with bridges, DNSCrypt and more, run in proxy
 * mode next to ByeZapret. Downloaded from its own GitHub releases for this phone's architecture,
 * checked against the SHA-256 GitHub reports for the asset, and installed by Android's installer.
 */
object InviZible {
    const val REPO = "Gedsh/InviZible"

    /** Names architectures go by in InviZible's file names, most specific first. */
    private fun tokens(abi: String): List<String> = when (abi) {
        "arm64-v8a" -> listOf("arm64-v8a", "arm64", "aarch64")
        "armeabi-v7a" -> listOf("armeabi-v7a", "armv7", "arm32", "armeabi")
        "x86_64" -> listOf("x86_64", "x64")
        "x86" -> listOf("x86")
        else -> listOf(abi)
    }

    private val ALL_TOKENS = listOf("arm64", "aarch64", "armeabi", "armv7", "arm32", "x86", "x64")

    /** The APK of [release] for [abis] (best first), else one without an architecture in its name. */
    fun assetFor(release: Updater.Release, abis: List<String> = Build.SUPPORTED_ABIS.toList()): Pair<String, String>? {
        val apks = release.assets.filterKeys { it.endsWith(".apk", ignoreCase = true) }
        for (abi in abis) {
            for (t in tokens(abi)) {
                apks.entries.firstOrNull { (name, _) ->
                    val n = name.lowercase()
                    // "x86" must not take the x86_64 file.
                    n.contains(t) && !(t == "x86" && n.contains("x86_64"))
                }?.let { return it.key to it.value }
            }
        }
        return apks.entries.firstOrNull { (name, _) -> ALL_TOKENS.none { name.lowercase().contains(it) } }?.let { it.key to it.value }
    }

    /** The newest release with an APK for this phone, with that APK. Network. */
    fun latest(): Pair<Updater.Release, Pair<String, String>>? =
        Updater.releasesOf(REPO).firstNotNullOfOrNull { r -> assetFor(r)?.let { r to it } }

    /** The installed version, or null. */
    fun installedVersion(context: Context): String? = TorProxy.Provider.INVIZIBLE.packages.firstNotNullOfOrNull { pkg ->
        runCatching { context.packageManager.getPackageInfo(pkg, 0).versionName }.getOrNull()
    }

    fun download(context: Context, release: Updater.Release, asset: Pair<String, String>, progress: (Int) -> Unit): File =
        Updater.downloadAsset(context, release, asset.first, asset.second, progress)
}
