package com.fetch.tv.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import com.fetch.tv.ui.theme.FetchTheme

/**
 * Fetch's media card: the one card implementation for posters, landscape tiles and the action
 * tiles that sit in their rows ("See all", "Load more"). Built on [LightweightCard], so a focus
 * change recomposes the card once rather than on every frame of the focus animation, with the
 * shared [FetchCardDefaults] visuals: the focus ring, the faint resting edge and the card scale and
 * lift. Reduced motion keeps the card its size.
 *
 * Use this rather than tv-material `Card` in Home, Library and Search; a guard test counts the raw
 * `Card(` calls left in each screen directory and only lets the number fall.
 */
@Composable
fun FetchMediaCard(
    onClick: () -> Unit,
    shape: Shape,
    modifier: Modifier = Modifier,
    /** Off for an action tile, whose own fill is the edge. */
    showRestingBorder: Boolean = true,
    /** See [LightweightCard]'s label: read once in place of the card's layers. */
    label: String? = null,
    stateDescription: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val fullMotion = FetchTheme.fullMotion
    LightweightCard(
        onClick = onClick,
        shape = shape,
        containerColor = FetchTheme.colors.BackgroundCard,
        modifier = modifier,
        focusedBorder = FetchCardDefaults.focusedBorder(shape),
        restingBorder = if (showRestingBorder) FetchCardDefaults.restingBorder(shape) else androidx.tv.material3.Border.None,
        focusedScale = if (fullMotion) FetchCardDefaults.FocusedScale else 1f,
        focusedShadow = if (fullMotion) FetchTheme.focus.cardShadow else FetchTheme.spacing.none,
        label = label,
        stateDescription = stateDescription,
        content = content
    )
}
