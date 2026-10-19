package io.github.zapretdroid.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.zapretdroid.R
import io.github.zapretdroid.core.AppLog
import io.github.zapretdroid.core.ArgTemplates
import io.github.zapretdroid.core.Backup
import io.github.zapretdroid.core.ConfigCheck
import io.github.zapretdroid.core.FakePayloads
import io.github.zapretdroid.core.HostLists
import io.github.zapretdroid.core.Settings
import io.github.zapretdroid.core.Strategies
import io.github.zapretdroid.core.TorProxy
import io.github.zapretdroid.core.splitArgs

/** The rules of the settings file, with checks that need the app (strategy references, proxies). */
private fun rules(context: Context) = ConfigCheck.Rules(Settings.SCHEMA, Settings.CHOICES) { key, value ->
    when (key) {
        Settings.KEY_STRATEGY -> (value as String).takeIf { id -> id != Strategies.CUSTOM && Strategies.presets.none { it.id == id } }
            ?.let { "no strategy \"$it\": one of ${(Strategies.presets.map { it.id } + Strategies.CUSTOM).joinToString()}" }
        Settings.KEY_CUSTOM_ARGS -> {
            ArgTemplates.resolve(splitArgs(value as String), FakePayloads.install(context), HostLists.dir(context))
            null
        }
        Settings.KEY_PROXY_ADDRESS -> (value as String).takeIf { !TorProxy.isValid(it) }?.let { "not host:port: $it" }
        Settings.KEY_EXTRA_PROXIES -> (value as String).lines().filter { it.isNotBlank() }.firstNotNullOfOrNull { line ->
            val parts = line.split('\t')
            when {
                parts.size < 2 -> "\"$line\": name, \\t, address (and \\t 1 for UDP) expected"
                !TorProxy.isValid(parts[1]) -> "proxy ${parts[0]}: not host:port: ${parts[1]}"
                else -> null
            }
        }
        else -> null
    }
}

/**
 * The settings as one JSON text to edit by hand (the backup without host lists), checked
 * before it is applied: JSON errors with their line, wrong types, unknown words, strategies
 * with unknown references.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigEditorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(Backup.exportSettings(context)) }
    // Issues of the last check, and the text they belong to (applying needs a fresh check).
    var issues by remember { mutableStateOf<List<ConfigCheck.Issue>?>(null) }
    var checkedText by remember { mutableStateOf<String?>(null) }
    val check = {
        issues = ConfigCheck.check(text, rules(context))
        checkedText = text
    }
    val canApply = checkedText == text && issues?.none { it.level == ConfigCheck.Level.ERROR } == true

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.config_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .fillMaxSize(),
        ) {
            Text(
                stringResource(R.string.config_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                isError = checkedText == text && issues?.any { it.level == ConfigCheck.Level.ERROR } == true,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = check) { Text(stringResource(R.string.config_check)) }
                Button(enabled = canApply, onClick = {
                    runCatching { Backup.import(context, text, replace = true) }
                        .onSuccess { (n, strategies, _) ->
                            AppLog.i("settings editor: applied $n settings, $strategies strategies")
                            Toast.makeText(context, context.getString(R.string.config_applied, n), Toast.LENGTH_LONG).show()
                            onBack()
                        }
                        .onFailure {
                            issues = listOf(ConfigCheck.Issue(0, ConfigCheck.Level.ERROR, it.message ?: it.javaClass.simpleName))
                        }
                }) { Text(stringResource(R.string.config_apply)) }
                OutlinedButton(onClick = {
                    text = Backup.exportSettings(context)
                    issues = null
                    checkedText = null
                }) { Text(stringResource(R.string.config_reload)) }
            }
            val shown = issues
            when {
                shown == null -> Text(stringResource(R.string.config_hint), style = MaterialTheme.typography.bodySmall)
                checkedText != text -> Text(stringResource(R.string.config_changed), style = MaterialTheme.typography.bodySmall)
                shown.isEmpty() -> Text(stringResource(R.string.config_ok), color = MaterialTheme.colorScheme.primary)
                else -> LazyColumn(modifier = Modifier.heightIn(max = 180.dp).padding(bottom = 8.dp)) {
                    items(shown) { issue ->
                        val where = if (issue.line > 0) stringResource(R.string.config_line, issue.line) + ": " else ""
                        Text(
                            where + issue.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (issue.level == ConfigCheck.Level.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
