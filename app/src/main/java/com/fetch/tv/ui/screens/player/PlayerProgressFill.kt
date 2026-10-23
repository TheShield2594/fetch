package com.fetch.tv.ui.screens.player

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.unit.dp

// Animation state is read during drawing, not composition or measurement.
internal fun Modifier.playerProgressFill(
    progress: () -> Float,
    bufferedProgress: () -> Float,
    bufferedColor: Color,
    playedBrush: Brush
): Modifier = drawBehind {
    val radius = CornerRadius(3.dp.toPx())
    val buffered = bufferedProgress().coerceIn(0f, 1f)
    if (buffered > 0f) {
        drawRoundRect(
            color = bufferedColor.copy(alpha = 0.35f),
            size = Size(size.width * buffered, size.height),
            cornerRadius = radius
        )
    }
    val played = progress().coerceIn(0f, 1f)
    if (played > 0f) {
        // Match the former child Box: its gradient spans the played width.
        inset(left = 0f, top = 0f, right = size.width * (1f - played), bottom = 0f) {
            drawRoundRect(brush = playedBrush, cornerRadius = radius)
        }
    }
}

/**
 * The playhead: the same accent dot as a card's ready mark, centred on the end of the played
 * fill. Drawn over the bar on a parent that isn't clipped, so the dot can stand taller than it.
 */
internal fun Modifier.playerPlayhead(
    progress: () -> Float,
    dotColor: Color,
    ringColor: Color
): Modifier = drawWithContent {
    drawContent()
    val ring = 2.dp.toPx()
    val radius = 6.dp.toPx()
    if (size.width < radius * 2f) return@drawWithContent
    val x = (size.width * progress().coerceIn(0f, 1f)).coerceIn(radius, size.width - radius)
    val center = Offset(x, size.height / 2f)
    drawCircle(color = ringColor, radius = radius, center = center)
    drawCircle(color = dotColor, radius = radius - ring, center = center)
}
