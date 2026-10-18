package io.github.zapretdroid.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import io.github.zapretdroid.R
import io.github.zapretdroid.core.AppLog
import io.github.zapretdroid.core.InviZible
import io.github.zapretdroid.core.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings → Sites through a proxy: InviZible Pro (Tor) downloaded from its GitHub releases. */
@Composable
fun InviZibleCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var installed by remember { mutableStateOf(InviZible.installedVersion(context)) }
    var progress by remember { mutableIntStateOf(-1) }
    var note by remember { mutableStateOf<String?>(null) }

    // Installs finish outside the app: look again now and then.
    LaunchedEffect(Unit) {
        while (true) {
            installed = InviZible.installedVersion(context)
            delay(2_000)
        }
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
                    val (release, asset) = InviZible.latest() ?: throw IllegalStateException(context.getString(R.string.invizible_none))
                    AppLog.i("InviZible Pro ${release.version}: downloading ${asset.first}")
                    InviZible.download(context, release, asset) { p -> progress = p }
                }
            }
            progress = -1
            result.onSuccess { apk ->
                runCatching { Updater.install(context, apk) }.onFailure { note = it.message }
            }.onFailure {
                AppLog.w("InviZible Pro download failed: ${it.message}")
                note = context.getString(R.string.update_failed, it.message ?: it.javaClass.simpleName)
            }
        }
    }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(stringResource(R.string.invizible_title), style = MaterialTheme.typography.titleSmall)
        Text(
            installed?.let { stringResource(R.string.invizible_installed, it) } ?: stringResource(R.string.invizible_desc),
            style = MaterialTheme.typography.bodySmall,
            color = if (installed != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (progress >= 0) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
        FilledTonalButton(enabled = progress < 0, onClick = { downloadAndInstall() }, modifier = Modifier.padding(top = 4.dp)) {
            Text(stringResource(if (installed == null) R.string.invizible_download else R.string.invizible_update))
        }
    }
}
