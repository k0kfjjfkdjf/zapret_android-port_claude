package io.github.zapretdroid.core

import android.content.Context
import androidx.annotation.StringRes
import io.github.zapretdroid.R

/**
 * A ready-made ByeDPI command line. A preset that comes from the downloaded presets file only has
 * [name] and [about] instead of string resources.
 */
data class Strategy(
    val id: String,
    @StringRes val title: Int,
    @StringRes val description: Int,
    val args: String,
    val name: String? = null,
    val about: String? = null,
) {
    fun label(context: Context): String = name ?: if (title != 0) context.getString(title) else id
    fun summary(context: Context): String = about ?: if (description != 0) context.getString(description) else ""
}

object Strategies {
    /** The presets file in the repository and the APK (assets/strategies/presets.txt). */
    const val FILE = "strategies/presets.txt"

    @Volatile private var context: Context? = null
    @Volatile private var cache: Pair<Long, List<Strategy>>? = null

    /** Called once per process: from now on [presets] follows the presets file. */
    fun init(context: Context) {
        this.context = context.applicationContext
    }

    /**
     * The presets: the built-in ones with the command lines (and new presets) of the presets file,
     * which is updated from the repository with the built-in lists, so a preset changes without
     * an app update. Re-read when a new copy is downloaded, in every process.
     */
    val presets: List<Strategy>
        get() {
            val c = context ?: return bundled
            val stamp = BuiltinLists.stamp(c, FILE)
            cache?.let { if (it.first == stamp) return it.second }
            val list = runCatching { merge(bundled, parse(BuiltinLists.text(c, FILE))) }
                .onFailure { AppLog.w("presets file: ${it.message}; using the built-in presets") }
                .getOrDefault(bundled)
            cache = stamp to list
            return list
        }

    /** See [PresetFile.parse]. */
    fun parse(text: String): List<PresetFile.Entry> = PresetFile.parse(text)

    /** The built-in presets with the file's command lines; presets new in the file go last. */
    fun merge(base: List<Strategy>, entries: List<PresetFile.Entry>): List<Strategy> {
        val byId = entries.associateBy { it.id }
        val known = base.map { it.id }.toSet()
        return base.map { p -> byId[p.id]?.let { e -> p.copy(args = e.args, name = e.name ?: p.name, about = e.about ?: p.about) } ?: p } +
            entries.filter { it.id !in known }.map { e -> Strategy(e.id, 0, 0, e.args, e.name ?: e.id, e.about) }
    }

    /** The presets compiled into the app: used until the presets file is read, and as a fallback. */
    val bundled = listOf(
        Strategy(
            "auto", R.string.strategy_auto, R.string.strategy_auto_desc,
            // The last group: fakes with an allowed SNI at a low TTL, for networks (and phones)
            // where no splitting gets through but fakes do.
            "--disorder 1 --auto=torst --tlsrec 1+s " +
                "--auto=torst,ssl_err --fake -1 --ttl 4 --fake-tls-mod rand,orig --fake-sni www.google.com",
        ),
        Strategy(
            "disorder", R.string.strategy_disorder, R.string.strategy_disorder_desc,
            "--disorder 1",
        ),
        Strategy(
            "split_sni", R.string.strategy_split_sni, R.string.strategy_split_sni_desc,
            "--split 1+s --disorder 3+s",
        ),
        Strategy(
            "oob_sni", R.string.strategy_oob_sni, R.string.strategy_oob_sni_desc,
            "--oob 3+s",
        ),
        Strategy(
            "tlsrec", R.string.strategy_tlsrec, R.string.strategy_tlsrec_desc,
            "--tlsrec 1+s --auto=torst --timeout 3 --disorder 1",
        ),
        Strategy(
            "fake", R.string.strategy_fake, R.string.strategy_fake_desc,
            "--fake -1 --ttl 8",
        ),
        Strategy(
            "quic", R.string.strategy_quic, R.string.strategy_quic_desc,
            "--proto t,h --disorder 1 --tlsrec 1+s --auto=none " +
                "--proto u --pf 443 $UDP_FAKES --auto=none",
        ),
        Strategy(
            "discord", R.string.strategy_discord, R.string.strategy_discord_desc,
            // Discord voice: IP discovery and RTP on 50000-65535 and 19294-19344, STUN on 3478,
            // QUIC on 443. Fakes of the flow's own protocol, TTL 4, six before each of the first
            // four datagrams: the profile that gave two-way voice with the tpws UDP patch.
            "--proto t,h --disorder 1 --tlsrec 1+s --auto=none " +
                "--proto u --pf 50000-65535 $UDP_FAKES --auto=none " +
                "--proto u --pf 19294-19344 $UDP_FAKES --auto=none " +
                "--proto u --pf 3478-3481 $UDP_FAKES --auto=none " +
                "--proto u --pf 443 $UDP_FAKES",
        ),
        Strategy(
            "discord_termux", R.string.strategy_discord_termux, R.string.strategy_discord_termux_desc,
            // zapret-termux's "discord" TCP strategy (tpws multisplit with disorder and OOB, which gave
            // two-way voice there) with the UDP profile of the "Discord and calls" preset.
            "--proto t,h $DISCORD_TERMUX_TCP --auto=none " +
                "--proto u --pf 50000-65535 $UDP_FAKES --auto=none " +
                "--proto u --pf 19294-19344 $UDP_FAKES --auto=none " +
                "--proto u --pf 3478-3481 $UDP_FAKES --auto=none " +
                "--proto u --pf 443 $UDP_FAKES",
        ),
        Strategy(
            "fake_google", R.string.strategy_fake_google, R.string.strategy_fake_google_desc,
            "--fake -1 --ttl 8 --fake-data {fake:tls_clienthello_www_google_com.bin}",
        ),
        Strategy(
            "tpws", R.string.strategy_tpws, R.string.strategy_tpws_desc,
            "--proto h --split method+2 -M hostcase,hostdot --auto=none " +
                "--split 1 --disorder midsld --tlsrec sniext",
        ),
        Strategy(
            "byebyedpi", R.string.strategy_byebyedpi, R.string.strategy_byebyedpi_desc,
            "-o1 -a1 -r-5+se",
        ),
    )

    const val CUSTOM = PresetFile.CUSTOM

    /** zapret-termux "discord": tpws --split-pos=1,sniext+1,host+1,midsld-2,midsld,midsld+2,endhost-1 --disorder --oob. */
    const val DISCORD_TERMUX_TCP = "--disoob 1 --disorder sniext+1 --disorder host+1 --disorder midsld-2 " +
        "--disorder midsld --disorder midsld+2 --disorder endhost-1"

    /** UDP desync used by the presets: protocol-specific fakes with a low TTL. */
    const val UDP_FAKES = "--ttl 4 --udp-fake 6 --udp-desync 4 " +
        "--udp-proto-fake quic:{fake:quic_initial_www_google_com.bin} " +
        "--udp-proto-fake discord:{fake:discord-ip-discovery-with-port.bin} " +
        "--udp-proto-fake stun:{fake:stun.bin}"

    fun byId(id: String): Strategy? = presets.firstOrNull { it.id == id }
}
