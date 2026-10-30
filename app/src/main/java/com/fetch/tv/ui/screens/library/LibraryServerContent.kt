package com.fetch.tv.ui.screens.library

import com.fetch.tv.ui.components.CatalogRowsSkeleton
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fetch.tv.R
import com.fetch.tv.data.mediaserver.ServerFailure
import com.fetch.tv.data.mediaserver.messageRes
import com.fetch.tv.domain.model.CatalogRow
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.ui.components.CatalogRowSection
import com.fetch.tv.ui.components.EmptyScreenState
import com.fetch.tv.ui.components.StateAction
import com.fetch.tv.ui.components.PosterCardStyle

internal fun LazyGridScope.libraryServerContent(
    shelves: List<LibraryServerShelf>?,
    onNavigateToSettings: () -> Unit,
    onRetry: () -> Unit,
    posterCardStyle: PosterCardStyle,
    rowInset: Dp,
    focusTarget: Pair<String, Int>?,
    focusedIndexes: Map<String, Int>,
    onItemFocused: (String, Int) -> Unit,
    isWatched: (MetaPreview) -> Boolean,
    onItemClick: (String, String, String) -> Unit,
    onSeeAll: (CatalogRow) -> Unit,
    onItemLongPress: (MetaPreview, String) -> Unit
) {
    when {
        shelves == null -> item(span = { GridItemSpan(maxLineSpan) }) {
            CatalogRowsSkeleton(rowCount = 1, startPadding = 0.dp)
        }

        shelves.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
            EmptyScreenState(
                title = stringResource(R.string.library_servers_empty_title),
                subtitle = stringResource(R.string.library_servers_empty_message),
                icon = Icons.Default.Dns,
                height = 320.dp,
                action = StateAction(stringResource(R.string.library_action_choose_libraries), onNavigateToSettings)
            )
        }

        else -> shelves.forEach { shelf ->
            val row = shelf.row
            val failure = shelf.failure
            if (row != null && row.items.isNotEmpty()) {
                item(key = "server_shelf_${shelf.key}", span = { GridItemSpan(maxLineSpan) }) {
                    CatalogRowSection(
                        catalogRow = row,
                        onItemClick = onItemClick,
                        onSeeAll = { onSeeAll(row) },
                        showSeeAll = row.hasMore,
                        posterCardStyle = posterCardStyle,
                        showAddonName = false,
                        showCatalogTypeSuffix = false,
                        isItemWatched = isWatched,
                        onItemLongPress = onItemLongPress,
                        modifier = Modifier.bleed(rowInset),
                        focusedItemIndex = focusTarget?.takeIf { it.first == shelf.key }?.second ?: -1,
                        restorerFocusedIndex = focusedIndexes[shelf.key] ?: -1,
                        onItemFocused = { index -> onItemFocused(shelf.key, index) }
                    )
                }
            } else if (failure != null) {
                item(key = "server_shelf_error_${shelf.key}", span = { GridItemSpan(maxLineSpan) }) {
                    // Offer the action that addresses the failure: a retry for a server that may
                    // come back, Settings for sign-in, access or library choices.
                    val action = when (failure) {
                        ServerFailure.UNREACHABLE,
                        ServerFailure.FAILED,
                        ServerFailure.INCOMPLETE -> StateAction(stringResource(R.string.action_retry), onRetry)
                        ServerFailure.AUTH_REQUIRED -> StateAction(stringResource(R.string.servers_sign_in_again), onNavigateToSettings)
                        ServerFailure.FORBIDDEN,
                        ServerFailure.NOT_FOUND,
                        ServerFailure.UNSUPPORTED -> StateAction(stringResource(R.string.library_action_server_settings), onNavigateToSettings)
                    }
                    EmptyScreenState(
                        title = stringResource(R.string.library_server_load_failed, shelf.title),
                        subtitle = stringResource(failure.messageRes()),
                        icon = Icons.Default.Dns,
                        // Wrap the content: a fixed 180dp no longer fits the icon, text and button.
                        height = Dp.Unspecified,
                        action = action
                    )
                }
            }
        }
    }
}

private fun Modifier.bleed(horizontal: Dp): Modifier = layout { measurable, constraints ->
    val extra = horizontal.roundToPx() * 2
    val placeable = measurable.measure(
        constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra)
    )
    layout(placeable.width - extra, placeable.height) { placeable.place(-extra / 2, 0) }
}
