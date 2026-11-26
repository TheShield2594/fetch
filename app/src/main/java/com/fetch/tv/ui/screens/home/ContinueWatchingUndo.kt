package com.fetch.tv.ui.screens.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Undo for "Remove from Continue Watching" (#816).
 *
 * Remove used to delete the progress at once, on the tracker too. The removal is now held for
 * [CW_REMOVAL_UNDO_WINDOW_MS]: the card is hidden from the shown state straight away, but the
 * repository delete (and the tracker call it makes) waits until the banner expires, the user
 * removes something else, or Home is left. Undo just drops the hold, so the card comes back
 * exactly where it was.
 */
@Immutable
data class PendingContinueWatchingRemoval(
    val contentId: String,
    val season: Int?,
    val episode: Int?,
    val isNextUp: Boolean,
    /** Shown in the banner; blank when the card had no name. */
    val title: String,
    /** Distinguishes two removals of the same title, so a stale timer never commits a newer one. */
    val token: Long,
    /** The card as it was shown, so the commit can still read it once it is hidden. */
    internal val shownItems: List<ContinueWatchingItem>
)

internal const val CW_REMOVAL_UNDO_WINDOW_MS = 6_000L

/** Whether [item] is the card a pending removal hides. Mirrors what the commit removes. */
internal fun PendingContinueWatchingRemoval.hides(item: ContinueWatchingItem): Boolean {
    val id = contentId.trim()
    return when (item) {
        // A Next Up dismissal only hides the Next Up card; removing progress hides both.
        is ContinueWatchingItem.NextUp -> item.info.contentId.trim() == id
        is ContinueWatchingItem.InProgress -> !isNextUp && item.progress.contentId.trim() == id
    }
}

/** [state] with the card a pending removal hides taken out of both Continue Watching lists. */
internal fun hidePendingContinueWatchingRemoval(
    state: HomeUiState,
    pending: PendingContinueWatchingRemoval?
): HomeUiState {
    if (pending == null) return state
    val main = state.continueWatchingItems.filterNot(pending::hides)
    val upcoming = state.upcomingItems.filterNot(pending::hides)
    if (main.size == state.continueWatchingItems.size && upcoming.size == state.upcomingItems.size) return state
    return state.copy(continueWatchingItems = main, upcomingItems = upcoming)
}

internal fun HomeViewModel.requestContinueWatchingRemoval(
    contentId: String,
    season: Int?,
    episode: Int?,
    isNextUp: Boolean
) {
    // One banner at a time: a second removal commits the first.
    commitPendingContinueWatchingRemoval()
    val shown = uiState.value
    val shownItems = shown.continueWatchingItems + shown.upcomingItems
    val title = shownItems.firstOrNull { item ->
        item.contentId().trim() == contentId.trim() &&
            (!isNextUp || item is ContinueWatchingItem.NextUp)
    }?.displayName().orEmpty()
    val pending = PendingContinueWatchingRemoval(
        contentId = contentId,
        season = season,
        episode = episode,
        isNextUp = isNextUp,
        title = title,
        token = ++cwRemovalTokenCounter,
        shownItems = shownItems
    )
    _pendingCwRemoval.value = pending
    cwRemovalCommitJob?.cancel()
    cwRemovalCommitJob = viewModelScope.launch {
        delay(CW_REMOVAL_UNDO_WINDOW_MS)
        if (_pendingCwRemoval.value?.token == pending.token) commitPendingContinueWatchingRemoval()
    }
}

/** Puts the card back where it was; nothing was deleted yet. */
internal fun HomeViewModel.undoContinueWatchingRemoval() {
    cwRemovalCommitJob?.cancel()
    cwRemovalCommitJob = null
    _pendingCwRemoval.value = null
}

/**
 * Carries out the held removal, if any. [scope] runs the writes; pass a scope that outlives the
 * view model when it is being cleared, since its own scope is already cancelled by then.
 */
internal fun HomeViewModel.commitPendingContinueWatchingRemoval(scope: CoroutineScope = viewModelScope) {
    val pending = _pendingCwRemoval.value ?: return
    cwRemovalCommitJob?.cancel()
    cwRemovalCommitJob = null
    // The removal first, so the underlying lists no longer hold the card when the hold lifts.
    removeContinueWatchingPipeline(
        contentId = pending.contentId,
        season = pending.season,
        episode = pending.episode,
        isNextUp = pending.isNextUp,
        knownItems = pending.shownItems,
        scope = scope
    )
    if (_pendingCwRemoval.value?.token == pending.token) _pendingCwRemoval.value = null
}

private fun ContinueWatchingItem.displayName(): String = when (this) {
    is ContinueWatchingItem.InProgress -> progress.name
    is ContinueWatchingItem.NextUp -> info.name
}
