package io.github.zapretdroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.zapretdroid.R
import io.github.zapretdroid.core.AppLog
import io.github.zapretdroid.core.AutoFix
import io.github.zapretdroid.core.ConnMonitor
import io.github.zapretdroid.core.HostLists
import io.github.zapretdroid.core.Settings
import io.github.zapretdroid.test.AutoFixTester
import io.github.zapretdroid.test.FixCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The auto-fix: the sites that did not open in the VPN session, tried with strategies and
 * proxies; a site that a candidate opens can be given that candidate with one tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoFixScreen(settings: Settings, vpnActive: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    var hosts by remember { mutableStateOf(emptyList<String>()) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var extra by remember { mutableStateOf("") }
    // Bumped when rules change, so the applied list is read again.
    var rulesVersion by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        val failed = withContext(Dispatchers.IO) {
            ConnMonitor.failedHosts(AppLog.readTail(AppLog.file, 1024 * 1024))
        }
        hosts = (hosts + failed).distinct()
        selected = selected + failed.take(8)
    }
    val running = AutoFixTester.running
    val rules = remember(rulesVersion) { AutoFix.rules(context, settings).map { it to AutoFix.hosts(context, it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.autofix_title)) },
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
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(padding),
        ) {
            item {
                Text(
                    stringResource(R.string.autofix_intro, AutoFixTester.candidates(settings).size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Text(
                    stringResource(if (hosts.isEmpty()) R.string.autofix_none_failed else R.string.autofix_failed),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(hosts, key = { "h-$it" }) { host ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(value = host in selected, enabled = !running, role = Role.Checkbox) {
                            selected = if (it) selected + host else selected - host
                        },
                ) {
                    Checkbox(checked = host in selected, onCheckedChange = null, enabled = !running)
                    Text(host, modifier = Modifier.padding(start = 8.dp))
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = extra,
                        onValueChange = { extra = it },
                        label = { Text(stringResource(R.string.autofix_add_site)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        enabled = HostLists.normalize(extra).hosts.isNotEmpty(),
                        onClick = {
                            val added = HostLists.normalize(extra).hosts
                            hosts = (hosts + added).distinct()
                            selected = selected + added
                            extra = ""
                        },
                    ) { Text("+") }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    if (running) {
                        OutlinedButton(onClick = { AutoFixTester.cancel() }) { Text(stringResource(R.string.action_stop)) }
                    } else {
                        Button(enabled = selected.isNotEmpty(), onClick = {
                            AutoFixTester.start(context, settings, hosts.filter { it in selected })
                        }) { Text(stringResource(R.string.autofix_start, selected.size)) }
                    }
                }
            }
            if (running) {
                item {
                    val (done, total) = AutoFixTester.progress
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LinearProgressIndicator(progress = { if (total == 0) 0f else done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.autofix_progress, done, total, AutoFixTester.current), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (AutoFixTester.results.isNotEmpty()) {
                item {
                    Text(stringResource(R.string.autofix_results), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                }
                items(AutoFixTester.results, key = { "r-${it.host}" }) { r ->
                    val loaded = r.loaded
                    val answered = r.answered
                    ListItem(
                        headlineContent = { Text(r.host) },
                        supportingContent = {
                            Text(
                                when {
                                    r.note != null -> r.note
                                    loaded?.label == AutoFixTester.NONE -> stringResource(R.string.autofix_opens_plain)
                                    loaded != null -> stringResource(R.string.autofix_opens, loaded.label)
                                    answered != null -> stringResource(R.string.autofix_answers, answered.label)
                                    running -> stringResource(R.string.autofix_searching, r.tried)
                                    else -> stringResource(R.string.autofix_nothing, r.tried)
                                },
                            )
                        },
                        trailingContent = {
                            val pick: FixCandidate? = loaded?.takeIf { it.label != AutoFixTester.NONE }
                                ?: answered?.takeIf { !running && loaded == null && it.label != AutoFixTester.NONE }
                            if (pick != null) {
                                FilledTonalButton(onClick = {
                                    AutoFix.apply(context, settings, r.host, pick.label, pick.args)
                                    rulesVersion++
                                }) { Text(stringResource(R.string.autofix_apply)) }
                            }
                        },
                    )
                }
            }
            item {
                Text(
                    stringResource(if (rules.isEmpty()) R.string.autofix_no_rules else R.string.autofix_rules),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            if (rules.isNotEmpty() && vpnActive) {
                item {
                    Text(stringResource(R.string.autofix_restart), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            rules.forEach { (rule, ruleHosts) ->
                items(ruleHosts, key = { "a-${rule.list}-$it" }) { host ->
                    ListItem(
                        headlineContent = { Text(host) },
                        supportingContent = { Text(rule.label) },
                        trailingContent = {
                            IconButton(onClick = {
                                AutoFix.remove(context, settings, host)
                                rulesVersion++
                            }) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.autofix_remove)) }
                        },
                    )
                }
            }
        }
    }
}
