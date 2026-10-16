package com.teejei.weddingapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.teejei.weddingapp.data.MediaRow
import com.teejei.weddingapp.model.formatTime

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 5f

private fun isCloudinary(u: String) = u.contains("res.cloudinary.com") && u.contains("/upload/")
private fun thumb(m: MediaRow): String = when {
    !isCloudinary(m.url) -> m.url
    m.type == "video" -> m.url.substringBeforeLast('.') + ".jpg"
    else -> m.url.replace("/upload/", "/upload/c_fill,w_400,h_400,q_auto/")
}
private fun fullImage(m: MediaRow) = if (isCloudinary(m.url)) m.url.replace("/upload/", "/upload/q_auto,w_1600/") else m.url

@Composable
fun MemoriesScreen(vm: AppViewModel) {
    val media by vm.media.collectAsStateWithLifecycle()
    val stats by vm.memoryStats.collectAsStateWithLifecycle()
    val error by vm.memoryError.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    var type by remember { mutableStateOf<String?>(null) }
    var uploader by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<MediaRow?>(null) }
    var listMode by remember { mutableStateOf(false) }
    var openGroup by remember { mutableStateOf<String?>(null) }

    val uploaders = remember(media) { media.mapNotNull { it.uploader?.takeIf { s -> s.isNotBlank() } }.distinct().sorted() }
    val shown = remember(media, type, uploader, query) {
        val q = query.trim()
        media.filter { m ->
            (type == null || m.type == type) &&
                    (uploader == null || m.uploader == uploader) &&
                    (q.isEmpty() ||
                            (m.uploader?.contains(q, ignoreCase = true) == true) ||
                            (m.message?.contains(q, ignoreCase = true) == true) ||
                            m.name.contains(q, ignoreCase = true))
        }
    }

    // One entry per upload (same uploader + message), newest first
    val groups = remember(shown) {
        // Files without an upload id each get their own entry instead of merging into one
        shown.groupBy { it.uploadId.ifBlank { it.id } }.map { (id, items) ->
            UploadGroup(id, items.first().uploader?.ifBlank { null }, items.first().message?.ifBlank { null }, items)
        }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
            singleLine = true, placeholder = { Text("Search uploader or message") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Clear search") }
            },
            shape = RoundedCornerShape(16.dp)
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(!listMode, { listMode = false }, { Text("Tiles") },
                leadingIcon = { Icon(Icons.Filled.GridView, null, Modifier.size(18.dp)) })
            FilterChip(listMode, { listMode = true }, { Text("List") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.ViewList, null, Modifier.size(18.dp)) })
            FilterChip(type == null, { type = null }, { Text("All (${media.size})") })
            FilterChip(type == "photo", { type = if (type == "photo") null else "photo" }, { Text("Photos (${stats.photos})") })
            FilterChip(type == "video", { type = if (type == "video") null else "video" }, { Text("Videos (${stats.videos})") })
            Box {
                AssistChip(onClick = { menu = true }, label = { Text(uploader ?: "All uploaders") },
                    leadingIcon = { Icon(Icons.Filled.Person, null, Modifier.size(18.dp)) })
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("All uploaders") }, onClick = { uploader = null; menu = false })
                    uploaders.forEach { u -> DropdownMenuItem(text = { Text(u) }, onClick = { uploader = u; menu = false }) }
                }
            }
        }
        when {
            refreshing && media.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Artichoke) }
            media.isEmpty() -> Column(Modifier.fillMaxSize()) {
                error?.let { Box(Modifier.padding(16.dp)) { ErrorBanner(it) { vm.refresh() } } }
                EmptyState(Icons.Filled.PhotoLibrary, "No memories yet",
                    "Photos and videos uploaded by guests will appear here. If you know uploads exist, the Supabase read policy may be missing (see README).",
                    "Refresh") { vm.refresh() }
            }
            shown.isEmpty() -> EmptyState(Icons.Filled.SearchOff, "Nothing matches", "Try a different search, or change the type or uploader filter.",
                "Clear filters") { query = ""; type = null; uploader = null }
            listMode -> UploadList(groups, error, onRetry = { vm.refresh() }) { openGroup = it }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp), contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                error?.let { e -> item(span = { GridItemSpan(maxLineSpan) }) { ErrorBanner(e) { vm.refresh() } } }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("${shown.size} of ${media.size} memories", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(shown, key = { it.id }) { m -> MemoryTile(m) { viewing = m } }
            }
        }
    }
    // Tiled view of one upload; the viewer opens on top of it, so closing the viewer returns here
    openGroup?.let { id ->
        groups.firstOrNull { it.id == id }?.let { g ->
            UploadDialog(g, onPick = { viewing = it }, onClose = { openGroup = null })
        }
    }
    viewing?.let { MediaViewer(it) { viewing = null } }
}

