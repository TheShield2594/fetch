package com.fetch.desktop.ui

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.fetch.tv.domain.model.AppTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The TV app's default dark palette: near-black surfaces, white text and its red accent. */
object FetchPalette {
    val Background = Color(0xFF0B0B0C)
    val Surface = Color(0xFF16161A)
    val SurfaceRaised = Color(0xFF1E1E23)
    val SurfaceHigh = Color(0xFF2A2A30)
    val Outline = Color(0xFF3A3A42)
    val TextPrimary = Color(0xFFF5F5F5)
    val TextSecondary = Color(0xFFB3B3B8)
    val TextMuted = Color(0xFF85858C)
    private val accentState = mutableStateOf(accentFor(AppTheme.FETCH))

    /** The theme's accent. Reading it in composition follows theme changes. */
    val Accent: Color get() = accentState.value
    /** The accent sunk into the background, for the selected sidebar tile. */
    val AccentDim: Color get() = lerp(Background, Accent, 0.22f)
    /** Text on the accent: dark on the light accents (White, Graphite, Amber…). */
    val OnAccent: Color get() = if (Accent.luminance() > 0.5f) Color.Black else Color.White

    fun setTheme(theme: AppTheme) {
        accentState.value = accentFor(theme)
    }
    val Imdb = Color(0xFFF5C518)
    val Error = Color(0xFFFF6B6B)
}

/** Each theme's accent, from the TV app's palettes. */
fun accentFor(theme: AppTheme): Color = when (theme) {
    AppTheme.FETCH, AppTheme.CUSTOM -> Color(0xFFDC2B33)
    AppTheme.GOLD -> Color(0xFFE8A91C)
    AppTheme.JADE -> Color(0xFF22D37C)
    AppTheme.ROSE_GOLD -> Color(0xFFEC70A9)
    AppTheme.ARCTIC_BLUE -> Color(0xFF3185F5)
    AppTheme.GRAPHITE -> Color(0xFFAAB2BE)
    AppTheme.CRIMSON -> Color(0xFFE5484D)
    AppTheme.OCEAN -> Color(0xFF4CB5F5)
    AppTheme.VIOLET -> Color(0xFFA78BFA)
    AppTheme.EMERALD -> Color(0xFF3DD68C)
    AppTheme.AMBER -> Color(0xFFF5A524)
    AppTheme.ROSE -> Color(0xFFF06A9E)
    AppTheme.WHITE -> Color(0xFFF5F5F5)
}

private fun fetchColors() = darkColorScheme(
    primary = FetchPalette.Accent,
    onPrimary = FetchPalette.OnAccent,
    primaryContainer = FetchPalette.AccentDim,
    onPrimaryContainer = FetchPalette.TextPrimary,
    secondary = FetchPalette.TextPrimary,
    onSecondary = FetchPalette.Background,
    secondaryContainer = FetchPalette.SurfaceHigh,
    onSecondaryContainer = FetchPalette.TextPrimary,
    background = FetchPalette.Background,
    onBackground = FetchPalette.TextPrimary,
    surface = FetchPalette.Surface,
    onSurface = FetchPalette.TextPrimary,
    surfaceVariant = FetchPalette.SurfaceRaised,
    onSurfaceVariant = FetchPalette.TextSecondary,
    surfaceContainer = FetchPalette.Surface,
    surfaceContainerHigh = FetchPalette.SurfaceRaised,
    surfaceContainerHighest = FetchPalette.SurfaceHigh,
    outline = FetchPalette.Outline,
    outlineVariant = FetchPalette.SurfaceHigh,
    error = FetchPalette.Error
)

private val FetchTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold)
    )
}

/** Small uppercase label, like "BEST MATCH" on the TV detail page. */
val OverlineStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)

/**
 * A thin, rounded scrollbar that brightens on hover. Every long page has one, so a mouse user
 * can grab it to jump around instead of only using the wheel.
 */
private val FetchScrollbarStyle = ScrollbarStyle(
    minimalHeight = 48.dp,
    thickness = 8.dp,
    shape = RoundedCornerShape(4.dp),
    hoverDurationMillis = 250,
    unhoverColor = Color.White.copy(alpha = 0.18f),
    hoverColor = Color.White.copy(alpha = 0.45f)
)

@Composable
fun FetchTheme(content: @Composable () -> Unit) {
    val accent = FetchPalette.Accent
    val colors = remember(accent) { fetchColors() }
    MaterialTheme(colorScheme = colors, typography = FetchTypography) {
        CompositionLocalProvider(LocalScrollbarStyle provides FetchScrollbarStyle, content = content)
    }
}
