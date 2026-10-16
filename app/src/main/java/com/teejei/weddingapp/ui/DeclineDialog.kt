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

private const val MAX_BATCH = 8

/**
 * Manual decline notices: pick the guest groups that have declined seats you have not texted about yet, preview, and send.
 * Nothing is texted until you tap Send. At most [MAX_BATCH] groups per tap, a few seconds apart.
 */
@Composable
fun DeclineDialog(vm: AppViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val guests by vm.guests.collectAsStateWithLifecycle()
    val pending by vm.declinePending.collectAsStateWithLifecycle()
    val busy by vm.declineBusy.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(setOf<String>()) }
    var preview by remember { mutableStateOf<Guest?>(null) }
    var confirmSend by remember { mutableStateOf(false) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.toast(if (granted) "SMS permission allowed. Tap Send again." else "SMS permission denied, so nothing can be texted.")
    }

    LaunchedEffect(Unit) { vm.loadDeclines() }

    val pend = pending.orEmpty()
    val rows = remember(guests, pending) {
        guests.filter { it.token in pend }
            .sortedWith(compareBy({ it.groupName.isBlank() }, { it.groupName.lowercase() }))
    }
    fun canSend(g: Guest) = vm.hasValidNumber(g)
    val readyCount = rows.count { canSend(it) }
    val noNumberCount = rows.count { !canSend(it) }
    val picked = rows.filter { it.token in selected && canSend(it) }
    val batchSize = minOf(picked.size, MAX_BATCH)

    fun namesOf(g: Guest): String {
        val seatNos = pend[g.token].orEmpty()
        return g.seats.filter { it.seatNo in seatNos }.joinToString(", ") { it.name.trim().ifBlank { "a guest" } }
    }

    Dialog(onDismissRequest = { if (busy == null) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.padding(12.dp).fillMaxWidth().widthIn(max = 720.dp).heightIn(max = 760.dp),
            shape = RoundedCornerShape(28.dp), color = Cream
        ) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Decline notices", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Groups with declined seats not yet texted", style = MaterialTheme.typography.bodySmall,
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
                }
                if (pending != null && rows.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("No new declines to text about.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else LazyColumn(
                    Modifier.weight(1f, fill = false),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(rows, key = { it.token }) { g ->
                        val valid = canSend(g)
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
                                Text("Declined: ${namesOf(g)}", style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    if (valid) g.contact else "No valid contact number",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (valid) MaterialTheme.colorScheme.onSurfaceVariant else Rose
                                )
                            }
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
                val msg = vm.declineMessage(g)
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
            title = { Text("Send decline notices?") },
            text = {
                Text("This texts $batchSize guest group(s) now from this phone's SIM, one every few seconds, and tags those seats as texted in your sheet. " +
                    "It can't be undone.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSend = false
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
                        val batch = picked.take(MAX_BATCH)
                        selected = selected - batch.map { it.token }.toSet()
                        vm.sendDeclines(batch)
                    } else permission.launch(Manifest.permission.SEND_SMS)
                }) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { confirmSend = false }) { Text("Cancel") } }
        )
    }
}
