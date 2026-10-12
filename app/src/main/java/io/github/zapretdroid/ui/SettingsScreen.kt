package io.github.zapretdroid.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.zapretdroid.BuildConfig
import io.github.zapretdroid.R
import io.github.zapretdroid.core.AppFilterMode
import io.github.zapretdroid.core.AppLog
import io.github.zapretdroid.core.ArgTemplates
import io.github.zapretdroid.core.FakePayloads
import io.github.zapretdroid.core.HostLists
import io.github.zapretdroid.core.HostScope
import io.github.zapretdroid.core.LogLevel
import io.github.zapretdroid.core.Settings
import io.github.zapretdroid.core.Strategies
import io.github.zapretdroid.core.isIpLiteral

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: Settings,
    vpnActive: Boolean,
    onBack: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenTest: () -> Unit,
    onOpenLists: () -> Unit,
    onOpenAutoHost: () -> Unit,
) {
    var strategyId by remember { mutableStateOf(settings.strategyId) }
    var customArgs by remember { mutableStateOf(settings.customArgs) }
    var dns by remember { mutableStateOf(settings.dns) }
    var ipv6 by remember { mutableStateOf(settings.ipv6) }
    var appMode by remember { mutableStateOf(settings.appFilterMode) }
    var logLevel by remember { mutableStateOf(settings.logLevel) }
    var fakePicker by remember { mutableStateOf(false) }
    var hostScope by remember { mutableStateOf(settings.hostScope) }
    var hostList by remember { mutableStateOf(settings.hostList) }
    var autohost by remember { mutableStateOf(settings.autohost) }
    var listPicker by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val argsBefore = settings.customArgs
        val dnsBefore = settings.dns
        onDispose {
            if (settings.customArgs != argsBefore) AppLog.i("setting customArgs: '$argsBefore' -> '${settings.customArgs}'")
            if (settings.dns != dnsBefore) AppLog.i("setting dns: '$dnsBefore' -> '${settings.dns}'")
        }
    }
    val context = LocalContext.current
    val setCustomArgs = { args: String ->
        customArgs = args
        settings.customArgs = args
        strategyId = Strategies.CUSTOM
        settings.strategyId = Strategies.CUSTOM
    }
    val selectedCount = remember { settings.selectedApps.size }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.padding(padding)) {
            if (vpnActive) {
                item {
                    Text(
                        stringResource(R.string.settings_apply_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            item { SectionHeader(R.string.section_strategy) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.test_open)) },
                    supportingContent = { Text(stringResource(R.string.test_open_desc)) },
                    modifier = Modifier.selectable(selected = false, onClick = onOpenTest),
                )
            }
            items(Strategies.presets, key = { it.id }) { preset ->
                RadioRow(
                    title = stringResource(preset.title),
                    subtitle = stringResource(preset.description),
                    selected = strategyId == preset.id,
                    onSelect = {
                        strategyId = preset.id
                        settings.strategyId = preset.id
                    },
                )
            }
            item {
                RadioRow(
                    title = stringResource(R.string.strategy_custom),
                    subtitle = stringResource(R.string.strategy_custom_desc),
                    selected = strategyId == Strategies.CUSTOM,
                    onSelect = {
                        strategyId = Strategies.CUSTOM
                        settings.strategyId = Strategies.CUSTOM
                    },
                )
            }
            item {
                val shown = Strategies.byId(strategyId)?.args ?: customArgs
                OutlinedTextField(
                    value = shown,
                    // Editing a preset turns it into a custom strategy.
                    onValueChange = setCustomArgs,
                    label = { Text(stringResource(R.string.strategy_args)) },
                    supportingText = { Text(stringResource(R.string.strategy_args_hint)) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            item {
                TextButton(onClick = { fakePicker = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(stringResource(R.string.fake_insert))
                }
            }

            item { SectionHeader(R.string.section_hosts) }
            items(HostScope.entries.toList(), key = { "scope_" + it.name }) { scope ->
                RadioRow(
                    title = stringResource(
                        when (scope) {
                            HostScope.ALL -> R.string.hosts_all
                            HostScope.ONLY_LIST -> R.string.hosts_only_list
                            HostScope.EXCEPT_LIST -> R.string.hosts_except_list
                        },
                    ),
                    subtitle = null,
                    selected = hostScope == scope,
                    onSelect = {
                        hostScope = scope
                        settings.hostScope = scope
                        if (scope != HostScope.ALL && hostList.isEmpty()) listPicker = true
                    },
                )
            }
            if (hostScope != HostScope.ALL) {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.hosts_list)) },
                        supportingContent = {
                            Text(
                                if (hostList.isEmpty()) stringResource(R.string.hosts_list_none)
                                else stringResource(R.string.hosts_list_value, hostList, HostLists.count(context, hostList)),
                            )
                        },
                        modifier = Modifier.selectable(selected = false, onClick = { listPicker = true }),
                    )
                }
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.title_host_lists)) },
                    supportingContent = { Text(stringResource(R.string.host_lists_open_desc)) },
                    modifier = Modifier.selectable(selected = false, onClick = onOpenLists),
                )
            }

            item { SectionHeader(R.string.section_network) }
            item {
                val valid = isIpLiteral(dns.trim())
                OutlinedTextField(
                    value = dns,
                    onValueChange = {
                        dns = it
                        if (isIpLiteral(it.trim())) settings.dns = it.trim()
                    },
                    label = { Text(stringResource(R.string.dns_server)) },
                    supportingText = {
                        Text(stringResource(if (valid) R.string.dns_server_hint else R.string.dns_server_invalid))
                    },
                    isError = !valid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.ipv6)) },
                    supportingContent = { Text(stringResource(R.string.ipv6_desc)) },
                    trailingContent = { Switch(checked = ipv6, onCheckedChange = null) },
                    modifier = Modifier.toggleable(value = ipv6, role = Role.Switch) {
                        ipv6 = it
                        settings.ipv6 = it
                    },
                )
            }

            item { SectionHeader(R.string.section_apps) }
            items(AppFilterMode.entries.toList(), key = { it.name }) { mode ->
                RadioRow(
                    title = stringResource(
                        when (mode) {
                            AppFilterMode.ALL -> R.string.apps_all
                            AppFilterMode.ONLY_SELECTED -> R.string.apps_only_selected
                            AppFilterMode.EXCEPT_SELECTED -> R.string.apps_except_selected
                        },
                    ),
                    subtitle = null,
                    selected = appMode == mode,
                    onSelect = {
                        appMode = mode
                        settings.appFilterMode = mode
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.apps_choose)) },
                    supportingContent = { Text(stringResource(R.string.apps_selected_count, selectedCount)) },
                    modifier = Modifier.selectable(
                        selected = false,
                        enabled = appMode != AppFilterMode.ALL,
                        onClick = onOpenApps,
                    ),
                )
            }

            item { SectionHeader(R.string.section_debug) }
            items(LogLevel.entries.toList(), key = { "log_" + it.name }) { level ->
                RadioRow(
                    title = stringResource(
                        when (level) {
                            LogLevel.NORMAL -> R.string.log_level_normal
                            LogLevel.DEBUG -> R.string.log_level_debug
                            LogLevel.VERBOSE -> R.string.log_level_verbose
                        },
                    ),
                    subtitle = stringResource(
                        when (level) {
                            LogLevel.NORMAL -> R.string.log_level_normal_desc
                            LogLevel.DEBUG -> R.string.log_level_debug_desc
                            LogLevel.VERBOSE -> R.string.log_level_verbose_desc
                        },
                    ),
                    selected = logLevel == level,
                    onSelect = {
                        logLevel = level
                        settings.logLevel = level
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.autohost_toggle)) },
                    supportingContent = { Text(stringResource(R.string.autohost_toggle_desc)) },
                    trailingContent = { Switch(checked = autohost, onCheckedChange = null) },
                    modifier = Modifier.toggleable(value = autohost, role = Role.Switch) {
                        autohost = it
                        settings.autohost = it
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.title_autohost)) },
                    supportingContent = { Text(stringResource(R.string.autohost_open_desc)) },
                    modifier = Modifier.selectable(selected = false, onClick = onOpenAutoHost),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.title_logs)) },
                    supportingContent = { Text(stringResource(R.string.logs_open_desc)) },
                    modifier = Modifier.selectable(selected = false, onClick = onOpenLogs),
                )
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.about_credits),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (listPicker) {
        ListPickerDialog(
            onDismiss = { listPicker = false },
            onPick = { name ->
                listPicker = false
                if (!HostLists.file(context, name).exists()) {
                    AppLog.i("host list '$name' created")
                    HostLists.save(context, name, "")
                }
                hostList = name
                settings.hostList = name
            },
        )
    }

    if (fakePicker) {
        val names = remember { FakePayloads.names(context) }
        FakePickerDialog(
            names = names,
            onPick = { name ->
                fakePicker = false
                val current = Strategies.byId(strategyId)?.args ?: customArgs
                setCustomArgs("${current.trimEnd()} --fake-data ${ArgTemplates.fake(name)}")
            },
            onDismiss = { fakePicker = false },
        )
    }
}

@Composable
private fun FakePickerDialog(names: List<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.fake_insert)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.fake_insert_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(Modifier.heightIn(max = 360.dp).padding(top = 8.dp)) {
                    items(names) { name ->
                        Text(
                            name.removeSuffix(".bin"),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(selected = false, onClick = { onPick(name) })
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun SectionHeader(title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun RadioRow(title: String, subtitle: String?, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(8.dp))
        Column(Modifier.padding(start = 8.dp, end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