private data class UploadGroup(val id: String, val uploader: String?, val message: String?, val items: List<MediaRow>)

private fun UploadGroup.summary(): String {
    val photos = items.count { it.type != "video" }
    val videos = items.count { it.type == "video" }
    return listOfNotNull(
        if (photos > 0) "$photos photo${if (photos == 1) "" else "s"}" else null,
        if (videos > 0) "$videos video${if (videos == 1) "" else "s"}" else null
    ).joinToString(" · ")
}

@Composable
private fun UploadList(groups: List<UploadGroup>, error: String?, onRetry: () -> Unit, onOpen: (String) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        error?.let { e -> item { ErrorBanner(e) { onRetry() } } }
        item {
            Text("${groups.size} upload${if (groups.size == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        groups.forEach { g -> item(key = g.id) { UploadCard(g) { onOpen(g.id) } } }
    }
}

@Composable
private fun UploadCard(g: UploadGroup, onClick: () -> Unit) {
    val first = g.items.first()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(SurfaceWhite)
            .clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(76.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.secondaryContainer)) {
            AsyncImage(thumb(first), first.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (first.type == "video") {
                Icon(Icons.Filled.PlayCircle, null, Modifier.align(Alignment.Center).size(28.dp), tint = Color.White)
            }
            if (g.items.size > 1) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(RoundedCornerShape(50))
                        .background(Color(0xB3000000)).padding(horizontal = 7.dp, vertical = 2.dp)
                ) { Text("${g.items.size}", color = Color.White, style = MaterialTheme.typography.labelSmall) }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(g.uploader ?: "Unknown uploader", style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (g.message != null) Text(g.message, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(g.summary(), formatTime(first.createdAt)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = Artichoke)
        }
        Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun UploadDialog(g: UploadGroup, onPick: (MediaRow) -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.padding(horizontal = 12.dp).fillMaxWidth().widthIn(max = 720.dp).heightIn(max = 720.dp),
            shape = RoundedCornerShape(28.dp), color = Cream
        ) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(g.uploader ?: "Unknown uploader", style = MaterialTheme.typography.titleLarge)
                        if (g.message != null) Text(g.message, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        Text(g.summary(), style = MaterialTheme.typography.labelLarge, color = Artichoke)
                    }
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close") }
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(110.dp), modifier = Modifier.weight(1f, fill = false),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(g.items, key = { it.id }) { m -> MemoryTile(m, showCaption = false) { onPick(m) } }
                }
            }
        }
    }
}

