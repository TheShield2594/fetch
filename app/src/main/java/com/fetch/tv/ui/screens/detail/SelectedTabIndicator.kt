package com.fetch.tv.ui.screens.detail

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/**
 * Draws a bar along the bottom edge of a selected tab, inset by [inset] on each side, so the
 * selection reads without relying on colour (it survives a greyscale screenshot) while focus is
 * elsewhere. Apply it before the tab label's padding so the bar sits in the padding.
 */
internal fun Modifier.selectedTabIndicator(
    selected: Boolean,
    color: Color,
    thickness: Dp,
    inset: Dp
): Modifier {
    if (!selected) return this
    return this.then(
        Modifier.drawBehind {
            val thicknessPx = thickness.toPx()
            val insetPx = inset.toPx().coerceAtMost(size.width / 2f)
            drawRoundRect(
                color = color,
                topLeft = Offset(insetPx, size.height - thicknessPx),
                size = Size((size.width - insetPx * 2f).coerceAtLeast(0f), thicknessPx),
                cornerRadius = CornerRadius(thicknessPx / 2f)
            )
        }
    )
}
