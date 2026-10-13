package com.teejei.weddingapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
    var menu by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<MediaRow?>(null) }

    val uploaders = remember(media) { media.mapNotNull { it.uploader?.takeIf { s -> s.isNotBlank() } }.distinct().sorted() }
    val shown = remember(media, type, uploader) {
        media.filter { (type == null || it.type == type) && (uploader == null || it.uploader == uploader) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
        ) {
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
            shown.isEmpty() -> EmptyState(Icons.Filled.SearchOff, "Nothing matches", "Change the type or uploader filter.")
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(120.dp), contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                error?.let { e -> item(span = { GridItemSpan(maxLineSpan) }) { ErrorBanner(e) { vm.refresh() } } }
                items(shown, key = { it.id }) { m ->
                    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(Color(0xFFE6EDE2)).clickable { viewing = m }) {
                        AsyncImage(thumb(m), m.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        if (m.type == "video") {
                            Icon(Icons.Filled.PlayCircle, "Video", Modifier.align(Alignment.Center).size(40.dp), tint = Color.White)
                        }
                    }
                }
            }
        }
    }
    viewing?.let { MediaViewer(it) { viewing = null } }
}

@Composable
private fun MediaViewer(m: MediaRow, onClose: () -> Unit) {
    val ctx = LocalContext.current
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF111611)) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close", tint = Color.White) }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { shareText(ctx, m.url) }) { Icon(Icons.Filled.Share, "Share link", tint = Color.White) }
                    IconButton(onClick = { openUrl(ctx, m.url) {} }) { Icon(Icons.Filled.OpenInBrowser, "Open in browser", tint = Color.White) }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (m.type == "video") VideoPlayer(m.url)
                    else AsyncImage(fullImage(m), m.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(m.uploader?.ifBlank { null } ?: "Unknown uploader", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    if (!m.message.isNullOrBlank()) Text(m.message, color = Color(0xFFDDE5D8))
                    Text(listOfNotNull(m.name.ifBlank { null }, if (m.sizeMb > 0) "${m.sizeMb} MB" else null, formatTime(m.createdAt))
                        .joinToString(" · "), color = Color(0xFFB8C2B3), style = MaterialTheme.typography.bodySmall)
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
