package io.github.zapretdroid.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.zapretdroid.R
import io.github.zapretdroid.core.AppLog
import io.github.zapretdroid.core.Settings
import io.github.zapretdroid.core.TorModule
import io.github.zapretdroid.core.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings → Sites through a proxy: the downloadable Tor module, its state and its bridges. */
@Composable
fun TorModuleCard(settings: Settings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var installed by remember { mutableStateOf(TorModule.installedVersion(context)) }
    var trusted by remember { mutableStateOf(TorModule.isTrusted(context)) }
    var status by remember { mutableStateOf(TorModule.status) }
    var useModule by remember { mutableStateOf(settings.useTorModule) }
    var bridges by remember { mutableStateOf(settings.torBridges) }
    var custom by remember { mutableStateOf(settings.torBridgesCustom) }
    var progress by remember { mutableIntStateOf(-1) }
    var note by remember { mutableStateOf<String?>(null) }

    // Installs finish outside the app: look again now and then.
    LaunchedEffect(Unit) {
        while (true) {
            installed = TorModule.installedVersion(context)
            trusted = TorModule.isTrusted(context)
            delay(2_000)
        }
    }
    DisposableEffect(Unit) {
        val unregister = TorModule.register(context) { s -> status = s }
        TorModule.requestStatus(context)
        onDispose { unregister() }
    }

    fun downloadAndInstall() {
        if (!Updater.canInstall(context)) {
            runCatching { context.startActivity(Updater.installPermissionIntent(context)) }
            note = context.getString(R.string.update_allow_install)
            return
        }
        progress = 0
        note = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val (release, asset) = TorModule.latest() ?: throw IllegalStateException(context.getString(R.string.tor_module_none_released))
                    if (installed != null && !TorModule.isUpdate(context, asset.first)) return@runCatching null
                    TorModule.download(context, release, asset) { p -> progress = p }
                }
            }
            progress = -1
            result.onSuccess { apk ->
                if (apk == null) note = context.getString(R.string.tor_module_up_to_date)
                else runCatching { Updater.install(context, apk) }.onFailure { note = it.message }
            }.onFailure {
                AppLog.w("Tor module download failed: ${it.message}")
                note = context.getString(R.string.update_failed, it.message ?: it.javaClass.simpleName)
            }
        }
    }

    fun restartIfRunning() {
        if (status?.state == "ON" || status?.state == "STARTING") TorModule.start(context, settings)
    }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(stringResource(R.string.tor_module_title), style = MaterialTheme.typography.titleSmall)
        when {
            installed == null -> Text(stringResource(R.string.tor_module_desc), style = MaterialTheme.typography.bodySmall)
            !trusted -> Text(stringResource(R.string.tor_module_untrusted), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            else -> {
                val s = status
                Text(
                    stringResource(R.string.tor_module_installed, installed.orEmpty()) + "\n" + when (s?.state) {
                        "ON" -> stringResource(R.string.tor_module_on, s.socks)
                        "STARTING" -> stringResource(R.string.tor_module_starting, s.progress, s.message)
                        "ERROR" -> stringResource(R.string.tor_module_error, s.message)
                        else -> stringResource(R.string.tor_module_off)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when (s?.state) {
                        "ON" -> MaterialTheme.colorScheme.primary
                        "ERROR" -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (s?.state == "STARTING") {
                    LinearProgressIndicator(progress = { s.progress / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
        }
        if (progress >= 0) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            FilledTonalButton(enabled = progress < 0, onClick = { downloadAndInstall() }) {
                Text(stringResource(if (installed == null) R.string.tor_module_download else R.string.tor_module_update))
            }
            if (installed != null && trusted) {
                FilledTonalButton(onClick = { TorModule.start(context, settings) }) { Text(stringResource(R.string.tor_module_start)) }
                FilledTonalButton(onClick = { TorModule.stop(context) }) { Text(stringResource(R.string.tor_module_stop)) }
            }
        }
    }
    if (installed != null && trusted) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.tor_module_use)) },
            supportingContent = { Text(stringResource(R.string.tor_module_use_desc)) },
            trailingContent = { Switch(checked = useModule, onCheckedChange = null) },
            modifier = Modifier.toggleable(value = useModule, role = Role.Switch) {
                useModule = it
                settings.useTorModule = it
            },
        )
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(stringResource(R.string.tor_bridges_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.tor_bridges_desc), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                TorModule.Bridges.entries.forEach { b ->
                    FilterChip(
                        selected = bridges == b.id,
                        onClick = {
                            bridges = b.id
                            settings.torBridges = b.id
                            restartIfRunning()
                        },
                        label = { Text(stringResource(bridgeLabel(b))) },
                    )
                }
            }
            if (bridges == TorModule.Bridges.CUSTOM.id) {
                OutlinedTextField(
                    value = custom,
                    onValueChange = {
                        custom = it
                        settings.torBridgesCustom = it
                    },
                    label = { Text(stringResource(R.string.tor_bridges_custom)) },
                    supportingText = { Text(stringResource(R.string.tor_bridges_custom_hint)) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                FilledTonalButton(onClick = { restartIfRunning() }) { Text(stringResource(R.string.tor_bridges_apply)) }
            }
        }
    }
}

private fun bridgeLabel(b: TorModule.Bridges) = when (b) {
    TorModule.Bridges.NONE -> R.string.tor_bridges_none
    TorModule.Bridges.SNOWFLAKE -> R.string.tor_bridges_snowflake
    TorModule.Bridges.OBFS4 -> R.string.tor_bridges_obfs4
    TorModule.Bridges.WEBTUNNEL -> R.string.tor_bridges_webtunnel
    TorModule.Bridges.MEEK -> R.string.tor_bridges_meek
    TorModule.Bridges.CUSTOM -> R.string.tor_bridges_mine
}
