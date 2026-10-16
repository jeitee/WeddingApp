package com.teejei.weddingapp.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/* ---------- Theme choice (saved on the phone) ---------- */

enum class ThemeMode { SYSTEM, LIGHT, DARK }

private val Context.themeDs by preferencesDataStore("theme_state")

object ThemePrefs {
    private val kMode = stringPreferencesKey("mode")
    fun flow(ctx: Context): Flow<ThemeMode> = ctx.themeDs.data.map { p ->
        runCatching { ThemeMode.valueOf(p[kMode] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM)
    }
    suspend fun get(ctx: Context): ThemeMode = flow(ctx).first()
    suspend fun set(ctx: Context, mode: ThemeMode) {
        ctx.themeDs.edit { it[kMode] = mode.name }
    }
}

/**
 * True while the dark theme is showing. The palette colors below read it, and because it is Compose state, every screen
 * that uses Cream / Ink / Sage / ... recomposes with the new colors when it changes, so no screen needs editing.
 * (It is set once, at the root, by [WeddingTheme].)
 */
object ThemeState {
    var dark by mutableStateOf(false)
}

private fun pick(light: Color, dark: Color): Color = if (ThemeState.dark) dark else light

/* ---------- Palette (each color has a light and a dark value) ---------- */

val Cream: Color get() = pick(Color(0xFFFAF7F2), Color(0xFF141813))          // page background
val Sage: Color get() = pick(Color(0xFFA3B899), Color(0xFF4F6148))
val Artichoke: Color get() = pick(Color(0xFF6E7F65), Color(0xFF7C9672))       // accent
val Gold: Color get() = pick(Color(0xFFD4AF37), Color(0xFFE3BE4F))
val SurfaceWhite: Color get() = pick(Color(0xFFFFFFFF), Color(0xFF1E241C))   // cards, dialogs, bars
val Ink: Color get() = pick(Color(0xFF2E3A2B), Color(0xFFE7ECE3))            // main text
val Rose: Color get() = pick(Color(0xFFB5524A), Color(0xFFD47A71))

private val lightScheme = lightColorScheme(
    primary = Color(0xFF6E7F65), onPrimary = Color.White,
    primaryContainer = Color(0xFFA3B899), onPrimaryContainer = Color(0xFF2E3A2B),
    secondary = Color(0xFF6E7F65), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6EDE2), onSecondaryContainer = Color(0xFF2E3A2B),
    tertiary = Color(0xFFD4AF37), onTertiary = Color(0xFF2E3A2B),
    background = Color(0xFFFAF7F2), onBackground = Color(0xFF2E3A2B),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF2E3A2B),
    surfaceVariant = Color(0xFFF1EEE6), onSurfaceVariant = Color(0xFF5B6657),
    outline = Color(0xFFCFC8B8), error = Color(0xFFB5524A)
)

private val darkScheme = darkColorScheme(
    primary = Color(0xFF7C9672), onPrimary = Color(0xFF0E120D),
    primaryContainer = Color(0xFF3A4A34), onPrimaryContainer = Color(0xFFE7ECE3),
    secondary = Color(0xFF7C9672), onSecondary = Color(0xFF0E120D),
    secondaryContainer = Color(0xFF2B3628), onSecondaryContainer = Color(0xFFE7ECE3),
    tertiary = Color(0xFFE3BE4F), onTertiary = Color(0xFF241C00),
    background = Color(0xFF141813), onBackground = Color(0xFFE7ECE3),
    surface = Color(0xFF1E241C), onSurface = Color(0xFFE7ECE3),
    surfaceVariant = Color(0xFF262D24), onSurfaceVariant = Color(0xFFB3BDAE),
    outline = Color(0xFF535E4F), outlineVariant = Color(0xFF3A4337),
    error = Color(0xFFD47A71), onError = Color(0xFF1A0A08),
    inverseSurface = Color(0xFFE7ECE3), inverseOnSurface = Color(0xFF222820), inversePrimary = Color(0xFF6E7F65),
    surfaceDim = Color(0xFF141813), surfaceBright = Color(0xFF343C31),
    surfaceContainerLowest = Color(0xFF11140F), surfaceContainerLow = Color(0xFF1A1F18),
    surfaceContainer = Color(0xFF1E241C), surfaceContainerHigh = Color(0xFF252C23), surfaceContainerHighest = Color(0xFF2C342A)
)

private val base = Typography()
private val serif = FontFamily.Serif
private val typography = base.copy(
    headlineMedium = base.headlineMedium.copy(fontFamily = serif, fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontFamily = serif, fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontFamily = serif, fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp)
)

@Composable
fun WeddingTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    ThemeState.dark = dark
    MaterialTheme(colorScheme = if (dark) darkScheme else lightScheme, typography = typography, content = content)
}