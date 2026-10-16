package io.github.zapretdroid.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.zapretdroid.R
import io.github.zapretdroid.core.AppCollect
import io.github.zapretdroid.core.AppLog
import io.github.zapretdroid.core.HostLists
import io.github.zapretdroid.core.Settings
import io.github.zapretdroid.service.VpnState

/**
 * "Collect an app's domains": the VPN routes only the chosen app while the user uses it; the
 * engine's connection log then gives every host it connected to, saved as a host list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectScreen(settings: Settings, state: VpnState, onStart: () -> Unit, onStop: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val apps = remember { loadApps(context.packageManager, context.packageName) }
    var filter by rememberSaveable { mutableStateOf("") }
    var collecting by remember { mutableStateOf(settings.collectApp) }
    var result by remember { mutableStateOf<AppCollect.Result?>(null) }
    val label = apps.firstOrNull { it.packageName == collecting }?.label ?: collecting

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.collect_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(padding),
        ) {
            item { Text(stringResource(R.string.collect_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (collecting.isNotEmpty()) {
                item {
                    Text(
                        stringResource(if (state == VpnState.RUNNING) R.string.collect_running else R.string.collect_starting, label),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                item {
                    Button(
                        onClick = {
                            settings.collectApp = ""
                            collecting = ""
                            onStop()
                            val r = AppCollect.parse(AppLog.readTail(AppLog.file, 4 * 1024 * 1024))
                            AppLog.i("collect: ${r.domains.size} domains, ${r.ips.size} IPs, ${r.udp.size} UDP flows, ${r.blocks.size} hosts with blocks")
                            result = r
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.collect_stop)) }
                }
            }
            result?.let { r ->
                item {
                    Text(stringResource(R.string.collect_result, r.domains.size, r.ips.size, r.udp.size), style = MaterialTheme.typography.titleSmall)
                }
                if (r.domains.isNotEmpty()) {
                    item {
                        OutlinedButton(
                            onClick = {
                                val name = "collect_" + lastApp(context).substringAfterLast('.').replace(Regex("[^A-Za-z0-9_-]"), "_").take(30)
                                val saved = HostLists.save(context, name, r.domains.keys.sorted().joinToString("\n"))
                                AppLog.i("collect: saved ${saved.hosts.size} domains as host list '$name'")
                                Toast.makeText(context, context.getString(R.string.collect_saved, saved.hosts.size, name), Toast.LENGTH_LONG).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.collect_save)) }
                    }
                }
                items(r.domains.entries.sortedByDescending { it.value }.map { it.key to it.value }) { (host, count) ->
                    val blocked = r.blocks[host]
                    Text(
                        "$host ×$count" + (blocked?.let { "  ⚠ " + context.getString(R.string.collect_blocked, it) } ?: ""),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (blocked != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (r.ips.isNotEmpty() || r.udp.isNotEmpty()) {
                    item { Text(stringResource(R.string.collect_ips), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp)) }
                    items((r.ips.entries + r.udp.entries).map { it.key to it.value }) { (key, count) ->
                        Text("$key ×$count", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (collecting.isEmpty()) {
                item {
                    OutlinedTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.collect_pick)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                items(apps.filter { filter.isBlank() || it.label.contains(filter, true) || it.packageName.contains(filter, true) }, key = { it.packageName }) { app ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                AppLog.i("collect: started for ${app.packageName}")
                                lastAppPrefs(context).edit().putString("last", app.packageName).apply()
                                settings.collectApp = app.packageName
                                collecting = app.packageName
                                result = null
                                onStart()
                            }
                            .padding(vertical = 8.dp),
                    ) {
                        Text(app.label, style = MaterialTheme.typography.bodyMedium)
                        Text(app.packageName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun lastAppPrefs(context: android.content.Context) = context.getSharedPreferences("collect", android.content.Context.MODE_PRIVATE)

private fun lastApp(context: android.content.Context) = lastAppPrefs(context).getString("last", "app") ?: "app"
