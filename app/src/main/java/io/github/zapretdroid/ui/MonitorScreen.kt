package io.github.zapretdroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.zapretdroid.R
import io.github.zapretdroid.core.AppLog
import io.github.zapretdroid.core.ConnMonitor
import io.github.zapretdroid.core.ConnMonitor.Kind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Live view of the engine's connection log for the current VPN session. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorScreen(vpnRunning: Boolean, onBack: () -> Unit) {
    var discordOnly by rememberSaveable { mutableStateOf(true) }
    var events by remember { mutableStateOf(emptyList<ConnMonitor.Event>()) }
    LaunchedEffect(Unit) {
        while (true) {
            events = withContext(Dispatchers.IO) { ConnMonitor.parse(AppLog.readTail(AppLog.file, 1024 * 1024)) }
            delay(1000)
        }
    }
    val shown = (if (discordOnly) events.filter { it.discord } else events).asReversed()
    val summary = ConnMonitor.summarize(if (discordOnly) events.filter { it.discord } else events)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.monitor_title)) },
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
                    stringResource(if (vpnRunning) R.string.monitor_intro else R.string.monitor_vpn_off),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = discordOnly, onClick = { discordOnly = true }, label = { Text(stringResource(R.string.monitor_discord)) })
                    FilterChip(selected = !discordOnly, onClick = { discordOnly = false }, label = { Text(stringResource(R.string.monitor_all)) })
                }
            }
            item {
                Text(
                    stringResource(R.string.monitor_summary, summary.connections, summary.blocks, summary.freezes, summary.retries, summary.udpNoReply),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            items(shown) { e -> EventLine(e) }
        }
    }
}

@Composable
private fun EventLine(e: ConnMonitor.Event) {
    val (mark, label, color) = when (e.kind) {
        Kind.CONNECT -> Triple("→", R.string.monitor_connect, MaterialTheme.colorScheme.onSurface)
        Kind.CLOSED -> Triple("✓", R.string.monitor_closed, MaterialTheme.colorScheme.onSurfaceVariant)
        Kind.FREEZE -> Triple("❄", R.string.monitor_freeze, MaterialTheme.colorScheme.tertiary)
        Kind.BLOCK -> Triple("✗", R.string.monitor_block, MaterialTheme.colorScheme.error)
        Kind.TCP_FAILED -> Triple("✗", R.string.monitor_tcp_failed, MaterialTheme.colorScheme.error)
        Kind.TCP_SLOW -> Triple("…", R.string.monitor_tcp_slow, MaterialTheme.colorScheme.tertiary)
        Kind.UDP_START -> Triple("⇢", R.string.monitor_udp_start, MaterialTheme.colorScheme.onSurface)
        Kind.UDP_REPLY -> Triple("⇠", R.string.monitor_udp_reply, MaterialTheme.colorScheme.primary)
        Kind.UDP_RETRY -> Triple("⟳", R.string.monitor_udp_retry, MaterialTheme.colorScheme.tertiary)
        Kind.UDP_NO_REPLY -> Triple("✗", R.string.monitor_udp_no_reply, MaterialTheme.colorScheme.error)
        Kind.UDP_CLOSED -> Triple("✓", R.string.monitor_udp_closed, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row {
        Text(mark, color = color, fontFamily = FontFamily.Monospace, modifier = Modifier.width(20.dp))
        Column {
            Text("${e.time}  ${stringResource(label)}", style = MaterialTheme.typography.labelMedium, color = color)
            Text(e.text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}
