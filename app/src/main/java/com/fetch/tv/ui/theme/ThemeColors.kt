package com.fetch.tv.ui.theme

import androidx.compose.ui.graphics.Color
import com.fetch.tv.domain.model.AppTheme
import com.fetch.tv.domain.model.CustomThemeColors

data class ThemeColorPalette(
    val secondary: Color,
    val secondaryVariant: Color,
    val onSecondary: Color = FetchPrimitives.white,
    val onSecondaryVariant: Color = FetchPrimitives.white,
    val accentGradient: List<Color> = listOf(secondary),
    val focusRing: Color,
    val focusRingGradient: List<Color> = listOf(focusRing),
    val focusBackground: Color,
    val background: Color = FetchPrimitives.neutral950,
    val backgroundElevated: Color = FetchPrimitives.neutral900,
    val backgroundCard: Color = FetchPrimitives.neutral825,
    val surface: Color = FetchPrimitives.neutral875,
    val surfaceVariant: Color = FetchPrimitives.neutral800,
    val panel: Color = FetchPrimitives.neutral900,
    val overlay: Color = Color(0xD9000000),
    val field: Color = FetchPrimitives.neutral850,
    val menu: Color = FetchPrimitives.neutral875,
    val modal: Color = FetchPrimitives.neutral900,
    val playerOverlay: Color = Color(0xCC000000)
)

object ThemeColors {
    val Crimson = ThemeColorPalette(
        secondary = FetchPrimitives.red500,
        secondaryVariant = FetchPrimitives.red600,
        focusRing = FetchPrimitives.red300,
        focusBackground = Color(0xFF3D1A1A),
        backgroundCard = Color(0xFF241A1A)
    )

    val Ocean = ThemeColorPalette(
        secondary = FetchPrimitives.blue500,
        secondaryVariant = FetchPrimitives.blue700,
        focusRing = FetchPrimitives.blue300,
        focusBackground = Color(0xFF1A2D3D),
        background = Color(0xFF0D0D0F),
        backgroundElevated = Color(0xFF1A1A1E),
        backgroundCard = Color(0xFF1A1F24)
    )

    val Violet = ThemeColorPalette(
        secondary = FetchPrimitives.violet500,
        secondaryVariant = FetchPrimitives.violet700,
        focusRing = FetchPrimitives.violet300,
        focusBackground = Color(0xFF2D1A3D),
        background = Color(0xFF0D0D0F),
        backgroundElevated = Color(0xFF1A1A1E),
        backgroundCard = Color(0xFF1F1A24)
    )

    val Emerald = ThemeColorPalette(
        secondary = FetchPrimitives.green500,
        secondaryVariant = FetchPrimitives.green700,
        focusRing = FetchPrimitives.green300,
        focusBackground = Color(0xFF1A3D1E),
        backgroundCard = Color(0xFF1A241A)
    )

    val Amber = ThemeColorPalette(
        secondary = FetchPrimitives.amber500,
        secondaryVariant = FetchPrimitives.amber700,
        focusRing = FetchPrimitives.amber300,
        focusBackground = Color(0xFF3D2D1A),
        background = Color(0xFF0F0D0D),
        backgroundElevated = Color(0xFF1E1A1A),
        backgroundCard = Color(0xFF24201A)
    )

    val Rose = ThemeColorPalette(
        secondary = FetchPrimitives.rose500,
        secondaryVariant = FetchPrimitives.rose700,
        focusRing = FetchPrimitives.rose300,
        focusBackground = Color(0xFF3D1A2D),
        backgroundCard = Color(0xFF241A1F)
    )

    val White = ThemeColorPalette(
        secondary = FetchPrimitives.neutral100,
        secondaryVariant = FetchPrimitives.neutral200,
        onSecondary = FetchPrimitives.neutral925,
        onSecondaryVariant = FetchPrimitives.neutral925,
        focusRing = FetchPrimitives.white,
        focusBackground = Color(0xFF303030),
        backgroundCard = FetchPrimitives.neutral850
    )

    fun getColorPalette(
        theme: AppTheme,
        customColors: CustomThemeColors = CustomThemeColors.Default
    ): ThemeColorPalette {
        return when (theme) {
            AppTheme.CUSTOM -> customColors.toColorPalette()
            AppTheme.GOLD -> SupporterThemeColors.Gold
            AppTheme.JADE -> SupporterThemeColors.Jade
            AppTheme.ROSE_GOLD -> SupporterThemeColors.RoseGold
            AppTheme.ARCTIC_BLUE -> SupporterThemeColors.ArcticBlue
            AppTheme.GRAPHITE -> SupporterThemeColors.Graphite
            AppTheme.CRIMSON -> Crimson
            AppTheme.OCEAN -> Ocean
            AppTheme.VIOLET -> Violet
            AppTheme.EMERALD -> Emerald
            AppTheme.AMBER -> Amber
            AppTheme.ROSE -> Rose
            AppTheme.WHITE -> White
        }
    }
}
