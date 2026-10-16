package com.teejei.weddingapp.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    val scope = rememberCoroutineScope()
    val themeMode by remember { ThemePrefs.flow(ctx) }.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
    val smsEnabled by vm.smsEnabled.collectAsStateWithLifecycle()
    var showFollowUp by remember { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }   // only one section open at a time
    val flip = { k: String -> expanded = if (expanded == k) null else k }
    val bgOn by vm.backgroundOn.collectAsStateWithLifecycle()
    var showConfirmList by remember { mutableStateOf(false) }
    var showDeclineList by remember { mutableStateOf(false) }
    val declineOn by vm.declineEnabled.collectAsStateWithLifecycle()
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
    val declinePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.setDeclineEnabled(true) else vm.toast("SMS permission denied, so decline notices can't be texted.")
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(XLSX_MIME)) { uri -> uri?.let { vm.exportExcel(it) } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).widthIn(max = 720.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsSection(Icons.Filled.Palette, "Appearance",
            when (themeMode) { ThemeMode.SYSTEM -> "Follows your phone's setting"; ThemeMode.LIGHT -> "Light theme"; ThemeMode.DARK -> "Dark theme" },
            expanded == "theme", { flip("theme") }) {
            Text("Theme", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark").forEach { (m, label) ->
                    FilterChip(selected = themeMode == m, onClick = { scope.launch { ThemePrefs.set(ctx, m) } }, label = { Text(label) })
                }
            }
            Text("System switches automatically with your phone's light/dark setting. Your choice is remembered on this phone.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SettingsSection(Icons.Filled.Link, "Connections", "Apps Script, Supabase & Save", expanded == "conn", { flip("conn") }) {
            Text("Guest backend (Google Apps Script)", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(script, { script = it }, Modifier.fillMaxWidth(), label = { Text("Web app URL (…/exec)") })
            OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("Admin key (ADMIN_KEY script property)") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true)
            HorizontalDivider()
            Text("Memories backend (Supabase)", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(sbUrl, { sbUrl = it }, Modifier.fillMaxWidth(), label = { Text("Project URL") })
            OutlinedTextField(sbKey, { sbKey = it }, Modifier.fillMaxWidth(), label = { Text("Publishable key") }, singleLine = true)
            OutlinedTextField(event, { event = it }, Modifier.fillMaxWidth(), label = { Text("Event ID") }, singleLine = true)
            Button(
                onClick = { vm.saveSettings(saved.copy(scriptUrl = script, adminKey = key, supabaseUrl = sbUrl, supabaseKey = sbKey, eventId = event)) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save & refresh") }
        }

        SettingsSection(Icons.Filled.Edit, "Message details", "Names and dates used in texts", expanded == "msg", { flip("msg") }) {
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

        SettingsSection(Icons.Filled.Sms, "SMS confirmations", if (smsEnabled) "Automatic texting is on" else "Automatic texting is off", expanded == "sms", { flip("sms") }) {
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
                    "Turning this on tags people who already confirmed without texting them. It checks on refresh and every 1-5 minutes (you choose under Background sync) while the app is open or Background sync is on.",
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
        }
        SettingsSection(Icons.Filled.EventBusy, "Decline notices",
            if (declineOn) "Automatic texting is on" else "Automatic texting is off", expanded == "decline", { flip("decline") }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Text guests when a seat is declined", style = MaterialTheme.typography.bodyMedium)
                    Text(if (declineOn) "On" else "Off", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = declineOn, enabled = guests.isNotEmpty(),
                    onCheckedChange = { on ->
                        if (!on) vm.setDeclineEnabled(false)
                        else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) vm.setDeclineEnabled(true)
                        else declinePermission.launch(Manifest.permission.SEND_SMS)
                    }
                )
            }
            Text("A gentle text naming who will not attend. Each declined seat is texted once and tagged in your Google Sheet " +
                    "(column sms_declined_seats). Turning this on tags seats that already declined without texting them.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { showDeclineList = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Send decline notices manually")
            }
            Text("Choose which groups to text and preview each message first. Send the ones you want before turning the automatic switch on.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (showDeclineList) DeclineDialog(vm) { showDeclineList = false }
        }
        SettingsSection(Icons.Filled.Send, "Follow-up for finalization", "Manual text asking guests to finalize", expanded == "follow", { flip("follow") }) {
            OutlinedButton(onClick = { showFollowUp = true }, modifier = Modifier.fillMaxWidth()) { Text("Open follow-up list") }
            Text("Manual: nothing is texted until you choose guest groups (invitation sent) and tap Send. " +
                    "Each group is texted once and tagged in the sheet column finalize_sms_sent_at. You can preview each message first.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (showFollowUp) FollowUpDialog(vm) { showFollowUp = false }
        }

        SettingsSection(Icons.Filled.Sync, "Background sync", if (bgOn) "On - keeps syncing when minimized" else "Off", expanded == "bg", { flip("bg") }) {
            val pm = remember { ctx.getSystemService(android.os.PowerManager::class.java) }
            var unrestricted by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(ctx.packageName)) }
            val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                unrestricted = pm.isIgnoringBatteryOptimizations(ctx.packageName)
            }
            val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> vm.setBackground(true) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Keep syncing when the app is minimized", style = MaterialTheme.typography.bodyMedium)
                    Text(if (bgOn) "On" else "Off", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = bgOn,
                    onCheckedChange = { on ->
                        if (!on) vm.setBackground(false)
                        else if (android.os.Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else vm.setBackground(true)
                    }
                )
            }
            Text("Shows a small \"syncing\" notification and keeps refreshing guests and sending the automatic confirmation texts " +
                    "every few minutes, even with the screen off. Minimize the app normally; do not swipe it out of Recents, " +
                    "because that stops it. Use the Stop button in the notification or this switch to turn it off.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val mins by vm.syncMinutes.collectAsStateWithLifecycle()
            Text("Check for new replies every", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 2, 5).forEach { m ->
                    FilterChip(selected = mins == m, onClick = { vm.setSyncMinutes(m) }, label = { Text("$m min") })
                }
            }
            Text("Shorter means guests get their text almost right away, but it uses more battery and data. " +
                    "1-2 minutes is recommended around the days people are replying.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(
                enabled = !unrestricted, modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val direct = android.content.Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:" + ctx.packageName)
                    )
                    runCatching { batteryLauncher.launch(direct) }.onFailure {
                        runCatching { batteryLauncher.launch(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    }
                }
            ) { Text(if (unrestricted) "Battery use: unrestricted (good)" else "Allow unrestricted battery use") }
            if (!unrestricted) {
                Text("Needed so the phone does not pause the sync while the screen is off. On Xiaomi, Oppo, Vivo or Samsung phones also set the app's " +
                        "battery mode to Unrestricted / No restrictions in the system App info page.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        SettingsSection(Icons.Filled.Storage, "Data and Excel", "Synced: ${formatMillis(saved.lastGuestSync) ?: "never"}", expanded == "data", { flip("data") }) {
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

/** One row of the settings list: tap the header to show or hide its content. */
@Composable
private fun SettingsSection(
    icon: ImageVector,
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = SurfaceWhite, tonalElevation = 1.dp) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Sage.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = Artichoke)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content
                )
            }
        }
    }
}