package io.github.zapretdroid.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.zapretdroid.BuildConfig
import io.github.zapretdroid.R
import io.github.zapretdroid.core.AppLog
import io.github.zapretdroid.core.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A newer release: what changed, then download (with progress), verify and install. */
@Composable
fun UpdateDialog(release: Updater.Release, onLater: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableIntStateOf(-1) }
    var error by remember { mutableStateOf<String?>(null) }
    val busy = progress >= 0 && error == null

    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(stringResource(R.string.update_title, release.version)) },
        text = {
            Column {
                Text(stringResource(R.string.update_installed, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall)
                Text(
                    release.notes.ifBlank { release.tag },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState()),
                )
                if (busy) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    )
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    if (!Updater.canInstall(context)) {
                        // Android 8+: the user allows installs from this app once, then taps again.
                        runCatching { context.startActivity(Updater.installPermissionIntent(context)) }
                        error = context.getString(R.string.update_allow_install)
                        return@TextButton
                    }
                    error = null
                    progress = 0
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { Updater.download(context, release) { p -> progress = p } }
                        }
                        result.onSuccess { apk ->
                            progress = 100
                            runCatching { Updater.install(context, apk) }
                                .onFailure { error = it.message ?: it.toString() }
                                .onSuccess { onClose() }
                        }.onFailure {
                            AppLog.w("update download failed: ${it.message}")
                            error = context.getString(R.string.update_failed, it.message ?: it.javaClass.simpleName)
                        }
                    }
                },
            ) { Text(stringResource(R.string.update_install)) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onLater) { Text(stringResource(R.string.update_later)) }
        },
    )
}
