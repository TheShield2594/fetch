package com.fetch.tv.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/** WCAG contrast ratio between two opaque colours, from 1:1 to 21:1. */
fun contrastRatio(a: Color, b: Color): Float {
    val lighter = maxOf(a.luminance(), b.luminance()) + 0.05f
    val darker = minOf(a.luminance(), b.luminance()) + 0.05f
    return lighter / darker
}

/**
 * This colour, mixed towards white in small steps until it has at least [minRatio] contrast with
 * [background]. A colour that already passes comes back unchanged. Only for dark backgrounds.
 */
fun Color.liftedForContrast(background: Color, minRatio: Float): Color {
    if (contrastRatio(this, background) >= minRatio) return this
    var step = 1
    while (step <= LIFT_STEPS) {
        val lifted = lerp(this, Color.White, step.toFloat() / LIFT_STEPS)
        if (contrastRatio(lifted, background) >= minRatio) return lifted
        step++
    }
    return Color.White
}

/** WCAG's minimum for a control's fill against what surrounds it (non-text contrast). */
const val MIN_CONTROL_CONTRAST = 3f

private const val LIFT_STEPS = 40
