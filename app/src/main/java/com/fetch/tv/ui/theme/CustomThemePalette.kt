package com.fetch.tv.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.fetch.tv.domain.model.CustomThemeColors

/**
 * Builds a palette from the member's colours. The surfaces take a faint tint of the chosen accent;
 * the accent itself is then lifted towards white wherever it has less than 3:1 contrast with the
 * cards it sits on, so a very dark pick still gives a Play button you can see. The label on the
 * accent is black or white, whichever reads better on every colour behind it; a gradient on which
 * neither reaches 4.5:1 (the editor won't save one, but an older or synced one can arrive) is
 * drawn as its middle colour alone. The stored colours are never changed; see
 * [CustomThemeColors.adjustedForContrast] and [CustomThemeColors.meetsContrastMinimum].
 */
fun CustomThemeColors.toColorPalette(): ThemeColorPalette {
    val parts = contrastParts()
    val brushColors = if (parts.labelContrast >= MIN_TEXT_CONTRAST) parts.brush else listOf(parts.accent)
    val gradient = parts.lifted
    val surface = parts.surface

    return ThemeColorPalette(
        secondary = parts.accent,
        secondaryVariant = gradient[2],
        onSecondary = bestLabelOn(brushColors),
        onSecondaryVariant = bestLabelOn(listOf(gradient[2])),
        accentGradient = brushColors,
        focusBackground = surface(0xFF242424, 0.18f),
        background = surface(0xFF0C0D0F, 0.025f),
        backgroundElevated = surface(0xFF17191D, 0.045f),
        backgroundCard = parts.backgroundCard,
        surface = surface(0xFF1C1F23, 0.05f),
        surfaceVariant = surface(0xFF292E35, 0.06f),
        panel = surface(0xFF17191D, 0.045f),
        field = surface(0xFF24282E, 0.055f),
        menu = surface(0xFF1C1F23, 0.05f),
        modal = surface(0xFF17191D, 0.045f)
    )
}

/** True when [toColorPalette] had to lighten one of the picked colours to keep it visible. */
val CustomThemeColors.adjustedForContrast: Boolean
    get() {
        val parts = contrastParts()
        val picked = colors.map { Color(it or 0xFF000000.toInt()) }
        val used = if (isSolid) listOf(picked[1]) else picked
        return used != parts.brush
    }

/**
 * The weakest contrast between a Play label (black or white, whichever is better) and any colour
 * of the accent brush these colours draw, after the lift that keeps the accent visible.
 */
val CustomThemeColors.labelContrast: Float
    get() = contrastParts().labelContrast

/**
 * The custom editor's contrast check, at the thresholds every built-in theme is held to: the
 * label on the accent reaches 4.5:1 on every colour behind it, and the accent reaches 3:1 against
 * the cards it sits on. The editor won't save colours that fail.
 */
val CustomThemeColors.meetsContrastMinimum: Boolean
    get() {
        val parts = contrastParts()
        return parts.labelContrast >= MIN_TEXT_CONTRAST &&
            parts.brush.all { contrastRatio(it, parts.backgroundCard) >= MIN_CONTROL_CONTRAST }
    }

private class CustomContrastParts(
    val surface: (Long, Float) -> Color,
    val backgroundCard: Color,
    /** The three picked colours, each lifted to 3:1 against [backgroundCard]. */
    val lifted: List<Color>,
    val accent: Color,
    /** What the accent brush would draw: [accent] alone for a solid theme, else [lifted]. */
    val brush: List<Color>,
    val labelContrast: Float
)

private fun CustomThemeColors.contrastParts(): CustomContrastParts {
    val picked = colors.map { Color(it or 0xFF000000.toInt()) }
    val tint = picked[1]
    val surface = { base: Long, amount: Float -> lerp(Color(base), tint, amount) }
    val backgroundCard = surface(0xFF20242A, 0.06f)
    val lifted = picked.map { it.liftedForContrast(backgroundCard, MIN_CONTROL_CONTRAST) }
    val accent = lifted[1]
    val brush = if (isSolid) listOf(accent) else lifted
    val label = bestLabelOn(brush)
    return CustomContrastParts(
        surface = surface,
        backgroundCard = backgroundCard,
        lifted = lifted,
        accent = accent,
        brush = brush,
        labelContrast = brush.minOf { contrastRatio(label, it) }
    )
}

/** Black or white, whichever has the higher worst-case contrast across [fills]. */
private fun bestLabelOn(fills: List<Color>): Color {
    val onBlack = fills.minOf { contrastRatio(Color.Black, it) }
    val onWhite = fills.minOf { contrastRatio(Color.White, it) }
    return if (onBlack > onWhite) Color.Black else Color.White
}
