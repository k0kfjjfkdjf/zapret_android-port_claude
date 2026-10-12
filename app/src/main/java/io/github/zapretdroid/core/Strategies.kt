package io.github.zapretdroid.core

import androidx.annotation.StringRes
import io.github.zapretdroid.R

/** A ready-made ByeDPI command line. */
data class Strategy(val id: String, @StringRes val title: Int, @StringRes val description: Int, val args: String)

object Strategies {
    val presets = listOf(
        Strategy(
            "auto", R.string.strategy_auto, R.string.strategy_auto_desc,
            "--disorder 1 --auto=torst --tlsrec 1+s",
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
                "--proto u --pf 443 --udp-fake 6 --fake-data {fake:quic_initial_www_google_com.bin} --auto=none",
        ),
        Strategy(
            "discord", R.string.strategy_discord, R.string.strategy_discord_desc,
            "--proto t,h --disorder 1 --tlsrec 1+s --auto=none " +
                "--proto u --pf 50000-65535 --udp-fake 6 --fake-data {fake:discord-ip-discovery-with-port.bin} --auto=none " +
                "--proto u --pf 443 --udp-fake 6 --fake-data {fake:quic_initial_www_google_com.bin} --auto=none " +
                "--proto u --pf 3478-3481 --udp-fake 4 --fake-data {fake:stun.bin}",
        ),
        Strategy(
            "fake_google", R.string.strategy_fake_google, R.string.strategy_fake_google_desc,
            "--fake -1 --ttl 8 --fake-data {fake:tls_clienthello_www_google_com.bin}",
        ),
        Strategy(
            "byebyedpi", R.string.strategy_byebyedpi, R.string.strategy_byebyedpi_desc,
            "-o1 -a1 -r-5+se",
        ),
    )

    const val CUSTOM = "custom"

    fun byId(id: String): Strategy? = presets.firstOrNull { it.id == id }
}
