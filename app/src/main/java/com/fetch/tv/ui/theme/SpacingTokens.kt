package com.fetch.tv.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class FetchScreenSpacing(
    val horizontal: Dp,
    val vertical: Dp,
    val overscanHorizontal: Dp,
    val overscanVertical: Dp
)

@Immutable
data class FetchSpacingTokens(
    val none: Dp,
    val hairline: Dp,
    val xxs: Dp,
    val xs: Dp,
    val sm: Dp,
    val md: Dp,
    val lg: Dp,
    val xl: Dp,
    val xxl: Dp,
    val xxxl: Dp,
    val huge: Dp,
    /** The TV safe area: the one place screen padding is defined. */
    val screen: FetchScreenSpacing
)

object FetchSpacing {
    val tokens = FetchSpacingTokens(
        none = 0.dp,
        hairline = 1.dp,
        xxs = 2.dp,
        xs = 4.dp,
        sm = 8.dp,
        md = 12.dp,
        lg = 16.dp,
        xl = 24.dp,
        xxl = 32.dp,
        xxxl = 48.dp,
        huge = 56.dp,
        screen = FetchScreenSpacing(
            horizontal = 48.dp,
            // 5% of a 960x540dp TV screen on each side, the usual TV safe area.
            vertical = 27.dp,
            overscanHorizontal = 56.dp,
            overscanVertical = 36.dp
        )
    )
}
