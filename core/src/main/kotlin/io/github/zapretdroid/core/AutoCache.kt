package io.github.zapretdroid.core

import java.io.File
import java.security.MessageDigest

/**
 * The engine's --auto-cache: what its --auto groups learnt (this address needed group N) kept in
 * a file across restarts, so that a site that needed the second group does not have to fail
 * once more after every start of the VPN, network change or killed process.
 *
 * One file per strategy and network type: group numbers mean something only for the strategy
 * that has them (the engine also checks their count), and what the provider of the Wi-Fi blocks
 * is not what the mobile operator blocks.
 */
object AutoCache {
    /** Files kept besides the current one; older ones are deleted. */
    const val KEEP = 8

    /** Whether [args] (the engine's, resolved) have a group that --auto switches to. */
    fun hasAuto(args: List<String>): Boolean = args.any { arg ->
        arg == "-A" || arg == "--auto" || arg.startsWith("--auto=") ||
            // "-Atorst", but not other short options clustered after a value-less one
            (arg.startsWith("-A") && arg.length > 2 && !arg.startsWith("--"))
    }

    /** The file name for [args] on [network] ("wifi", "mobile", "other"). */
    fun fileName(args: List<String>, network: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        args.forEach { digest.update(it.toByteArray()); digest.update(0) }
        val hash = digest.digest().take(8).joinToString("") { "%02x".format(it) }
        val net = network.filter { it.isLetterOrDigit() }.ifEmpty { "other" }
        return "$net-$hash.cache"
    }

    /**
     * The engine arguments that keep the cache in [dir], or none when [args] have no --auto
     * group. Old files beyond [KEEP] are removed.
     */
    fun args(dir: File, args: List<String>, network: String): List<String> {
        if (!hasAuto(args)) return emptyList()
        dir.mkdirs()
        val file = File(dir, fileName(args, network))
        prune(dir, file)
        return listOf("--auto-cache", file.absolutePath)
    }

    /** Removes everything in [dir] but [current] and the [KEEP] newest other cache files. */
    fun prune(dir: File, current: File) {
        val files = dir.listFiles().orEmpty().filter { it.isFile && it != current }
        files.filter { it.name.endsWith(".tmp") }.forEach { it.delete() }
        files.filter { it.name.endsWith(".cache") }
            .sortedByDescending { it.lastModified() }
            .drop(KEEP)
            .forEach { it.delete() }
    }

    /** Forgets everything learnt (all strategies and networks). */
    fun clear(dir: File) {
        dir.listFiles().orEmpty().filter { it.isFile }.forEach { it.delete() }
    }
}
