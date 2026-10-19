package io.github.zapretdroid.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Export and import of settings as one JSON file: preferences, saved strategies and host lists
 * (lists over 2 MB are left out; they can be downloaded again from their source).
 */
object Backup {
    private const val FORMAT = 1
    private const val MAX_LIST_BYTES = 2L * 1024 * 1024

    /** The settings and saved strategies without the host lists: what the settings editor shows. */
    fun exportSettings(context: Context): String =
        export(context, lists = false)

    fun export(context: Context): String = export(context, lists = true)

    private fun export(context: Context, lists: Boolean): String {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE).all
        val settings = JSONObject()
        prefs.forEach { (key, value) ->
            when (value) {
                is Set<*> -> settings.put(key, JSONObject().put("set", JSONArray(value.map { it.toString() })))
                is Boolean, is Int, is Long, is Float, is String -> settings.put(key, value)
            }
        }
        val listsJson = JSONObject()
        if (lists) HostLists.names(context).forEach { name ->
            val file = HostLists.file(context, name)
            if (file.length() <= MAX_LIST_BYTES) {
                listsJson.put(name, JSONObject().put("hosts", file.readText()).put("source", HostLists.source(context, name) ?: ""))
            }
        }
        return JSONObject()
            .put("app", "ByeZapret")
            .put("format", FORMAT)
            .put("settings", settings)
            .put("strategies", SavedStrategies.file(context).takeIf { it.exists() }?.readText() ?: "")
            .apply { if (lists) put("lists", listsJson) }
            .toString(2)
    }

    /**
     * Returns how many settings, strategies and lists were restored. With [replace] (the settings
     * editor) a setting left out goes back to its default, and no strategies means none.
     */
    fun import(context: Context, text: String, replace: Boolean = false): Triple<Int, Int, Int> {
        val root = JSONObject(text)
        // Backups from before the rename to ByeZapret say "ZapretDroid".
        require(root.optString("app") in setOf("ByeZapret", "ZapretDroid")) { "not a ByeZapret backup" }
        val settings = root.optJSONObject("settings") ?: JSONObject()
        val editor = context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
        if (replace) editor.clear()
        settings.keys().forEach { key ->
            when (val v = settings.get(key)) {
                is JSONObject -> v.optJSONArray("set")?.let { arr -> editor.putStringSet(key, (0 until arr.length()).map { arr.getString(it) }.toSet()) }
                is Boolean -> editor.putBoolean(key, v)
                is Int -> editor.putInt(key, v)
                is Long -> editor.putLong(key, v)
                is Double -> editor.putFloat(key, v.toFloat())
                is String -> editor.putString(key, v)
            }
        }
        editor.commit()
        val strategies = SavedStrategies.parse(root.optString("strategies"))
        if (strategies.isNotEmpty() || replace) SavedStrategies.save(context, strategies)
        val lists = root.optJSONObject("lists") ?: JSONObject()
        var restored = 0
        lists.keys().forEach { name ->
            if (!HostLists.isValidName(name)) return@forEach
            val entry = lists.getJSONObject(name)
            HostLists.save(context, name, entry.optString("hosts"))
            entry.optString("source").takeIf { it.startsWith("http") }?.let {
                java.io.File(HostLists.dir(context), "$name.src").writeText(it)
            }
            restored++
        }
        AppLog.i("settings imported: ${settings.length()} settings, ${strategies.size} strategies, $restored lists")
        return Triple(settings.length(), strategies.size, restored)
    }
}
