package io.github.zapretdroid.test

import androidx.annotation.StringRes
import io.github.zapretdroid.R

/** A group of sites that are usually blocked together. */
data class SiteGroup(val id: String, @StringRes val title: Int, val hosts: List<String>)

object TestPlan {
    val siteGroups = listOf(
        SiteGroup("youtube", R.string.sites_youtube, listOf("www.youtube.com", "i.ytimg.com", "yt3.ggpht.com", "redirector.googlevideo.com")),
        SiteGroup("discord", R.string.sites_discord, listOf("discord.com", "gateway.discord.gg", "cdn.discordapp.com", "media.discordapp.net")),
        SiteGroup("meta", R.string.sites_meta, listOf("www.instagram.com", "www.facebook.com", "static.cdninstagram.com")),
        SiteGroup("x", R.string.sites_x, listOf("x.com", "abs.twimg.com", "pbs.twimg.com")),
        SiteGroup("other", R.string.sites_other, listOf("rutracker.org", "www.linkedin.com", "proton.me", "signal.org")),
    )

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
