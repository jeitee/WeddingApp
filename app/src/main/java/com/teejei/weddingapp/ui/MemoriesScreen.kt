package com.teejei.weddingapp.ui

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.TransformOrigin
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlin.random.Random
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
// Original quality: no Cloudinary transformation, the file exactly as uploaded
private fun fullImage(m: MediaRow) = m.url

private const val EXPORT_ROOT = "WeddingMemories"

private fun safeName(s: String): String =
    s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.').take(60).ifBlank { "Unknown uploader" }

private fun exportFileName(m: MediaRow): String {
    val raw = m.name.ifBlank { "memory" }
    val urlExt = m.url.substringBefore('?').substringAfterLast('.', "").takeIf { it.length in 2..4 }
    val ext = raw.substringAfterLast('.', "").takeIf { it.length in 2..4 } ?: urlExt ?: if (m.type == "video") "mp4" else "jpg"
    val base = safeName(raw.substringBeforeLast('.', raw))
    return "${base}_${m.id.toString().takeLast(6)}.$ext"
}

/**
 * Saves each item into Downloads/WeddingMemories/<uploader>/ using the system DownloadManager
 * (original file, shows a notification, no storage permission needed on Android 10+).
 * Returns how many downloads were queued.
 */
private fun downloadMedia(ctx: Context, items: List<MediaRow>): Int {
    val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    var queued = 0
    items.forEach { m ->
        val folder = safeName(m.uploader?.ifBlank { null } ?: "Unknown uploader")
        val file = exportFileName(m)
        runCatching {
            val req = DownloadManager.Request(Uri.parse(m.url))
                .setTitle(file)
                .setDescription(folder)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "$EXPORT_ROOT/$folder/$file")
            dm.enqueue(req)
            queued++
        }
    }
    val msg = when {
        queued == 0 -> "Couldn't start the download"
        queued == 1 -> "Downloading to Downloads/$EXPORT_ROOT/${safeName(items.first().uploader?.ifBlank { null } ?: "Unknown uploader")}"
        else -> "Downloading $queued files to Downloads/$EXPORT_ROOT/<uploader>"
    }
    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
    return queued
}

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
    var allPhotos by remember { mutableStateOf(false) } // tile view with one tile per file
    var openGroup by remember { mutableStateOf<String?>(null) }
    var confirmExport by remember { mutableStateOf(false) }
    var showReel by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

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

    // One entry per uploader (all their uploads together), used by the Tiles view
    val uploaderGroups = remember(shown) {
        shown.groupBy { it.uploader?.ifBlank { null } ?: "Unknown uploader" }.map { (name, items) ->
            UploadGroup("uploader:$name", name, null, items)
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
            FilterChip(!listMode && !allPhotos, { listMode = false; allPhotos = false }, { Text("Tiles") },
                leadingIcon = { Icon(Icons.Filled.GridView, null, Modifier.size(18.dp)) })
            FilterChip(!listMode && allPhotos, { listMode = false; allPhotos = true }, { Text("All files") },
                leadingIcon = { Icon(Icons.Filled.PhotoLibrary, null, Modifier.size(18.dp)) })
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
            AssistChip(onClick = { showReel = true }, enabled = shown.any { it.type != "video" },
                label = { Text("Memory reel") },
                leadingIcon = { Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp)) })
            AssistChip(onClick = { confirmExport = true }, enabled = shown.isNotEmpty(),
                label = { Text("Export (${shown.size})") },
                leadingIcon = { Icon(Icons.Filled.Download, null, Modifier.size(18.dp)) })
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
                    Text(
                        if (allPhotos) "${shown.size} of ${media.size} memories"
                        else "${uploaderGroups.size} uploader${if (uploaderGroups.size == 1) "" else "s"} · ${shown.size} of ${media.size} memories",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (allPhotos) items(shown, key = { it.id }) { m -> MemoryTile(m) { viewing = m } }
                else items(uploaderGroups, key = { it.id }) { g -> UploaderTile(g) { openGroup = g.id } }
            }
        }
    }
    // Tiled view of one upload; the viewer opens on top of it, so closing the viewer returns here
    openGroup?.let { id ->
        (groups + uploaderGroups).firstOrNull { it.id == id }?.let { g ->
            UploadDialog(g, onPick = { viewing = it }, onClose = { openGroup = null })
        }
    }
    viewing?.let { MediaViewer(it) { viewing = null } }
    if (showReel) MemoryReel(shown) { showReel = false }
    if (confirmExport) {
        val folders = shown.map { safeName(it.uploader?.ifBlank { null } ?: "Unknown uploader") }.distinct().size
        AlertDialog(
            onDismissRequest = { confirmExport = false },
            title = { Text("Export ${shown.size} file${if (shown.size == 1) "" else "s"}?") },
            text = {
                Text("Original-quality files will be saved to Downloads/$EXPORT_ROOT, in $folders folder${if (folders == 1) "" else "s"} named after each uploader. Current filters apply.")
            },
            confirmButton = { TextButton(onClick = { confirmExport = false; downloadMedia(ctx, shown) }) { Text("Download") } },
            dismissButton = { TextButton(onClick = { confirmExport = false }) { Text("Cancel") } }
        )
    }
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
    val ctx = LocalContext.current
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
                    IconButton(onClick = { downloadMedia(ctx, g.items) }) { Icon(Icons.Filled.Download, "Download all from this upload") }
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
private fun UploaderTile(g: UploadGroup, onClick: () -> Unit) {
    val first = g.items.first()
    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.secondaryContainer).clickable(onClick = onClick)) {
        AsyncImage(thumb(first), first.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (first.type == "video") {
            Icon(Icons.Filled.PlayCircle, null, Modifier.align(Alignment.Center).size(40.dp), tint = Color.White)
        }
        if (g.items.size > 1) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(8.dp).clip(RoundedCornerShape(50))
                    .background(Color(0xB3000000)).padding(horizontal = 9.dp, vertical = 3.dp)
            ) { Text("${g.items.size}", color = Color.White, style = MaterialTheme.typography.labelMedium) }
        }
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                .padding(start = 8.dp, end = 8.dp, top = 20.dp, bottom = 8.dp)
        ) {
            Text(g.uploader ?: "Unknown uploader", color = Color.White, style = MaterialTheme.typography.titleSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(g.summary(), color = Color.White, style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                    IconButton(onClick = { downloadMedia(ctx, listOf(m)) }) { Icon(Icons.Filled.Download, "Download original", tint = Color.White) }
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

// ---------- Memory reel (iPhone "Memories"-style slideshow) ----------

private const val REEL_SLIDE_MS = 4000
private const val REEL_SIZE = 12

private fun reelImage(m: MediaRow) =
    if (isCloudinary(m.url)) m.url.replace("/upload/", "/upload/q_auto,w_1280/") else m.url

/** Picks a random set of photos. Sometimes themes the reel around one guest who uploaded 3+ photos. */
private fun buildReel(source: List<MediaRow>): Pair<String, List<MediaRow>> {
    val photos = source.filter { it.type != "video" }
    val byUploader = photos.groupBy { it.uploader?.ifBlank { null } }
        .filter { (who, list) -> who != null && list.size >= 3 }
    return if (byUploader.isNotEmpty() && Random.nextBoolean()) {
        val (who, list) = byUploader.entries.random()
        "Moments from $who" to list.shuffled().take(REEL_SIZE)
    } else {
        "Wedding highlights" to photos.shuffled().take(REEL_SIZE)
    }
}

@Composable
private fun ReelSlide(m: MediaRow, i: Int) {
    val zoom = remember { Animatable(1f) }
    LaunchedEffect(Unit) { zoom.animateTo(1.2f, tween(REEL_SLIDE_MS + 1000, easing = LinearEasing)) }
    AsyncImage(
        reelImage(m), m.name,
        Modifier.fillMaxSize().graphicsLayer {
            scaleX = zoom.value; scaleY = zoom.value
            transformOrigin = TransformOrigin(if (i % 2 == 0) 0.2f else 0.8f, if (i % 3 == 0) 0.3f else 0.7f)
        },
        contentScale = ContentScale.Crop
    )
}

@Composable
private fun MemoryReel(source: List<MediaRow>, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var reel by remember { mutableStateOf(buildReel(source)) }
    var index by remember { mutableIntStateOf(0) }
    var cycle by remember { mutableIntStateOf(0) }
    var progress by remember { mutableFloatStateOf(0f) }
    var paused by remember { mutableStateOf(false) }
    val slides = reel.second

    fun step(d: Int) {
        val n = slides.size
        index = (index + d + n) % n
        cycle++
    }

    // Timer: fills the progress bar, then moves to the next photo (pausing freezes it)
    LaunchedEffect(index, reel, cycle) {
        progress = 0f
        slides.getOrNull((index + 1) % slides.size)?.let {
            ctx.imageLoader.enqueue(ImageRequest.Builder(ctx).data(reelImage(it)).build())
        }
        while (progress < 1f) {
            delay(33)
            if (!paused) progress = (progress + 33f / REEL_SLIDE_MS).coerceAtMost(1f)
        }
        step(1)
    }

    val current = slides[index.coerceIn(0, slides.lastIndex)]
    val who = current.uploader?.ifBlank { null }
    val message = current.message?.ifBlank { null }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Box(
                Modifier.fillMaxSize().pointerInput(reel) {
                    detectTapGestures(onTap = { o ->
                        when {
                            o.x < size.width * 0.3f -> step(-1)
                            o.x > size.width * 0.7f -> step(1)
                            else -> paused = !paused
                        }
                    })
                }
            ) {
                Crossfade(targetState = index, animationSpec = tween(900), label = "reel") { i ->
                    slides.getOrNull(i)?.let { ReelSlide(it, i) }
                }
                // Top bar: progress segments, title, controls
                Column(
                    Modifier.align(Alignment.TopStart).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent)))
                        .systemBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        slides.indices.forEach { i ->
                            val frac = when { i < index -> 1f; i == index -> progress; else -> 0f }
                            Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(50)).background(Color(0x4DFFFFFF))) {
                                Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(Color.White))
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(reel.first, color = Color.White, style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f).padding(start = 4.dp))
                        IconButton(onClick = { reel = buildReel(source); index = 0; cycle++; paused = false }) {
                            Icon(Icons.Filled.Shuffle, "New reel", tint = Color.White)
                        }
                        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close", tint = Color.White) }
                    }
                }
                // Caption
                if (who != null || message != null) {
                    Column(
                        Modifier.align(Alignment.BottomStart).fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                            .systemBarsPadding().padding(start = 20.dp, end = 20.dp, top = 40.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (who != null) Text(who, color = Color.White, style = MaterialTheme.typography.titleLarge)
                        if (message != null) Text(message, color = Color.White, style = MaterialTheme.typography.bodyLarge,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (paused) Icon(Icons.Filled.Pause, "Paused", Modifier.align(Alignment.Center).size(64.dp), tint = Color(0xB3FFFFFF))
            }
        }
    }
}