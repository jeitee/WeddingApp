package com.teejei.weddingapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teejei.weddingapp.model.Guest
import com.teejei.weddingapp.model.formatMillis

// Empty part of the charts / bars: light gray in the light theme, deep green-gray in the dark theme.
private val TrackGray: Color get() = if (ThemeState.dark) Color(0xFF353E32) else Color(0xFFE3E7DF)

private const val ATTENDING = "attending"
private const val NOT_ATTENDING = "not_attending"
private const val MAX_SEATS = 150

/* ---------------------------------------------------------------------------------------------
 * RSVP summary: one place that counts seats (people) and groups, derived from the guest list.
 *
 * Seats  : every seat is attending / not attending / awaiting (anything else).
 * Groups : a group counts as
 *            - attending     if at least one of its seats is attending
 *            - not attending if it has no attending seat and every seat declined
 *            - awaiting      otherwise
 * ------------------------------------------------------------------------------------------- */
private data class RsvpSummary(
    val seatsAttending: Int = 0,
    val seatsNotAttending: Int = 0,
    val seatsAwaiting: Int = 0,
    // A group counts under every status it has at least one seat in, so these can overlap.
    val groupsAttending: Int = 0,
    val groupsNotAttending: Int = 0,
    val groupsAwaiting: Int = 0,
    val groups: Int = 0,
) {
    val seats get() = seatsAttending + seatsNotAttending + seatsAwaiting
    val seatsAvailable get() = (MAX_SEATS - seatsAttending).coerceAtLeast(0)
}

private fun summarize(guests: List<Guest>): RsvpSummary {
    var sa = 0; var sn = 0; var sw = 0
    var ga = 0; var gn = 0; var gw = 0; var gt = 0
    guests.forEach { g ->
        var a = 0; var n = 0; var w = 0
        g.seats.forEach { s ->
            when (s.attending) {
                ATTENDING -> a++
                NOT_ATTENDING -> n++
                else -> w++
            }
        }
        sa += a; sn += n; sw += w
        if (a + n + w > 0) {
            gt++
            if (a > 0) ga++
            if (n > 0) gn++
            if (w > 0) gw++
        }
    }
    return RsvpSummary(sa, sn, sw, ga, gn, gw, gt)
}

