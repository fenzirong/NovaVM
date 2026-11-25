package com.novavm.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Cyber Slate palette — the single source of truth for NovaVM UI colors. */
object NovaPalette {
    val Background = Color(0xFF0D1117)
    val Surface = Color(0xFF161B22)
    val SurfaceElevated = Color(0xFF1C2230)
    val SurfaceMuted = Color(0xFF212A38)
    val Border = Color(0xFF2A3444)

    val Cyan = Color(0xFF38BDF8)
    val CyanDark = Color(0xFF0284C7)
    val CyanSoft = Color(0xFF0E2A3D)
    val Emerald = Color(0xFF22C55E)
    val EmeraldSoft = Color(0xFF10281B)
    val Amber = Color(0xFFF59E0B)
    val AmberSoft = Color(0xFF2B2110)
    val Coral = Color(0xFFEF4444)
    val CoralSoft = Color(0xFF2C1416)
    val Violet = Color(0xFFA78BFA)

    val TextPrimary = Color(0xFFE6EDF3)
    val TextSecondary = Color(0xFF9BA9BC)
    val TextMuted = Color(0xFF6B7A8F)
}

private val DarkScheme = darkColorScheme(
    primary = NovaPalette.Cyan,
    onPrimary = Color(0xFF04121C),
    primaryContainer = NovaPalette.CyanSoft,
    onPrimaryContainer = NovaPalette.Cyan,
    secondary = NovaPalette.Emerald,
    onSecondary = Color(0xFF04140A),
    secondaryContainer = NovaPalette.EmeraldSoft,
    onSecondaryContainer = NovaPalette.Emerald,
    tertiary = NovaPalette.Amber,
    onTertiary = Color(0xFF1A1204),
    tertiaryContainer = NovaPalette.AmberSoft,
    onTertiaryContainer = NovaPalette.Amber,
    background = NovaPalette.Background,
    onBackground = NovaPalette.TextPrimary,
    surface = NovaPalette.Surface,
    onSurface = NovaPalette.TextPrimary,
    surfaceVariant = NovaPalette.SurfaceElevated,
    onSurfaceVariant = NovaPalette.TextSecondary,
    surfaceContainer = NovaPalette.Surface,
    surfaceContainerHigh = NovaPalette.SurfaceElevated,
    outline = NovaPalette.Border,
    outlineVariant = NovaPalette.Border,
    error = NovaPalette.Coral,
    onError = Color(0xFF1A0505),
    errorContainer = NovaPalette.CoralSoft,
    onErrorContainer = NovaPalette.Coral
)

private val LightScheme = lightColorScheme(
    primary = NovaPalette.CyanDark,
    onPrimary = Color.White,
    secondary = NovaPalette.Emerald,
    background = Color(0xFFF4F7FB),
    onBackground = Color(0xFF0D1117),
    surface = Color.White,
    onSurface = Color(0xFF0D1117),
    surfaceVariant = Color(0xFFEBF1F8),
    onSurfaceVariant = Color(0xFF44546A),
    error = NovaPalette.Coral
)

@Composable
fun NovaVmTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme || isSystemInDarkTheme()) DarkScheme else LightScheme,
        content = content
    )
}
