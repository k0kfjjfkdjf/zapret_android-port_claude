package io.github.zapretdroid.core

import android.content.Context
import java.io.File

/**
 * Fake packet payloads from zapret2 (files/fake), shipped as assets. The engine reads files,
 * so they are copied to app storage and referenced from arguments as {fake:name}.
 */
object FakePayloads {
    private const val ASSET_DIR = "fake"

    fun names(context: Context): List<String> =
        context.assets.list(ASSET_DIR).orEmpty().filter { it.endsWith(".bin") }.sorted()

    /** Copies the payloads into app storage (once per app version) and returns the directory. */
    fun install(context: Context): File {
        val dir = File(context.filesDir, ASSET_DIR)
        val stamp = File(dir, ".version")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        if (stamp.exists() && stamp.readText() == version) return dir
        dir.mkdirs()
        names(context).forEach { name ->
            context.assets.open("$ASSET_DIR/$name").use { input ->
                File(dir, name).outputStream().use { input.copyTo(it) }
            }
        }
        stamp.writeText(version)
        return dir
    }
}
