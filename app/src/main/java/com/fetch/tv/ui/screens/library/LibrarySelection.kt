package com.fetch.tv.ui.screens.library

import com.fetch.tv.core.tracking.LOCAL_LIBRARY_LIST_KEY
import com.fetch.tv.core.tracking.supportsMembershipFor
import com.fetch.tv.data.local.CachedNextUpItem
import com.fetch.tv.domain.model.LibraryListTab

/**
 * The Library's multi-select (#653): whether it's on, and the focus keys of the chosen cards.
 * Only the screen holds it; the bulk actions are handed the chosen items.
 */
data class LibrarySelectionState(
    val active: Boolean = false,
    val selectedKeys: Set<String> = emptySet()
) {
    val count: Int get() = selectedKeys.size
    fun isSelected(key: String): Boolean = key in selectedKeys
}

sealed interface LibrarySelectionAction {
    /** "Select" was pressed: selection mode starts with nothing chosen. */
    data object Enter : LibrarySelectionAction

    /** Back, a finished bulk action, or a tab change. */
    data object Exit : LibrarySelectionAction

    /** OK on a card while selecting. */
    data class Toggle(val key: String) : LibrarySelectionAction

    /** The grid changed: chosen cards that are gone are dropped. */
    data class Retain(val visibleKeys: Set<String>) : LibrarySelectionAction
}

internal fun reduceLibrarySelection(
    state: LibrarySelectionState,
    action: LibrarySelectionAction
): LibrarySelectionState = when (action) {
    LibrarySelectionAction.Enter -> LibrarySelectionState(active = true)
    LibrarySelectionAction.Exit -> LibrarySelectionState()
    is LibrarySelectionAction.Toggle -> if (!state.active) {
        state
    } else if (action.key in state.selectedKeys) {
        state.copy(selectedKeys = state.selectedKeys - action.key)
    } else {
        state.copy(selectedKeys = state.selectedKeys + action.key)
    }
    is LibrarySelectionAction.Retain -> {
        val kept = state.selectedKeys.intersect(action.visibleKeys)
        if (kept.size == state.selectedKeys.size) state else state.copy(selectedKeys = kept)
    }
}

/**
 * Where focus goes once [removedKeys] leave a grid shown in [orderedKeys] order: [anchorKey] if it
 * stays, else the first card after it that stays, else the nearest one before it. Null when
 * nothing is left (or the anchor isn't in the grid and nothing stays), so the caller focuses
 * the tab row instead.
 */
internal fun libraryFocusKeyAfterRemoval(
    orderedKeys: List<String>,
    removedKeys: Set<String>,
    anchorKey: String?
): String? {
    val anchorIndex = anchorKey?.let(orderedKeys::indexOf) ?: -1
    if (anchorIndex < 0) return orderedKeys.firstOrNull { it !in removedKeys }
    if (anchorKey !in removedKeys) return anchorKey
    return orderedKeys.subList(anchorIndex + 1, orderedKeys.size).firstOrNull { it !in removedKeys }
        ?: orderedKeys.subList(0, anchorIndex).lastOrNull { it !in removedKeys }
}

/** The list key the Saved grid is showing, which Remove takes titles off. */
internal fun libraryCurrentListKey(selectedListKey: String?): String =
    selectedListKey ?: LOCAL_LIBRARY_LIST_KEY

/**
 * The lists a bulk "Move to list" / "Add to list" can put titles on: those that take titles,
 * other than [currentListKey] (null on In progress and History, where nothing is moved off).
 * With no lists at all on this TV's own library, its saved titles are the one place to add to.
 */
internal fun libraryBulkDestinations(
    listTabs: List<LibraryListTab>,
    currentListKey: String?,
    localLibraryTitle: String
): List<LibraryListTab> {
    val destinations = listTabs.filter { it.isMembershipDestination && it.key != currentListKey }
    if (listTabs.isEmpty() && currentListKey == null) {
        return listOf(LibraryListTab(key = LOCAL_LIBRARY_LIST_KEY, title = localLibraryTitle, type = LibraryListTab.Type.WATCHLIST))
    }
    return destinations
}

/**
 * The membership to save for one title of a bulk list change: [snapshot] (every list the title
 * is on now) with [addTo] switched on, other lists of its single-choice group (a Simkl status)
 * switched off, and [removeFrom] switched off. Null when [addTo]'s list can't hold [contentType],
 * so the title is skipped.
 */
internal fun libraryBulkMembership(
    snapshot: Map<String, Boolean>,
    tabs: List<LibraryListTab>,
    contentType: String,
    addTo: String?,
    removeFrom: String?
): Map<String, Boolean>? {
    val next = snapshot.toMutableMap()
    if (addTo != null && addTo != LOCAL_LIBRARY_LIST_KEY) {
        val tab = tabs.firstOrNull { it.key == addTo } ?: return null
        if (!tab.supportsMembershipFor(contentType)) return null
        if (tab.selectionGroup != null) {
            tabs.filter { it.trackingProviderId == tab.trackingProviderId && it.selectionGroup == tab.selectionGroup }
                .forEach { next[it.key] = false }
        }
    }
    if (removeFrom != null && removeFrom != addTo) next[removeFrom] = false
    if (addTo != null) next[addTo] = true
    return next
}

/** The corner badge a Library card shows for a show with something new to watch. */
enum class LibraryNewEpisodeBadge { NEW_EPISODE, NEW_SEASON }

/**
 * Shows with a new episode or season out, by content id, from Home's Next up snapshot: the same
 * release alerts that put "New" on Continue Watching. Episodes that haven't aired don't count.
 */
internal fun libraryNewEpisodeBadges(nextUp: List<CachedNextUpItem>): Map<String, LibraryNewEpisodeBadge> =
    buildMap {
        nextUp.forEach { item ->
            if (!item.isReleaseAlert || !item.hasAired) return@forEach
            val badge = if (item.isNewSeasonRelease) LibraryNewEpisodeBadge.NEW_SEASON else LibraryNewEpisodeBadge.NEW_EPISODE
            // A show listed twice keeps the stronger badge.
            if (this[item.contentId] != LibraryNewEpisodeBadge.NEW_SEASON) put(item.contentId, badge)
        }
    }

/** The badge for a title known by [id] (and, for saved titles, [imdbId]). */
internal fun Map<String, LibraryNewEpisodeBadge>.badgeFor(id: String, imdbId: String? = null): LibraryNewEpisodeBadge? =
    this[id] ?: imdbId?.takeIf { it.isNotBlank() }?.let(::get)

/** What the selection bar does to the chosen titles. */
sealed interface LibraryBulkOperation {
    /** Remove on Watchlist and Lists: off the list shown. */
    data class RemoveFromList(val listKey: String) : LibraryBulkOperation

    /** Remove on History. */
    data object RemoveFromHistory : LibraryBulkOperation

    /** Remove on In progress. */
    data object RemoveProgress : LibraryBulkOperation

    data object MarkWatched : LibraryBulkOperation

    /** Onto [listKey]; a move also takes the titles off [removeFrom]. */
    data class AddToList(val listKey: String, val removeFrom: String?) : LibraryBulkOperation
}
