package com.indohpl.presensi.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Green = Color(0xFF1B7F4E)
val GreenLight = Color(0xFFCFF2DD)
val Amber = Color(0xFFB7791F)
val AmberLight = Color(0xFFFFF0C7)
val Red = Color(0xFFB3261E)
val RedLight = Color(0xFFFFDAD6)
val Blue = Color(0xFF2F5FA8)
val BlueLight = Color(0xFFD9E5FF)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F5FBF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E5FF),
    onPrimaryContainer = Color(0xFF0B2A5B),
    secondary = Green,
    onSecondary = Color.White,
    secondaryContainer = GreenLight,
    onSecondaryContainer = Color(0xFF0B3B22),
    background = Color(0xFFF7F8FB),
    surface = Color.White,
    surfaceVariant = Color(0xFFEDEFF4),
    error = Red,
    errorContainer = RedLight,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9C7FF),
    onPrimary = Color(0xFF00315F),
    primaryContainer = Color(0xFF1F4A8F),
    onPrimaryContainer = Color(0xFFD9E5FF),
    secondary = Color(0xFF8ED6AC),
    secondaryContainer = Color(0xFF12523A),
    onSecondaryContainer = Color(0xFFCFF2DD),
)

@Composable
fun PresensiTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
