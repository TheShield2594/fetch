package com.fetch.tv.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.Dp

@Immutable
class FetchFocusRingStyle internal constructor(
    val solidColor: Color,
    /** Multiplies every ring width; 1.5 with "Increase contrast", so 2dp rings draw at 3dp. */
    private val widthScale: Float = 1f
) {
    private val fullBrush = SolidColor(solidColor)

    fun brush(alpha: Float = 1f): Brush {
        val normalizedAlpha = alpha.coerceIn(0f, 1f)
        if (normalizedAlpha == 1f) return fullBrush
        return SolidColor(solidColor.copy(alpha = solidColor.alpha * normalizedAlpha))
    }

    /**
     * The ring, always [FetchStrokeTokens.focus] wide (scaled by "Increase contrast"). There is no
     * width parameter on purpose: one ring width everywhere is what makes focus recognisable.
     */
    fun border(alpha: Float = 1f): BorderStroke = BorderStroke(width, brush(alpha))

    /** The drawn ring width: [FetchStrokeTokens.focus], scaled by "Increase contrast". */
    val width: Dp
        get() = FetchStrokes.tokens.focus * widthScale

    /**
     * True when the ring would melt into a focused control filled with [fill]: under WCAG's 3:1
     * for non-text contrast. The near-white ring on a white Highlight button is 1:1, and on the
     * White, Emerald and Amber accents under 2:1. Such a control draws the ring [innerGap] outside
     * its edge instead, so the page shows as a dark band between the two.
     */
    fun needsInnerGap(fill: Color): Boolean = contrastRatio(solidColor, fill) < MIN_CONTROL_CONTRAST

    /** The dark band between a light control and its ring; see [needsInnerGap]. */
    val innerGap: Dp
        get() = FetchStrokes.tokens.focus
}

fun ThemeColorPalette.accentBrush(): Brush = createThemeBrush(accentGradient)

internal fun createThemeBrush(colors: List<Color>): Brush {
    return if (colors.size == 1) {
        SolidColor(colors.first())
    } else {
        Brush.linearGradient(colors)
    }
}

/**
 * Focus is the same soft white in every theme, so it reads the same over any artwork and never
 * competes with the accent, which is kept for primary actions. "Increase contrast" makes it pure
 * white and half as thick again.
 */
internal fun createFocusRingStyle(highContrast: Boolean = false): FetchFocusRingStyle {
    return if (highContrast) {
        FetchFocusRingStyle(solidColor = FetchPrimitives.white, widthScale = HIGH_CONTRAST_RING_SCALE)
    } else {
        FetchFocusRingStyle(solidColor = FetchPrimitives.neutral100)
    }
}

private const val HIGH_CONTRAST_RING_SCALE = 1.5f
