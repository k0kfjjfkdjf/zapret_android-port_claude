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
import io.github.zapretdroid.service.VpnState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: VpnState,
    error: String?,
    txBytes: Long,
    rxBytes: Long,
    strategyName: String,
    onToggle: () -> Unit,
    onDismissError: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLogs: () -> Unit,
) {
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
            Card(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.current_strategy),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(strategyName, style = MaterialTheme.typography.titleMedium)
                }
            }
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
            TextButton(onClick = onOpenLogs) { Text(stringResource(R.string.action_show_logs)) }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_dismiss))
            }
        }
    }
}