@Composable
fun DashboardScreen(vm: AppViewModel) {
    val inv by vm.inviteStats.collectAsStateWithLifecycle()
    val mem by vm.memoryStats.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val gErr by vm.guestError.collectAsStateWithLifecycle()
    val mErr by vm.memoryError.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val guests by vm.guests.collectAsStateWithLifecycle()
    val rsvp = remember(guests) { summarize(guests) }
    var listFor by remember { mutableStateOf<String?>(null) }
    val full: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }

    when (listFor) {
        ATTENDING -> NamesDialog("Confirmed to attend", ATTENDING, guests, Artichoke) { listFor = null }
        NOT_ATTENDING -> NamesDialog("Declined", NOT_ATTENDING, guests, Rose) { listFor = null }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(340.dp), modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(span = full) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Teejei & Judith Marie", style = MaterialTheme.typography.headlineMedium)
                Text("December 20, 2026", color = Artichoke, style = MaterialTheme.typography.labelLarge)
                val g = formatMillis(settings.lastGuestSync); val m = formatMillis(settings.lastMemorySync)
                Text(
                    "Guests synced: ${g ?: "never"}\nMemories synced: ${m ?: "never"}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (refreshing) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Artichoke)
            }
        }
        gErr?.let { e -> item(span = full) { ErrorBanner("Guests: $e") { vm.refresh() } } }
        mErr?.let { e -> item(span = full) { ErrorBanner("Memories: $e") { vm.refresh() } } }

        // Attendance counters: people (seats) attending / not attending, with their groups
        item(span = full) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CounterCard(
                    label = "Attending", accent = Artichoke,
                    seats = rsvp.seatsAttending, groups = rsvp.groupsAttending,
                    modifier = Modifier.weight(1f)
                ) { listFor = ATTENDING }
                CounterCard(
                    label = "Not attending", accent = Rose,
                    seats = rsvp.seatsNotAttending, groups = rsvp.groupsNotAttending,
                    modifier = Modifier.weight(1f)
                ) { listFor = NOT_ATTENDING }
            }
        }

        // RSVP charts
        item {
            SoftCard {
                Text("RSVP overview", style = MaterialTheme.typography.titleMedium)

                Text("Seats ($MAX_SEATS max · ${rsvp.seats} invited)", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    DonutChart(
                        slices = listOf(
                            rsvp.seatsAttending.toFloat() to Artichoke,
                            rsvp.seatsAwaiting.toFloat() to Gold
                        ),
                        centerTop = "${rsvp.seatsAvailable}", centerBottom = "seats left",
                        capacity = MAX_SEATS.toFloat()
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        LegendRow(Artichoke, "Attending", "${rsvp.seatsAttending}") { listFor = ATTENDING }
                        LegendRow(Gold, "Awaiting", "${rsvp.seatsAwaiting}")
                        LegendRow(TrackGray, "Available", "${rsvp.seatsAvailable}")
                        LegendRow(Rose, "Not attending", "${rsvp.seatsNotAttending}") { listFor = NOT_ATTENDING }
                    }
                }

                HorizontalDivider()

                Text("Groups (${rsvp.groups} total)", style = MaterialTheme.typography.labelLarge)
                val gt = rsvp.groups.toFloat()
                ProgressRow("Attending", rsvp.groupsAttending.toFloat(), gt, Artichoke)
                ProgressRow("Not attending", rsvp.groupsNotAttending.toFloat(), gt, Rose)
                ProgressRow("Awaiting", rsvp.groupsAwaiting.toFloat(), gt, Gold)
                Text(
                    "A group appears under each status it has people in, so rows can add up to more than ${rsvp.groups}.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Invitation progress bars
        item {
            SoftCard {
                Text("Invitation progress", style = MaterialTheme.typography.titleMedium)
                val total = inv.sent + inv.notSent
                ProgressRow("Sent", inv.sent.toFloat(), total.toFloat(), Artichoke)
                ProgressRow("Opened", inv.opened.toFloat(), total.toFloat(), Gold)
                HorizontalDivider()
                Row(Modifier.fillMaxWidth()) {
                    Stat("${inv.notSent}", "Not yet sent", Modifier.weight(1f), accent = Gold)
                    Stat("${inv.visits}", "Total visits", Modifier.weight(1f), sub = "Repeat view within 30 min counts once")
                }
            }
        }

        // Memories
        item {
            SoftCard {
                Text("Memories", style = MaterialTheme.typography.titleMedium)
                StackedBar(listOf(mem.photos.toFloat() to Artichoke, mem.videos.toFloat() to Gold))
                Row(Modifier.fillMaxWidth()) {
                    Stat("${mem.photos}", "Photos", Modifier.weight(1f))
                    Stat("${mem.videos}", "Videos", Modifier.weight(1f), accent = Gold)
                    Stat("${mem.uploaders}", "Uploaders", Modifier.weight(1f))
                }
            }
        }
    }
}

/** Big tappable counter: number of people, with the number of groups they belong to underneath. */
@Composable
private fun CounterCard(
    label: String, accent: Color, seats: Int, groups: Int,
    modifier: Modifier = Modifier, onClick: () -> Unit
) {
    SoftCard(modifier = modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(accent))
            Spacer(Modifier.width(8.dp))
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Icon(Icons.Filled.ChevronRight, null, Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("$seats", color = accent, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text(
            "${if (seats == 1) "person" else "people"} · $groups ${if (groups == 1) "group" else "groups"}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Donut chart. When [capacity] is set, the ring represents that many seats and any unfilled part
 * shows as the gray track (= available). If the slices exceed the capacity, the ring scales to them.
 */
@Composable
private fun DonutChart(
    slices: List<Pair<Float, Color>>, centerTop: String, centerBottom: String,
    capacity: Float = 0f
) {
    val total = maxOf(slices.sumOf { it.first.toDouble() }.toFloat(), capacity)
    Box(Modifier.size(140.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 22.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            drawArc(TrackGray, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            if (total > 0f) {
                val visible = slices.count { it.first > 0f }
                var start = -90f
                slices.forEach { (v, c) ->
                    if (v > 0f) {
                        val sweep = v / total * 360f
                        // Only leave a gap between slices when there is more than one
                        val gap = if (visible > 1) 2f else 0f
                        drawArc(c, start, (sweep - gap).coerceAtLeast(0.5f), false, topLeft, arcSize,
                            style = Stroke(stroke, cap = StrokeCap.Butt))
                        start += sweep
                    }
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(centerTop, style = MaterialTheme.typography.headlineSmall)
            Text(centerBottom, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LegendRow(color: Color, label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.titleSmall)
        if (onClick != null) Icon(Icons.Filled.ChevronRight, null, Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NamesDialog(title: String, attending: String, guests: List<Guest>, accent: Color, onDismiss: () -> Unit) {
    val groups = remember(guests, attending) {
        guests.mapNotNull { g ->
            val names = g.seats.filter { it.attending == attending }.map { it.name.ifBlank { "Unnamed guest" } }
            if (names.isEmpty()) null else g.groupName.ifBlank { "(unnamed group)" } to names
        }.sortedWith(compareBy({ it.first.startsWith("(unnamed") }, { it.first.lowercase() }))
    }
    val total = groups.sumOf { it.second.size }
    var query by remember { mutableStateOf("") }
    val q = query.trim()
    val filtered = remember(groups, q) {
        if (q.isEmpty()) groups
        else groups.mapNotNull { (group, names) ->
            if (group.contains(q, ignoreCase = true)) group to names
            else names.filter { it.contains(q, ignoreCase = true) }.takeIf { it.isNotEmpty() }?.let { group to it }
        }
    }
    val headerIcon = if (attending == ATTENDING) Icons.Filled.CheckCircle else Icons.Filled.Cancel

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.padding(horizontal = 20.dp).fillMaxWidth().widthIn(max = 480.dp).heightIn(max = 620.dp),
            shape = RoundedCornerShape(28.dp), color = SurfaceWhite, tonalElevation = 6.dp
        ) {
            Column {
                // Header
                Box(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(accent, accent.copy(alpha = 0.72f))))) {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 18.dp, bottom = 18.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f)),
                            contentAlignment = Alignment.Center) {
                            Icon(headerIcon, null, tint = Color.White, modifier = Modifier.size(26.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("$total ${if (total == 1) "person" else "people"} · ${groups.size} ${if (groups.size == 1) "group" else "groups"}",
                                color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Close", tint = Color.White) }
                    }
                }

                if (groups.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("No names recorded yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp),
                        singleLine = true, placeholder = { Text("Search name or group") },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Clear search") }
                        },
                        shape = RoundedCornerShape(16.dp)
                    )
                    if (filtered.isEmpty()) {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text("No matches.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else LazyColumn(
                        Modifier.weight(1f, fill = false),
                        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        filtered.forEach { (group, names) ->
                            item {
                                Column(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Cream).padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(group, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Box(Modifier.clip(RoundedCornerShape(50)).background(accent.copy(alpha = 0.16f))
                                            .padding(horizontal = 10.dp, vertical = 3.dp)) {
                                            Text("${names.size}", color = accent, style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    names.forEach { n ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(Modifier.size(32.dp).clip(CircleShape).background(accent.copy(alpha = 0.18f)),
                                                contentAlignment = Alignment.Center) {
                                                Text(n.trim().firstOrNull()?.uppercase() ?: "?", color = accent,
                                                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                            }
                                            Spacer(Modifier.width(12.dp))
                                            Text(n, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StackedBar(parts: List<Pair<Float, Color>>) {
    val total = parts.sumOf { it.first.toDouble() }.toFloat()
    Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(TrackGray)) {
        if (total > 0f) parts.forEach { (v, c) ->
            if (v > 0f) Box(Modifier.weight(v).fillMaxHeight().background(c))
        }
    }
}

@Composable
private fun ProgressRow(label: String, value: Float, total: Float, color: Color) {
    val pct = if (total > 0f) (value / total).coerceIn(0f, 1f) else 0f
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text("${value.toInt()} of ${total.toInt()} · ${(pct * 100).toInt()}%", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(TrackGray)) {
            if (pct > 0f) Box(Modifier.fillMaxWidth(pct).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(color))
        }
    }
}