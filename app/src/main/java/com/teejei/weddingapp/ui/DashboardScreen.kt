package com.teejei.weddingapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teejei.weddingapp.model.formatMillis

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

        item {
            SoftCard {
                Text("Guests", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth()) {
                    Stat("${gs.groups}", "Guest groups", Modifier.weight(1f))
                    Stat("${gs.seats}", "Seats invited", Modifier.weight(1f), sub = "sum of max_pax")
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth()) {
                    Stat("${gs.confirmedGroups}", "Confirmed", Modifier.weight(1f), "${gs.confirmedSeats} seats")
                    Stat("${gs.declinedGroups}", "Declined", Modifier.weight(1f), "${gs.declinedSeats} seats", Rose)
                    Stat("${gs.awaitingGroups}", "Awaiting", Modifier.weight(1f), "${gs.awaitingSeats} seats", Gold)
                }
                Text("Counts are guest groups; seat counts come from individual seat RSVPs.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            SoftCard {
                Text("Invitations", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth()) {
                    Stat("${inv.sent}", "Sent", Modifier.weight(1f))
                    Stat("${inv.notSent}", "Not yet sent", Modifier.weight(1f), accent = Gold)
                }
                Row(Modifier.fillMaxWidth()) {
                    Stat("${inv.opened}", "Opened", Modifier.weight(1f))
                    Stat("${inv.notOpened}", "Not opened", Modifier.weight(1f), accent = Gold)
                }
                HorizontalDivider()
                Stat("${inv.visits}", "Total visits", sub = "A repeat view within 30 minutes counts once")
            }
        }
        item {
            SoftCard {
                Text("Memories", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth()) {
                    Stat("${mem.photos}", "Photos", Modifier.weight(1f))
                    Stat("${mem.videos}", "Videos", Modifier.weight(1f))
                    Stat("${mem.uploaders}", "Uploaders", Modifier.weight(1f))
                }
            }
        }
    }
}
