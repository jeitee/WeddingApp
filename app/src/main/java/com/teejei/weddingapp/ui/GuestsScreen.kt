package com.teejei.weddingapp.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teejei.weddingapp.model.*

fun shareText(ctx: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
    ctx.startActivity(Intent.createChooser(send, "Share invitation"))
}

fun rsvpMessage(url: String): String =
    "$url\n\n\nYour presence would make our wedding day even more special. " +
            "Kindly RSVP through the link above as soon as you’re able. 🤍"

fun openUrl(ctx: Context, url: String, onFail: () -> Unit) {
    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    catch (e: ActivityNotFoundException) { onFail() }
}

@Composable
fun GuestsScreen(vm: AppViewModel, onOpenSettings: () -> Unit) {
    val all by vm.guests.collectAsStateWithLifecycle()
    val guests by vm.visibleGuests.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val error by vm.guestError.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    var dialogFor by remember { mutableStateOf<Guest?>(null) }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = filter.query, onValueChange = { q -> vm.updateFilter { it.copy(query = q) } },
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
            singleLine = true, placeholder = { Text("Search guest or group name") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (filter.query.isNotEmpty()) IconButton(onClick = { vm.updateFilter { it.copy(query = "") } }) {
                    Icon(Icons.Filled.Close, "Clear search")
                }
            },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
        )
        FilterBar(filter, vm)
        if (refreshing && all.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Artichoke) }
        } else when {
            all.isEmpty() -> Column(Modifier.fillMaxSize()) {
                error?.let { Box(Modifier.padding(16.dp)) { ErrorBanner(it) { vm.refresh() } } }
                EmptyState(Icons.Filled.People, "No guests yet",
                    "Sync with the Apps Script backend (needs the admin key) or import your Excel file from Settings.",
                    "Open Settings", onOpenSettings)
            }
            guests.isEmpty() -> EmptyState(Icons.Filled.SearchOff, "No matches", "Try clearing a filter or the search.",
                "Clear filters") { vm.updateFilter { GuestFilter(sort = it.sort) } }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(360.dp), contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                error?.let { e -> item(span = { GridItemSpan(maxLineSpan) }) { ErrorBanner(e) { vm.refresh() } } }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("${guests.size} of ${all.size} guest groups", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(guests, key = { it.token }) { g -> GuestCard(g, vm) { dialogFor = g } }
            }
        }
    }

    dialogFor?.let { g -> SentDialog(g, onDismiss = { dialogFor = null }) { sent, notes -> vm.setSent(g, sent, notes); dialogFor = null } }
}

