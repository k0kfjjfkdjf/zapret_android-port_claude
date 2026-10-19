package io.github.zapretdroid.core

import io.github.zapretdroid.core.ConfigCheck.Kind
import io.github.zapretdroid.core.ConfigCheck.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigCheckTest {
    private val rules = ConfigCheck.Rules(
        schema = mapOf("dns" to Kind.STRING, "ipv6" to Kind.BOOLEAN, "apps" to Kind.SET, "dns_mode" to Kind.STRING),
        choices = mapOf("dns_mode" to listOf("PLAIN", "DOH")),
        value = { key, value -> if (key == "dns" && value == "bad") "not an address" else null },
    )

    private fun doc(settings: String) = "{\n  \"app\": \"ByeZapret\",\n  \"format\": 1,\n  \"settings\": {\n$settings\n  },\n  \"strategies\": \"\"\n}"

    @Test
    fun goodFile() {
        val text = doc("    \"dns\": \"8.8.8.8\",\n    \"ipv6\": false,\n    \"apps\": {\"set\": [\"a.b\"]},\n    \"dns_mode\": \"DOH\"")
        assertEquals(emptyList<ConfigCheck.Issue>(), ConfigCheck.check(text, rules))
    }

    @Test
    fun syntaxErrorHasItsLine() {
        val text = doc("    \"dns\": \"8.8.8.8\"\n    \"ipv6\": false")
        val issues = ConfigCheck.check(text, rules)
        assertEquals(1, issues.size)
        assertEquals(Level.ERROR, issues[0].level)
        assertEquals(6, issues[0].line)
    }

    @Test
    fun typesChoicesAndRules() {
        val text = doc("    \"dns\": \"bad\",\n    \"ipv6\": \"yes\",\n    \"apps\": [\"a\"],\n    \"dns_mode\": \"FAST\",\n    \"colour\": 1")
        val issues = ConfigCheck.check(text, rules).associateBy { it.line }
        assertTrue(issues[5]!!.message.contains("not an address"))
        assertTrue(issues[6]!!.message.contains("true or false"))
        assertTrue(issues[7]!!.message.contains("set"))
        assertTrue(issues[8]!!.message.contains("PLAIN, DOH"))
        assertEquals(Level.WARNING, issues[9]!!.level)
    }

    @Test
    fun notOurFile() {
        val issues = ConfigCheck.check("{\"settings\": 3}", rules)
        assertEquals(2, issues.size)
        assertTrue(issues.all { it.level == Level.ERROR })
    }
}
