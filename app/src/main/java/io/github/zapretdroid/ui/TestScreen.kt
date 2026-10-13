package io.github.zapretdroid.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.zapretdroid.R
import io.github.zapretdroid.core.AppLog
import io.github.zapretdroid.core.Settings
import io.github.zapretdroid.core.Strategies
import io.github.zapretdroid.test.ProbeResult
import io.github.zapretdroid.test.StrategyResult
import io.github.zapretdroid.test.StrategySet
import io.github.zapretdroid.test.StrategyTester
import io.github.zapretdroid.test.TestPlan
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TestScreen(settings: Settings, onBack: () -> Unit, onApplied: () -> Unit) {
    val context = LocalContext.current
    var groups by rememberSaveable { mutableStateOf(setOf("youtube", "googlevideo")) }
    var custom by rememberSaveable { mutableStateOf("") }
    var set by rememberSaveable { mutableStateOf(StrategySet.QUICK) }
    var sni by rememberSaveable { mutableStateOf(TestPlan.DEFAULT_SNI) }
    var attempts by rememberSaveable { mutableIntStateOf(1) }
    val siteGroups = remember { TestPlan.siteGroups(context) }
    val candidates = remember(set, sni) { TestPlan.strategies(context, set, sni) }
    val hosts = remember(groups, custom) {
        (siteGroups.filter { it.id in groups }.flatMap { it.hosts } +
            custom.split(Regex("[\\s,]+")).map { it.trim().removePrefix("https://").substringBefore('/') }.filter { it.contains('.') })
            .distinct()
    }
    val running = StrategyTester.running
    val (done, total) = StrategyTester.progress

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_test)) },
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
                Text(
                    stringResource(R.string.test_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    siteGroups.forEach { group ->
                        FilterChip(
                            selected = group.id in groups,
                            enabled = !running,
                            onClick = { groups = if (group.id in groups) groups - group.id else groups + group.id },
                            label = { Text(stringResource(group.title) + " · " + group.hosts.size) },
                        )
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    enabled = !running,
                    label = { Text(stringResource(R.string.test_custom_sites)) },
                    placeholder = { Text("example.com") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StrategySet.entries.forEach { option ->
                        val count = if (option == set) candidates.size else TestPlan.strategies(context, option, sni).size
                        FilterChip(
                            selected = set == option,
                            enabled = !running,
                            onClick = { set = option },
                            label = {
                                Text(
                                    stringResource(
                                        when (option) {
                                            StrategySet.QUICK -> R.string.test_quick
                                            StrategySet.FULL -> R.string.test_full
                                            StrategySet.COMMUNITY -> R.string.test_community
                                            StrategySet.UDP -> R.string.test_udp
                                        },
                                        count,
                                    ),
                                )
                            },
                        )
                    }
                }
            }
            if (set == StrategySet.UDP) {
                item {
                    Text(
                        stringResource(R.string.test_udp_hint, TestPlan.stunServers.joinToString { "${it.host}:${it.port}" }),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (set == StrategySet.COMMUNITY) {
                item {
                    OutlinedTextField(
                        value = sni,
                        onValueChange = { sni = it.trim() },
                        enabled = !running,
                        singleLine = true,
                        label = { Text(stringResource(R.string.test_sni)) },
                        supportingText = { Text(stringResource(R.string.test_sni_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.test_attempts), style = MaterialTheme.typography.bodyMedium)
                    (1..3).forEach { n ->
                        FilterChip(
                            selected = attempts == n,
                            enabled = !running,
                            onClick = { attempts = n },
                            label = { Text(n.toString()) },
                        )
                    }
                }
            }
            item {
                if (running) {
                    OutlinedButton(onClick = { StrategyTester.cancel() }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.test_stop))
                    }
                } else {
                    Button(
                        onClick = { StrategyTester.start(context, hosts, candidates, settings.dns, attempts, udp = set == StrategySet.UDP) },
                        enabled = hosts.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        val targets = if (set == StrategySet.UDP) TestPlan.udpTargets(hosts).size else hosts.size
                        Text(stringResource(R.string.test_start, targets, candidates.size))
                    }
                }
            }
            StrategyTester.report?.takeIf { !running }?.let { file ->
                item {
                    OutlinedButton(
                        onClick = {
                            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(file.lastModified()))
                            shareFile(context, file, "zapretdroid-test-$stamp.txt", "strategy test report shared")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.test_share_report))
                    }
                }
            }
            if (running || done > 0) {
                item {
                    Column {
                        LinearProgressIndicator(
                            progress = { if (total == 0) 0f else done.toFloat() / total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (running) {
                            Text(
                                stringResource(R.string.test_progress, done, total, StrategyTester.currentArgs.ifEmpty { "—" }),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
            StrategyTester.message?.let { msg ->
                item { Text(msg, color = MaterialTheme.colorScheme.error) }
            }
            item {
                Text(
                    stringResource(R.string.test_status_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StrategyTester.baseline?.let { base ->
                item {
                    val allOk = base.passed == base.probes.size
                    Text(
                        stringResource(
                            if (allOk) R.string.test_baseline_ok else R.string.test_baseline,
                            base.passed, base.probes.size,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
            items(StrategyTester.results, key = { it.args }) { result ->
                ResultCard(
                    result = result,
                    onApply = {
                        AppLog.i("strategy test: applied '${result.args}' (${result.passed}/${result.probes.size})", source = "test")
                        settings.customArgs = result.args
                        settings.strategyId = Strategies.CUSTOM
                        onApplied()
                    },
                )
            }
        }
    }
}

@Composable
private fun ProbeLine(probe: ProbeResult) {
    val timings = listOfNotNull(
        probe.tlsMs?.let { stringResource(R.string.test_tls_ms, it) },
        stringResource(R.string.test_total_ms, probe.totalMs),
    ).joinToString(" · ")
    val text = if (probe.ok) {
        stringResource(R.string.test_probe_ok, probe.host, probe.detail, timings)
    } else {
        stringResource(R.string.test_probe_fail, probe.host, probe.detail, timings)
    }
    Text(
        text + (probe.address?.let { " · $it" } ?: ""),
        style = MaterialTheme.typography.bodySmall,
        color = if (probe.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun ResultCard(result: StrategyResult, onApply: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val total = result.probes.size
    val color = when {
        result.passed == total -> MaterialTheme.colorScheme.primaryContainer
        result.passed == 0 -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = color),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.test_result_score, result.passed, total, result.averageMillis),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(result.args, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                if (result.passed > 0) {
                    TextButton(onClick = onApply) { Text(stringResource(R.string.test_apply)) }
                }
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(top = 8.dp)) {
                    result.probes.forEach { probe -> ProbeLine(probe) }
                }
            }
        }
    }
}
