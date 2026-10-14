package com.teejei.weddingapp.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teejei.weddingapp.data.formatLongDate
import com.teejei.weddingapp.model.Guest
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

private const val MAX_BATCH = 8

/**
 * Manual December 1 follow-up: pick the guest groups (invitation sent) and tap Send. Nothing is texted until you do.
 * Sends at most [MAX_BATCH] groups per tap, one every few seconds, to stay under Android's SMS volume limit.
 */
@Composable
fun FollowUpDialog(vm: AppViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val guests by vm.guests.collectAsStateWithLifecycle()
    val sentTokens by vm.followUpSent.collectAsStateWithLifecycle()
    val busy by vm.followUpBusy.collectAsStateWithLifecycle()
    val cfg by vm.smsConfig.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(setOf<String>()) }
    var preview by remember { mutableStateOf<Guest?>(null) }
    var confirmSend by remember { mutableStateOf(false) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.toast(if (granted) "SMS permission allowed. Tap Send again." else "SMS permission denied, so nothing can be texted.")
    }

    LaunchedEffect(Unit) { vm.loadFollowUp() }

    val rows = remember(guests) {
        guests.filter { it.invitationSent }
            .sortedWith(compareBy({ it.groupName.isBlank() }, { it.groupName.lowercase() }))
    }
    val sent = sentTokens.orEmpty()
    fun canSend(g: Guest) = g.token !in sent && vm.hasValidNumber(g)
    val toSendCount = rows.count { canSend(it) }
    val alreadyCount = rows.count { it.token in sent }
    val noNumberCount = rows.count { it.token !in sent && !vm.hasValidNumber(g = it) }
    val picked = rows.filter { it.token in selected && canSend(it) }
    val batchSize = minOf(picked.size, MAX_BATCH)
    val daysToPlanned = ChronoUnit.DAYS.between(LocalDate.now(ZoneId.of("Asia/Manila")), cfg.followUpDate)
    val plannedText = when {
        daysToPlanned > 0 -> "Planned for ${formatLongDate(cfg.followUpDate)} (in $daysToPlanned day(s)) - you can still send early"
        daysToPlanned == 0L -> "Planned for today (${formatLongDate(cfg.followUpDate)})"
        else -> "Planned for ${formatLongDate(cfg.followUpDate)} (${-daysToPlanned} day(s) ago)"
    }

    Dialog(onDismissRequest = { if (busy == null) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.padding(12.dp).fillMaxWidth().widthIn(max = 720.dp).heightIn(max = 760.dp),
            shape = RoundedCornerShape(28.dp), color = Cream
        ) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Follow-up SMS", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Ask guests to finalize their list", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(plannedText, style = MaterialTheme.typography.bodySmall,
                            color = if (daysToPlanned > 0) Gold else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onClose, enabled = busy == null) { Icon(Icons.Filled.Close, "Close") }
                }
                if (sentTokens == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), color = Artichoke)
                Text("$toSendCount ready to send · $alreadyCount already sent · $noNumberCount without a valid number",
                    Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge, color = Artichoke)
                Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled = busy == null && sentTokens != null,
                        onClick = { selected = rows.filter { canSend(it) }.map { it.token }.toSet() }) { Text("Select all unsent") }
                    TextButton(enabled = busy == null && selected.isNotEmpty(), onClick = { selected = emptySet() }) { Text("Clear") }
                }
                if (rows.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("No guest groups are marked “invitation sent” yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else LazyColumn(
                    Modifier.weight(1f, fill = false),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(rows, key = { it.token }) { g ->
                        val wasSent = g.token in sent
                        val valid = vm.hasValidNumber(g)
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SurfaceWhite)
                                .clickable { preview = g }.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = g.token in selected, enabled = canSend(g) && busy == null,
                                onCheckedChange = { on -> selected = if (on) selected + g.token else selected - g.token }
                            )
                            Column(Modifier.weight(1f)) {
                                Text(g.groupName.ifBlank { "(unnamed)" }, style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    when {
                                        wasSent -> "Already sent"
                                        !valid -> "No valid contact number"
                                        else -> g.contact
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (!valid && !wasSent) Rose else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (wasSent) Icon(Icons.Filled.CheckCircle, "Sent", tint = Artichoke, modifier = Modifier.padding(end = 8.dp))
                        }
                    }
                }
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    busy?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Artichoke) }
                    if (picked.size > MAX_BATCH) {
                        Text("Sends $MAX_BATCH at a time. Wait a few minutes, then send the next batch.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(
                        onClick = { confirmSend = true },
                        enabled = busy == null && batchSize > 0, modifier = Modifier.fillMaxWidth()
                    ) { Text(if (batchSize == 0) "Select groups to send" else "Send to $batchSize group(s)") }
                }
            }
        }
    }

    preview?.let { g ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(g.groupName.ifBlank { "(unnamed)" }) },
            text = {
                val msg = vm.followUpMessage(g)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(msg)
                    Text("${msg.length} characters", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { preview = null }) { Text("Close") } }
        )
    }

    if (confirmSend) {
        AlertDialog(
            onDismissRequest = { confirmSend = false },
            title = { Text("Send follow-up SMS?") },
            text = {
                Text("This texts $batchSize guest group(s) now from this phone's SIM, one every few seconds, and tags them as sent in your sheet. " +
                        "It can't be undone." +
                        if (daysToPlanned > 0) "\n\nNote: this is before your planned date (${formatLongDate(cfg.followUpDate)})." else "")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSend = false
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
                        val batch = picked.take(MAX_BATCH)
                        selected = selected - batch.map { it.token }.toSet()
                        vm.sendFollowUps(batch)
                    } else permission.launch(Manifest.permission.SEND_SMS)
                }) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { confirmSend = false }) { Text("Cancel") } }
        )
    }
}