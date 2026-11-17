package com.alexandretupinamba.posetrace.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PoseTraceDarkColors = darkColorScheme(
    primary = Color(0xFF67D7E8),
    onPrimary = Color(0xFF00363D),
    primaryContainer = Color(0xFF004F59),
    onPrimaryContainer = Color(0xFFB8F1FA),
    secondary = Color(0xFFAFCBD0),
    background = Color(0xFF0D0F12),
    surface = Color(0xFF15181D),
    surfaceVariant = Color(0xFF262A31),
    onSurface = Color(0xFFF1F3F5),
    onSurfaceVariant = Color(0xFFC2C7CF),
    error = Color(0xFFFF6B6B),
    errorContainer = Color(0xFF4D1F23),
    onErrorContainer = Color(0xFFFFDAD9),
)

@Composable
fun PoseTraceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PoseTraceDarkColors,
        content = content,
    )
}
