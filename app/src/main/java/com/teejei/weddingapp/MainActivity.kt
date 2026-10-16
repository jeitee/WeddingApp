package com.teejei.weddingapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import com.teejei.weddingapp.ui.*
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Read the saved theme choice before the first frame so the app does not flash the wrong theme.
        val initialMode = runBlocking { ThemePrefs.get(applicationContext) }
        setContent {
            val mode by remember { ThemePrefs.flow(applicationContext) }.collectAsStateWithLifecycle(initialValue = initialMode)
            val dark = when (mode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            // Keep the status bar / navigation bar icons readable on the chosen theme (not just the phone's own setting).
            DisposableEffect(dark) {
                val clear = android.graphics.Color.TRANSPARENT
                val style = if (dark) SystemBarStyle.dark(clear) else SystemBarStyle.light(clear, clear)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }
            WeddingTheme(dark) { AppRoot() }
        }
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

@Composable
fun AppRoot(vm: AppViewModel = viewModel()) {
    val nav = rememberNavController()
    val tabs = listOf(
        Tab("dashboard", "Dashboard", Icons.Filled.Dashboard),
        Tab("guests", "Guests", Icons.Filled.People),
        Tab("memories", "Memories", Icons.Filled.PhotoLibrary)
    )
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: "dashboard"
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val guests by vm.guests.collectAsStateWithLifecycle()
    val media by vm.media.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snack.showSnackbar(it) } }

    val (title, subtitle, icon) = when (route) {
        "guests" -> Triple("Guests", "${guests.size} guest groups · invitations & RSVPs", Icons.Filled.People)
        "memories" -> Triple("Memories", "${media.size} photos & videos from your guests", Icons.Filled.PhotoLibrary)
        "settings" -> Triple("Settings", "Connections, import & preferences", Icons.Filled.Settings)
        else -> Triple("Dashboard", "Your wedding at a glance", Icons.Filled.Dashboard)
    }

    Scaffold(
        containerColor = Cream,
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            AppHeader(
                title = title, subtitle = subtitle, icon = icon,
                showBack = route == "settings", refreshing = refreshing,
                onBack = { nav.popBackStack() },
                onRefresh = { vm.refresh() },
                onSettings = { nav.navigate("settings") }
            )
        },
        bottomBar = {
            if (route != "settings") NavigationBar(containerColor = SurfaceWhite) {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = route == t.route,
                        onClick = {
                            nav.navigate(t.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true; restoreState = true
                            }
                        },
                        icon = { Icon(t.icon, t.label) }, label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = Sage.copy(alpha = 0.45f), selectedIconColor = Ink, selectedTextColor = Ink)
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = 1100.dp).fillMaxSize()) {
                NavHost(nav, startDestination = "dashboard") {
                    composable("dashboard") { DashboardScreen(vm) }
                    composable("guests") { GuestsScreen(vm) { nav.navigate("settings") } }
                    composable("memories") { MemoriesScreen(vm) }
                    composable("settings") { SettingsScreen(vm) }
                }
            }
        }
    }
}

@Composable
private fun AppHeader(
    title: String,
    subtitle: String,
    icon: ImageVector,
    showBack: Boolean,
    refreshing: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit
) {
    val actionColors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = SurfaceWhite, contentColor = Ink)
    Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
            .background(Brush.verticalGradient(listOf(Sage.copy(alpha = 0.65f), Cream)))
    ) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showBack) {
                FilledTonalIconButton(onClick = onBack, colors = actionColors) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            } else {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Artichoke), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Ink.copy(alpha = 0.65f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalIconButton(onClick = onRefresh, enabled = !refreshing, colors = actionColors) {
                if (refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Artichoke)
                else Icon(Icons.Filled.Refresh, "Refresh")
            }
            if (!showBack) {
                Spacer(Modifier.width(6.dp))
                FilledTonalIconButton(onClick = onSettings, colors = actionColors) { Icon(Icons.Filled.Settings, "Settings") }
            }
        }
    }
}