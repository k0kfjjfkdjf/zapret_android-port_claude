package io.github.zapretdroid.desktop

import java.io.File

/**
 * Where things are. The app's resources (the engine, fakes, lists, presets) come with the app
 * (Compose's resources dir) or, when run from the repository, from the source tree; the user's
 * data in the XDG directories (~/.local/share/byezapret, ~/.config/byezapret).
 */
object Paths {
    private val home = System.getProperty("user.home")

    /** Compose's app resources dir: the engine and assets/. */
    private val resources: File? = System.getProperty("compose.application.resources.dir")?.let(::File)?.takeIf { it.isDirectory }

    /** The repository, when the app runs from it (./gradlew :desktop:run without resources). */
    private val repo: File? = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "app/src/main/assets").isDirectory }

    val assets: File = resources?.let { File(it, "assets") }?.takeIf { it.isDirectory }
        ?: repo?.let { File(it, "app/src/main/assets") }
        ?: File("assets")

    val data: File = File(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share", "byezapret").apply { mkdirs() }

    val config: File = File(System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.config", "byezapret").apply { mkdirs() }

    val fakeDir: File get() = File(assets, "fake")

    /** The user's host lists, made from the bundled ones once; {list:name} reads them. */
    val listDir: File by lazy {
        File(data, "lists").apply {
            mkdirs()
            File(assets, "lists").listFiles()?.forEach { f -> File(this, f.name).takeIf { !it.exists() }?.let { f.copyTo(it) } }
        }
    }

    val telegramIps: File get() = File(assets, "telegram-ip.txt")

    val presets: File get() = File(assets, "strategies/presets.txt")

    val log: File get() = File(data, "engine.log")

    /** The engine: $BYEZAPRET_ENGINE, the one that came with the app, or one built in the repository. */
    fun engine(): File? = listOfNotNull(
        System.getenv("BYEZAPRET_ENGINE")?.let(::File),
        resources?.let { File(it, "ciadpi") },
        File(data, "ciadpi"),
        repo?.let { File(it, "app/src/main/cpp/engine/ciadpi") },
    ).firstOrNull { it.isFile && it.canExecute() }
}
