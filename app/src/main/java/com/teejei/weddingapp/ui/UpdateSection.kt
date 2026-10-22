package com.teejei.weddingapp.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teejei.weddingapp.data.UpdateChecker

/** Content of the "App updates" section in Settings. */
@Composable
fun UpdateContent(vm: AppViewModel) {
    val ctx = LocalContext.current
    val info by vm.updateInfo.collectAsStateWithLifecycle()
    val busy by vm.checkingUpdate.collectAsStateWithLifecycle()
    val status by vm.updateStatus.collectAsStateWithLifecycle()
    val installedName = remember { UpdateChecker.installedVersion(ctx).second }

    Text("Installed version: $installedName", style = MaterialTheme.typography.bodyMedium)

    info?.let { u ->
        Text("New version available: ${u.versionName}", style = MaterialTheme.typography.titleSmall)
        if (u.notes.isNotBlank()) {
            Text(u.notes, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(
            onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.apkUrl))) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Download update") }
        Text("After the download finishes, open the file to install it over the current app. " +
                "If Android asks, allow installing from this source.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    status?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    OutlinedButton(onClick = { vm.checkForUpdate(manual = true) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text(if (busy) "Checking…" else "Check for updates")
    }
}
