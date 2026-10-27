package com.tunemind.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// TuneMind Palette (Deep Slate-950 Acoustic Cyber Dark)
val Surface = Color(0xFF020617)
val SurfaceContainerLowest = Color(0xFF020617)
val SurfaceContainerLow = Color(0xFF090D16)
val SurfaceContainer = Color(0xFF0F172A)
val SurfaceContainerHigh = Color(0xFF1E293B)
val SurfaceContainerHighest = Color(0xFF334155)
val SurfaceBright = Color(0xFF475569)

// Primary & Accents
val PrimaryViolet = Color(0xFF8B5CF6)
val PrimaryVioletDark = Color(0xFF7C3AED)
val PrimaryVioletBright = Color(0xFFA78BFA)
val AccentCyan = Color(0xFF06B6D4)
val AccentCyanBright = Color(0xFF38BDF8)
val AccentTeal = Color(0xFF14B8A6)
val AccentAmber = Color(0xFFF59E0B)
val AccentCrimson = Color(0xFFEF4444)
val AccentGreen = Color(0xFF10B981)

// Text & Monochromes
val TextPrimary = Color(0xFFF8FAFC)
val TextSecondary = Color(0xFF94A3B8)
val TextMuted = Color(0xFF64748B)
val BorderSubtle = Color(0xFF1E293B)
val BorderHighlight = Color(0xFF334155)

// Gradients
val PrimaryGradient = Brush.horizontalGradient(
    colors = listOf(PrimaryViolet, AccentCyan)
)
val CardGlowGradient = Brush.verticalGradient(
    colors = listOf(SurfaceContainerHighest.copy(alpha = 0.5f), SurfaceContainer)
)
val BurnoutShieldGradient = Brush.linearGradient(
    colors = listOf(Color(0xFF1E1B4B), Color(0xFF0F172A))
)

val DarkColorScheme = darkColorScheme(
    primary = PrimaryViolet,
    onPrimary = Color.White,
    primaryContainer = SurfaceContainerHigh,
    onPrimaryContainer = PrimaryVioletBright,
    secondary = AccentCyan,
    onSecondary = Color.Black,
    secondaryContainer = SurfaceContainerHighest,
    onSecondaryContainer = AccentCyanBright,
    tertiary = AccentAmber,
    background = Surface,
    onBackground = TextPrimary,
    surface = SurfaceContainer,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceContainerHigh,
    onSurfaceVariant = TextSecondary,
    outline = BorderSubtle,
    error = AccentCrimson
)

@Composable
fun TuneMindTheme(
    darkTheme: Boolean = true, // TuneMind is natively an immersive dark experience
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = TuneMindTypography,
        shapes = TuneMindShapes,
        content = content
    )
}
