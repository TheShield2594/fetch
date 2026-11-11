package com.fetch.tv.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The poster card's defaults. The size people see usually comes from their "Card width" and
 * "Corner radius" settings; these are the values used before those resolve and by skeletons.
 */
@Immutable
data class FetchCardComponentTokens(
    val width: Dp,
    val height: Dp,
    val cornerRadius: Dp,
    val focusedScale: Float
)

@Immutable
data class FetchSidebarComponentTokens(
    /** Width of the icon column while browsing. */
    val collapsedWidth: Dp,
    /** Width while focused, wide enough for the label beside the focused icon. */
    val expandedWidth: Dp,
    /** How far screen content starts from the left edge while the rail floats over it. */
    val contentOffset: Dp,
    val floatingGap: Dp,
    /**
     * Gap between the screen edge and the rail's buttons. At least the TV safe area's 5% of
     * the 540dp screen height (27dp), so no rail element sits where overscan can crop it.
     */
    val floatingInset: Dp,
    val iconSize: Dp
)

@Immutable
data class FetchDialogComponentTokens(
    val width: Dp,
    val contentPadding: Dp,
    val cornerRadius: Dp,
    val actionSpacing: Dp
)

@Immutable
data class FetchSettingsComponentTokens(
    val containerRadius: Dp,
    val secondaryCardRadius: Dp,
    val railItemHeight: Dp,
    val workspacePadding: Dp
)

@Immutable
data class FetchComponentTokens(
    val posterCard: FetchCardComponentTokens,
    val sidebar: FetchSidebarComponentTokens,
    val dialog: FetchDialogComponentTokens,
    val settings: FetchSettingsComponentTokens
)

object FetchComponents {
    val tokens = FetchComponentTokens(
        posterCard = FetchCardComponentTokens(
            width = 126.dp,
            height = 189.dp,
            cornerRadius = FetchRadii.tokens.sm,
            focusedScale = FetchFocus.tokens.cardScale
        ),
        sidebar = FetchSidebarComponentTokens(
            collapsedWidth = 90.dp,
            expandedWidth = 300.dp,
            contentOffset = 72.dp,
            floatingGap = 6.dp,
            floatingInset = 27.dp,
            iconSize = 22.dp
        ),
        dialog = FetchDialogComponentTokens(
            width = 520.dp,
            contentPadding = FetchSpacing.tokens.xl,
            cornerRadius = FetchRadii.tokens.xl,
            actionSpacing = FetchSpacing.tokens.md
        ),
        settings = FetchSettingsComponentTokens(
            containerRadius = FetchRadii.tokens.xl,
            secondaryCardRadius = FetchRadii.tokens.md,
            railItemHeight = 56.dp,
            workspacePadding = 20.dp
        )
    )
}
