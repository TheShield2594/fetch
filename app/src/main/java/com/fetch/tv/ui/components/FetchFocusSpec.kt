package com.fetch.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Border
import com.fetch.tv.ui.theme.FetchTheme

/**
 * The one focus spec for Fetch's components: a [FetchTheme.focusRing] ring [ringWidth] wide, and
 * the theme's focus scale. Every component in this layer (buttons, chips, media cards) draws focus
 * through here, so a change to how focus looks is made once.
 *
 * "Increase contrast" is already folded into [FetchTheme.focusRing], which widens the ring.
 */
object FetchFocusSpec {
    /** The focus ring's width before the contrast setting scales it. */
    val ringWidth: Dp
        get() = FetchTheme.strokes.focus

    /** The ring as a stroke, for a modifier `border`. */
    @Composable
    @ReadOnlyComposable
    fun stroke(): BorderStroke = FetchTheme.focusRing.border(ringWidth)

    /** The ring as a tv-material [Border] on [shape]. */
    @Composable
    @ReadOnlyComposable
    fun border(shape: Shape): Border = Border(border = stroke(), shape = shape)

    /** The resting hairline that keeps a control's edge visible on a matching surface. */
    @Composable
    @ReadOnlyComposable
    fun restingBorder(shape: Shape): Border =
        Border(border = BorderStroke(FetchTheme.spacing.hairline, FetchTheme.colors.Border), shape = shape)

    /** How much a focused control grows; 1 with reduced motion. */
    val controlScale: Float
        @Composable @ReadOnlyComposable get() = if (FetchTheme.fullMotion) FetchTheme.focus.scale else 1f

    /** How much a focused media card grows; 1 with reduced motion. */
    val cardScale: Float
        @Composable @ReadOnlyComposable get() = if (FetchTheme.fullMotion) FetchTheme.focus.cardScale else 1f
}
