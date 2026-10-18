package io.github.zapretdroid.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import io.github.zapretdroid.core.Backup
import io.github.zapretdroid.core.DnsFilter
import io.github.zapretdroid.core.DnsMode
import io.github.zapretdroid.core.FakePayloads
import io.github.zapretdroid.core.HostLists
import io.github.zapretdroid.core.HostScope
import io.github.zapretdroid.core.LogLevel
import io.github.zapretdroid.core.SavedStrategies
import io.github.zapretdroid.core.SecureDns
import io.github.zapretdroid.core.Settings
import io.github.zapretdroid.core.Strategies
import io.github.zapretdroid.core.TorProxy
import io.github.zapretdroid.core.Updater
import io.github.zapretdroid.core.isIpLiteral
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: Settings,
    vpnActive: Boolean,
    onBack: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenTest: () -> Unit,
    onOpenSelfCheck: () -> Unit,
    onOpenDeep: () -> Unit,
    onOpenCollect: () -> Unit,
    onOpenDnsServers: () -> Unit,
    onOpenLists: () -> Unit,
    onOpenAutoHost: () -> Unit,
    onOpenClassic: () -> Unit,
    onCheckUpdates: () -> Unit,
) {
    var strategyId by remember { mutableStateOf(settings.strategyId) }
    var customArgs by remember { mutableStateOf(settings.customArgs) }
    var dns by remember { mutableStateOf(settings.dns) }
    var dnsMode by remember { mutableStateOf(settings.dnsMode) }
    var dnsProvider by remember { mutableStateOf(settings.dnsProvider) }
    var proxyEnabled by remember { mutableStateOf(settings.proxyEnabled) }
    var proxyAddress by remember { mutableStateOf(settings.proxyAddress) }
    var proxyUdp by remember { mutableStateOf(settings.proxyUdp) }
    var proxyTelegram by remember { mutableStateOf(settings.proxyTelegram) }
    var dnsBlock by remember { mutableStateOf(settings.dnsBlock) }
    var ipv6 by remember { mutableStateOf(settings.ipv6) }
    var appMode by remember { mutableStateOf(settings.appFilterMode) }
    var logLevel by remember { mutableStateOf(settings.logLevel) }
    var fakePicker by remember { mutableStateOf(false) }
    var hostScope by remember { mutableStateOf(settings.hostScope) }
    var hostList by remember { mutableStateOf(settings.hostList) }
    var autohost by remember { mutableStateOf(settings.autohost) }
    var listPicker by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dnsListsVersion by remember { mutableStateOf(0) }
    var dnsUpdating by remember { mutableStateOf(false) }
    var dnsUpdateStatus by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        val argsBefore = settings.customArgs
        val dnsBefore = settings.dns
        val providerBefore = settings.dnsProvider
        val proxyBefore = settings.proxyAddress
        onDispose {
            if (settings.customArgs != argsBefore) {
                AppLog.i("setting customArgs: '$argsBefore' -> '${settings.customArgs}'")
                if (settings.strategyId == Strategies.CUSTOM) SavedStrategies.remember(context, settings.customArgs)
            }
            if (settings.dns != dnsBefore) AppLog.i("setting dns: '$dnsBefore' -> '${settings.dns}'")
            if (settings.dnsProvider != providerBefore) AppLog.i("setting dnsProvider: '$providerBefore' -> '${settings.dnsProvider}'")
            if (settings.proxyAddress != proxyBefore) AppLog.i("setting proxyAddress: '$proxyBefore' -> '${settings.proxyAddress}'")
        }
    }
    val setCustomArgs = { args: String ->
        customArgs = args
        settings.customArgs = args
        strategyId = Strategies.CUSTOM
        settings.strategyId = Strategies.CUSTOM
    }
    val selectedCount = remember { settings.selectedApps.size }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)!!.use { it.write(Backup.export(context).toByteArray()) }
                AppLog.i("settings exported")
                Toast.makeText(context, R.string.backup_exported, Toast.LENGTH_SHORT).show()
            }.onFailure {
                AppLog.e("settings export failed", it)
                Toast.makeText(context, it.message, Toast.LENGTH_LONG).show()
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                val (n, strategies, lists) = Backup.import(context, text)
                Toast.makeText(context, context.getString(R.string.backup_imported, n, strategies, lists), Toast.LENGTH_LONG).show()
                // The screen holds the old values: go back so everything is read again.
                onBack()
            }.onFailure {
                AppLog.e("settings import failed", it)
                Toast.makeText(context, context.getString(R.string.backup_import_failed, it.message ?: it.toString()), Toast.LENGTH_LONG).show()
            }
        }
    }

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

            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.classic_title)) },
                    supportingContent = { Text(stringResource(R.string.classic_desc)) },
                    modifier = Modifier.selectable(selected = false, onClick = onOpenClassic),
                )
            }

            item {
                var auto by remember { mutableStateOf(Updater.isAuto(context)) }
                ListItem(
                    headlineContent = { Text(stringResource(R.string.update_check)) },
                    supportingContent = { Text(stringResource(R.string.update_check_desc, BuildConfig.VERSION_NAME)) },
                    trailingContent = {
                        Switch(checked = auto, onCheckedChange = {
                            auto = it
                            Updater.setAuto(context, it)
                        })
                    },
                    modifier = Modifier.selectable(selected = false, onClick = onCheckUpdates),
                )
            }

            item { SectionHeader(R.string.section_strategy) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.test_open)) },
                    supportingContent = { Text(stringResource(R.string.test_open_desc)) },
                    modifier = Modifier.selectable(selected = false, onClick = onOpenTest),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.deep_title)) },
                    supportingContent = { Text(stringResource(R.string.deep_desc)) },
                    modifier = Modifier.selectable(selected = false, onClick = onOpenDeep),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.selfcheck_title)) },
                    supportingContent = { Text(stringResource(R.string.selfcheck_desc)) },
                    modifier = Modifier.selectable(selected = false, onClick = onOpenSelfCheck),
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
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.collect_title)) },
                    supportingContent = { Text(stringResource(R.string.collect_desc)) },
                    modifier = Modifier.selectable(selected = false, onClick = onOpenCollect),
                )
            }
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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .padding(start = 16.dp, end = 16.dp, top = 4.dp)
                        .horizontalScroll(rememberScrollState()),
                ) {
                    DnsMode.entries.forEach { mode ->
                        FilterChip(
                            selected = dnsMode == mode,
                            onClick = {
                                dnsMode = mode
                                settings.dnsMode = mode
                            },
                            label = {
                                Text(
                                    stringResource(
                                        when (mode) {
                                            DnsMode.PLAIN -> R.string.dns_mode_plain
                                            DnsMode.DOH -> R.string.dns_mode_doh
                                            DnsMode.DOT -> R.string.dns_mode_dot
                                            DnsMode.DNSCRYPT -> R.string.dns_mode_dnscrypt
                                        },
                                    ),
                                )
                            },
                        )
                    }
                }
            }
            if (dnsMode == DnsMode.PLAIN) {
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
                    ) {
                        listOf(Settings.SYSTEM_DNS, "77.88.8.8", "1.1.1.1", "8.8.8.8").forEach { option ->
                            FilterChip(
                                selected = dns.trim() == option,
                                onClick = {
                                    dns = option
                                    settings.dns = option
                                },
                                label = { Text(if (option == Settings.SYSTEM_DNS) stringResource(R.string.dns_system) else option) },
                            )
                        }
                    }
                }
                item {
                    val valid = isIpLiteral(dns.trim()) || dns.trim() == Settings.SYSTEM_DNS
                    OutlinedTextField(
                        value = dns,
                        onValueChange = {
                            dns = it
                            if (isIpLiteral(it.trim()) || it.trim() == Settings.SYSTEM_DNS) settings.dns = it.trim()
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
            } else {
                item {
                    Text(
                        stringResource(R.string.dns_secure_desc),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                if (dnsMode == DnsMode.DNSCRYPT) {
                    item {
                        val chosen = remember(dnsMode) { settings.dnsServers }
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.dns_servers_title)) },
                            supportingContent = {
                                Text(
                                    if (chosen.isEmpty()) stringResource(R.string.dns_servers_none_chosen)
                                    else stringResource(R.string.dns_servers_chosen, chosen.joinToString { it.first }),
                                    color = if (chosen.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            modifier = Modifier.selectable(selected = false, onClick = onOpenDnsServers),
                        )
                    }
                } else {
                    item {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .padding(start = 16.dp, end = 16.dp, top = 4.dp)
                                .horizontalScroll(rememberScrollState()),
                        ) {
                            SecureDns.providers.forEach { provider ->
                                FilterChip(
                                    selected = dnsProvider == provider.id,
                                    onClick = {
                                        dnsProvider = provider.id
                                        settings.dnsProvider = provider.id
                                    },
                                    label = { Text(provider.title) },
                                )
                            }
                        }
                    }
                    item {
                        val custom = SecureDns.providers.none { it.id == dnsProvider }
                        OutlinedTextField(
                            value = if (custom) dnsProvider else "",
                            onValueChange = {
                                dnsProvider = it.trim()
                                if (it.trim().isNotEmpty()) settings.dnsProvider = it.trim()
                            },
                            label = { Text(stringResource(R.string.dns_provider_custom)) },
                            supportingText = { Text(stringResource(R.string.dns_provider_custom_hint)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    SecureDns.providers.firstOrNull { it.id == dnsProvider }?.filters?.takeIf { it.isNotEmpty() }?.let { filters ->
                        item {
                            val names = DnsFilter.Category.entries.filter { it.id in filters }.map { stringResource(categoryTitle(it)) }
                            Text(
                                stringResource(R.string.dns_server_blocks, names.joinToString()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.dns_block_title)) },
                        supportingContent = { Text(stringResource(R.string.dns_block_desc)) },
                    )
                }
                items(DnsFilter.Category.entries.toList(), key = { "dnsblock-" + it.id }) { category ->
                    val on = category.id in dnsBlock
                    val count = remember(dnsListsVersion) { DnsFilter.count(context, category) }
                    val updated = remember(dnsListsVersion) { DnsFilter.updatedAt(context, category) }
                    ListItem(
                        headlineContent = { Text(stringResource(categoryTitle(category))) },
                        supportingContent = {
                            Text(
                                if (count != null && updated != null) {
                                    stringResource(R.string.dns_block_count, count, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(updated)))
                                } else {
                                    stringResource(R.string.dns_block_missing)
                                },
                            )
                        },
                        trailingContent = { Switch(checked = on, onCheckedChange = null) },
                        modifier = Modifier.toggleable(value = on, role = Role.Switch) {
                            dnsBlock = if (it) dnsBlock + category.id else dnsBlock - category.id
                            settings.dnsBlock = dnsBlock
                        },
                    )
                }
                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            TextButton(
                                enabled = !dnsUpdating && dnsBlock.isNotEmpty(),
                                onClick = {
                                    dnsUpdating = true
                                    dnsUpdateStatus = null
                                    scope.launch {
                                        val errors = withContext(Dispatchers.IO) {
                                            DnsFilter.Category.entries.filter { it.id in dnsBlock }.mapNotNull { c ->
                                                runCatching { DnsFilter.update(context, c) }.exceptionOrNull()?.let { "${c.id}: ${it.message ?: it.javaClass.simpleName}" }
                                            }
                                        }
                                        dnsUpdating = false
                                        dnsListsVersion++
                                        dnsUpdateStatus = if (errors.isEmpty()) context.getString(R.string.dns_block_updated)
                                        else context.getString(R.string.dns_block_failed, errors.joinToString("; "))
                                    }
                                },
                            ) { Text(stringResource(if (dnsUpdating) R.string.dns_block_updating else R.string.dns_block_update)) }
                            TextButton(onClick = {
                                // Created empty, so that both show up on the Lists screen.
                                listOf(DnsFilter.OWN_BLOCK, DnsFilter.OWN_ALLOW).forEach { name ->
                                    if (!HostLists.file(context, name).exists()) runCatching { HostLists.save(context, name, "") }
                                }
                                onOpenLists()
                            }) { Text(stringResource(R.string.dns_block_own)) }
                        }
                        dnsUpdateStatus?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
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

            item { SectionHeader(R.string.section_proxy) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.proxy_toggle)) },
                    supportingContent = { Text(stringResource(R.string.proxy_toggle_desc)) },
                    trailingContent = { Switch(checked = proxyEnabled, onCheckedChange = null) },
                    modifier = Modifier.toggleable(value = proxyEnabled, role = Role.Switch) {
                        proxyEnabled = it
                        settings.proxyEnabled = it
                        if (it && settings.proxyList == TorProxy.LIST) runCatching { TorProxy.ensureList(context) }
                    },
                )
            }
            if (proxyEnabled) {
                item {
                    val installed = remember { TorProxy.installed(context) }
                    var reachable by remember { mutableStateOf<Boolean?>(null) }
                    LaunchedEffect(proxyAddress) {
                        reachable = null
                        reachable = withContext(Dispatchers.IO) { TorProxy.isValid(proxyAddress) && TorProxy.reachable(proxyAddress.trim()) }
                    }
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text(
                            when {
                                reachable == null -> stringResource(R.string.proxy_checking)
                                reachable == true -> stringResource(R.string.proxy_up, proxyAddress.trim())
                                installed.isEmpty() -> stringResource(R.string.proxy_none_installed)
                                else -> stringResource(R.string.proxy_down, installed.joinToString { it.first.title })
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (reachable == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            installed.forEach { (provider, pkg) ->
                                TextButton(onClick = {
                                    if (provider == TorProxy.Provider.ORBOT) TorProxy.requestStart(context)
                                    context.packageManager.getLaunchIntentForPackage(pkg)?.let { runCatching { context.startActivity(it) } }
                                }) { Text(stringResource(R.string.proxy_open, provider.title)) }
                            }
                            if (installed.isEmpty()) {
                                TextButton(onClick = {
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ORBOT_URL))) }
                                }) { Text(stringResource(R.string.proxy_get_orbot)) }
                            }
                            TextButton(onClick = onOpenLists) { Text(stringResource(R.string.proxy_edit_list, settings.proxyList)) }
                        }
                    }
                }
                item { TorModuleCard(settings) }
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.proxy_telegram)) },
                        supportingContent = { Text(stringResource(R.string.proxy_telegram_desc)) },
                        trailingContent = { Switch(checked = proxyTelegram, onCheckedChange = null) },
                        modifier = Modifier.toggleable(value = proxyTelegram, role = Role.Switch) {
                            proxyTelegram = it
                            settings.proxyTelegram = it
                        },
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.proxy_udp)) },
                        supportingContent = { Text(stringResource(R.string.proxy_udp_desc)) },
                        trailingContent = { Switch(checked = proxyUdp, onCheckedChange = null) },
                        modifier = Modifier.toggleable(value = proxyUdp, role = Role.Switch) {
                            proxyUdp = it
                            settings.proxyUdp = it
                        },
                    )
                }
                item {
                    val valid = TorProxy.isValid(proxyAddress)
                    OutlinedTextField(
                        value = proxyAddress,
                        onValueChange = {
                            proxyAddress = it
                            if (TorProxy.isValid(it)) settings.proxyAddress = it.trim()
                        },
                        label = { Text(stringResource(R.string.proxy_address)) },
                        supportingText = { Text(stringResource(if (valid) R.string.proxy_address_hint else R.string.proxy_address_invalid)) },
                        isError = !valid,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
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

            item { SectionHeader(R.string.section_backup) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.backup_export)) },
                    supportingContent = { Text(stringResource(R.string.backup_export_desc)) },
                    modifier = Modifier.selectable(selected = false, onClick = {
                        val stamp = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
                        exportLauncher.launch("zapretdroid-settings-$stamp.json")
                    }),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.backup_import)) },
                    supportingContent = { Text(stringResource(R.string.backup_import_desc)) },
                    modifier = Modifier.selectable(selected = false, onClick = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) }),
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

private fun categoryTitle(category: DnsFilter.Category) = when (category) {
    DnsFilter.Category.ADS -> R.string.dns_cat_ads
    DnsFilter.Category.MALWARE -> R.string.dns_cat_malware
    DnsFilter.Category.ADULT -> R.string.dns_cat_adult
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

private const val ORBOT_URL = "https://orbot.app/download/"
