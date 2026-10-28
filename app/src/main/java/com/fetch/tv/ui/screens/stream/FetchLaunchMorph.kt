package com.fetch.tv.ui.screens.stream

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import com.fetch.tv.ui.components.SourceChipItem
import com.fetch.tv.ui.components.SourceChipStatus
import com.fetch.tv.ui.theme.FetchMotion
import com.fetch.tv.ui.theme.FetchTheme

/** The share of the morph after which the Fetching panel's own content starts to show. */
private const val CONTENT_REVEAL_START = 0.3f

/** The shape stays the accent colour for this share of the morph, so it reads as the button. */
private const val ACCENT_HOLD = 0.4f

/**
 * Fetch's launch motion: the accent Play button that was pressed grows into the Fetching panel.
 * [from] and [to] are in this canvas's coordinates. [progress] is linear time from 0 to 1: the
 * size follows FetchMotion's emphasized curve, the colour holds the accent and then fades to the
 * panel's. Nothing is drawn once it reaches 1.
 */
@Composable
internal fun FetchLaunchMorph(
    from: Rect,
    to: Rect,
    progress: () -> Float,
    modifier: Modifier = Modifier
) {
    val startColor = FetchTheme.colors.Secondary
    val endColor = FetchTheme.colors.BackgroundElevated.copy(alpha = FETCHING_PANEL_ALPHA)
    val endRadius = with(LocalDensity.current) { FetchTheme.radii.md.toPx() }
    Canvas(modifier) {
        val time = progress()
        if (time >= 1f) return@Canvas
        val grow = FetchMotion.tokens.easings.emphasized.transform(time)
        val bounds = lerp(from, to, grow)
        val radius = androidx.compose.ui.util.lerp(from.height / 2f, endRadius, grow)
        drawRoundRect(
            color = lerp(startColor, endColor, ((time - ACCENT_HOLD) / (1f - ACCENT_HOLD)).coerceIn(0f, 1f)),
            topLeft = bounds.topLeft,
            size = bounds.size,
            cornerRadius = CornerRadius(radius)
        )
    }
}

/** How visible the panel's content is at [progress] through the morph: it fades in over the last 70%. */
internal fun launchContentAlpha(progress: Float): Float =
    ((progress - CONTENT_REVEAL_START) / (1f - CONTENT_REVEAL_START)).coerceIn(0f, 1f)

/**
 * The addons in the order their dots should appear: those that answered first, in the order they
 * answered ([answerOrder]), then the ones still loading in their own order. The filled dots grow
 * from the left as answers come in.
 */
internal fun addonsInAnswerOrder(
    chips: List<SourceChipItem>,
    answerOrder: List<String>
): List<SourceChipItem> {
    val answered = chips.filter { it.status != SourceChipStatus.LOADING }
    val rank = answerOrder.withIndex().associate { (index, name) -> name to index }
    return answered.sortedBy { rank[it.name] ?: Int.MAX_VALUE } +
        chips.filter { it.status == SourceChipStatus.LOADING }
}
