package com.example.videoplayer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// 深色为主：低亮度背景 + 紫色/青色强调色，避免纯黑带来的拖影感
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFB9A4FF),
    onPrimary = Color(0xFF2A0F63),
    primaryContainer = Color(0xFF422A80),
    onPrimaryContainer = Color(0xFFE9DEFF),
    secondary = Color(0xFF8FD6F5),
    onSecondary = Color(0xFF00363F),
    secondaryContainer = Color(0xFF164C57),
    onSecondaryContainer = Color(0xFFC8F0FF),
    tertiary = Color(0xFFFFB4AB),
    onTertiary = Color(0xFF690005),
    background = Color(0xFF0C0C0E),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF121116),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF242229),
    onSurfaceVariant = Color(0xFFC9C4CF),
    surfaceContainer = Color(0xFF19181D),
    surfaceContainerHigh = Color(0xFF1F1E24),
    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF48454E),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color(0xFF000000)
)

private val AppTypography = Typography(
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    )
)

@Composable
fun VideoPlayerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = AppTypography,
        content = content
    )
}
