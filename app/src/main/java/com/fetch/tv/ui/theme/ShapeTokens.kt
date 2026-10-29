package com.fetch.tv.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
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

/**
 * Fetch's radii. Composables read the current theme's through [FetchTheme.radii]; these are the
 * defaults and what code outside composition uses.
 */
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

    /**
     * Signal's: machined, almost square. Tags and badges are square, cards and buttons keep a
     * 2dp break so they don't look clipped, and panels only soften slightly. Pills stay pills.
     */
    val signal = FetchRadiusTokens(
        none = 0.dp,
        xxs = 0.dp,
        xs = 0.dp,
        sm = 2.dp,
        md = 4.dp,
        xl = 6.dp,
        full = 999.dp
    )
}

object FetchShapes {
    /** The shape set a theme's radii give, the same rule for every theme. */
    fun from(radii: FetchRadiusTokens) = FetchShapeTokens(
        posterCard = RoundedCornerShape(radii.sm),
        backdropCard = RoundedCornerShape(radii.sm),
        collectionCard = RoundedCornerShape(radii.sm),
        button = RoundedCornerShape(radii.sm),
        iconButton = RoundedCornerShape(radii.sm),
        chip = RoundedCornerShape(radii.full),
        badge = RoundedCornerShape(radii.xs),
        dialog = RoundedCornerShape(radii.xl),
        sidePanel = RoundedCornerShape(radii.xl),
        sidebar = RoundedCornerShape(radii.full),
        navItem = RoundedCornerShape(radii.full),
        progress = RoundedCornerShape(radii.xxs),
        slider = RoundedCornerShape(radii.full),
        field = RoundedCornerShape(radii.sm),
        menu = RoundedCornerShape(radii.md),
        circle = CircleShape
    )

    val tokens = from(FetchRadii.tokens)

    /**
     * Signal breaks its own square rule in two places, like a device's hardware keys: the main
     * button is a pill and icon buttons are circles. Chips are square tags.
     */
    val signal = from(FetchRadii.signal).copy(
        button = RoundedCornerShape(FetchRadii.signal.full),
        iconButton = CircleShape,
        chip = RoundedCornerShape(FetchRadii.signal.none)
    )
}

/**
 * The card corner radius for the member's "Corner radius" setting: the size they picked, or with
 * [com.fetch.tv.domain.model.THEME_CARD_CORNER_RADIUS] the current theme's card radius.
 */
@Composable
@ReadOnlyComposable
fun cardCornerRadius(preferenceDp: Int): Dp =
    if (preferenceDp < 0) FetchTheme.radii.sm else preferenceDp.dp
