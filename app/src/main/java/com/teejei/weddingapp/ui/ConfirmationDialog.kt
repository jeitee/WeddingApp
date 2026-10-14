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
import com.teejei.weddingapp.model.Guest

private const val MAX_CONFIRM_BATCH = 8

/**
 * Manual confirmation texts (used when the automatic switch is OFF). Lists guest groups that have confirmed seats
 * which were not texted yet. Pick groups, preview the message, tap Send.
 */
@Composable
fun ConfirmationDialog(vm: AppViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val guests by vm.guests.collectAsStateWithLifecycle()
    val pending by vm.confirmPending.collectAsStateWithLifecycle()
    val busy by vm.confirmBusy.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(setOf<String>()) }
    var preview by remember { mutableStateOf<Guest?>(null) }
    var confirmSend by remember { mutableStateOf(false) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.toast(if (granted) "SMS permission allowed. Tap Send again." else "SMS permission denied, so nothing can be texted.")
    }

    LaunchedEffect(Unit) { vm.loadConfirmations() }

    val map = pending.orEmpty()
    val rows = remember(guests, pending) {
        guests.filter { it.token in map }
            .sortedWith(compareBy({ it.groupName.isBlank() }, { it.groupName.lowercase() }))
    }
    fun canSend(g: Guest) = vm.hasValidNumber(g)
    val readyCount = rows.count { canSend(it) }
    val noNumberCount = rows.count { !canSend(it) }
    val picked = rows.filter { it.token in selected && canSend(it) }
    val batchSize = minOf(picked.size, MAX_CONFIRM_BATCH)

    Dialog(onDismissRequest = { if (busy == null) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.padding(12.dp).fillMaxWidth().widthIn(max = 720.dp).heightIn(max = 760.dp),
            shape = RoundedCornerShape(28.dp), color = Cream
        ) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Send confirmations", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Guests who confirmed but were not texted yet", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onClose, enabled = busy == null) { Icon(Icons.Filled.Close, "Close") }
                }
                if (pending == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), color = Artichoke)
                Text("$readyCount ready to send · $noNumberCount without a valid number",
                    Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge, color = Artichoke)
                Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled = busy == null && pending != null,
                        onClick = { selected = rows.filter { canSend(it) }.map { it.token }.toSet() }) { Text("Select all") }
                    TextButton(enabled = busy == null && selected.isNotEmpty(), onClick = { selected = emptySet() }) { Text("Clear") }
                    TextButton(enabled = busy == null, onClick = { vm.loadConfirmations() }) { Text("Reload") }
                }
                if (pending != null && rows.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("Everyone who confirmed has already been texted.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else LazyColumn(
                    Modifier.weight(1f, fill = false),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(rows, key = { it.token }) { g ->
                        val valid = vm.hasValidNumber(g)
                        val seatNos = map[g.token].orEmpty()
                        val names = g.seats.filter { it.seatNo in seatNos }.joinToString(", ") { it.name.ifBlank { "a guest" } }
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SurfaceWhite)
                                .clickable { preview = g }.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = g.token in selected, enabled = valid && busy == null,
                                onCheckedChange = { on -> selected = if (on) selected + g.token else selected - g.token }
                            )
                            Column(Modifier.weight(1f)) {
                                Text(g.groupName.ifBlank { "(unnamed)" }, style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("Confirmed: $names", style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (valid) g.contact else "No valid contact number",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (!valid) Rose else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    busy?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Artichoke) }
                    if (picked.size > MAX_CONFIRM_BATCH) {
                        Text("Sends $MAX_CONFIRM_BATCH at a time. Wait a few minutes, then send the next batch.",
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
                val msg = vm.confirmationMessage(g)
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
            title = { Text("Send confirmation SMS?") },
            text = {
                Text("This texts $batchSize guest group(s) now from this phone's SIM, one every few seconds, and tags those seats as texted in your sheet. It can't be undone.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSend = false
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
                        val batch = picked.take(MAX_CONFIRM_BATCH)
                        selected = selected - batch.map { it.token }.toSet()
                        vm.sendConfirmations(batch)
                    } else permission.launch(Manifest.permission.SEND_SMS)
                }) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { confirmSend = false }) { Text("Cancel") } }
        )
    }
}