@Composable
private fun FilterBar(f: GuestFilter, vm: AppViewModel) {
    var sortMenu by remember { mutableStateOf(false) }
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Rsvp.values().forEach { r ->
            FilterChip(f.rsvp == r, { vm.updateFilter { it.copy(rsvp = if (it.rsvp == r) null else r) } }, { Text(r.label) })
        }
        FilterChip(f.sent == true, { vm.updateFilter { it.copy(sent = if (it.sent == true) null else true) } }, { Text("Sent") })
        FilterChip(f.sent == false, { vm.updateFilter { it.copy(sent = if (it.sent == false) null else false) } }, { Text("Not sent") })
        FilterChip(f.opened == true, { vm.updateFilter { it.copy(opened = if (it.opened == true) null else true) } }, { Text("Opened") })
        FilterChip(f.opened == false, { vm.updateFilter { it.copy(opened = if (it.opened == false) null else false) } }, { Text("Not opened") })
        Box {
            AssistChip(onClick = { sortMenu = true }, label = { Text("Sort: ${f.sort.label}") },
                leadingIcon = { Icon(Icons.Filled.SwapVert, null, Modifier.size(18.dp)) })
            DropdownMenu(sortMenu, { sortMenu = false }) {
                SortKey.values().forEach { k ->
                    DropdownMenuItem(text = { Text(k.label) }, onClick = { vm.updateFilter { it.copy(sort = k) }; sortMenu = false })
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GuestCard(g: Guest, vm: AppViewModel, onMark: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var expanded by remember { mutableStateOf(false) }
    val usable = g.urlState == UrlState.OK || g.urlState == UrlState.TOKEN_MISMATCH

    SoftCard(Modifier.clickable { expanded = !expanded }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(g.groupName.ifBlank { "(unnamed)" }, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // One chip per status the group has people in (a group can have several at once)
            if (g.confirmedSeats > 0) StatusChip("Confirmed ${g.confirmedSeats}", Artichoke)
            if (g.declinedSeats > 0) StatusChip("Declined ${g.declinedSeats}", Rose)
            if (g.awaitingSeats > 0) StatusChip("Awaiting ${g.awaitingSeats}", Gold)
            StatusChip(if (g.invitationSent) "Sent" else "Not sent", if (g.invitationSent) Artichoke else Gold)
            StatusChip(if (g.opened) "Opened" else "Not opened", if (g.opened) Artichoke else Color(0xFF8A8A80))
            if (g.pendingSync) StatusChip("Sync pending", Rose)
        }
        Text(
            "${g.maxPax} seat(s) · ${g.confirmedSeats} confirmed · ${g.declinedSeats} declined · ${g.awaitingSeats} awaiting",
            style = MaterialTheme.typography.bodyMedium
        )
        if (g.contact.isNotBlank()) Text("Contact: ${g.contact}", style = MaterialTheme.typography.bodyMedium)
        formatTime(g.lastOpenedAt)?.let {
            Text("Last visit: $it · ${g.visitCount} visit(s)", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // Invitation URL
        when (g.urlState) {
            UrlState.MISSING -> Text("⚠ No invitation URL on record", color = Rose, style = MaterialTheme.typography.bodySmall)
            UrlState.INVALID -> Text("⚠ Invalid invitation URL: ${g.url}", color = Rose, style = MaterialTheme.typography.bodySmall)
            UrlState.TOKEN_MISMATCH -> Text("⚠ URL token doesn't match this guest's token\n${shortUrl(g.url)}", color = Rose, style = MaterialTheme.typography.bodySmall)
            UrlState.OK -> Text(if (expanded) g.url else shortUrl(g.url), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(enabled = usable, onClick = { clipboard.setText(AnnotatedString(g.url)); vm.toast("Invitation URL copied") }) {
                Icon(Icons.Filled.ContentCopy, "Copy invitation URL")
            }
            IconButton(enabled = usable, onClick = { shareText(ctx, g.url) }) { Icon(Icons.Filled.Share, "Share invitation URL") }
            IconButton(enabled = usable, onClick = { shareText(ctx, rsvpMessage(g.url)) }) {
                Icon(Icons.Filled.Chat, "Share RSVP message")
            }
            IconButton(enabled = usable, onClick = { openUrl(ctx, g.url) { vm.toast("No browser found") } }) {
                Icon(Icons.Filled.OpenInBrowser, "Open invitation in browser")
            }
            Spacer(Modifier.weight(1f))
            if (g.invitationSent) OutlinedButton(onClick = onMark) { Text("Mark not sent") }
            else FilledTonalButton(onClick = onMark) { Text("Mark as sent") }
        }

        if (expanded) {
            HorizontalDivider()
            if (g.invitationSent) {
                Text("Marked sent: ${formatTime(g.sentAt) ?: "—"}", style = MaterialTheme.typography.bodyMedium)
                if (g.sentNotes.isNotBlank()) Text("Notes: ${g.sentNotes}", style = MaterialTheme.typography.bodyMedium)
            }
            formatTime(g.firstOpenedAt)?.let { Text("First opened: $it", style = MaterialTheme.typography.bodyMedium) }
            Text("Seats", style = MaterialTheme.typography.labelLarge)
            if (g.seats.isEmpty()) Text("No RSVP seat details yet.", style = MaterialTheme.typography.bodySmall)
            g.seats.forEach { s ->
                val label = when (s.attending) { "attending" -> "Attending"; "not_attending" -> "Not attending"; else -> "Undecided" }
                Text("${s.seatNo}. ${s.name.ifBlank { "—" }} · $label", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun SentDialog(g: Guest, onDismiss: () -> Unit, onConfirm: (Boolean, String) -> Unit) {
    var notes by remember { mutableStateOf("") }
    val toSent = !g.invitationSent
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (toSent) "Mark invitation as sent?" else "Mark as not sent?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (toSent) {
                    Text("Only confirm if you have actually delivered ${g.groupName}'s invitation. Copying or sharing the link isn't recorded as sent.")
                    OutlinedTextField(notes, { notes = it }, label = { Text("Notes (optional)") },
                        placeholder = { Text("e.g. sent via Messenger") })
                } else {
                    Text("This clears the sent record for ${g.groupName}" +
                            (formatTime(g.sentAt)?.let { " (marked sent $it)" } ?: "") + ".")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(toSent, notes) }) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}