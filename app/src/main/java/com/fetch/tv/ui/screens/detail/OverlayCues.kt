package com.fetch.tv.ui.screens.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import com.fetch.tv.ui.theme.FetchTheme

/**
 * Marks a text panel that takes focus only so it can scroll: [focusBorder] (the shared focus
 * ring, or null while unfocused) around the panel, and a scrollbar on its trailing edge while the
 * text is longer than the panel. Apply it before the panel's padding and verticalScroll, so it
 * wraps the viewport and the scrollbar sits in the padding.
 */
internal fun Modifier.scrollableTextPanel(
    scrollState: ScrollState,
    focusBorder: BorderStroke?,
    shape: Shape,
    thumbColor: Color,
    trackColor: Color
): Modifier {
    val bordered = if (focusBorder != null) this.border(focusBorder, shape) else this
    return bordered.drawWithContent {
        drawContent()
        val maxScroll = scrollState.maxValue.toFloat()
        if (maxScroll <= 0f) return@drawWithContent
        val viewportHeight = size.height
        val trackInset = 8.dp.toPx()
        val trackHeight = (viewportHeight - trackInset * 2f).coerceAtLeast(0f)
        val contentHeight = viewportHeight + maxScroll
        val thumbHeight = (trackHeight * viewportHeight / contentHeight)
            .coerceAtLeast(36.dp.toPx())
            .coerceAtMost(trackHeight)
        val thumbTop = trackInset + (trackHeight - thumbHeight) * (scrollState.value.toFloat() / maxScroll)
        val edgeInset = 6.dp.toPx()
        val x = if (layoutDirection == LayoutDirection.Rtl) edgeInset else size.width - edgeInset
        drawLine(
            color = trackColor,
            start = Offset(x, trackInset),
            end = Offset(x, viewportHeight - trackInset),
            strokeWidth = 1.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawLine(
            color = thumbColor,
            start = Offset(x, thumbTop),
            end = Offset(x, thumbTop + thumbHeight),
            strokeWidth = 2.5.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}

/**
 * Chevrons at the start and end edges of an overlay that pages with Left/Right, shown only while
 * there is a previous or next item. They are decorative: the overlay handles the keys.
 *
 * [followLayoutDirection] false pins previous to the physical left, for overlays that map the
 * Left key to previous in every layout direction. [edgeOffset] pushes each chevron outwards, into
 * a gutter the parent's padding leaves free.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun OverlayPagingCues(
    showPrevious: Boolean,
    showNext: Boolean,
    modifier: Modifier = Modifier,
    followLayoutDirection: Boolean = true,
    iconSize: Dp = FetchTheme.spacing.xxl,
    edgeOffset: Dp = FetchTheme.spacing.none
) {
    if (!showPrevious && !showNext) return
    val tint = FetchTheme.colors.TextSecondary
    val cues: @Composable () -> Unit = {
        Box(modifier = modifier.fillMaxSize()) {
            if (showPrevious) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = -edgeOffset)
                        .size(iconSize)
                )
            }
            if (showNext) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .offset(x = edgeOffset)
                        .size(iconSize)
                )
            }
        }
    }
    if (followLayoutDirection) {
        cues()
    } else {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { cues() }
    }
}
