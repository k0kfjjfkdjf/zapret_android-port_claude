package io.github.zapretdroid.core

import android.content.Context
import io.github.zapretdroid.test.QuicInitial
import java.io.File

/**
 * Fake packet payloads from zapret2 (files/fake), shipped as assets. The engine reads files,
 * so they are copied to app storage and referenced from arguments as {fake:name}.
 */
object FakePayloads {
    private const val ASSET_DIR = "fake"

    fun names(context: Context): List<String> =
        context.assets.list(ASSET_DIR).orEmpty().filter { it.endsWith(".bin") }.sorted()

    /** Directory name of the generated QUIC Initials: {fake:quic_sni} in arguments. */
    const val QUIC_SNI_DIR = "quic_sni"

    /**
     * SNIs for the generated QUIC Initial fakes: sites DPI lets through, so that a fake looks like
     * an allowed connection (the list of zapret-termux's udp-sni).
     */
    val QUIC_SNIS = listOf(
        "www.google.com", "yandex.ru", "ya.ru", "vk.com", "mail.ru", "ok.ru", "dzen.ru", "gosuslugi.ru",
        "sberbank.ru", "ozon.ru", "wildberries.ru", "avito.ru", "rutube.ru", "kinopoisk.ru",
    )
    private const val QUIC_SNI_COUNT = 24

    /**
     * Copies the payloads into app storage (once per app version) and returns the directory.
     * Every call also makes a fresh set of QUIC Initials with random SNIs and connection IDs in
     * quic_sni/, which the engine sends in turn (--udp-proto-fake quic:{fake:quic_sni}).
     */
    fun install(context: Context): File {
        val dir = File(context.filesDir, ASSET_DIR)
        runCatching { generateQuic(File(dir, QUIC_SNI_DIR)) }
            .onFailure { AppLog.w("QUIC fakes with random SNI not generated: ${it.message}") }
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

    /**
     * Replaces the files one by one with a rename, never emptying the directory: the VPN and the
     * tester may install at the same time, and an engine starting meanwhile loads every file.
     */
    fun generateQuic(out: File, count: Int = QUIC_SNI_COUNT) {
        out.mkdirs()
        val keep = (0 until count).map { "%02d.bin".format(it) }.toSet()
        out.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
        keep.forEach { name ->
            // Outside the directory until complete, so that it is never loaded half written.
            val tmp = File(out.parentFile, ".quic_${android.os.Process.myPid()}_$name")
            tmp.writeBytes(QuicInitial.build(QUIC_SNIS.random()))
            if (!tmp.renameTo(File(out, name))) tmp.delete()
        }
    }
}
