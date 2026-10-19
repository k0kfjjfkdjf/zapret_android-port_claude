package io.github.zapretdroid.core

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Checks the settings file as the settings editor shows it (the backup format without host
 * lists): JSON syntax with the line of the error, the type of every known setting, the values of
 * settings that take one of a few words, and whatever [Rules.value] adds per setting.
 */
object ConfigCheck {
    enum class Kind { BOOLEAN, INT, LONG, FLOAT, STRING, SET }

    enum class Level { ERROR, WARNING }

    /** [line] is 1-based; 0 when the place is not known. */
    data class Issue(val line: Int, val level: Level, val message: String)

    class Rules(
        val schema: Map<String, Kind>,
        /** Settings that take one of these words. */
        val choices: Map<String, Collection<String>> = emptyMap(),
        /** A further check of a setting's value: an error message, or null. */
        val value: (key: String, value: Any) -> String? = { _, _ -> null },
    )

    // "… at character 25 of {…}" on Android, "… at 25 [character 3 line 2]" in the JSON-java jar.
    private val AT_CHARACTER = Regex("""\bat (?:character )?(\d+)""")

    fun lineOf(text: String, index: Int): Int {
        var line = 1
        for (i in 0 until minOf(index, text.length)) if (text[i] == '\n') line++
        return line
    }

    /** The line of the first `"key":` in [text], or 0. */
    fun lineOfKey(text: String, key: String): Int {
        val at = Regex("\"" + Regex.escape(key) + "\"\\s*:").find(text)?.range?.first ?: return 0
        return lineOf(text, at)
    }

    fun check(text: String, rules: Rules): List<Issue> {
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            val message = e.message ?: "invalid JSON"
            val at = AT_CHARACTER.find(message)?.groupValues?.get(1)?.toIntOrNull()
            // org.json repeats the whole input after "of": keep only the reason.
            return listOf(Issue(at?.let { lineOf(text, it) } ?: 0, Level.ERROR, message.substringBefore(" at ")))
        }
        val issues = mutableListOf<Issue>()
        if (root.optString("app") !in setOf("ByeZapret", "ZapretDroid")) {
            issues += Issue(lineOfKey(text, "app"), Level.ERROR, "\"app\" must be \"ByeZapret\"")
        }
        val settings = root.opt("settings")
        if (settings !is JSONObject) {
            issues += Issue(lineOfKey(text, "settings"), Level.ERROR, "\"settings\" must be an object")
            return issues
        }
        root.opt("strategies")?.let { if (it !is String) issues += Issue(lineOfKey(text, "strategies"), Level.ERROR, "\"strategies\" must be a string") }
        settings.keys().forEach { key ->
            val line = lineOfKey(text, key)
            val raw = settings.get(key)
            val kind = rules.schema[key]
            if (kind == null) {
                issues += Issue(line, Level.WARNING, "unknown setting \"$key\": it is kept but nothing reads it")
                return@forEach
            }
            val value = typed(raw, kind)
            if (value == null) {
                issues += Issue(line, Level.ERROR, "\"$key\" must be ${describe(kind)}")
                return@forEach
            }
            rules.choices[key]?.let { allowed ->
                if (value !in allowed) issues += Issue(line, Level.ERROR, "\"$key\" must be one of: ${allowed.joinToString()}")
            }
            runCatching { rules.value(key, value) }.getOrElse { it.message ?: it.javaClass.simpleName }?.let {
                issues += Issue(line, Level.ERROR, "\"$key\": $it")
            }
        }
        return issues
    }

    /** The value as the setting stores it (a set as Set<String>), or null if it has another type. */
    fun typed(raw: Any?, kind: Kind): Any? = when (kind) {
        Kind.BOOLEAN -> raw as? Boolean
        Kind.INT -> (raw as? Int)
        Kind.LONG -> (raw as? Long) ?: (raw as? Int)?.toLong()
        Kind.FLOAT -> (raw as? Number)?.takeIf { it !is Boolean }?.toDouble()
        Kind.STRING -> raw as? String
        Kind.SET -> (raw as? JSONObject)?.opt("set")?.let { it as? JSONArray }?.let { arr ->
            (0 until arr.length()).map { arr.opt(it) as? String ?: return null }.toSet()
        }
    }

    private fun describe(kind: Kind) = when (kind) {
        Kind.BOOLEAN -> "true or false"
        Kind.INT, Kind.LONG -> "a whole number"
        Kind.FLOAT -> "a number"
        Kind.STRING -> "a string in quotes"
        Kind.SET -> "{\"set\": [\"…\", …]}"
    }
}
