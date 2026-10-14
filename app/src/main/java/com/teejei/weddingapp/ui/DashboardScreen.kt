package com.teejei.weddingapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teejei.weddingapp.model.formatMillis

private val TrackGray = Color(0xFFE3E7DF)

@Composable
fun DashboardScreen(vm: AppViewModel) {
    val gs by vm.guestStats.collectAsStateWithLifecycle()
    val inv by vm.inviteStats.collectAsStateWithLifecycle()
    val mem by vm.memoryStats.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val gErr by vm.guestError.collectAsStateWithLifecycle()
    val mErr by vm.memoryError.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val full: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(340.dp), modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(span = full) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Teejei & Judith", style = MaterialTheme.typography.headlineMedium)
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

        // RSVP donut chart
        item {
            SoftCard {
                Text("RSVP overview", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    DonutChart(
                        slices = listOf(
                            gs.confirmedGroups.toFloat() to Artichoke,
                            gs.declinedGroups.toFloat() to Rose,
                            gs.awaitingGroups.toFloat() to Gold
                        ),
                        centerTop = "${gs.groups}", centerBottom = "groups"
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        LegendRow(Artichoke, "Confirmed", "${gs.confirmedGroups}")
                        LegendRow(Rose, "Declined", "${gs.declinedGroups}")
                        LegendRow(Gold, "Awaiting", "${gs.awaitingGroups}")
                    }
                }
                HorizontalDivider()
                Text("Seats (${gs.seats} invited)", style = MaterialTheme.typography.labelLarge)
                StackedBar(
                    listOf(
                        gs.confirmedSeats.toFloat() to Artichoke,
                        gs.declinedSeats.toFloat() to Rose,
                        gs.awaitingSeats.toFloat() to Gold
                    )
                )
                Text("${gs.confirmedSeats} confirmed · ${gs.declinedSeats} declined · ${gs.awaitingSeats} awaiting",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
private fun DonutChart(slices: List<Pair<Float, Color>>, centerTop: String, centerBottom: String) {
    val total = slices.sumOf { it.first.toDouble() }.toFloat()
    Box(Modifier.size(140.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 22.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            drawArc(TrackGray, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            if (total > 0f) {
                var start = -90f
                slices.forEach { (v, c) ->
                    if (v > 0f) {
                        val sweep = v / total * 360f
                        drawArc(c, start, (sweep - 2f).coerceAtLeast(0.5f), false, topLeft, arcSize,
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
private fun LegendRow(color: Color, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.titleSmall)
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