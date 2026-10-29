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
    /**
     * The one colour that marks "here" and "now": watch progress, the current page, the
     * wordmark's dot. Most themes use their action colour; Fetch keeps its action white and
     * this red.
     */
    val accent: Color = secondary,
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

/** Fetch red: the wordmark's dot, watch progress and the current page. Nothing else. */
val FetchRed = Color(0xFFE8333A)

object ThemeColors {
    // Each built-in theme is an art direction, not a hue swap: its own tinted ground ramp
    // (background → elevated → card → surface → variant), an accent with a dark label on it,
    // and a focus background in the same family. Focus itself stays white in every theme.
    /**
     * Fetch's own look: near-monochrome. A true-dark ground with neutral greys, white for the
     * main action, and Fetch red as the only colour, for progress, the current page and the
     * wordmark's dot. The artwork brings the colour.
     */
    val Fetch = ThemeColorPalette(
        secondary = Color(0xFFF2F2F2),
        secondaryVariant = Color(0xFFD9D9D9),
        onSecondary = Color(0xFF0A0A0B),
        onSecondaryVariant = Color(0xFF0A0A0B),
        accent = FetchRed,
        focusBackground = Color(0xFF2A2A2E),
        background = Color(0xFF0A0A0B),
        backgroundElevated = Color(0xFF111113),
        backgroundCard = Color(0xFF151517),
        surface = Color(0xFF18181B),
        surfaceVariant = Color(0xFF202023),
        panel = Color(0xFF111113),
        field = Color(0xFF18181B),
        menu = Color(0xFF18181B),
        modal = Color(0xFF111113)
    )

    /** Velvet-seat warm black and a red that still reads on black text. */
    val Crimson = ThemeColorPalette(
        secondary = Color(0xFFE5484D),
        secondaryVariant = Color(0xFFD9434A),
        onSecondary = Color(0xFF000000),
        onSecondaryVariant = Color(0xFF000000),
        focusBackground = Color(0xFF3A2224),
        background = Color(0xFF120B0C),
        backgroundElevated = Color(0xFF1B1112),
        backgroundCard = Color(0xFF211517),
        surface = Color(0xFF241719),
        surfaceVariant = Color(0xFF2C1C1E),
        panel = Color(0xFF1B1112),
        field = Color(0xFF241719),
        menu = Color(0xFF241719),
        modal = Color(0xFF1B1112)
    )

    /** Blue-black night with a lifted blue, so the Play label can be dark. */
    val Ocean = ThemeColorPalette(
        secondary = Color(0xFF4CB5F5),
        secondaryVariant = Color(0xFF2E95D6),
        onSecondary = Color(0xFF0A0F14),
        onSecondaryVariant = Color(0xFF0A0F14),
        focusBackground = Color(0xFF1F3242),
        background = Color(0xFF0A0F14),
        backgroundElevated = Color(0xFF101820),
        backgroundCard = Color(0xFF131D27),
        surface = Color(0xFF16212C),
        surfaceVariant = Color(0xFF1A2733),
        panel = Color(0xFF101820),
        field = Color(0xFF16212C),
        menu = Color(0xFF16212C),
        modal = Color(0xFF101820)
    )

    /** Ink-violet ground and a soft lavender accent. */
    val Violet = ThemeColorPalette(
        secondary = Color(0xFFA78BFA),
        secondaryVariant = Color(0xFF8B6CF0),
        onSecondary = Color(0xFF0E0B14),
        onSecondaryVariant = Color(0xFF0E0B14),
        focusBackground = Color(0xFF2F2645),
        background = Color(0xFF0E0B14),
        backgroundElevated = Color(0xFF15111E),
        backgroundCard = Color(0xFF1A1524),
        surface = Color(0xFF1C1727),
        surfaceVariant = Color(0xFF241D33),
        panel = Color(0xFF15111E),
        field = Color(0xFF1C1727),
        menu = Color(0xFF1C1727),
        modal = Color(0xFF15111E)
    )

    /** Green-room black with a clean signal green. */
    val Emerald = ThemeColorPalette(
        secondary = Color(0xFF3DD68C),
        secondaryVariant = Color(0xFF26B873),
        onSecondary = Color(0xFF0B110E),
        onSecondaryVariant = Color(0xFF0B110E),
        focusBackground = Color(0xFF213A2D),
        background = Color(0xFF0B110E),
        backgroundElevated = Color(0xFF111A15),
        backgroundCard = Color(0xFF142019),
        surface = Color(0xFF16231C),
        surfaceVariant = Color(0xFF1B2A22),
        panel = Color(0xFF111A15),
        field = Color(0xFF16231C),
        menu = Color(0xFF16231C),
        modal = Color(0xFF111A15)
    )

    /** Tungsten warmth: brown-black ground, a marquee-bulb accent. */
    val Amber = ThemeColorPalette(
        secondary = Color(0xFFF5A524),
        secondaryVariant = Color(0xFFD88A0E),
        onSecondary = Color(0xFF120F0B),
        onSecondaryVariant = Color(0xFF120F0B),
        focusBackground = Color(0xFF3A2F20),
        background = Color(0xFF120F0B),
        backgroundElevated = Color(0xFF1B1711),
        backgroundCard = Color(0xFF201B14),
        surface = Color(0xFF231D16),
        surfaceVariant = Color(0xFF2C251B),
        panel = Color(0xFF1B1711),
        field = Color(0xFF231D16),
        menu = Color(0xFF231D16),
        modal = Color(0xFF1B1711)
    )

    /** Plum-black ground with a rose that dark text sits on. */
    val Rose = ThemeColorPalette(
        secondary = Color(0xFFF06A9E),
        secondaryVariant = Color(0xFFD94E85),
        onSecondary = Color(0xFF130B10),
        onSecondaryVariant = Color(0xFF130B10),
        focusBackground = Color(0xFF3D2333),
        background = Color(0xFF130B10),
        backgroundElevated = Color(0xFF1C1118),
        backgroundCard = Color(0xFF22151D),
        surface = Color(0xFF25171F),
        surfaceVariant = Color(0xFF2E1C27),
        panel = Color(0xFF1C1118),
        field = Color(0xFF25171F),
        menu = Color(0xFF25171F),
        modal = Color(0xFF1C1118)
    )

    val White = ThemeColorPalette(
        secondary = FetchPrimitives.neutral100,
        secondaryVariant = FetchPrimitives.neutral200,
        onSecondary = FetchPrimitives.neutral925,
        onSecondaryVariant = FetchPrimitives.neutral925,
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
            AppTheme.FETCH -> Fetch
        }
    }
}
