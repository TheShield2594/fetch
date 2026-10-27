package com.fetch.tv.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class FetchCardComponentTokens(
    val width: Dp,
    val height: Dp,
    val cornerRadius: Dp,
    val contentPadding: Dp,
    val focusedBorderWidth: Dp,
    val focusedScale: Float
)

@Immutable
data class FetchRowComponentTokens(
    val horizontalPadding: Dp,
    val verticalPadding: Dp,
    val itemSpacing: Dp,
    val titleBottomSpacing: Dp
)

@Immutable
data class FetchSidebarComponentTokens(
    /** Width of the icon column while browsing. */
    val collapsedWidth: Dp,
    /** Width while focused, wide enough for the label beside the focused icon. */
    val expandedWidth: Dp,
    val floatingButtonSize: Dp,
    val floatingGap: Dp,
    val floatingInset: Dp,
    val iconSize: Dp
)

@Immutable
data class FetchDialogComponentTokens(
    val maxWidth: Dp,
    val contentPadding: Dp,
    val cornerRadius: Dp,
    val actionSpacing: Dp
)

@Immutable
data class FetchPlayerComponentTokens(
    val overlayHorizontalPadding: Dp,
    val overlayVerticalPadding: Dp,
    val controlSize: Dp,
    val sidePanelWidth: Dp,
    val railWidth: Dp,
    val progressHeight: Dp
)

@Immutable
data class FetchSettingsComponentTokens(
    val containerRadius: Dp,
    val secondaryCardRadius: Dp,
    val railItemHeight: Dp,
    val workspacePadding: Dp,
    val rowGap: Dp
)

@Immutable
data class FetchComponentTokens(
    val posterCard: FetchCardComponentTokens,
    val backdropCard: FetchCardComponentTokens,
    val collectionCard: FetchCardComponentTokens,
    val continueWatchingCard: FetchCardComponentTokens,
    val episodeCard: FetchCardComponentTokens,
    val row: FetchRowComponentTokens,
    val sidebar: FetchSidebarComponentTokens,
    val dialog: FetchDialogComponentTokens,
    val sidePanel: FetchDialogComponentTokens,
    val settings: FetchSettingsComponentTokens,
    val player: FetchPlayerComponentTokens,
    val buttonHeight: Dp,
    val chipHeight: Dp,
    val badgeHeight: Dp,
    val skeletonCornerRadius: Dp
)

object FetchComponents {
    val tokens = FetchComponentTokens(
        posterCard = FetchCardComponentTokens(
            width = 126.dp,
            height = 189.dp,
            cornerRadius = 8.dp,
            contentPadding = 8.dp,
            focusedBorderWidth = 2.dp,
            focusedScale = 1.04f
        ),
        backdropCard = FetchCardComponentTokens(
            width = 320.dp,
            height = 180.dp,
            cornerRadius = 8.dp,
            contentPadding = 16.dp,
            focusedBorderWidth = 2.dp,
            focusedScale = 1.04f
        ),
        collectionCard = FetchCardComponentTokens(
            width = 320.dp,
            height = 180.dp,
            cornerRadius = 8.dp,
            contentPadding = 16.dp,
            focusedBorderWidth = 2.dp,
            focusedScale = 1.04f
        ),
        continueWatchingCard = FetchCardComponentTokens(
            width = 260.dp,
            height = 146.dp,
            cornerRadius = 8.dp,
            contentPadding = 12.dp,
            focusedBorderWidth = 2.dp,
            focusedScale = 1.04f
        ),
        episodeCard = FetchCardComponentTokens(
            width = 320.dp,
            height = 207.dp,
            cornerRadius = 8.dp,
            contentPadding = 16.dp,
            focusedBorderWidth = 2.dp,
            focusedScale = 1.04f
        ),
        row = FetchRowComponentTokens(
            horizontalPadding = 48.dp,
            verticalPadding = 6.dp,
            itemSpacing = 12.dp,
            titleBottomSpacing = 14.dp
        ),
        sidebar = FetchSidebarComponentTokens(
            collapsedWidth = 72.dp,
            expandedWidth = 300.dp,
            floatingButtonSize = 44.dp,
            floatingGap = 6.dp,
            floatingInset = 9.dp,
            iconSize = 22.dp
        ),
        dialog = FetchDialogComponentTokens(
            maxWidth = 720.dp,
            contentPadding = 24.dp,
            cornerRadius = 16.dp,
            actionSpacing = 12.dp
        ),
        sidePanel = FetchDialogComponentTokens(
            maxWidth = 420.dp,
            contentPadding = 20.dp,
            cornerRadius = 16.dp,
            actionSpacing = 12.dp
        ),
        settings = FetchSettingsComponentTokens(
            containerRadius = 16.dp,
            secondaryCardRadius = 12.dp,
            railItemHeight = 56.dp,
            workspacePadding = 20.dp,
            rowGap = 16.dp
        ),
        player = FetchPlayerComponentTokens(
            overlayHorizontalPadding = 52.dp,
            overlayVerticalPadding = 36.dp,
            controlSize = 44.dp,
            sidePanelWidth = 360.dp,
            railWidth = 280.dp,
            progressHeight = 4.dp
        ),
        buttonHeight = 52.dp,
        chipHeight = 32.dp,
        badgeHeight = 20.dp,
        skeletonCornerRadius = 10.dp
    )
}
