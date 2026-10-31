package com.tunemind.app.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Composition local providing the active Adaptive Personal Atmosphere state.
 */
val LocalAdaptiveTheme = staticCompositionLocalOf {
    AdaptiveThemeState.defaultForNight()
}

/**
 * Central theme accessor.
 */
object TuneMindTheme {
    val colors: AdaptiveThemeState
        @Composable
        @ReadOnlyComposable
        get() = LocalAdaptiveTheme.current

    val typography
        @Composable
        @ReadOnlyComposable
        get() = TuneMindTypography

    val shapes
        @Composable
        @ReadOnlyComposable
        get() = TuneMindShapes
}

// ─────────────────────────────────────────────────────────────
// Legacy token bridges to maintain backwards compatibility while
// delegating to the dynamic adaptive theme colors.
// ─────────────────────────────────────────────────────────────
val Surface: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalAdaptiveTheme.current.primaryBackground

val SurfaceContainerLowest: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalAdaptiveTheme.current.primaryBackground

val SurfaceContainerLow: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalAdaptiveTheme.current.secondaryBackground

val SurfaceContainer: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalAdaptiveTheme.current.cardSurface

val SurfaceContainerHigh: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalAdaptiveTheme.current.elevatedSurface

val SurfaceContainerHighest: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalAdaptiveTheme.current.border

val SurfaceBright: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalAdaptiveTheme.current.highlight

// Accents
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
val TextPrimary: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalAdaptiveTheme.current.primaryText

val TextSecondary: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalAdaptiveTheme.current.secondaryText

val TextMuted: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalAdaptiveTheme.current.secondaryText.copy(alpha = 0.7f)

val BorderSubtle: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalAdaptiveTheme.current.border

val BorderHighlight: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalAdaptiveTheme.current.highlight

// Gradients
val PrimaryGradient = Brush.horizontalGradient(
    colors = listOf(PrimaryViolet, AccentCyan)
)
val CardGlowGradient = Brush.verticalGradient(
    colors = listOf(Color(0xFF334155).copy(alpha = 0.5f), Color(0xFF0F172A))
)
val BurnoutShieldGradient = Brush.linearGradient(
    colors = listOf(Color(0xFF1E1B4B), Color(0xFF0F172A))
)

@Composable
fun TuneMindTheme(
    adaptiveState: AdaptiveThemeState = AdaptiveThemeState.defaultForNight(),
    content: @Composable () -> Unit
) {
    // 500-900ms smooth gradual transition between palettes without flashing
    val animDuration = adaptiveState.animationDurationMs
    val animSpec = tween<Color>(durationMillis = animDuration)

    val animatedBg = animateColorAsState(targetValue = adaptiveState.primaryBackground, animationSpec = animSpec, label = "bg").value
    val animatedSecondaryBg = animateColorAsState(targetValue = adaptiveState.secondaryBackground, animationSpec = animSpec, label = "secBg").value
    val animatedCard = animateColorAsState(targetValue = adaptiveState.cardSurface, animationSpec = animSpec, label = "card").value
    val animatedElevated = animateColorAsState(targetValue = adaptiveState.elevatedSurface, animationSpec = animSpec, label = "elevated").value
    val animatedTextPrimary = animateColorAsState(targetValue = adaptiveState.primaryText, animationSpec = animSpec, label = "textPrimary").value
    val animatedTextSecondary = animateColorAsState(targetValue = adaptiveState.secondaryText, animationSpec = animSpec, label = "textSecondary").value
    val animatedAccentPrimary = animateColorAsState(targetValue = adaptiveState.primaryAccent, animationSpec = animSpec, label = "accentPrimary").value
    val animatedAccentSecondary = animateColorAsState(targetValue = adaptiveState.secondaryAccent, animationSpec = animSpec, label = "accentSecondary").value
    val animatedHighlight = animateColorAsState(targetValue = adaptiveState.highlight, animationSpec = animSpec, label = "highlight").value
    val animatedBorder = animateColorAsState(targetValue = adaptiveState.border, animationSpec = animSpec, label = "border").value

    val animatedState = adaptiveState.copy(
        primaryBackground = animatedBg,
        secondaryBackground = animatedSecondaryBg,
        cardSurface = animatedCard,
        elevatedSurface = animatedElevated,
        primaryText = animatedTextPrimary,
        secondaryText = animatedTextSecondary,
        primaryAccent = animatedAccentPrimary,
        secondaryAccent = animatedAccentSecondary,
        highlight = animatedHighlight,
        border = animatedBorder
    )

    val colorScheme = darkColorScheme(
        primary = animatedAccentPrimary,
        onPrimary = animatedBg,
        primaryContainer = animatedElevated,
        onPrimaryContainer = animatedHighlight,
        secondary = animatedAccentSecondary,
        onSecondary = animatedBg,
        secondaryContainer = animatedCard,
        onSecondaryContainer = animatedTextPrimary,
        tertiary = animatedHighlight,
        background = animatedBg,
        onBackground = animatedTextPrimary,
        surface = animatedCard,
        onSurface = animatedTextPrimary,
        surfaceVariant = animatedElevated,
        onSurfaceVariant = animatedTextSecondary,
        outline = animatedBorder,
        error = AccentCrimson
    )

    CompositionLocalProvider(LocalAdaptiveTheme provides animatedState) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = TuneMindTypography,
            shapes = TuneMindShapes,
            content = content
        )
    }
}
