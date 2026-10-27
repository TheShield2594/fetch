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

    fun border(width: Dp, alpha: Float = 1f): BorderStroke {
        return BorderStroke(width * widthScale, brush(alpha))
    }
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
