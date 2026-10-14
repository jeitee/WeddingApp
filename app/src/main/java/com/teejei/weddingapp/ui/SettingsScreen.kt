package com.teejei.weddingapp.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.teejei.weddingapp.data.MessageConfig
import com.teejei.weddingapp.data.SimOption
import com.teejei.weddingapp.data.formatLongDate
import java.time.LocalDate
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
    val ctx = LocalContext.current
    val smsEnabled by vm.smsEnabled.collectAsStateWithLifecycle()
    var showFollowUp by remember { mutableStateOf(false) }
    var showConfirmList by remember { mutableStateOf(false) }
    val simId by vm.simId.collectAsStateWithLifecycle()
    var sims by remember { mutableStateOf(vm.loadSims()) }
    val phoneState = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) sims = vm.loadSims() else vm.toast("Phone permission denied, so the SIM can't be chosen.")
    }
    val savedCfg by vm.smsConfig.collectAsStateWithLifecycle()
    var cfg by remember(savedCfg) { mutableStateOf(savedCfg) }
    val smsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.setSmsEnabled(true) else vm.toast("SMS permission denied, so confirmations can't be texted.")
    }
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
            Text("Message details", style = MaterialTheme.typography.titleMedium)
            Text("Used in every SMS the app sends. Changing these needs no rebuild; texts already sent are not affected.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(cfg.couple, { cfg = cfg.copy(couple = it.take(40)) }, Modifier.fillMaxWidth(),
                label = { Text("Couple names (signature)") }, singleLine = true)
            DateRow("Wedding date", cfg.weddingDate, { cfg = cfg.copy(weddingDate = it) })
            DateRow("Follow-up send date (when you plan to send it)", cfg.followUpDate, { cfg = cfg.copy(followUpDate = it) })
            DateRow("Finalize-by deadline (shown in the follow-up text)", cfg.finalizeBy, { cfg = cfg.copy(finalizeBy = it) },
                onClear = { cfg = cfg.copy(finalizeBy = null) })
            Text("Use plain letters in the names: accents or emojis make each text hold fewer characters and cost more.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(
                onClick = { vm.saveSmsConfig(cfg.copy(couple = cfg.couple.ifBlank { MessageConfig().couple })) },
                enabled = cfg != savedCfg, modifier = Modifier.fillMaxWidth()
            ) { Text("Save message details") }
        }

        SoftCard {
            Text("SMS confirmations", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Text guests when a seat confirms", style = MaterialTheme.typography.bodyMedium)
                    Text(if (smsEnabled) "On" else "Off", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = smsEnabled, enabled = guests.isNotEmpty(),
                    onCheckedChange = { on ->
                        if (!on) vm.setSmsEnabled(false)
                        else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) vm.setSmsEnabled(true)
                        else smsPermission.launch(Manifest.permission.SEND_SMS)
                    }
                )
            }
            Text("Sent from this phone's SIM to the group's contact number, naming only the newly confirmed guests and how many seats are still open. " +
                    "Each seat is texted once. Texted seats are tagged in your Google Sheet (column sms_sent_seats), so another phone won't text them again. " +
                    "Turning this on tags people who already confirmed without texting them. It checks on refresh and every 5 minutes while the app is running.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            Text("Send texts from", style = MaterialTheme.typography.bodyMedium)
            if (sims.isEmpty()) {
                OutlinedButton(onClick = { phoneState.launch(Manifest.permission.READ_PHONE_STATE) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Choose SIM (allow phone access)")
                }
                Text("Currently using the phone's default SMS SIM.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                (listOf(SimOption(-1, "Phone default")) + sims).forEach { o ->
                    Row(Modifier.fillMaxWidth().clickable { vm.setSim(o.subId) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = simId == o.subId, onClick = { vm.setSim(o.subId) })
                        Text(o.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (!smsEnabled) {
                OutlinedButton(onClick = { showConfirmList = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Send confirmations manually")
                }
                Text("Automatic texting is off. Open the list to choose which confirmed guests to text. " +
                        "Send the ones you want first: turning the switch on tags everyone not yet texted as done, without texting them.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (showConfirmList) ConfirmationDialog(vm) { showConfirmList = false }
            HorizontalDivider()
            Text("Follow-up for finalization", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { showFollowUp = true }, modifier = Modifier.fillMaxWidth()) { Text("Open follow-up list") }
            Text("Manual: nothing is texted until you choose guest groups (invitation sent) and tap Send. " +
                    "Each group is texted once and tagged in the sheet column finalize_sms_sent_at. You can preview each message first.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (showFollowUp) FollowUpDialog(vm) { showFollowUp = false }
        }

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

@Composable
private fun DateRow(label: String, date: LocalDate?, onPick: (LocalDate) -> Unit, onClear: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(date?.let { formatLongDate(it) } ?: "Not set", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (date != null && onClear != null) TextButton(onClick = onClear) { Text("Clear") }
        OutlinedButton(onClick = {
            val d = date ?: LocalDate.now()
            android.app.DatePickerDialog(ctx, { _, y, m, day -> onPick(LocalDate.of(y, m + 1, day)) },
                d.year, d.monthValue - 1, d.dayOfMonth).show()
        }) { Text(if (date == null) "Set" else "Change") }
    }
}