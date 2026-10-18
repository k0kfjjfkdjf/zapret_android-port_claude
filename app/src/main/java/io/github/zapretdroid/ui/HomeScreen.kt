package io.github.zapretdroid.ui

import android.text.format.Formatter
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.zapretdroid.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import io.github.zapretdroid.core.SavedStrategies
import io.github.zapretdroid.core.SavedStrategy
import io.github.zapretdroid.core.Strategies
import io.github.zapretdroid.service.VpnState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: VpnState,
    error: String?,
    txBytes: Long,
    rxBytes: Long,
    strategyName: String,
    strategyArgs: String,
    privateDns: String?,
    onPickPreset: (String) -> Unit,
    onPickCustom: (String) -> Unit,
    onToggle: () -> Unit,
    onDismissError: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenMonitor: () -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenLogs) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.title_logs))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.title_settings))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            PowerButton(state = state, onClick = onToggle)
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(
                    when (state) {
                        VpnState.STOPPED -> R.string.state_stopped
                        VpnState.STARTING -> R.string.state_starting
                        VpnState.RUNNING -> R.string.state_running
                        VpnState.STOPPING -> R.string.state_stopping
                    },
                ),
                style = MaterialTheme.typography.headlineSmall,
            )
            if (state == VpnState.RUNNING) {
                val context = LocalContext.current
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        R.string.traffic,
                        Formatter.formatShortFileSize(context, txBytes),
                        Formatter.formatShortFileSize(context, rxBytes),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            if (error != null) {
                ErrorCard(error, onDismissError, onOpenLogs)
                Spacer(Modifier.height(12.dp))
            }
            if (privateDns != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                ) {
                    Text(
                        stringResource(R.string.private_dns_warning, privateDns),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            Card(
                onClick = { picking = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.current_strategy),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(strategyName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        strategyArgs,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.strategy_tap_to_change),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            FilledTonalButton(onClick = onOpenMonitor) { Text(stringResource(R.string.monitor_open)) }
        }
    }
    if (picking) {
        StrategyPicker(
            onDismiss = { picking = false },
            onPickPreset = { picking = false; onPickPreset(it) },
            onPickCustom = { picking = false; onPickCustom(it) },
            onOpenSettings = { picking = false; onOpenSettings() },
        )
    }
}

@Composable
private fun StrategyPicker(onDismiss: () -> Unit, onPickPreset: (String) -> Unit, onPickCustom: (String) -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    var saved by remember { mutableStateOf(SavedStrategies.all(context)) }
    val refresh = { saved = SavedStrategies.all(context) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.strategy_pick)) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val pinned = saved.filter { it.pinned }
                val history = saved.filter { !it.pinned }
                if (pinned.isNotEmpty()) {
                    item { PickerHeader(R.string.strategy_pinned) }
                    items(pinned, key = { "p:" + it.args }) { s ->
                        SavedRow(s, onPick = { onPickCustom(s.args) }, onPin = {
                            SavedStrategies.update(context, s.args) { it.copy(pinned = false) }
                            refresh()
                        }, onDelete = null)
                    }
                }
                item { PickerHeader(R.string.strategy_presets) }
                items(Strategies.presets, key = { "s:" + it.id }) { preset ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPickPreset(preset.id) }
                            .padding(vertical = 6.dp),
                    ) {
                        Text(stringResource(preset.title), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(preset.description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (history.isNotEmpty()) {
                    item { PickerHeader(R.string.strategy_history) }
                    items(history, key = { "h:" + it.args }) { s ->
                        SavedRow(s, onPick = { onPickCustom(s.args) }, onPin = {
                            SavedStrategies.update(context, s.args) { it.copy(pinned = true) }
                            refresh()
                        }, onDelete = {
                            SavedStrategies.update(context, s.args) { null }
                            refresh()
                        })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.title_settings)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun PickerHeader(title: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun SavedRow(s: SavedStrategy, onPick: () -> Unit, onPin: () -> Unit, onDelete: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .weight(1f)
                .clickable(onClick = onPick)
                .padding(vertical = 6.dp),
        ) {
            if (s.name.isNotEmpty()) Text(s.name, style = MaterialTheme.typography.bodyMedium)
            Text(s.args, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = onPin) { Text(if (s.pinned) "★" else "☆") }
        if (onDelete != null) {
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.host_lists_delete)) }
        }
    }
}

@Composable
private fun PowerButton(state: VpnState, onClick: () -> Unit) {
    val active = state == VpnState.RUNNING || state == VpnState.STARTING
    val container by animateColorAsState(
        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        label = "power-container",
    )
    val content by animateColorAsState(
        if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "power-content",
    )
    val label = stringResource(if (active) R.string.action_disconnect else R.string.action_connect)
    Surface(
        onClick = onClick,
        enabled = state != VpnState.STOPPING,
        shape = CircleShape,
        color = container,
        contentColor = content,
        shadowElevation = if (active) 8.dp else 2.dp,
        modifier = Modifier
            .size(168.dp)
            .semantics {
                role = Role.Switch
                contentDescription = label
            },
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_power),
            contentDescription = null,
            modifier = Modifier.padding(48.dp),
        )
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit, onOpenLogs: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Start)
            FilledTonalButton(onClick = onOpenLogs) { Text(stringResource(R.string.action_show_logs)) }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_dismiss))
            }
        }
    }
}
