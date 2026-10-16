package io.github.zapretdroid.core

import androidx.annotation.StringRes
import io.github.zapretdroid.R

/** A host list published in a zapret community repository, downloadable in one tap. */
data class ListSource(val name: String, val repo: String, @StringRes val description: Int, val url: String)

/**
 * Lists from repositories that are updated often. Flowseal's zapret-discord-youtube is the most
 * used Windows zapret bundle; itdoginfo/allow-domains and Re-filter are updated every few days;
 * zapret.cfgs (Snowy-Fluffy) is large but has not been updated since early 2026.
 */
object ListSources {
    private const val FLOWSEAL = "https://raw.githubusercontent.com/Flowseal/zapret-discord-youtube/main/lists/"
    private const val ITDOG = "https://raw.githubusercontent.com/itdoginfo/allow-domains/main/"
    private const val REFILTER = "https://raw.githubusercontent.com/1andrevich/Re-filter-lists/main/"
    private const val CFGS = "https://raw.githubusercontent.com/Snowy-Fluffy/zapret.cfgs/main/lists/"

    val all = listOf(
        ListSource("flowseal_general", "Flowseal/zapret-discord-youtube", R.string.src_flowseal_general, FLOWSEAL + "list-general.txt"),
        ListSource("flowseal_google", "Flowseal/zapret-discord-youtube", R.string.src_flowseal_google, FLOWSEAL + "list-google.txt"),
        ListSource("flowseal_exclude", "Flowseal/zapret-discord-youtube", R.string.src_flowseal_exclude, FLOWSEAL + "list-exclude.txt"),
        ListSource("itdog_russia", "itdoginfo/allow-domains", R.string.src_itdog_russia, ITDOG + "Russia/inside-raw.lst"),
        ListSource("itdog_discord", "itdoginfo/allow-domains", R.string.src_itdog_discord, ITDOG + "Services/discord.lst"),
        ListSource("itdog_youtube", "itdoginfo/allow-domains", R.string.src_itdog_youtube, ITDOG + "Services/youtube.lst"),
        ListSource("itdog_telegram", "itdoginfo/allow-domains", R.string.src_itdog_telegram, ITDOG + "Services/telegram.lst"),
        ListSource("itdog_meta", "itdoginfo/allow-domains", R.string.src_itdog_meta, ITDOG + "Services/meta.lst"),
        ListSource("itdog_twitter", "itdoginfo/allow-domains", R.string.src_itdog_twitter, ITDOG + "Services/twitter.lst"),
        ListSource("itdog_tiktok", "itdoginfo/allow-domains", R.string.src_itdog_tiktok, ITDOG + "Services/tiktok.lst"),
        ListSource("refilter_community", "1andrevich/Re-filter-lists", R.string.src_refilter_community, REFILTER + "community.lst"),
        ListSource("refilter_all", "1andrevich/Re-filter-lists", R.string.src_refilter_all, REFILTER + "domains_all.lst"),
        ListSource("cfgs_basic", "Snowy-Fluffy/zapret.cfgs", R.string.src_cfgs_basic, CFGS + "list-basic.txt"),
    )
}
