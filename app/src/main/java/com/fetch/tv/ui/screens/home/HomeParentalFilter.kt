package com.fetch.tv.ui.screens.home

import com.fetch.tv.core.parental.CertificationQuery
import com.fetch.tv.core.parental.ParentalControls
import com.fetch.tv.domain.model.ContentType

/** Rows a kids Home shows after Continue Watching. */
internal const val KIDS_HOME_MAX_ROWS = 6

/** How much bigger cards are on a kids Home than the poster size setting. */
internal const val KIDS_HOME_CARD_SCALE = 1.25f

internal fun ContinueWatchingItem.certificationQuery(): CertificationQuery = when (this) {
    is ContinueWatchingItem.InProgress -> CertificationQuery(
        contentId = progress.contentId,
        type = ContentType.fromString(progress.contentType)
    )
    is ContinueWatchingItem.NextUp -> CertificationQuery(
        contentId = info.contentId,
        type = ContentType.fromString(info.contentType)
    )
}

/**
 * Hides Continue Watching and upcoming entries the active profile's limit or hidden titles don't allow.
 * Kids profiles get a Home of their own (see [HomeUiState.kidsHome]), without the upcoming row.
 */
internal fun filterContinueWatchingForProfile(state: HomeUiState, parentalControls: ParentalControls): HomeUiState {
    if (!parentalControls.filtersContent()) return state
    val restrictions = parentalControls.activeRestrictions.value
    val main = parentalControls.filterAllowed(state.continueWatchingItems) { it.certificationQuery() }
    val upcoming = if (restrictions.isKidsProfile) {
        emptyList()
    } else {
        parentalControls.filterAllowed(state.upcomingItems) { it.certificationQuery() }
    }
    if (main.size == state.continueWatchingItems.size && upcoming.size == state.upcomingItems.size) return state
    return state.copy(continueWatchingItems = main, upcomingItems = upcoming)
}
