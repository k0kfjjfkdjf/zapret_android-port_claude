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
import io.github.zapretdroid.core.AppRoutes
import io.github.zapretdroid.core.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Settings → Sites through a proxy → Apps: each app normal, wholly through the proxy or wholly direct. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoutesScreen(settings: Settings, onBack: () -> Unit) {
    val context = LocalContext.current
    val packageManager = context.packageManager
    var routes by remember { mutableStateOf(settings.appRoutes) }
    // Apps with a route when the screen opened are listed first; changing one does not reorder.
    val pinned = remember { settings.appRoutes.keys }
    var query by remember { mutableStateOf("") }
    val apps by produceState<List<AppEntry>?>(null) {
        value = withContext(Dispatchers.IO) { loadApps(packageManager, context.packageName) }
    }

    fun set(pkg: String, route: AppRoutes.Route?) {
        routes = if (route == null) routes - pkg else routes + (pkg to route)
        settings.appRoutes = routes
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
                        ListItem(
                            leadingContent = { AppIcon(packageManager, app.packageName) },
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
