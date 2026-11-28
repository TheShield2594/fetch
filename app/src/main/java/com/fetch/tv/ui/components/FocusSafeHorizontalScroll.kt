package com.fetch.tv.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import com.fetch.tv.ui.theme.FetchTheme

/**
 * [horizontalScroll] for a row of focusable chips or buttons, without cutting off the focused one.
 *
 * A scrolling row clips to its bounds, so the first (or last) control loses the edge of its focus
 * ring and focus growth: on TV it reads as the sidebar or the title beside the row overlapping it.
 * This widens the clip by [inset] on each side, into the screen padding or gap the row already
 * sits in, and pads the content back by the same amount, so nothing moves and the row takes the
 * same space in its parent.
 *
 * The default covers a focused pill of up to about 200dp: tv-material's 1.1 focus scale grows it
 * by a twentieth of its width on each side, and the ring and its gap add a few dp more. 8dp was
 * too little, and still cut the left edge of a focused Library "Watchlist" tab.
 */
@Composable
fun Modifier.focusSafeHorizontalScroll(
    state: ScrollState = rememberScrollState(),
    inset: Dp = FetchTheme.spacing.lg,
    /**
     * Fades the row out over [inset] at a side with more to scroll to, so a control cut off at
     * the screen edge reads as "more this way" rather than as clipped. Off for rows that sit
     * between other content, where a fade would look like a gap.
     */
    fadeEdges: Boolean = false
): Modifier = this
    .bleed(horizontal = inset, vertical = if (fadeEdges) inset else Dp.Hairline)
    .then(if (fadeEdges) Modifier.fadeScrolledEdges(state, inset, FetchTheme.colors.Background) else Modifier)
    .horizontalScroll(state)
    .padding(horizontal = inset, vertical = if (fadeEdges) inset else Dp.Hairline)

/**
 * The fade is drawn into an offscreen layer, which clips to its bounds; the caller bleeds the row
 * vertically too, so focus rings above and below the controls aren't cut.
 *
 * The mask keeps content where [opaque] is drawn and hides it where the gradient is clear. Only
 * its alpha counts: any opaque colour does, and the page background is one.
 */
private fun Modifier.fadeScrolledEdges(state: ScrollState, fade: Dp, opaque: Color): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val width = fade.toPx().coerceAtMost(size.width / 2)
        if (width <= 0f) return@drawWithContent
        if (state.canScrollBackward) {
            drawRect(
                brush = Brush.horizontalGradient(0f to Color.Transparent, 1f to opaque, startX = 0f, endX = width),
                size = Size(width, size.height),
                blendMode = BlendMode.DstIn
            )
        }
        if (state.canScrollForward) {
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to opaque,
                    1f to Color.Transparent,
                    startX = size.width - width,
                    endX = size.width
                ),
                topLeft = Offset(size.width - width, 0f),
                size = Size(width, size.height),
                blendMode = BlendMode.DstIn
            )
        }
    }

/**
 * Measures the content [horizontal] wider on each side (and [vertical] taller) and places it
 * centred, reporting the original size.
 */
private fun Modifier.bleed(horizontal: Dp, vertical: Dp): Modifier = layout { measurable, constraints ->
    val extraX = horizontal.roundToPx() * 2
    val extraY = vertical.roundToPx() * 2
    val maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + extraX else constraints.maxWidth
    val maxHeight = if (constraints.hasBoundedHeight) constraints.maxHeight + extraY else constraints.maxHeight
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = constraints.minWidth + extraX,
            maxWidth = maxWidth,
            minHeight = constraints.minHeight + extraY,
            maxHeight = maxHeight
        )
    )
    val width = (placeable.width - extraX).coerceAtLeast(0)
    val height = (placeable.height - extraY).coerceAtLeast(0)
    layout(width, height) { placeable.place(-extraX / 2, -extraY / 2) }
}
