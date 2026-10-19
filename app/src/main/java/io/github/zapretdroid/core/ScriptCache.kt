package io.github.zapretdroid.core

import android.content.Context
import androidx.core.content.edit

/**
 * Winners of the script's @try blocks per network type ("wifi"/"mobile"), kept by the VPN
 * service (its process alone writes them). An entry is for the variants as they were written:
 * editing a block's variants makes it stale.
 */
object ScriptCache {
    private const val PREFS = "script_tries"
    private const val FRESH_MS = 24 * 60 * 60 * 1000L

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(network: String, block: Script.TryBlock) = "$network|${block.name}"

    fun hash(block: Script.TryBlock) = block.variants.joinToString("\n").hashCode()

    /** The winning variant (index) for [block] on [network], or null if none is known. */
    fun choice(context: Context, network: String, block: Script.TryBlock): Int? {
        val parts = prefs(context).getString(key(network, block), null)?.split('|') ?: return null
        val index = parts.getOrNull(0)?.toIntOrNull() ?: return null
        if (parts.getOrNull(2)?.toIntOrNull() != hash(block) || index !in block.variants.indices) return null
        return index
    }

    /** Whether [block] needs a search on [network]: no winner, or one older than a day. */
    fun stale(context: Context, network: String, block: Script.TryBlock): Boolean {
        if (choice(context, network, block) == null) return true
        val time = prefs(context).getString(key(network, block), null)?.split('|')?.getOrNull(1)?.toLongOrNull() ?: 0
        return System.currentTimeMillis() - time > FRESH_MS
    }

    fun save(context: Context, network: String, name: String, index: Int, hash: Int) {
        prefs(context).edit(commit = true) { putString("$network|$name", "$index|${System.currentTimeMillis()}|$hash") }
    }
}
