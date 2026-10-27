package com.fetch.tv.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One rule for corners, by what the shape is:
 * - [xxs] 2dp: progress bars and other hairline shapes.
 * - [xs] 4dp: chips, badges and tags inside a card or line of text.
 * - [sm] 8dp: cards, buttons, fields and tiles.
 * - [md] 12dp: menus, list rows and small panels inside a screen.
 * - [xl] 16dp: panels, dialogs, side panels and sheets.
 * - [full]: pills and circles.
 */
@Immutable
data class FetchRadiusTokens(
    val none: Dp,
    val xxs: Dp,
    val xs: Dp,
    val sm: Dp,
    val md: Dp,
    val xl: Dp,
    val full: Dp
)

@Immutable
data class FetchShapeTokens(
    val posterCard: Shape,
    val backdropCard: Shape,
    val collectionCard: Shape,
    val button: Shape,
    val iconButton: Shape,
    val chip: Shape,
    val badge: Shape,
    val dialog: Shape,
    val sidePanel: Shape,
    val sidebar: Shape,
    val navItem: Shape,
    val progress: Shape,
    val slider: Shape,
    val field: Shape,
    val menu: Shape,
    val circle: Shape
)

object FetchRadii {
    val tokens = FetchRadiusTokens(
        none = 0.dp,
        xxs = 2.dp,
        xs = 4.dp,
        sm = 8.dp,
        md = 12.dp,
        xl = 16.dp,
        full = 999.dp
    )
}

object FetchShapes {
    val tokens = FetchShapeTokens(
        posterCard = RoundedCornerShape(FetchRadii.tokens.sm),
        backdropCard = RoundedCornerShape(FetchRadii.tokens.sm),
        collectionCard = RoundedCornerShape(FetchRadii.tokens.sm),
        button = RoundedCornerShape(FetchRadii.tokens.sm),
        iconButton = RoundedCornerShape(FetchRadii.tokens.sm),
        chip = RoundedCornerShape(FetchRadii.tokens.full),
        badge = RoundedCornerShape(FetchRadii.tokens.xs),
        dialog = RoundedCornerShape(FetchRadii.tokens.xl),
        sidePanel = RoundedCornerShape(FetchRadii.tokens.xl),
        sidebar = RoundedCornerShape(FetchRadii.tokens.full),
        navItem = RoundedCornerShape(FetchRadii.tokens.full),
        progress = RoundedCornerShape(FetchRadii.tokens.xxs),
        slider = RoundedCornerShape(FetchRadii.tokens.full),
        field = RoundedCornerShape(FetchRadii.tokens.sm),
        menu = RoundedCornerShape(FetchRadii.tokens.md),
        circle = CircleShape
    )
}
