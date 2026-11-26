package com.fetch.tv.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
 */
@Composable
fun Modifier.focusSafeHorizontalScroll(
    state: ScrollState = rememberScrollState(),
    inset: Dp = FetchTheme.spacing.sm
): Modifier = this
    .bleedHorizontally(inset)
    .horizontalScroll(state)
    .padding(horizontal = inset)

/** Measures [horizontal] wider on each side and places it centred, reporting the original width. */
private fun Modifier.bleedHorizontally(horizontal: Dp): Modifier = layout { measurable, constraints ->
    val extra = horizontal.roundToPx() * 2
    val maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + extra else constraints.maxWidth
    val placeable = measurable.measure(
        constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = maxWidth)
    )
    val width = (placeable.width - extra).coerceAtLeast(0)
    layout(width, placeable.height) { placeable.place(-extra / 2, 0) }
}
