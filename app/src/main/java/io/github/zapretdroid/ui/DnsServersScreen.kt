package io.github.zapretdroid.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.zapretdroid.R
import io.github.zapretdroid.core.DnsCryptClient
import io.github.zapretdroid.core.DnsServers
import io.github.zapretdroid.core.DnsStamp
import io.github.zapretdroid.core.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.text.DateFormat
import java.util.Date

private enum class ServerFilter { DNSCRYPT, DOH, DNSSEC, NO_LOG, NO_FILTER, IPV6, CHOSEN }

/**
 * DNSCrypt mode: servers from the public list (signature-checked) and the user's own, with
 * search, filters like dnscrypt-proxy's require_* options, and a check of how fast each answers.
 * Ticked servers are saved at once, in the order ticked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DnsServersScreen(settings: Settings, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableStateOf(0) }
    var servers by remember { mutableStateOf<List<DnsServers.Server>>(emptyList()) }
    var chosen by remember { mutableStateOf(settings.dnsServers) }
    var query by remember { mutableStateOf("") }
    var filters by remember { mutableStateOf(setOf(ServerFilter.DNSCRYPT, ServerFilter.DOH)) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val pings = remember { mutableStateMapOf<String, String>() }
    var ownStamp by remember { mutableStateOf("") }
    var ownName by remember { mutableStateOf("") }
    var ownError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(version) {
        servers = withContext(Dispatchers.IO) { DnsServers.load(context) }
        // Stamps of chosen servers follow the list (servers move to new addresses).
        val fresh = DnsServers.refreshed(chosen, servers)
        if (fresh != chosen) {
            chosen = fresh
            settings.dnsServers = fresh
        }
    }

    fun update() {
        busy = true
        status = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { DnsServers.update(context) } }
            busy = false
            status = r.fold(
                { context.getString(R.string.dns_servers_updated, it) },
                { context.getString(R.string.dns_servers_update_failed, it.message ?: it.javaClass.simpleName) },
            )
            version++
        }
    }

    LaunchedEffect(Unit) {
        if (DnsServers.updatedAt(context) == null) update()
    }

    val chosenNames = chosen.map { it.first }.toSet()
    val shown = servers.filter { s ->
        val st = runCatching { s.stamp }.getOrNull() ?: return@filter false
        s.matches(query) &&
            (if (st.protocol == DnsStamp.Protocol.DNSCRYPT) ServerFilter.DNSCRYPT in filters else ServerFilter.DOH in filters) &&
            (ServerFilter.DNSSEC !in filters || st.dnssec) &&
            (ServerFilter.NO_LOG !in filters || st.noLog) &&
            (ServerFilter.NO_FILTER !in filters || st.noFilter) &&
            (ServerFilter.IPV6 in filters || !st.ipv6) &&
            (ServerFilter.CHOSEN !in filters || s.name in chosenNames)
    }

    fun check(list: List<DnsServers.Server>) {
        busy = true
        scope.launch {
            val gate = Semaphore(8)
            list.take(40).map { s ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        pings[s.name] = "…"
                        val start = System.currentTimeMillis()
                        val ok = runCatching {
                            val st = s.stamp
                            if (st.protocol == DnsStamp.Protocol.DNSCRYPT) {
                                DnsCryptClient(st, 3000).fetchCert()
                            } else {
                                val host = st.host.ifEmpty { st.hostname }
                                Socket().use { it.connect(InetSocketAddress(host, st.port), 3000) }
                            }
                        }
                        pings[s.name] = ok.fold({ "${System.currentTimeMillis() - start} ms" }, { "✗ " + (it.message ?: it.javaClass.simpleName).take(60) })
                    }
                }
            }.awaitAll()
            busy = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dns_servers_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    val updated = remember(version) { DnsServers.updatedAt(context) }
                    Text(
                        if (updated != null) {
                            stringResource(
                                R.string.dns_servers_list_info, servers.count { !it.own },
                                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(updated)),
                            )
                        } else {
                            stringResource(R.string.dns_servers_no_list)
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        if (chosen.isEmpty()) stringResource(R.string.dns_servers_none_chosen)
                        else stringResource(R.string.dns_servers_chosen, chosen.joinToString { it.first }),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (chosen.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    status?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton(enabled = !busy, onClick = { update() }) { Text(stringResource(R.string.dns_servers_update)) }
                        TextButton(enabled = !busy && shown.isNotEmpty(), onClick = { check(shown) }) { Text(stringResource(R.string.dns_servers_check)) }
                        TextButton(enabled = chosen.isNotEmpty(), onClick = {
                            chosen = emptyList()
                            settings.dnsServers = emptyList()
                        }) { Text(stringResource(R.string.dns_servers_clear)) }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.dns_servers_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()),
                ) {
                    ServerFilter.entries.forEach { f ->
                        FilterChip(
                            selected = f in filters,
                            onClick = { filters = if (f in filters) filters - f else filters + f },
                            label = { Text(stringResource(filterLabel(f))) },
                        )
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.dns_servers_shown, shown.size),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            items(shown, key = { (if (it.own) "own:" else "") + it.name }) { s ->
                val isChosen = s.name in chosenNames
                val st = s.stamp
                val tags = buildList {
                    add(if (st.protocol == DnsStamp.Protocol.DNSCRYPT) "DNSCrypt" else "DoH")
                    if (st.dnssec) add("DNSSEC")
                    add(stringResource(if (st.noLog) R.string.dns_servers_tag_nolog else R.string.dns_servers_tag_logs))
                    add(stringResource(if (st.noFilter) R.string.dns_servers_tag_nofilter else R.string.dns_servers_tag_filter))
                    if (st.ipv6) add("IPv6")
                    if (s.own) add(stringResource(R.string.dns_servers_tag_own))
                }
                ListItem(
                    headlineContent = { Text(s.name) },
                    supportingContent = {
                        Column {
                            if (s.description.isNotEmpty()) {
                                Text(s.description, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            }
                            Text(
                                tags.joinToString(" · ") + (pings[s.name]?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            if (s.own) {
                                TextButton(onClick = {
                                    DnsServers.removeOwn(context, s.name)
                                    chosen = chosen.filter { it.first != s.name }
                                    settings.dnsServers = chosen
                                    version++
                                }) { Text(stringResource(R.string.dns_servers_remove)) }
                            }
                        }
                    },
                    leadingContent = { Checkbox(checked = isChosen, onCheckedChange = null) },
                    modifier = Modifier.toggleable(value = isChosen, role = Role.Checkbox) { on ->
                        chosen = if (on) chosen + (s.name to s.stampText) else chosen.filter { it.first != s.name }
                        settings.dnsServers = chosen
                    },
                )
            }
            item {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.dns_servers_add_title), style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = ownStamp,
                        onValueChange = { ownStamp = it; ownError = null },
                        label = { Text("sdns://…") },
                        singleLine = true,
                        isError = ownError != null,
                        supportingText = { Text(ownError ?: stringResource(R.string.dns_servers_add_hint)) },
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = ownName,
                        onValueChange = { ownName = it },
                        label = { Text(stringResource(R.string.dns_servers_add_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(enabled = ownStamp.isNotBlank(), onClick = {
                        runCatching { DnsServers.addOwn(context, ownName, ownStamp) }
                            .onSuccess { name ->
                                chosen = chosen.filter { it.first != name } + (name to ownStamp.trim())
                                settings.dnsServers = chosen
                                ownStamp = ""
                                ownName = ""
                                version++
                            }
                            .onFailure { ownError = context.getString(R.string.dns_servers_add_bad, it.message ?: "") }
                    }) { Text(stringResource(R.string.dns_servers_add)) }
                }
            }
        }
    }
}

private fun filterLabel(f: ServerFilter) = when (f) {
    ServerFilter.DNSCRYPT -> R.string.dns_servers_f_dnscrypt
    ServerFilter.DOH -> R.string.dns_servers_f_doh
    ServerFilter.DNSSEC -> R.string.dns_servers_f_dnssec
    ServerFilter.NO_LOG -> R.string.dns_servers_f_nolog
    ServerFilter.NO_FILTER -> R.string.dns_servers_f_nofilter
    ServerFilter.IPV6 -> R.string.dns_servers_f_ipv6
    ServerFilter.CHOSEN -> R.string.dns_servers_f_chosen
}
