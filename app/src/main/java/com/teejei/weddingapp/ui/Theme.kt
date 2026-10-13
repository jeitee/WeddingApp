package com.teejei.weddingapp.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Cream = Color(0xFFFAF7F2)
val Sage = Color(0xFFA3B899)
val Artichoke = Color(0xFF6E7F65)
val Gold = Color(0xFFD4AF37)
val SurfaceWhite = Color(0xFFFFFFFF)
val Ink = Color(0xFF2E3A2B)
val Rose = Color(0xFFB5524A)

private val scheme = lightColorScheme(
    primary = Artichoke, onPrimary = Color.White,
    primaryContainer = Sage, onPrimaryContainer = Ink,
    secondary = Artichoke, onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6EDE2), onSecondaryContainer = Ink,
    tertiary = Gold, onTertiary = Ink,
    background = Cream, onBackground = Ink,
    surface = SurfaceWhite, onSurface = Ink,
    surfaceVariant = Color(0xFFF1EEE6), onSurfaceVariant = Color(0xFF5B6657),
    outline = Color(0xFFCFC8B8), error = Rose
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
fun WeddingTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
