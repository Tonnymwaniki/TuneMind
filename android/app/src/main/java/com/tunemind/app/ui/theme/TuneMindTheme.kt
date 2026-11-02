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
 * Composition local providing the active Deep Obsidian Amber state.
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
// Token bridges to maintain backwards compatibility while
// delegating to Deep Obsidian Amber foundational colors.
// ─────────────────────────────────────────────────────────────
val Surface: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalAdaptiveTheme.current.primaryBackground

val SurfaceContainerLowest: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalAdaptiveTheme.current.recessedBackground

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
    get() = LocalAdaptiveTheme.current.surfaceHighlight

val SurfaceBright: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalAdaptiveTheme.current.accentSoft

// Feature Domain Accents
val PrimaryViolet = DeepObsidianAmber.NeuralViolet
val PrimaryVioletDark = DeepObsidianAmber.ElectricViolet
val PrimaryVioletBright = DeepObsidianAmber.NeuralViolet
val AccentCyan = DeepObsidianAmber.CryoCyan
val AccentCyanBright = DeepObsidianAmber.EQBassCyan
val AccentTeal = Color(0xFF26A69A)
val AccentAmber = DeepObsidianAmber.PrimaryAccent
val AccentCrimson = DeepObsidianAmber.SatietyEmber
val AccentGreen = DeepObsidianAmber.DecibelSafe

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
    get() = LocalAdaptiveTheme.current.textMuted

val BorderSubtle: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalAdaptiveTheme.current.border

val BorderHighlight: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalAdaptiveTheme.current.surfaceHighlight

// Gradients
val PrimaryGradient = Brush.horizontalGradient(
    colors = listOf(DeepObsidianAmber.PrimaryAccent, DeepObsidianAmber.SecondaryAccent)
)
val CardGlowGradient = Brush.verticalGradient(
    colors = listOf(DeepObsidianAmber.CardElevated, DeepObsidianAmber.CardBackground)
)
val BurnoutShieldGradient = Brush.linearGradient(
    colors = listOf(DeepObsidianAmber.SatietyEmber.copy(alpha = 0.3f), DeepObsidianAmber.CardBackground)
)

@Composable
fun TuneMindTheme(
    adaptiveState: AdaptiveThemeState = AdaptiveThemeState.defaultForNight(),
    content: @Composable () -> Unit
) {
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
        primary = DeepObsidianAmber.PrimaryAccent,
        onPrimary = DeepObsidianAmber.AppBackground,
        primaryContainer = DeepObsidianAmber.CardElevated,
        onPrimaryContainer = DeepObsidianAmber.TextPrimary,
        secondary = DeepObsidianAmber.SecondaryAccent,
        onSecondary = DeepObsidianAmber.AppBackground,
        secondaryContainer = DeepObsidianAmber.CardBackground,
        onSecondaryContainer = DeepObsidianAmber.TextPrimary,
        tertiary = DeepObsidianAmber.CopperAccent,
        background = DeepObsidianAmber.AppBackground,
        onBackground = DeepObsidianAmber.TextPrimary,
        surface = DeepObsidianAmber.CardBackground,
        onSurface = DeepObsidianAmber.TextPrimary,
        surfaceVariant = DeepObsidianAmber.CardElevated,
        onSurfaceVariant = DeepObsidianAmber.TextSecondary,
        outline = DeepObsidianAmber.SurfaceBorder,
        outlineVariant = DeepObsidianAmber.SurfaceHighlight,
        error = DeepObsidianAmber.Error
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
