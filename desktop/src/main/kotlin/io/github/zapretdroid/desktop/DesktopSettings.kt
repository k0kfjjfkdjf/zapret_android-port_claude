package io.github.zapretdroid.desktop

import io.github.zapretdroid.core.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A named SOCKS5 proxy for {proxy:name} ("" is the main one, {proxy}). */
data class Proxy(val name: String, val address: String)

/** The desktop app's settings, in ~/.config/byezapret/settings.json. */
data class DesktopSettings(
    /** A preset's id, or PresetFile.CUSTOM for [custom]. */
    val preset: String = "auto",
    val custom: String = "",
    val port: Int = 1080,
    /** For @if wifi|mobile. */
    val network: String = "wifi",
    val proxies: List<Proxy> = emptyList(),
    val dark: Boolean = true,
) {
    fun save(file: File = FILE) {
        val o = JSONObject()
            .put("preset", preset)
            .put("custom", custom)
            .put("port", port)
            .put("network", network)
            .put("dark", dark)
            .put("proxies", JSONArray(proxies.map { JSONObject().put("name", it.name).put("address", it.address) }))
        val tmp = File(file.path + ".tmp")
        tmp.writeText(o.toString(2))
        if (!tmp.renameTo(file)) file.writeText(o.toString(2))
    }

    companion object {
        val FILE: File get() = File(Paths.config, "settings.json")

        fun load(file: File = FILE): DesktopSettings = runCatching {
            if (!file.isFile) return DesktopSettings()
            val o = JSONObject(file.readText())
            val proxies = o.optJSONArray("proxies") ?: JSONArray()
            DesktopSettings(
                preset = o.optString("preset", "auto"),
                custom = o.optString("custom", ""),
                port = o.optInt("port", 1080).takeIf { it in 1..65535 } ?: 1080,
                network = o.optString("network", "wifi").takeIf { it == "wifi" || it == "mobile" } ?: "wifi",
                proxies = (0 until proxies.length()).map { proxies.getJSONObject(it) }
                    .map { Proxy(it.optString("name"), it.optString("address")) },
                dark = o.optBoolean("dark", true),
            )
        }.getOrElse {
            AppLog.w("settings: ${file.path} unreadable, defaults used: ${it.message}")
            DesktopSettings()
        }
    }
}
