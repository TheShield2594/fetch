package com.fetch.tv.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fetch.tv.ui.theme.FetchTheme
import kotlin.math.max
import kotlin.math.roundToInt

/** Gap between two dots, as a share of a dot's diameter. */
private const val DOT_GAP_RATIO = 0.75f

/**
 * Draws [progress] as a row of dots as tall as the modifier, the lit ones in [fillColor]: the
 * Signal theme's stand-in for a progress bar. The dots are two cached paths, so a row of cards
 * redraws with two draw calls each and no extra layout nodes.
 *
 * The first unlit dot is kept on its own path, so [pulseAlpha] can blink it in [fillColor].
 */
internal fun Modifier.dotProgress(
    progress: Float,
    fillColor: Color,
    trackColor: Color,
    pulseAlpha: () -> Float = { 0f }
): Modifier = drawWithCache {
    val diameter = size.height
    val gap = diameter * DOT_GAP_RATIO
    val count = max(1, ((size.width + gap) / (diameter + gap)).toInt())
    // Spread the dots so the row ends flush with both edges, like the bar it replaces.
    val step = if (count > 1) (size.width - diameter) / (count - 1) else 0f
    val clamped = progress.coerceIn(0f, 1f)
    // Any progress at all lights the first dot, so a started episode never reads as unwatched.
    val lit = if (clamped > 0f) (count * clamped).roundToInt().coerceIn(1, count) else 0
    val litPath = Path()
    val trackPath = Path()
    val nextPath = Path()
    repeat(count) { index ->
        val center = Offset(diameter / 2f + index * step, size.height / 2f)
        val target = when {
            index < lit -> litPath
            index == lit -> nextPath
            else -> trackPath
        }
        target.addOval(Rect(center, diameter / 2f))
    }
    onDrawBehind {
        drawPath(trackPath, trackColor)
        drawPath(nextPath, trackColor)
        drawPath(litPath, fillColor)
        val alpha = pulseAlpha()
        if (alpha > 0f) drawPath(nextPath, fillColor.copy(alpha = fillColor.alpha * alpha))
    }
}

/**
 * A full-width row of dots for a count that is still filling in, such as addons that have
 * answered. The next dot to light blinks while [active]; it holds still when motion is reduced.
 */
@Composable
internal fun DotProgressRow(
    progress: Float,
    active: Boolean,
    modifier: Modifier = Modifier,
    dotSize: Dp = 6.dp
) {
    val fill = FetchTheme.colors.TextPrimary
    val track = FetchTheme.colors.TextPrimary.copy(alpha = 0.16f)
    val pulse = if (active && FetchTheme.fullMotion) {
        rememberInfiniteTransition(label = "dotPulse").animateFloat(
            initialValue = 0f,
            targetValue = 0.85f,
            animationSpec = infiniteRepeatable(tween(DOT_PULSE_MS), RepeatMode.Reverse),
            label = "dotPulseAlpha"
        )
    } else {
        null
    }
    val clamped = progress.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(clamped, 0f..1f) }
            .fillMaxWidth()
            .height(dotSize)
            // The pulse is read while drawing, so a blink redraws the row without recomposing it.
            .dotProgress(clamped, fill, track, pulseAlpha = { pulse?.value ?: 0f })
    )
}

private const val DOT_PULSE_MS = 600
