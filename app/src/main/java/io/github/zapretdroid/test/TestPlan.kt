package io.github.zapretdroid.test

import android.content.Context
import androidx.annotation.StringRes
import io.github.zapretdroid.R

/** A group of sites that are usually blocked together (assets/sites/<id>.sites, from ByeByeDPI). */
data class SiteGroup(val id: String, @StringRes val title: Int, val hosts: List<String>)

/** A set of strategies to try. */
enum class StrategySet { QUICK, FULL, COMMUNITY }

object TestPlan {
    private val GROUPS = listOf(
        "youtube" to R.string.sites_youtube,
        "googlevideo" to R.string.sites_googlevideo,
        "discord" to R.string.sites_discord,
        "social" to R.string.sites_social,
        "telegram" to R.string.sites_telegram,
        "cloudflare" to R.string.sites_cloudflare,
        "general" to R.string.sites_other,
    )

    /** Fake SNI substituted for {sni} in community strategies, as in ByeByeDPI. */
    const val DEFAULT_SNI = "google.com"

    fun siteGroups(context: Context): List<SiteGroup> = GROUPS.map { (id, title) ->
        SiteGroup(id, title, readLines(context, "sites/$id.sites"))
    }

    fun strategies(context: Context, set: StrategySet, sni: String): List<String> = when (set) {
        StrategySet.QUICK -> quick
        StrategySet.FULL -> full
        // Strategies collected by the ByeByeDPI community (assets/strategies/byebyedpi.list).
        StrategySet.COMMUNITY -> readLines(context, "strategies/byebyedpi.list")
            .map { it.replace("{sni}", sni.ifBlank { DEFAULT_SNI }) }
    }

    private fun readLines(context: Context, asset: String): List<String> =
        context.assets.open(asset).bufferedReader().useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
        }

    /** Strategies tried in quick mode: the main socket-level techniques. */
    val quick = listOf(
        "--disorder 1",
        "--disorder 1+s",
        "--split 1+s --disorder 3+s",
        "--split 0+sm",
        "--oob 3+s",
        "--disoob 1+s",
        "--tlsrec 1+s",
        "--tlsrec 0+sm",
        "--disorder 1 --tlsrec 1+s",
        "--oob 1 --disorder 1 --tlsrec 1+s",
        "--split 1 --disorder midsld --tlsrec sniext",
        "--disorder sniext+1",
        "--split host+1 --disorder midsld",
        "--fake -1 --ttl 8",
        "--fake -1 --ttl 8 --fake-data {fake:tls_clienthello_www_google_com.bin}",
        "--fake -1 --ttl 8 --fake-tls-mod rand,orig --fake-sni www.google.com",
        "-o1 -a1 -r-5+se",
    )

    /** Full mode adds positions and a TTL sweep for fake packets. */
    val full = quick + listOf(
        "--disorder 2",
        "--disorder 3+s",
        "--split 1 --disorder 1+s",
        "--split 2+s",
        "--oob 1+s",
        "--disoob 3+s --disorder 7",
        "--tlsrec 3+s",
        "--tlsrec 1+s --split 1+s --disorder 3+s",
        "--tlsrec 1+s --tlsminor 1",
        "--disorder 1 --drop-sack",
        "--split midsld",
        "--disorder sld+1",
        "--oob midsld",
        "--tlsrec sniext+4 --disorder 1",
        "--split 1 --disorder endhost-1",
    ) + (3..12).filter { it != 8 }.map { "--fake -1 --ttl $it" } +
        listOf(4, 6, 10).map { "--disorder 1 --fake -1 --ttl $it --fake-data {fake:tls_clienthello_www_google_com.bin}" }
}
