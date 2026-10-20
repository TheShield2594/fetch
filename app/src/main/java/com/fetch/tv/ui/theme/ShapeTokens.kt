package com.fetch.tv.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class FetchRadiusTokens(
    val none: Dp,
    val xxs: Dp,
    val xs: Dp,
    val sm: Dp,
    val md: Dp,
    val lg: Dp,
    val xl: Dp,
    val xxl: Dp,
    val panel: Dp,
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
        lg = 14.dp,
        xl = 16.dp,
        xxl = 20.dp,
        panel = 28.dp,
        full = 999.dp
    )
}

object FetchShapes {
    val tokens = FetchShapeTokens(
        posterCard = RoundedCornerShape(FetchRadii.tokens.md),
        backdropCard = RoundedCornerShape(FetchRadii.tokens.xl),
        collectionCard = RoundedCornerShape(FetchRadii.tokens.xl),
        button = RoundedCornerShape(FetchRadii.tokens.md),
        iconButton = RoundedCornerShape(FetchRadii.tokens.md),
        chip = RoundedCornerShape(FetchRadii.tokens.full),
        badge = RoundedCornerShape(FetchRadii.tokens.xs),
        dialog = RoundedCornerShape(FetchRadii.tokens.xl),
        sidePanel = RoundedCornerShape(FetchRadii.tokens.xxl),
        sidebar = RoundedCornerShape(30.dp),
        navItem = RoundedCornerShape(FetchRadii.tokens.full),
        progress = RoundedCornerShape(FetchRadii.tokens.xxs),
        slider = RoundedCornerShape(FetchRadii.tokens.full),
        field = RoundedCornerShape(FetchRadii.tokens.md),
        menu = RoundedCornerShape(FetchRadii.tokens.lg),
        circle = CircleShape
    )
}
