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
import com.fetch.tv.domain.model.AppTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The TV app's default dark palette: near-black surfaces, white text and its red accent. */
object FetchPalette {
    // The surfaces and text follow Appearance's contrast, AMOLED and Custom theme settings (see
    // ThemeLook.kt). Reading them in composition follows changes.
    private var appearance = com.fetch.desktop.data.AppearanceSettings()
    private var theme = AppTheme.FETCH
    private val surfaceState = mutableStateOf(SurfaceColors.Default)
    val Surfaces: SurfaceColors get() = surfaceState.value
    val Background: Color get() = surfaceState.value.background
    val Surface: Color get() = surfaceState.value.surface
    val SurfaceRaised: Color get() = surfaceState.value.surfaceRaised
    val SurfaceHigh: Color get() = surfaceState.value.surfaceHigh
    val Outline: Color get() = surfaceState.value.outline
    val TextPrimary: Color get() = surfaceState.value.textPrimary
    val TextSecondary: Color get() = surfaceState.value.textSecondary
    val TextMuted: Color get() = surfaceState.value.textMuted
    private val accentState = mutableStateOf(accentFor(AppTheme.FETCH))
    private val fontState = mutableStateOf(com.fetch.tv.domain.model.AppFont.SCHIBSTED_GROTESK)

    /** Appearance › App Font. */
    val Font: com.fetch.tv.domain.model.AppFont get() = fontState.value

    /** The Custom theme's accent, from its colours. */
    val CustomAccent: Color get() = customAccent(appearance.customColors)

    /** The theme's accent. Reading it in composition follows theme changes. */
    val Accent: Color get() = accentState.value
    /** The accent sunk into the background, for the selected sidebar tile. */
    val AccentDim: Color get() = lerp(Background, Accent, 0.22f)
    /** Text on the accent: dark on the light accents (White, Graphite, Amber…). */
    val OnAccent: Color get() = readableOn(listOf(Accent))

    fun setTheme(theme: AppTheme) {
        this.theme = theme
        accentState.value = accentFor(theme)
        surfaceState.value = surfaceColors(theme, appearance)
    }

    /** Appearance's contrast, AMOLED, Custom theme colours and font. */
    fun setAppearance(appearance: com.fetch.desktop.data.AppearanceSettings) {
        this.appearance = appearance
        fontState.value = appearance.appFont
        setTheme(theme)
    }
    val Imdb = Color(0xFFF5C518)
    val Error = Color(0xFFFF6B6B)
    val Warning = Color(0xFFFFB74D)
}

/** Each theme's accent, from the TV app's palettes. */
fun accentFor(theme: AppTheme): Color = when (theme) {
    AppTheme.FETCH -> Color(0xFFDC2B33)
    AppTheme.CUSTOM -> FetchPalette.CustomAccent
    AppTheme.OCEAN -> Color(0xFF4CB5F5)
    AppTheme.EMERALD -> Color(0xFF3DD68C)
    AppTheme.AMBER -> Color(0xFFF5A524)
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
    val surfaces = FetchPalette.Surfaces
    val colors = remember(accent, surfaces) { fetchColors() }
    val font = FetchPalette.Font
    val typography = remember(font) { FetchTypography.withFontFamily(fontFamilyFor(font)) }
    MaterialTheme(colorScheme = colors, typography = typography) {
        CompositionLocalProvider(LocalScrollbarStyle provides FetchScrollbarStyle, content = content)
    }
}
