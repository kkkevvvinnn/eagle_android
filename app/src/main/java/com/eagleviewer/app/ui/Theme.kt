package com.eagleviewer.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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

private val EagleLightScheme = lightColorScheme(
    primary = Color(0xFF8A5100),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDDB3),
    onPrimaryContainer = Color(0xFF2D1800),
    secondary = Color(0xFF00629E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCFE5FF),
    onSecondaryContainer = Color(0xFF001D34),
    background = Color(0xFFF8F9FC),
    onBackground = Color(0xFF1B1D23),
    surface = Color(0xFFF8F9FC),
    onSurface = Color(0xFF1B1D23),
    surfaceVariant = Color(0xFFE5E8EF),
    onSurfaceVariant = Color(0xFF44484F),
    surfaceContainer = Color(0xFFEDF0F5),
    outline = Color(0xFF75787F),
    error = Color(0xFFBA1A1A),
)

/** 主题跟随系统深/浅色。图片查看场景深色更突出，但尊重系统设置。 */
@Composable
fun EagleTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) EagleDarkScheme else EagleLightScheme,
        content = content,
    )
}
