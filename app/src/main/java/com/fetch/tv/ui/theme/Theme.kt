package com.fetch.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.fetch.tv.domain.model.AppFont
import com.fetch.tv.domain.model.AppTheme
import com.fetch.tv.domain.model.CustomThemeColors

data class FetchExtendedColors(
    val backgroundElevated: Color,
    val backgroundCard: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val focusRing: Color,
    val focusBackground: Color,
    val rating: Color
)

val LocalFetchColors = staticCompositionLocalOf {
    FetchColorScheme(ThemeColors.Fetch)
}

val LocalFetchExtendedColors = staticCompositionLocalOf {
    FetchExtendedColors(
        backgroundElevated = Color(0xFF1A1A1A),
        backgroundCard = Color(0xFF242424),
        textSecondary = Color(0xFFB3B3B3),
        textTertiary = Color(0xFFA3A3A3),
        focusRing = ThemeColors.Fetch.focusRing,
        focusBackground = ThemeColors.Fetch.focusBackground,
        rating = Color(0xFFFFD700)
    )
}

val LocalFetchTextStyles = staticCompositionLocalOf { FetchTextStyles }

val LocalAppTheme = staticCompositionLocalOf { com.fetch.tv.domain.model.DEFAULT_APP_THEME }

val LocalThemePalette = staticCompositionLocalOf { ThemeColors.White }

val LocalFetchFocusRingStyle = staticCompositionLocalOf {
    createFocusRingStyle(ThemeColors.Fetch)
}

/** See [isFullMotionAllowed]; read it through [FetchTheme.fullMotion]. */
val LocalFullMotion = staticCompositionLocalOf { true }

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FetchTheme(
    appTheme: AppTheme = com.fetch.tv.domain.model.DEFAULT_APP_THEME,
    appFont: AppFont = AppFont.SCHIBSTED_GROTESK,
    amoledMode: Boolean = false,
    amoledSurfacesMode: Boolean = false,
    customThemeColors: CustomThemeColors = CustomThemeColors.Default,
    content: @Composable () -> Unit
) {
    val palette = androidx.compose.runtime.remember(appTheme, customThemeColors) {
        ThemeColors.getColorPalette(appTheme, customThemeColors)
    }
    val focusRingStyle = createFocusRingStyle(palette)
    val colorScheme = FetchColorScheme(
        palette = palette,
        amoledMode = amoledMode,
        amoledSurfacesMode = amoledSurfacesMode
    )
    val typography = buildFetchTypography(getFontFamily(appFont), getDisplayFontFamily(appFont))
    val textStyles = buildFetchTextStyles(typography)
    val fullMotion = rememberFullMotionAllowed()

    val materialColorScheme = darkColorScheme(
        primary = colorScheme.Primary,
        onPrimary = colorScheme.OnPrimary,
        secondary = colorScheme.Secondary,
        onSecondary = colorScheme.OnSecondary,
        background = colorScheme.Background,
        surface = colorScheme.Surface,
        surfaceVariant = colorScheme.SurfaceVariant,
        onBackground = colorScheme.TextPrimary,
        onSurface = colorScheme.TextPrimary,
        onSurfaceVariant = colorScheme.TextSecondary,
        error = colorScheme.Error
    )

    val extendedColors = FetchExtendedColors(
        backgroundElevated = colorScheme.BackgroundElevated,
        backgroundCard = colorScheme.BackgroundCard,
        textSecondary = colorScheme.TextSecondary,
        textTertiary = colorScheme.TextTertiary,
        focusRing = colorScheme.FocusRing,
        focusBackground = colorScheme.FocusBackground,
        rating = colorScheme.Rating
    )

    CompositionLocalProvider(
        LocalFetchColors provides colorScheme,
        LocalFetchExtendedColors provides extendedColors,
        LocalFetchTextStyles provides textStyles,
        LocalAppTheme provides appTheme,
        LocalThemePalette provides palette,
        LocalFetchFocusRingStyle provides focusRingStyle,
        LocalFullMotion provides fullMotion
    ) {
        MaterialTheme(
            colorScheme = materialColorScheme,
            typography = typography,
            content = content
        )
    }
}

object FetchTheme {
    val palette: ThemeColorPalette
        @Composable
        @ReadOnlyComposable
        get() = LocalThemePalette.current

    val colors: FetchColorScheme
        @Composable
        @ReadOnlyComposable
        get() = LocalFetchColors.current

    val extendedColors: FetchExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalFetchExtendedColors.current

    val textStyles: FetchTextStyleTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalFetchTextStyles.current

    val spacing: FetchSpacingTokens
        get() = FetchSpacing.tokens

    val radii: FetchRadiusTokens
        get() = FetchRadii.tokens

    val shapes: FetchShapeTokens
        get() = FetchShapes.tokens

    val sizes: FetchSizeTokens
        get() = FetchSizes.tokens

    val strokes: FetchStrokeTokens
        get() = FetchStrokes.tokens

    val elevations: FetchElevationTokens
        get() = FetchElevations.tokens

    val effects: FetchEffectTokens
        get() = FetchEffects.tokens

    val motion: FetchMotionTokens
        get() = FetchMotion.tokens

    val focus: FetchFocusTokens
        get() = FetchFocus.tokens

    val focusRing: FetchFocusRingStyle
        @Composable
        @ReadOnlyComposable
        get() = LocalFetchFocusRingStyle.current

    val layout: FetchLayoutTokens
        get() = FetchLayout.tokens

    val media: FetchMediaTokens
        get() = FetchMedia.tokens

    val components: FetchComponentTokens
        get() = FetchComponents.tokens

    /**
     * False when the system's animations are off or the device is low on RAM. Decorative motion
     * (shimmers, pulses, slides) then holds still or cuts straight to its end state.
     */
    val fullMotion: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalFullMotion.current

    val currentTheme: AppTheme
        @Composable
        @ReadOnlyComposable
        get() = LocalAppTheme.current
}
