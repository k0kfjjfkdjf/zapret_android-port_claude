package io.github.zapretdroid.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import io.github.zapretdroid.R
import io.github.zapretdroid.core.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var errorsOnly by remember { mutableStateOf(false) }
    var hidden by remember { mutableStateOf(emptySet<String>()) }
    var query by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    // Bumped to force a re-read, e.g. after clearing.
    var reload by remember { mutableIntStateOf(0) }
    val file = AppLog.file

    val lines by produceState(emptyList<String>(), file, reload) {
        while (true) {
            value = withContext(Dispatchers.IO) { runCatching { AppLog.readTail(file) }.getOrDefault(emptyList()) }
            delay(1000)
        }
    }
    val shown = remember(lines, query, errorsOnly, hidden) {
        lines.filter { line ->
            (query.isBlank() || line.contains(query, ignoreCase = true)) &&
                (!errorsOnly || isProblem(line)) &&
                (hidden.isEmpty() || lineSource(line) !in hidden)
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) saveLog(context, file, uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_logs)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { shareLog(context, file) }, enabled = lines.isNotEmpty()) {
                        Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.logs_share))
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.logs_save)) },
                                enabled = lines.isNotEmpty(),
                                onClick = {
                                    menuOpen = false
                                    saveLauncher.launch(logFileName())
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.logs_copy)) },
                                enabled = lines.isNotEmpty(),
                                onClick = {
                                    menuOpen = false
                                    copyLog(context, shown)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.logs_clear)) },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    confirmClear = true
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                FilterChip(
                    selected = errorsOnly,
                    onClick = { errorsOnly = !errorsOnly },
                    label = { Text(stringResource(R.string.logs_errors_only)) },
                )
                SOURCES.forEach { source ->
                    FilterChip(
                        selected = source !in hidden,
                        onClick = { hidden = if (source in hidden) hidden - source else hidden + source },
                        label = { Text(source) },
                    )
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.logs_filter)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(if (lines.isEmpty()) R.string.logs_empty else R.string.logs_no_match),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LogLines(shown)
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            text = { Text(stringResource(R.string.logs_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    AppLog.clear()
                    AppLog.i("journal cleared")
                    reload++
                }) { Text(stringResource(R.string.logs_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun LogLines(lines: List<String>) {
    val listState = rememberLazyListState()
    // Follow new lines while the user is at the bottom of the list.
    val atBottom = !listState.canScrollForward
    LaunchedEffect(lines.size) {
        if (atBottom && lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }
    val error = MaterialTheme.colorScheme.error
    val warning = MaterialTheme.colorScheme.tertiary
    val normal = MaterialTheme.colorScheme.onSurface
    SelectionContainer {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
        ) {
            itemsIndexed(lines) { _, line ->
                Text(
                    text = line,
                    color = lineColor(line, error, warning, normal),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
        }
    }
}

private val SOURCES = listOf("ui", "vpn", "test", "byedpi", "hev")

/** "MM-dd HH:mm:ss.SSS source | message" -> source. */
private fun lineSource(line: String): String = line.substringBefore(" | ").substringAfterLast(' ')

private fun isProblem(line: String): Boolean =
    line.contains("| E/") || line.contains("| W/") || line.contains("| [E]") || line.contains("| [W]") ||
        line.contains("|   ") // stack trace lines

private fun lineColor(line: String, error: Color, warning: Color, normal: Color): Color = when {
    line.contains("| E/") || line.contains("| [E]") -> error
    line.contains("| W/") || line.contains("| [W]") -> warning
    else -> normal
}

private fun logFileName(): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    return "zapretdroid-$stamp.log"
}

private fun shareLog(context: Context, file: File) = shareFile(context, file, logFileName(), "journal shared")

/** Shares a copy of [file] under [name] through the FileProvider. */
internal fun shareFile(context: Context, file: File, name: String, logLine: String) {
    runCatching {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        AppLog.i(logLine)
        val copy = File(dir, name)
        file.copyTo(copy, overwrite = true)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.logs", copy)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, copy.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(copy.name, uri)
        context.startActivity(Intent.createChooser(send, context.getString(R.string.logs_share)))
    }.onFailure {
        AppLog.e("share log failed", it)
        Toast.makeText(context, it.message, Toast.LENGTH_LONG).show()
    }
}

private fun saveLog(context: Context, file: File, uri: Uri) {
    runCatching {
        AppLog.i("journal saved to a file")
        context.contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        Toast.makeText(context, R.string.logs_saved, Toast.LENGTH_SHORT).show()
    }.onFailure {
        AppLog.e("save log failed", it)
        Toast.makeText(context, it.message, Toast.LENGTH_LONG).show()
    }
}

private fun copyLog(context: Context, lines: List<String>) {
    // Clipboard transactions are limited in size; copy the most recent part.
    AppLog.i("journal copied to the clipboard (${lines.size} lines)")
    val text = lines.joinToString("\n").takeLast(200_000)
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
    clipboard?.setPrimaryClip(ClipData.newPlainText("ByeZapret log", text))
    Toast.makeText(context, R.string.logs_copied, Toast.LENGTH_SHORT).show()
}
