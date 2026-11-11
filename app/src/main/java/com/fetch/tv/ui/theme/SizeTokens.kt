package com.fetch.tv.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class FetchIconSizes(
    /** Inline glyphs beside a label: check marks, row chevrons, status icons. */
    val sm: Dp,
    /** A control's own icon, such as the glyph inside an icon button. */
    val md: Dp
)

/**
 * How big a focusable control is. Sizes come from here rather than from [FetchSpacingTokens], so
 * a gap and a control's height can change independently.
 */
@Immutable
data class FetchControlSizes(
    /** Dense lists and inline actions. */
    val compact: Dp,
    /** Every ordinary button and field. */
    val default: Dp,
    /** The square an icon-only button occupies. */
    val icon: Dp
)

@Immutable
data class FetchSettingsSizes(
    val railWidth: Dp,
    val rowMinHeight: Dp
)

@Immutable
data class FetchSizeTokens(
    val icons: FetchIconSizes,
    val controls: FetchControlSizes,
    val settings: FetchSettingsSizes
)

object FetchSizes {
    val tokens = FetchSizeTokens(
        icons = FetchIconSizes(
            sm = 18.dp,
            md = 24.dp
        ),
        controls = FetchControlSizes(
            compact = 40.dp,
            default = 52.dp,
            icon = 44.dp
        ),
        settings = FetchSettingsSizes(
            railWidth = 220.dp,
            rowMinHeight = 64.dp
        )
    )
}
