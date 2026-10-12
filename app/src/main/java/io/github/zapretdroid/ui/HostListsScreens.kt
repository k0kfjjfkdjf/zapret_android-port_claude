package io.github.zapretdroid.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import io.github.zapretdroid.R
import io.github.zapretdroid.core.AppLog
import io.github.zapretdroid.core.AutoHost
import io.github.zapretdroid.core.HostLists
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Lists larger than this are not loaded into the text editor. */
private const val EDITABLE_BYTES = 256 * 1024

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostListsScreen(onBack: () -> Unit, onEdit: (String?) -> Unit) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val names = remember(version) { HostLists.names(context) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importFromUri(context, uri)
            version++
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_host_lists)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.padding(padding)) {
            item {
                Text(
                    stringResource(R.string.host_lists_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Button(onClick = { onEdit(null) }) { Text(stringResource(R.string.host_lists_new)) }
                    OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.host_lists_import)) }
                }
            }
            item {
                TextButton(onClick = { downloading = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(stringResource(R.string.host_lists_download))
                }
            }
            if (names.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.host_lists_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(names, key = { it }) { name ->
                ListItem(
                    headlineContent = { Text(name) },
                    supportingContent = {
                        Text(stringResource(R.string.host_lists_count, HostLists.count(context, name)) + "  ·  {list:$name}")
                    },
                    trailingContent = {
                        IconButton(onClick = { deleting = name }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.host_lists_delete))
                        }
                    },
                    modifier = Modifier.selectable(selected = false, onClick = { onEdit(name) }),
                )
            }
        }
    }

    deleting?.let { name ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            text = { Text(stringResource(R.string.host_lists_delete_confirm, name)) },
            confirmButton = {
                TextButton(onClick = {
                    AppLog.i("host list '$name' deleted")
                    HostLists.delete(context, name)
                    deleting = null
                    version++
                }) { Text(stringResource(R.string.host_lists_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    if (downloading) {
        DownloadDialog(onDismiss = { downloading = false }, onDone = { version++ })
    }
}

@Composable
private fun DownloadDialog(onDismiss: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val validName = HostLists.isValidName(name)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.host_lists_download)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.host_lists_name)) },
                    isError = name.isNotEmpty() && !validName,
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && validName && url.startsWith("http"),
                onClick = {
                    busy = true
                    val listName = name
                    val address = url.trim()
                    thread(name = "list-download") {
                        val message = runCatching {
                            val conn = URL(address).openConnection() as HttpURLConnection
                            conn.connectTimeout = 15_000
                            conn.readTimeout = 30_000
                            conn.instanceFollowRedirects = true
                            val bytes = conn.inputStream.use { it.readBytes() }
                            val result = HostLists.import(context, listName, bytes)
                            AppLog.i("host list '$listName' downloaded from $address: ${result.hosts.size} hosts, ${result.invalid.size} rejected")
                            context.getString(R.string.host_lists_saved, result.hosts.size, result.invalid.size)
                        }.getOrElse {
                            AppLog.e("host list download from $address failed", it)
                            context.getString(R.string.host_lists_download_failed, it.message ?: it.toString())
                        }
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            busy = false
                            onDone()
                            onDismiss()
                        }
                    }
                },
            ) { Text(stringResource(R.string.host_lists_download_action)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

private fun importFromUri(context: Context, uri: Uri) {
    runCatching {
        val display = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "imported"
        val base = display.substringBefore('.').replace(Regex("[^A-Za-z0-9_-]"), "_").take(32).ifEmpty { "imported" }
        val existing = HostLists.names(context).toSet()
        var name = base
        var i = 2
        while (name in existing) name = "${base}_${i++}"
        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val result = HostLists.import(context, name, bytes)
        AppLog.i("host list '$name' imported from $display: ${result.hosts.size} hosts, ${result.invalid.size} rejected")
        Toast.makeText(context, context.getString(R.string.host_lists_saved, result.hosts.size, result.invalid.size), Toast.LENGTH_LONG).show()
    }.onFailure {
        AppLog.e("host list import failed", it)
        Toast.makeText(context, it.message, Toast.LENGTH_LONG).show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostListEditorScreen(initialName: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(initialName.orEmpty()) }
    val tooBig = initialName != null && HostLists.file(context, initialName).length() > EDITABLE_BYTES
    var text by remember { mutableStateOf(if (initialName != null && !tooBig) HostLists.read(context, initialName) else "") }
    val nameTaken = initialName == null && name in HostLists.names(context)
    val canSave = HostLists.isValidName(name) && !nameTaken && !tooBig

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(initialName ?: stringResource(R.string.host_lists_new)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    TextButton(enabled = canSave, onClick = {
                        val result = HostLists.save(context, name, text)
                        AppLog.i("host list '$name' saved: ${result.hosts.size} hosts, rejected: ${result.invalid.take(20)}")
                        Toast.makeText(
                            context,
                            context.getString(R.string.host_lists_saved, result.hosts.size, result.invalid.size),
                            Toast.LENGTH_LONG,
                        ).show()
                        onBack()
                    }) { Text(stringResource(R.string.action_save)) }
                },
            )
        },
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .fillMaxSize(),
        ) {
            if (initialName == null) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.host_lists_name)) },
                    supportingText = {
                        Text(stringResource(if (nameTaken) R.string.host_lists_name_taken else R.string.host_lists_name_hint))
                    },
                    isError = (name.isNotEmpty() && !HostLists.isValidName(name)) || nameTaken,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (tooBig) {
                Text(stringResource(R.string.host_lists_too_big, HostLists.count(context, initialName!!)))
            } else {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("youtube.com\ngooglevideo.com\n# comment") },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
                Text(
                    stringResource(R.string.host_lists_format),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoHostScreen(enabled: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val entries = remember(version) { AutoHost.entries(context) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var picking by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_autohost)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        AppLog.i("autohost: detected blocks cleared")
                        AutoHost.clear(context)
                        selected = emptySet()
                        version++
                    }, enabled = entries.isNotEmpty()) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.autohost_clear))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            Text(
                stringResource(if (enabled) R.string.autohost_intro else R.string.autohost_disabled),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                OutlinedButton(onClick = { version++ }) { Text(stringResource(R.string.autohost_refresh)) }
                Button(enabled = selected.isNotEmpty(), onClick = { picking = true }) {
                    Text(stringResource(R.string.autohost_add, selected.size))
                }
            }
            if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.autohost_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(entries, key = { it.host }) { entry ->
                        val checked = entry.host in selected
                        ListItem(
                            headlineContent = { Text(entry.host) },
                            supportingContent = {
                                Text(
                                    stringResource(
                                        R.string.autohost_entry,
                                        entry.count,
                                        entry.reasons.sorted().joinToString(),
                                        DateUtils.getRelativeTimeSpanString(entry.lastSeen * 1000).toString(),
                                    ),
                                )
                            },
                            leadingContent = { Checkbox(checked = checked, onCheckedChange = null) },
                            modifier = Modifier.toggleable(value = checked, role = Role.Checkbox) {
                                selected = if (it) selected + entry.host else selected - entry.host
                            },
                        )
                    }
                }
            }
        }
    }

    if (picking) {
        ListPickerDialog(
            onDismiss = { picking = false },
            onPick = { list ->
                picking = false
                val result = HostLists.add(context, list, selected)
                AppLog.i("autohost: added ${selected.size} hosts to list '$list'")
                Toast.makeText(context, context.getString(R.string.host_lists_saved, result.hosts.size, result.invalid.size), Toast.LENGTH_LONG).show()
                selected = emptySet()
            },
        )
    }
}

/** Picks an existing host list or names a new one. */
@Composable
fun ListPickerDialog(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val context = LocalContext.current
    val names = remember { HostLists.names(context) }
    var choice by remember { mutableStateOf(names.firstOrNull() ?: "") }
    var newName by remember { mutableStateOf(if (names.isEmpty()) "autohostlist" else "") }
    val target = newName.ifEmpty { choice }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.autohost_pick_list)) },
        text = {
            Column {
                names.forEach { name ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = newName.isEmpty() && choice == name, role = Role.RadioButton) {
                                choice = name
                                newName = ""
                            },
                    ) {
                        RadioButton(selected = newName.isEmpty() && choice == name, onClick = null)
                        Text(name, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.host_lists_new_name)) },
                    isError = newName.isNotEmpty() && !HostLists.isValidName(newName),
                    singleLine = true,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = HostLists.isValidName(target), onClick = { onPick(target) }) {
                Text(stringResource(R.string.action_add))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
