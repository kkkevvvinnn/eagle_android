package com.eagleviewer.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val EagleDarkScheme = darkColorScheme(
    primary = Color(0xFFFFB74D),
    onPrimary = Color(0xFF3D2800),
    primaryContainer = Color(0xFF5C3D00),
    onPrimaryContainer = Color(0xFFFFDEA6),
    secondary = Color(0xFF9ECAFF),
    onSecondary = Color(0xFF003258),
    secondaryContainer = Color(0xFF00497D),
    onSecondaryContainer = Color(0xFFD1E4FF),
    background = Color(0xFF10131A),
    onBackground = Color(0xFFE2E5EE),
    surface = Color(0xFF10131A),
    onSurface = Color(0xFFE2E5EE),
    surfaceVariant = Color(0xFF1C202B),
    onSurfaceVariant = Color(0xFFC2C7D4),
    surfaceContainer = Color(0xFF1A1E28),
    outline = Color(0xFF8C919E),
    error = Color(0xFFFFB4AB),
)

/** 应用固定深色主题（图库浏览场景，突出图片本身）。 */
@Composable
fun EagleTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = EagleDarkScheme,
        content = content,
    )
}
