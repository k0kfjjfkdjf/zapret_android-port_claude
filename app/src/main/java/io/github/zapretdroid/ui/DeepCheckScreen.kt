package io.github.zapretdroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.zapretdroid.R
import io.github.zapretdroid.core.AppLog
import io.github.zapretdroid.core.HostLists
import io.github.zapretdroid.core.Settings
import io.github.zapretdroid.core.SavedStrategies
import io.github.zapretdroid.core.Strategies
import io.github.zapretdroid.test.DeepCheck
import io.github.zapretdroid.test.DeepConfig
import io.github.zapretdroid.test.DeepResult
import io.github.zapretdroid.test.StrategySet
import io.github.zapretdroid.test.StrategyTester
import io.github.zapretdroid.test.TestPlan
import io.github.zapretdroid.test.TlsMode

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DeepCheckScreen(settings: Settings, onBack: () -> Unit, onApplied: () -> Unit) {
    val context = LocalContext.current
    val siteGroups = remember { TestPlan.siteGroups(context) }
    val listNames = remember { HostLists.names(context) }
    // Sources: "group:<id>" for the built-in site groups, "list:<name>" for host lists.
    var sources by rememberSaveable { mutableStateOf(setOf("group:discord-full", "group:youtube")) }
    var set by rememberSaveable { mutableStateOf(StrategySet.COMMUNITY) }
    var limit by rememberSaveable { mutableStateOf("300") }
    var timeout by rememberSaveable { mutableStateOf("5") }
    var attempts by rememberSaveable { mutableStateOf("2") }
    var parallel by rememberSaveable { mutableStateOf("16") }
    var delay by rememberSaveable { mutableStateOf("0") }
    var tls by rememberSaveable { mutableStateOf(TlsMode.ANY) }
    val running = DeepCheck.running
    var multi by remember { mutableStateOf<DeepCheck.Multi?>(null) }

    val all = remember(sources) {
        sources.sorted().flatMap { s ->
            when {
                s.startsWith("group:") -> siteGroups.firstOrNull { "group:${it.id}" == s }?.hosts.orEmpty()
                s.startsWith("list:") -> HostLists.read(context, s.removePrefix("list:")).lines().map { it.trim() }.filter { it.isNotEmpty() }
                else -> emptyList()
            }
        }.distinct()
    }
    val max = limit.toIntOrNull()?.takeIf { it > 0 } ?: all.size
    // An evenly spread sample keeps the variety of a big list.
    val domains = remember(all, max) { if (all.size <= max) all else List(max) { all[(it.toLong() * all.size / max).toInt()] } }
    val strategies = remember(set) { TestPlan.strategies(context, set, TestPlan.DEFAULT_SNI) }
    val attemptsN = attempts.toIntOrNull()?.coerceIn(1, 5) ?: 1
    val timeoutS = timeout.toIntOrNull()?.coerceIn(1, 30) ?: 5
    val parallelN = parallel.toIntOrNull()?.coerceIn(1, 64) ?: 16
    // Worst case: every probe waits for the timeout.
    val probes = domains.size.toLong() * (strategies.size + 1) * attemptsN
    val worstMinutes = probes * timeoutS / parallelN / 60
    val trafficMb = probes * 32 / 1024

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.deep_title)) },
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(padding),
        ) {
            item {
                Text(stringResource(R.string.deep_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { Text(stringResource(R.string.deep_sources), style = MaterialTheme.typography.titleSmall) }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    siteGroups.forEach { g ->
                        val key = "group:${g.id}"
                        FilterChip(
                            selected = key in sources, enabled = !running,
                            onClick = { sources = if (key in sources) sources - key else sources + key },
                            label = { Text(stringResource(g.title) + " · " + g.hosts.size) },
                        )
                    }
                    listNames.forEach { name ->
                        val key = "list:$name"
                        FilterChip(
                            selected = key in sources, enabled = !running,
                            onClick = { sources = if (key in sources) sources - key else sources + key },
                            label = { Text("$name · ${HostLists.count(context, name)}") },
                        )
                    }
                }
            }
            if (listNames.isEmpty()) {
                item { Text(stringResource(R.string.deep_no_lists), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            item { Text(stringResource(R.string.deep_strategies), style = MaterialTheme.typography.titleSmall) }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(StrategySet.QUICK to R.string.test_quick, StrategySet.FULL to R.string.test_full, StrategySet.OURS to R.string.test_ours, StrategySet.COMMUNITY to R.string.test_community)
                        .forEach { (option, label) ->
                            FilterChip(
                                selected = set == option, enabled = !running, onClick = { set = option },
                                label = { Text(stringResource(label, TestPlan.strategies(context, option, TestPlan.DEFAULT_SNI).size)) },
                            )
                        }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(limit, { limit = it }, R.string.deep_limit, !running, Modifier.weight(1f))
                    NumberField(timeout, { timeout = it }, R.string.deep_timeout, !running, Modifier.weight(1f))
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(attempts, { attempts = it }, R.string.deep_attempts, !running, Modifier.weight(1f))
                    NumberField(parallel, { parallel = it }, R.string.deep_parallel, !running, Modifier.weight(1f))
                    NumberField(delay, { delay = it }, R.string.deep_delay, !running, Modifier.weight(1f))
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("TLS", style = MaterialTheme.typography.bodyMedium)
                    listOf(TlsMode.ANY to R.string.test_tls_auto, TlsMode.TLS12 to R.string.test_tls12, TlsMode.TLS13 to R.string.test_tls13, TlsMode.CHROME to R.string.test_tls_chrome).forEach { (mode, label) ->
                        FilterChip(
                            selected = tls == mode,
                            enabled = !running && (mode != TlsMode.TLS13 || android.os.Build.VERSION.SDK_INT >= 29),
                            onClick = { tls = mode },
                            label = { Text(stringResource(label)) },
                        )
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.deep_estimate, domains.size, all.size, strategies.size + 1, probes, worstMinutes, trafficMb),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item {
                if (running) {
                    OutlinedButton(onClick = { DeepCheck.cancel() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.test_stop)) }
                } else {
                    Button(
                        enabled = domains.isNotEmpty() && !StrategyTester.running,
                        onClick = {
                            DeepCheck.start(
                                context,
                                DeepConfig(
                                    domains = domains, strategies = strategies, timeoutMs = timeoutS * 1000, attempts = attemptsN,
                                    parallel = parallelN, delayMs = delay.toIntOrNull()?.coerceIn(0, 60_000) ?: 0, tls = tls,
                                    dns = settings.dns, sources = sources.sorted().joinToString() + ", sample ${domains.size} of ${all.size}",
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.deep_start)) }
                }
            }
            if (running) {
                item {
                    val (s, sTotal) = DeepCheck.strategyProgress
                    val (d, dTotal) = DeepCheck.domainProgress
                    Column {
                        LinearProgressIndicator(progress = { if (sTotal == 0) 0f else (s + d.toFloat() / maxOf(dTotal, 1)) / sTotal }, modifier = Modifier.fillMaxWidth())
                        val minutes = (System.currentTimeMillis() - DeepCheck.startedAt) / 60_000
                        Text(
                            stringResource(R.string.deep_progress, s, sTotal, d, dTotal, minutes, DeepCheck.currentArgs),
                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            DeepCheck.message?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (DeepCheck.runDir != null && !running) {
                item {
                    OutlinedButton(
                        onClick = {
                            DeepCheck.zip(context)?.let { shareFile(context, it, it.name, "deep check results shared") }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.deep_share)) }
                }
                if (DeepCheck.results.size > 1) {
                    item {
                        Button(onClick = { multi = DeepCheck.buildMulti(context) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.multi_build))
                        }
                    }
                }
            }
            multi?.let { m ->
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.multi_result, m.parts, m.covered, m.openable, m.total), style = MaterialTheme.typography.titleSmall)
                            Text(m.command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            if (m.lists.isNotEmpty()) {
                                Text(stringResource(R.string.multi_lists, m.lists.joinToString()), style = MaterialTheme.typography.labelSmall)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = {
                                    AppLog.i("deep check: applied multi-strategy '${m.command}'", source = "test")
                                    settings.customArgs = m.command
                                    SavedStrategies.remember(context, m.command, "multi ${m.covered}/${m.total}")
                                    settings.strategyId = Strategies.CUSTOM
                                    onApplied()
                                }) { Text(stringResource(R.string.test_apply)) }
                                TextButton(
                                    enabled = !running,
                                    onClick = {
                                        DeepCheck.lastConfig?.let { c ->
                                            DeepCheck.start(context, c.copy(domains = DeepCheck.domains, strategies = listOf(m.command), sources = c.sources + ", multi-strategy re-check"))
                                        }
                                        multi = null
                                    },
                                ) { Text(stringResource(R.string.multi_verify)) }
                            }
                        }
                    }
                }
            }
            val total = DeepCheck.domains.size
            DeepCheck.baseline?.let { base ->
                item { Text(stringResource(R.string.deep_baseline, base.count, total), style = MaterialTheme.typography.titleSmall) }
            }
            items(DeepCheck.results.take(50), key = { it.index }) { r ->
                DeepRow(r, total) {
                    AppLog.i("deep check: applied '${r.args}' (${r.count}/$total)", source = "test")
                    settings.customArgs = r.args
                    SavedStrategies.remember(context, r.args, "deep ${r.count}/$total")
                    settings.strategyId = Strategies.CUSTOM
                    onApplied()
                }
            }
        }
    }
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, label: Int, enabled: Boolean, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(6)) },
        enabled = enabled,
        singleLine = true,
        label = { Text(stringResource(label)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun DeepRow(r: DeepResult, total: Int, onApply: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("${r.count} / $total · s${r.index}", style = MaterialTheme.typography.titleSmall)
                if (r.respondedCount != r.count) {
                    Text(stringResource(R.string.test_responded, r.respondedCount, total), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(r.args, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                val reasons = r.reasons.entries.sortedByDescending { it.value }.joinToString(" ") { "${it.key}×${it.value}" }
                if (reasons.isNotEmpty()) Text(reasons, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (r.count > 0) TextButton(onClick = onApply) { Text(stringResource(R.string.test_apply)) }
        }
    }
}
