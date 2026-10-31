package com.teejei.weddingapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TableRestaurant
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.Normalizer

private const val SEATS_PAGE_URL = "https://jeitee.github.io/TheWedding/Seats.html"

private val tableOrder = compareBy<String>({ it.toIntOrNull() ?: Int.MAX_VALUE }, { it.lowercase() })

/** Case and accent insensitive ("Jose" finds "José"). */
private fun norm(s: String) =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().replace(Regex("\\s+"), " ").trim()

private fun tableHeading(t: String) = if (t.isNotEmpty() && t.all { it.isDigit() }) "Table $t" else t
private fun guestsText(n: Int) = if (n == 1) "1 guest" else "$n guests"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TablesScreen(vm: AppViewModel, onOpenSettings: () -> Unit) {
    val cfgState by vm.seatingConfig.collectAsStateWithLifecycle()
    val loaded by vm.seatRows.collectAsStateWithLifecycle()
    val error by vm.seatingError.collectAsStateWithLifecycle()
    val busy by vm.seatingBusy.collectAsStateWithLifecycle()
    val cfg = cfgState

    LaunchedEffect(cfg?.url) { if (cfg != null) vm.loadSeating() }

    if (cfg == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Artichoke) }
        return
    }
    if (cfg.url.isBlank()) {
        EmptyState(Icons.Filled.TableRestaurant, "Seating script not connected",
            "Add the seating web app URL and admin key under Settings > Connections.", "Open Settings", onOpenSettings)
        return
    }

    var query by remember { mutableStateOf("") }
    var hideDeclined by remember { mutableStateOf(true) }
    var onlyUnassigned by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }
    var selectMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<Int>()) }
    var assignFor by remember { mutableStateOf<List<Int>?>(null) }
    var directoryOpen by remember { mutableStateOf(false) }
    var openTable by remember { mutableStateOf("") }
    var scrollTick by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()
    val ctx = LocalContext.current

    val allRows = loaded.orEmpty()
    val pool = remember(allRows, hideDeclined) { allRows.filter { !hideDeclined || it.attending != "not_attending" } }
    val tableNames = remember(allRows) { allRows.map { it.table }.filter { it.isNotBlank() }.distinct().sortedWith(tableOrder) }
    val countByTable = remember(allRows) { allRows.filter { it.table.isNotBlank() }.groupingBy { it.table }.eachCount() }
    val q = norm(query)
    val words = if (q.isEmpty()) emptyList() else q.split(" ")
    fun matches(r: SeatEntry) =
        words.isNotEmpty() && (words.all { norm(r.name).contains(it) } || norm(r.group).contains(q) || norm(r.table) == q)

    val showResults = q.isNotEmpty() || onlyUnassigned || showAll
    val rows = remember(pool, query, onlyUnassigned, showAll) {
        pool.filter { (words.isEmpty() || matches(it)) && (!onlyUnassigned || it.table.isBlank()) }
            .sortedBy { norm(it.name) }
            .let { if (q.isNotEmpty() && !onlyUnassigned && !showAll) it.take(30) else it }
    }
    val myTables = if (q.isNotEmpty()) rows.map { it.table }.filter { it.isNotBlank() }.toSet() else emptySet()
    val assignedCount = pool.count { it.table.isNotBlank() }

    // Index of the "Browse" toggle in the list, used to scroll to the table panel.
    val toggleIndex = 3 + (if (showResults) maxOf(rows.size, 1) else 0)
    LaunchedEffect(scrollTick) { if (scrollTick > 0) listState.animateScrollToItem(toggleIndex + 2) }

    fun openTableNow(t: String) { openTable = t; directoryOpen = true; scrollTick++ }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f), state = listState, contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "search") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Find your table", style = MaterialTheme.typography.titleLarge, color = Ink,
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    OutlinedButton(
                        onClick = { openUrl(ctx, SEATS_PAGE_URL) { vm.toast("No browser found") } },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.OpenInBrowser, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Open Find your table page")
                    }
                    Text("Type a name to see where they are seated.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search guest, group or table") },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Clear") } },
                        shape = RoundedCornerShape(50)
                    )
                }
            }
            item(key = "chips") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(showAll, { showAll = !showAll }, { Text("Show all") })
                    FilterChip(onlyUnassigned, { onlyUnassigned = !onlyUnassigned }, { Text("Unassigned") })
                    FilterChip(hideDeclined, { hideDeclined = !hideDeclined }, { Text("Hide declined") })
                    FilterChip(selectMode, { selectMode = !selectMode; if (!selectMode) selected = emptySet() }, { Text("Select") })
                }
            }
            item(key = "status") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Artichoke)
                    error?.let { ErrorBanner(it) { vm.loadSeating() } }
                    Text(
                        if (loaded == null) "Loading the guest list..."
                        else if (!showResults) "Search among ${pool.size} guests · $assignedCount assigned · ${tableNames.size} table(s)"
                        else "${rows.size} shown · $assignedCount of ${pool.size} assigned · ${tableNames.size} table(s)",
                        style = MaterialTheme.typography.labelLarge, color = Artichoke
                    )
                    if (selectMode) Row {
                        TextButton(onClick = { selected = rows.map { it.row }.toSet() }) { Text("Select all shown") }
                        TextButton(enabled = selected.isNotEmpty(), onClick = { selected = emptySet() }) { Text("Clear") }
                    }
                }
            }

            if (showResults) {
                if (rows.isEmpty() && loaded != null) item(key = "none") {
                    Text(
                        if (q.isNotEmpty()) "No guest found for “${query.trim()}”. Try the first or last name." else "Nobody to show.",
                        Modifier.fillMaxWidth().padding(vertical = 16.dp), textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(rows, key = { "r${it.row}" }) { r ->
                    PlaceCard(
                        r, selectMode, r.row in selected,
                        onCheck = { on -> selected = if (on) selected + r.row else selected - r.row },
                        onClick = { if (selectMode) selected = if (r.row in selected) selected - r.row else selected + r.row else assignFor = listOf(r.row) },
                        onSeeTable = { openTableNow(r.table) }
                    )
                }
            }

            item(key = "toggle") {
                OutlinedButton(onClick = { directoryOpen = !directoryOpen }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (directoryOpen) "Hide all tables" else "Browse all tables")
                    Spacer(Modifier.width(6.dp))
                    Icon(if (directoryOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
                }
            }
            if (directoryOpen) {
                item(key = "directory") {
                    if (tableNames.isEmpty()) {
                        Text("Tables have not been assigned yet.", Modifier.fillMaxWidth().padding(16.dp), textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else FlowRow(
                        Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        tableNames.forEach { t ->
                            TableToken(t, countByTable[t] ?: 0, selected = t == openTable, mine = t in myTables) {
                                if (t == openTable) openTable = "" else openTableNow(t)
                            }
                        }
                    }
                }
                item(key = "panel") {
                    val members = allRows.filter { it.table == openTable }.sortedBy { norm(it.name) }
                    if (openTable.isBlank() || members.isEmpty()) {
                        Text(if (tableNames.isEmpty()) "" else "Choose a table to see who is seated there.",
                            Modifier.fillMaxWidth().padding(8.dp), textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else SoftCard {
                        Text(tableHeading(openTable), style = MaterialTheme.typography.titleLarge)
                        Text(guestsText(members.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        members.forEach { m ->
                            val hit = words.isNotEmpty() && words.all { norm(m.name).contains(it) }
                            Text(
                                m.name,
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                    .background(if (hit) Gold.copy(alpha = 0.25f) else Color.Transparent)
                                    .clickable { assignFor = listOf(m.row) }.padding(horizontal = 8.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (hit) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }
        if (selectMode && selected.isNotEmpty()) {
            Button(onClick = { assignFor = selected.toList() }, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Assign ${selected.size} selected")
            }
        }
    }

    assignFor?.let { keys ->
        val targets = allRows.filter { it.row in keys }
        val current = targets.map { it.table }.distinct().singleOrNull().orEmpty()
        var text by remember(keys) { mutableStateOf(current) }
        AlertDialog(
            onDismissRequest = { assignFor = null },
            title = { Text(if (targets.size == 1) targets[0].name else "Assign ${targets.size} guests") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Table number or name") })
                    if (tableNames.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            tableNames.forEach { t -> AssistChip(onClick = { text = t }, label = { Text("$t (${countByTable[t] ?: 0})") }) }
                        }
                    }
                    if (targets.any { it.table.isNotBlank() }) {
                        TextButton(enabled = !busy, onClick = { vm.assignTables(targets, ""); selected = selected - keys.toSet(); assignFor = null }) {
                            Text("Remove table", color = Rose)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank() && !busy && targets.isNotEmpty(), onClick = {
                    vm.assignTables(targets, text.trim()); selected = selected - keys.toSet(); assignFor = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { assignFor = null }) { Text("Cancel") } }
        )
    }
}

/** A guest's place card: name on the left, the table "seal" on the right. */
@Composable
private fun PlaceCard(
    r: SeatEntry, selectMode: Boolean, checked: Boolean,
    onCheck: (Boolean) -> Unit, onClick: () -> Unit, onSeeTable: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(SurfaceWhite)
            .clickable(onClick = onClick).padding(start = if (selectMode) 6.dp else 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectMode) Checkbox(checked = checked, onCheckedChange = onCheck)
        Column(Modifier.weight(1f)) {
            Text(r.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (r.group.isNotBlank()) Text(r.group, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (r.attending == "not_attending") Text("Declined", style = MaterialTheme.typography.bodySmall, color = Rose)
            if (r.table.isNotBlank()) TextButton(onClick = onSeeTable, contentPadding = PaddingValues(0.dp)) {
                Text("See who's at the table")
            }
        }
        Seal(r.table)
    }
}

@Composable
private fun Seal(table: String) {
    val numeric = table.isNotEmpty() && table.all { it.isDigit() }
    Box(
        Modifier.size(72.dp).clip(CircleShape).background(Gold.copy(alpha = if (table.isBlank()) 0.06f else 0.16f))
            .border(2.dp, if (table.isBlank()) Sage else Gold, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        when {
            table.isBlank() -> Text("To be\nassigned", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            numeric -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("TABLE", style = MaterialTheme.typography.labelSmall, color = Artichoke)
                Text(table, style = MaterialTheme.typography.titleLarge, color = Ink, maxLines = 1)
            }
            else -> Text(table, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, color = Ink,
                maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(6.dp))
        }
    }
}

/** A round table in the "Browse all tables" grid. Gold ring = matches the current search. */
@Composable
private fun TableToken(label: String, count: Int, selected: Boolean, mine: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(84.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(58.dp).clip(CircleShape)
                .background(if (selected) Artichoke else Sage.copy(alpha = 0.35f))
                .border(if (mine) 3.dp else 1.dp, if (mine) Gold else Artichoke.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label, style = if (label.length > 3) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleMedium,
                color = if (selected) Color.White else Ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
        Text(guestsText(count), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}