package io.github.zapretdroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.zapretdroid.R
import io.github.zapretdroid.core.Settings
import io.github.zapretdroid.test.CheckItem
import io.github.zapretdroid.test.CheckStatus
import io.github.zapretdroid.test.SelfCheck
import io.github.zapretdroid.test.StrategyTester
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelfCheckScreen(settings: Settings, onBack: () -> Unit) {
    val context = LocalContext.current
    val running = SelfCheck.running
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.selfcheck_title)) },
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
            item {
                Text(
                    stringResource(R.string.selfcheck_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Button(
                    onClick = { SelfCheck.start(context, settings) },
                    enabled = !running && !StrategyTester.running,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(if (running) R.string.selfcheck_running else R.string.selfcheck_start))
                }
            }
            if (running) {
                item {
                    Column {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(SelfCheck.current, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            SelfCheck.report?.takeIf { !running }?.let { file ->
                item {
                    val counts = SelfCheck.items.groupingBy { it.status }.eachCount()
                    Text(
                        stringResource(
                            R.string.selfcheck_summary,
                            counts[CheckStatus.OK] ?: 0, counts[CheckStatus.WARN] ?: 0, counts[CheckStatus.FAIL] ?: 0,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                item {
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(file.lastModified()))
                    ReportButtons(file, "byezapret-selfcheck-$stamp.txt", "self-check report", R.string.selfcheck_share)
                }
            }
            items(SelfCheck.items) { check -> CheckLine(check) }
        }
    }
}

@Composable
private fun CheckLine(check: CheckItem) {
    val (mark, color) = when (check.status) {
        CheckStatus.OK -> "✓" to MaterialTheme.colorScheme.primary
        CheckStatus.WARN -> "!" to MaterialTheme.colorScheme.tertiary
        CheckStatus.FAIL -> "✗" to MaterialTheme.colorScheme.error
        CheckStatus.INFO -> "i" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row {
        Text(mark, color = color, fontFamily = FontFamily.Monospace, modifier = Modifier.width(20.dp))
        Column {
            Text("${check.group} · ${check.name}", style = MaterialTheme.typography.bodyMedium)
            Text(check.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
