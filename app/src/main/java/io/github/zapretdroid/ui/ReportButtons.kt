package io.github.zapretdroid.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.zapretdroid.R
import io.github.zapretdroid.core.AppLog
import java.io.File

/**
 * Share a report, or save it where the person picks (Downloads…): the report itself lives in the
 * app's private folder, and some places (a browser chat) take only a file from storage.
 */
@Composable
fun ReportButtons(file: File, name: String, what: String, shareLabel: Int) {
    val context = LocalContext.current
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            AppLog.i("$what saved to a file")
            Toast.makeText(context, R.string.report_saved, Toast.LENGTH_SHORT).show()
        }.onFailure {
            AppLog.e("saving the $what failed", it)
            Toast.makeText(context, it.message, Toast.LENGTH_LONG).show()
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { shareFile(context, file, name, "$what shared") }, modifier = Modifier.weight(1f)) {
            Text(stringResource(shareLabel))
        }
        OutlinedButton(onClick = { save.launch(name) }, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.report_save))
        }
    }
}