@Composable
private fun MemoryTile(m: MediaRow, showCaption: Boolean = true, onClick: () -> Unit) {
    val who = m.uploader?.ifBlank { null }
    val msg = m.message?.ifBlank { null }
    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.secondaryContainer).clickable(onClick = onClick)) {
        AsyncImage(thumb(m), m.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (m.type == "video") {
            Icon(Icons.Filled.PlayCircle, "Video", Modifier.align(Alignment.Center).size(40.dp), tint = Color.White)
        }
        if (showCaption && (who != null || msg != null)) {
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                    .padding(start = 8.dp, end = 8.dp, top = 20.dp, bottom = 8.dp)
            ) {
                if (who != null) Text(who, color = Color.White, style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (msg != null) Text(msg, color = Color.White, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun MediaViewer(m: MediaRow, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var scale by remember(m.id) { mutableFloatStateOf(1f) }
    var offset by remember(m.id) { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val isVideo = m.type == "video"

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = box.width * (s - 1f) / 2f
        val maxY = box.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }
    fun zoomTo(s: Float) {
        val ns = s.coerceIn(MIN_ZOOM, MAX_ZOOM)
        scale = ns
        offset = if (ns <= MIN_ZOOM) Offset.Zero else clamp(offset, ns)
    }

    val who = m.uploader?.ifBlank { null } ?: "Unknown uploader"
    val message = m.message?.ifBlank { null }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF111611)) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close", tint = Color.White) }
                    Spacer(Modifier.weight(1f))
                    if (!isVideo) {
                        IconButton(enabled = scale > MIN_ZOOM, onClick = { zoomTo(scale / 1.5f) }) {
                            Icon(Icons.Filled.ZoomOut, "Zoom out", tint = if (scale > MIN_ZOOM) Color.White else Color(0xFF6B756A))
                        }
                        IconButton(enabled = scale < MAX_ZOOM, onClick = { zoomTo(scale * 1.5f) }) {
                            Icon(Icons.Filled.ZoomIn, "Zoom in", tint = if (scale < MAX_ZOOM) Color.White else Color(0xFF6B756A))
                        }
                    }
                    IconButton(onClick = { shareText(ctx, m.url) }) { Icon(Icons.Filled.Share, "Share link", tint = Color.White) }
                    IconButton(onClick = { openUrl(ctx, m.url) {} }) { Icon(Icons.Filled.OpenInBrowser, "Open in browser", tint = Color.White) }
                }
                Box(
                    Modifier.weight(1f).fillMaxWidth().onSizeChanged { box = it },
                    contentAlignment = Alignment.Center
                ) {
                    if (isVideo) VideoPlayer(m.url)
                    else AsyncImage(
                        fullImage(m), m.name,
                        Modifier.fillMaxSize()
                            .pointerInput(m.id) {
                                detectTapGestures(onDoubleTap = { if (scale > MIN_ZOOM) zoomTo(MIN_ZOOM) else zoomTo(2.5f) })
                            }
                            .pointerInput(m.id) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    val ns = (scale * zoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                                    scale = ns
                                    offset = if (ns <= MIN_ZOOM) Offset.Zero else clamp(offset + pan, ns)
                                }
                            }
                            .graphicsLayer {
                                scaleX = scale; scaleY = scale
                                translationX = offset.x; translationY = offset.y
                            },
                        contentScale = ContentScale.Fit
                    )
                }
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(who, color = Color.White, style = MaterialTheme.typography.titleLarge)
                    if (message != null) Text(message, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                    Text(listOfNotNull(m.name.ifBlank { null }, if (m.sizeMb > 0) "${m.sizeMb} MB" else null, formatTime(m.createdAt))
                        .joinToString(" · "), color = Color(0xFFB8C2B3), style = MaterialTheme.typography.bodySmall)
                    if (!isVideo) Text("Pinch or double-tap to zoom", color = Color(0xFF8A958A), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun VideoPlayer(url: String) {
    val ctx = LocalContext.current
    var failed by remember(url) { mutableStateOf(false) }
    val player = remember(url) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(url)); prepare(); playWhenReady = true
            addListener(object : Player.Listener { override fun onPlayerError(error: PlaybackException) { failed = true } })
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    if (failed) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("This video couldn't be played in the app.", color = Color.White)
            Button(onClick = { openUrl(ctx, url) {} }) { Text("Open in browser") }
        }
    } else {
        AndroidView(
            factory = { PlayerView(it).apply { this.player = player; useController = true } },
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
        )
    }
}