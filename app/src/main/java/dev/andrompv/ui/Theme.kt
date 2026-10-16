package dev.andrompv.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** macmpv's dark glass palette: near-black stage, translucent panels, white controls. */
object Palette {
    val background = Color(0xFF06070C)
    val glass = Color(0xFF151927)
    val glassStrong = Color(0xFF10131D)
    val menu = Color(0xFF1B1F2C)
    val stroke = Color.White.copy(alpha = 0.10f)
    val strokeStrong = Color.White.copy(alpha = 0.18f)
    val textPrimary = Color.White
    val textSecondary = Color.White.copy(alpha = 0.62f)
    val textTertiary = Color.White.copy(alpha = 0.40f)
    val fill = Color.White.copy(alpha = 0.08f)
    val fillStrong = Color.White.copy(alpha = 0.14f)
    val indigo = Color(0xFF5E5CE6)
    val cyan = Color(0xFF40C8E0)
    val accent = Color(0xFF7DD3FC)
    val warning = Color(0xFFFF9F0A)
    val danger = Color(0xFFFF6961)
    val playGlyph = Color(0xFF0F1220)

    val currentBadge = Brush.linearGradient(listOf(indigo.copy(alpha = 0.9f), cyan.copy(alpha = 0.7f)))
    val idleBadge = Brush.linearGradient(listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.035f)))
}

@Composable
fun AndrompvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color.White,
            onPrimary = Palette.playGlyph,
            secondary = Palette.accent,
            background = Palette.background,
            surface = Palette.glassStrong,
            surfaceContainer = Palette.menu,
            onSurface = Palette.textPrimary,
            onSurfaceVariant = Palette.textSecondary,
            outline = Palette.strokeStrong,
            error = Palette.danger
        ),
        content = content
    )
}
