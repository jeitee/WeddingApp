package com.teejei.weddingapp.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teejei.weddingapp.model.formatMillis

private const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val saved by vm.settings.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val importResult by vm.importResult.collectAsStateWithLifecycle()
    val guests by vm.guests.collectAsStateWithLifecycle()
    var script by remember(saved) { mutableStateOf(saved.scriptUrl) }
    var key by remember(saved) { mutableStateOf(saved.adminKey) }
    var sbUrl by remember(saved) { mutableStateOf(saved.supabaseUrl) }
    var sbKey by remember(saved) { mutableStateOf(saved.supabaseKey) }
    var event by remember(saved) { mutableStateOf(saved.eventId) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importExcel(it) } }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(XLSX_MIME)) { uri -> uri?.let { vm.exportExcel(it) } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).widthIn(max = 720.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SoftCard {
            Text("Guest backend (Google Apps Script)", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(script, { script = it }, Modifier.fillMaxWidth(), label = { Text("Web app URL (…/exec)") })
            OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("Admin key (ADMIN_KEY script property)") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true)
        }
        SoftCard {
            Text("Memories backend (Supabase)", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(sbUrl, { sbUrl = it }, Modifier.fillMaxWidth(), label = { Text("Project URL") })
            OutlinedTextField(sbKey, { sbKey = it }, Modifier.fillMaxWidth(), label = { Text("Publishable key") }, singleLine = true)
            OutlinedTextField(event, { event = it }, Modifier.fillMaxWidth(), label = { Text("Event ID") }, singleLine = true)
        }
        Button(
            onClick = { vm.saveSettings(saved.copy(scriptUrl = script, adminKey = key, supabaseUrl = sbUrl, supabaseKey = sbKey, eventId = event)) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save & refresh") }

        SoftCard {
            Text("Data", style = MaterialTheme.typography.titleMedium)
            Text("Guests synced: ${formatMillis(saved.lastGuestSync) ?: "never"}\nMemories synced: ${formatMillis(saved.lastMemorySync) ?: "never"}",
                style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { vm.refresh() }, enabled = !refreshing, modifier = Modifier.fillMaxWidth()) {
                Text(if (refreshing) "Refreshing…" else "Refresh now")
            }
            OutlinedButton(
                onClick = { picker.launch(arrayOf(XLSX_MIME, "application/octet-stream")) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Import guest Excel (.xlsx)") }
            Text("The Excel file needs a header row with at least a “token” column. Optional: group_name, max_pax, contact, url. " +
                    "Imported invitation URLs are used only when the backend has none for that token.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            importResult?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            HorizontalDivider()
            OutlinedButton(
                onClick = { exporter.launch("guest-list-${java.time.LocalDate.now()}.xlsx") },
                enabled = guests.isNotEmpty(), modifier = Modifier.fillMaxWidth()
            ) { Text("Export guest list (.xlsx)") }
            Text("Saves two sheets: “Guest Invi” (one row per guest group with RSVP, invitation status and confirmed/declined names) " +
                    "and “People” (one row per seat). The first sheet can be imported back into this app.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}