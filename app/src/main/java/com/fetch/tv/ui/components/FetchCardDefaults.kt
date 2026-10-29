package com.fetch.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Border
import com.fetch.tv.ui.theme.FetchComponents
import com.fetch.tv.ui.theme.FetchStrokes
import com.fetch.tv.ui.theme.FetchTheme

/**
 * What every media card shares, so a title looks and reacts the same on Home, in Search, on the
 * detail page and in the episode list: one focus ring, one focus scale and one progress bar.
 * Cards keep their own layouts and behaviour; only these visuals come from here.
 */
object FetchCardDefaults {
    /** Width of the accent ring on a focused card. */
    val FocusedBorderWidth: Dp get() = FetchStrokes.tokens.focus

    /** How much a focused card grows. Cards that widen on focus instead don't also scale. */
    val FocusedScale: Float get() = FetchComponents.tokens.posterCard.focusedScale

    /** Height of the watch progress bar along a card's bottom edge. */
    val ProgressBarHeight: Dp get() = FetchStrokes.tokens.progress

    /** The progress bar's track, drawn over artwork. */
    val ProgressTrackColor: Color = Color.White.copy(alpha = 0.18f)

    /**
     * Faint edge on a card at rest, so dark artwork doesn't melt into a dark background. White at
     * low alpha rather than a grey, so over bright artwork it reads as an edge, not a frame.
     */
    val RestingBorderColor: Color = Color.White.copy(alpha = 0.06f)

    fun restingBorder(shape: Shape): Border = Border(
        border = androidx.compose.foundation.BorderStroke(FetchStrokes.tokens.hairline, RestingBorderColor),
        shape = shape
    )

    @Composable
    @ReadOnlyComposable
    fun focusedBorder(shape: Shape): Border = Border(
        border = FetchTheme.focusRing.border(FocusedBorderWidth),
        shape = shape
    )
}

/**
 * Draws a card's watch progress: a rounded track with a [fillColor] fill. Drawn rather than
 * composed, so a row of cards doesn't add two layout nodes per card.
 */
fun Modifier.fetchCardProgress(progress: Float, fillColor: Color): Modifier = this
    .fillMaxWidth()
    .height(FetchCardDefaults.ProgressBarHeight)
    .drawWithCache {
        val radius = CornerRadius(size.height / 2f)
        val fillWidth = size.width * progress.coerceIn(0f, 1f)
        onDrawBehind {
            drawRoundRect(color = FetchCardDefaults.ProgressTrackColor, cornerRadius = radius)
            if (fillWidth > 0f) {
                drawRoundRect(color = fillColor, size = Size(fillWidth, size.height), cornerRadius = radius)
            }
        }
    }
