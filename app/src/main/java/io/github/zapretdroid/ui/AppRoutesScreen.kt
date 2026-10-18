package io.github.zapretdroid.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.zapretdroid.R
import io.github.zapretdroid.core.AppFilterMode
import io.github.zapretdroid.core.AppRoutes
import io.github.zapretdroid.core.Settings
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import io.github.zapretdroid.core.SavedStrategies
import io.github.zapretdroid.core.Strategies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Settings → Sites through a proxy → Apps: each app normal, wholly through the proxy or wholly direct. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoutesScreen(settings: Settings, onBack: () -> Unit) {
    val context = LocalContext.current
    val packageManager = context.packageManager
    var routes by remember { mutableStateOf(settings.appRoutes) }
    var mode by remember { mutableStateOf(settings.appFilterMode) }
    var selected by remember { mutableStateOf(settings.selectedApps) }
    var strategies by remember { mutableStateOf(settings.appStrategies) }
    var appProxies by remember { mutableStateOf(settings.appProxies) }
    val extraProxies = remember { settings.extraProxies }
    // The app whose own strategy is being chosen.
    var picking by remember { mutableStateOf<String?>(null) }
    // Apps with a route when the screen opened are listed first; changing one does not reorder.
    val pinned = remember { settings.appRoutes.keys + settings.selectedApps }
    var query by remember { mutableStateOf("") }
    val apps by produceState<List<AppEntry>?>(null) {
        value = withContext(Dispatchers.IO) { loadApps(packageManager, context.packageName) }
    }

    fun set(pkg: String, route: AppRoutes.Route?) {
        routes = if (route == null) routes - pkg else routes + (pkg to route)
        settings.appRoutes = routes
    }

    fun setStrategy(pkg: String, args: String) {
        strategies = strategies + (pkg to args.trim())
        settings.appStrategies = strategies
        set(pkg, AppRoutes.Route.STRATEGY)
    }

    picking?.let { pkg ->
        AppStrategyDialog(
            current = strategies[pkg] ?: settings.strategyArgs,
            onPick = { args ->
                picking = null
                if (args.isNotBlank()) setStrategy(pkg, args)
            },
            onDismiss = { picking = null },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_routes_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            // Which apps go through the VPN at all (the ticks below make the list).
            Text(
                stringResource(R.string.app_vpn_mode),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .horizontalScroll(rememberScrollState()),
            ) {
                AppFilterMode.entries.forEach { m ->
                    FilterChip(
                        selected = mode == m,
                        onClick = {
                            mode = m
                            settings.appFilterMode = m
                        },
                        label = {
                            Text(
                                stringResource(
                                    when (m) {
                                        AppFilterMode.ALL -> R.string.apps_all
                                        AppFilterMode.ONLY_SELECTED -> R.string.apps_only_selected
                                        AppFilterMode.EXCEPT_SELECTED -> R.string.apps_except_selected
                                    },
                                ),
                            )
                        },
                    )
                }
            }
            Text(
                stringResource(R.string.app_routes_desc),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.apps_search)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            val list = apps
            if (list == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                val filtered = remember(list, query) {
                    list.filter {
                        query.isBlank() || it.label.contains(query, ignoreCase = true) ||
                            it.packageName.contains(query, ignoreCase = true)
                    }.sortedByDescending { it.packageName in pinned }
                }
                LazyColumn {
                    items(filtered, key = { it.packageName }) { app ->
                        val route = routes[app.packageName]
                        val ticked = app.packageName in selected
                        ListItem(
                            leadingContent = { AppIcon(packageManager, app.packageName) },
                            trailingContent = if (mode == AppFilterMode.ALL) null else {
                                {
                                    Checkbox(checked = ticked, onCheckedChange = {
                                        selected = if (it) selected + app.packageName else selected - app.packageName
                                        settings.selectedApps = selected
                                    })
                                }
                            },
                            headlineContent = { Text(app.label) },
                            supportingContent = {
                                Column {
                                    Text(app.packageName)
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    ) {
                                        FilterChip(
                                            selected = route == null,
                                            onClick = { set(app.packageName, null) },
                                            label = { Text(stringResource(R.string.app_route_default)) },
                                        )
                                        FilterChip(
                                            selected = route == AppRoutes.Route.PROXY,
                                            onClick = { set(app.packageName, AppRoutes.Route.PROXY) },
                                            label = { Text(stringResource(R.string.app_route_proxy)) },
                                        )
                                        FilterChip(
                                            selected = route == AppRoutes.Route.DIRECT,
                                            onClick = { set(app.packageName, AppRoutes.Route.DIRECT) },
                                            label = { Text(stringResource(R.string.app_route_direct)) },
                                        )
                                        FilterChip(
                                            selected = route == AppRoutes.Route.STRATEGY,
                                            onClick = { picking = app.packageName },
                                            label = { Text(stringResource(R.string.app_route_strategy)) },
                                        )
                                    }
                                    if (route == AppRoutes.Route.PROXY && extraProxies.isNotEmpty()) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                                        ) {
                                            (listOf("") + extraProxies.map { it.name }).forEach { name ->
                                                FilterChip(
                                                    selected = appProxies[app.packageName].orEmpty() == name,
                                                    onClick = {
                                                        appProxies = if (name.isEmpty()) appProxies - app.packageName else appProxies + (app.packageName to name)
                                                        settings.appProxies = appProxies
                                                    },
                                                    label = { Text(name.ifEmpty { stringResource(R.string.proxies_main) }) },
                                                )
                                            }
                                        }
                                    }
                                    if (route == AppRoutes.Route.STRATEGY) {
                                        Text(
                                            strategies[app.packageName].orEmpty(),
                                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 2,
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Chooses an app's own strategy: a preset, a saved strategy or arguments typed in. */
@Composable
private fun AppStrategyDialog(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var args by remember { mutableStateOf(current) }
    val saved = remember { SavedStrategies.all(context).sortedByDescending { it.pinned }.take(15) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_route_strategy_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = args,
                    onValueChange = { args = it },
                    label = { Text(stringResource(R.string.strategy_args)) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 320.dp).padding(top = 8.dp)) {
                    items(Strategies.presets, key = { "p-" + it.id }) { preset ->
                        Text(
                            stringResource(preset.title),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(selected = false, onClick = { args = preset.args })
                                .padding(vertical = 8.dp),
                        )
                    }
                    items(saved, key = { "s-" + it.args }) { s ->
                        Text(
                            s.name.ifBlank { s.args },
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            maxLines = 2,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(selected = false, onClick = { args = s.args })
                                .padding(vertical = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onPick(args) }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
