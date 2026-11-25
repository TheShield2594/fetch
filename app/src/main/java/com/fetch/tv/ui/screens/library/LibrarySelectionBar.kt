package com.fetch.tv.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.layout.onSizeChanged
import com.fetch.tv.ui.screens.detail.requestFocusAfterFrames
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.domain.model.LibraryListTab
import com.fetch.tv.domain.model.localizedTitle
import com.fetch.tv.ui.components.FetchButton
import com.fetch.tv.ui.components.FetchButtonStyle
import com.fetch.tv.ui.components.FetchDialog
import com.fetch.tv.ui.theme.FetchTheme

/** "Select" in the filter row; "Done" while choosing, so the same press ends it. */
@Composable
internal fun LibrarySelectButton(
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FetchButton(
        onClick = onClick,
        enabled = enabled || active,
        selected = active,
        modifier = modifier
    ) {
        Text(stringResource(if (active) R.string.library_selection_done else R.string.library_selection_start))
    }
}

/**
 * The bar under the grid while choosing titles: how many are chosen, then Remove, Mark watched
 * and Move (or Add) to list. Menu on a card jumps here; Up goes back to the card.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun LibrarySelectionBar(
    count: Int,
    showMarkWatched: Boolean,
    listActionLabel: String?,
    pending: Boolean,
    focusRequester: FocusRequester,
    upFocusRequester: FocusRequester?,
    onRemove: () -> Unit,
    onMarkWatched: () -> Unit,
    onListAction: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val barDescription = stringResource(R.string.library_selection_bar_description)
    val actionsEnabled = count > 0 && !pending
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(FetchTheme.colors.BackgroundElevated)
            .padding(
                horizontal = FetchTheme.spacing.screen.horizontal,
                vertical = FetchTheme.spacing.md
            )
            .semantics { paneTitle = barDescription },
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xs)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .focusRestorer { focusRequester }
                .then(if (upFocusRequester != null) Modifier.focusProperties { up = upFocusRequester } else Modifier),
            horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = pluralStringResource(R.plurals.library_selection_count, count, count),
                style = MaterialTheme.typography.titleSmall,
                color = FetchTheme.colors.TextPrimary,
                modifier = Modifier
                    .widthIn(min = FetchTheme.spacing.huge)
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
            Spacer(Modifier.widthIn(min = FetchTheme.spacing.sm))
            // Remove leads and takes the focus: it's what a long list is usually cleaned up with,
            // and it asks before doing anything.
            FetchButton(
                onClick = onRemove,
                enabled = actionsEnabled,
                modifier = Modifier.focusRequester(focusRequester)
            ) {
                Text(stringResource(R.string.library_selection_remove))
            }
            if (showMarkWatched) {
                FetchButton(onClick = onMarkWatched, enabled = actionsEnabled) {
                    Text(stringResource(R.string.library_selection_mark_watched))
                }
            }
            if (listActionLabel != null) {
                FetchButton(onClick = onListAction, enabled = actionsEnabled) {
                    Text(listActionLabel)
                }
            }
            FetchButton(onClick = onDone) {
                Text(stringResource(R.string.library_selection_done))
            }
        }
        Text(
            text = stringResource(R.string.library_selection_hint),
            style = MaterialTheme.typography.labelMedium,
            color = FetchTheme.colors.TextSecondary
        )
    }
}

/** Asks before a bulk removal, Cancel first and focused, as the list delete does. */
@Composable
internal fun LibraryBulkRemoveDialog(
    count: Int,
    placeName: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val cancelFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { cancelFocusRequester.requestFocus() }
    }
    FetchDialog(
        onDismiss = onCancel,
        title = pluralStringResource(R.plurals.library_selection_remove_confirm_title, count, count),
        subtitle = stringResource(R.string.library_selection_remove_confirm_subtitle, placeName)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)
        ) {
            FetchButton(
                onClick = onCancel,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(cancelFocusRequester)
            ) {
                Text(stringResource(R.string.action_cancel))
            }
            FetchButton(
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                style = FetchButtonStyle.Destructive
            ) {
                Text(stringResource(R.string.library_selection_remove))
            }
        }
    }
}

/** The list a bulk Move / Add to list puts the chosen titles on. */
@Composable
internal fun LibraryBulkListPicker(
    title: String,
    destinations: List<LibraryListTab>,
    onPick: (LibraryListTab) -> Unit,
    onDismiss: () -> Unit
) {
    val options = destinations.map { tab ->
        com.fetch.tv.ui.screens.settings.SettingsPickerOption(tab.key, tab.localizedTitle())
    }
    com.fetch.tv.ui.screens.settings.SettingsSingleChoiceDialog(
        title = title,
        options = options,
        selectedValue = "",
        onOptionSelected = { key -> destinations.firstOrNull { it.key == key }?.let(onPick) },
        onDismiss = onDismiss
    )
}

/**
 * Selection mode's bar and dialogs, over the bottom of the Library. Each action exits selection
 * mode and puts focus on a card that is still there: the one last focused, or after a removal
 * the nearest one left (else the tab row).
 */
