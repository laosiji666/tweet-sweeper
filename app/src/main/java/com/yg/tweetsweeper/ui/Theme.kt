package com.yg.tweetsweeper.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FAEFF),
    onPrimary = Color(0xFF07142E),
    primaryContainer = Color(0xFF1C2B4C),
    onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = Color(0xFF7FD1BE),
    onSecondary = Color(0xFF04231C),
    secondaryContainer = Color(0xFF173A34),
    onSecondaryContainer = Color(0xFFC7F0E5),
    tertiary = Color(0xFFE3B4FF),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE7ECF6),
    surface = Color(0xFF111A2B),
    onSurface = Color(0xFFE7ECF6),
    surfaceVariant = Color(0xFF1A2438),
    onSurfaceVariant = Color(0xFFAEB9CE),
    outline = Color(0xFF3B4760),
    outlineVariant = Color(0xFF27314A),
    error = Color(0xFFFF9A9A),
    onError = Color(0xFF40000A),
    errorContainer = Color(0xFF4A1520),
    onErrorContainer = Color(0xFFFFD9DC)
)

@Composable
fun TweetSweeperTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