@Composable
internal fun LibrarySelectionOverlay(
    tab: LibraryTab,
    selection: LibrarySelectionState,
    uiState: LibraryUiState,
    selectionKeys: List<String>,
    historyMonths: List<LibraryHistoryMonth>?,
    inProgressItems: List<LibraryActivityItem>?,
    anchorKey: String?,
    barFocusRequester: FocusRequester,
    requesterFor: (String) -> FocusRequester?,
    fallbackFocusRequester: FocusRequester,
    showRemoveConfirm: Boolean,
    onShowRemoveConfirm: (Boolean) -> Unit,
    showListPicker: Boolean,
    onShowListPicker: (Boolean) -> Unit,
    onBarHeightChanged: (Int) -> Unit,
    onExit: () -> Unit,
    onBulkAction: (List<LibraryBulkTarget>, LibraryBulkOperation, String?) -> Unit
) {
    val scope = rememberCoroutineScope()
    val savedGrid = tab.listScope != null
    val currentListKey = if (savedGrid) libraryCurrentListKey(uiState.selectedListKey) else null
    val localLibraryTitle = stringResource(R.string.trakt_library_source_fetch)
    val destinations = remember(uiState.listTabs, currentListKey, localLibraryTitle) {
        libraryBulkDestinations(uiState.listTabs, currentListKey, localLibraryTitle)
    }
    val destinationTitles = destinations.associate { it.key to it.localizedTitle() }
    val listActionLabel = when {
        destinations.isEmpty() -> null
        savedGrid -> stringResource(R.string.library_selection_move_to_list)
        else -> stringResource(R.string.library_selection_add_to_list)
    }
    val placeName = when (tab) {
        LibraryTab.HISTORY -> stringResource(R.string.library_tab_history)
        LibraryTab.IN_PROGRESS -> stringResource(R.string.library_tab_in_progress)
        else -> uiState.listTabs.firstOrNull { it.key == currentListKey }?.localizedTitle()
            ?: stringResource(R.string.library_tab_watchlist)
    }

    fun targets(): List<LibraryBulkTarget> = if (savedGrid) {
        uiState.visibleItems.filter { selection.isSelected("${it.type}:${it.id}") }.map { it.toBulkTarget() }
    } else {
        val items = if (tab == LibraryTab.HISTORY) historyMonths.orEmpty().flatMap { it.items } else inProgressItems.orEmpty()
        val keyOf: (LibraryActivityItem) -> String = if (tab == LibraryTab.HISTORY) ::historyFocusKey else ::inProgressFocusKey
        items.filter { selection.isSelected(keyOf(it)) }.map { it.toBulkTarget() }
    }

    fun finish(removes: Boolean, run: (List<LibraryBulkTarget>) -> Unit) {
        val chosen = targets()
        val focusKey = if (removes) {
            libraryFocusKeyAfterRemoval(selectionKeys, selection.selectedKeys, anchorKey)
        } else {
            anchorKey?.takeIf { it in selectionKeys }
        }
        onShowRemoveConfirm(false)
        onShowListPicker(false)
        onExit()
        run(chosen)
        scope.launch {
            val requester = focusKey?.let(requesterFor)
            if (requester?.requestFocusAfterFrames(frames = 1) != true) fallbackFocusRequester.requestFocusAfterFrames(frames = 0)
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        LibrarySelectionBar(
            count = selection.count,
            // History is already watched.
            showMarkWatched = tab != LibraryTab.HISTORY,
            listActionLabel = listActionLabel,
            pending = uiState.bulkPending,
            focusRequester = barFocusRequester,
            upFocusRequester = anchorKey?.takeIf { it in selectionKeys }?.let(requesterFor),
            onRemove = { onShowRemoveConfirm(true) },
            onMarkWatched = {
                finish(removes = false) { chosen -> onBulkAction(chosen, LibraryBulkOperation.MarkWatched, null) }
            },
            onListAction = { onShowListPicker(true) },
            onDone = {
                onExit()
                scope.launch {
                    val requester = anchorKey?.takeIf { it in selectionKeys }?.let(requesterFor)
                    if (requester?.requestFocusAfterFrames(frames = 1) != true) fallbackFocusRequester.requestFocusAfterFrames(frames = 0)
                }
            },
            modifier = Modifier.onSizeChanged { onBarHeightChanged(it.height) }
        )
    }

    if (showRemoveConfirm) {
        LibraryBulkRemoveDialog(
            count = selection.count,
            placeName = placeName,
            onConfirm = {
                val operation = when (tab) {
                    LibraryTab.HISTORY -> LibraryBulkOperation.RemoveFromHistory
                    LibraryTab.IN_PROGRESS -> LibraryBulkOperation.RemoveProgress
                    else -> LibraryBulkOperation.RemoveFromList(requireNotNull(currentListKey))
                }
                finish(removes = true) { chosen -> onBulkAction(chosen, operation, null) }
            },
            onCancel = {
                onShowRemoveConfirm(false)
                scope.launch { barFocusRequester.requestFocusAfterFrames(frames = 1) }
            }
        )
    }

    if (showListPicker && listActionLabel != null) {
        LibraryBulkListPicker(
            title = listActionLabel,
            destinations = destinations,
            onPick = { destination ->
                val title = destinationTitles[destination.key] ?: destination.title
                finish(removes = savedGrid) { chosen ->
                    onBulkAction(chosen, LibraryBulkOperation.AddToList(destination.key, removeFrom = currentListKey), title)
                }
            },
            onDismiss = {
                onShowListPicker(false)
                scope.launch { barFocusRequester.requestFocusAfterFrames(frames = 1) }
            }
        )
    }
}
