package com.fetch.tv.ui.screens.home

import com.fetch.tv.core.parental.CertificationQuery
import com.fetch.tv.core.parental.ParentalControls
import com.fetch.tv.domain.model.ContentType

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
 * Hides Continue Watching and upcoming entries the active profile's limit doesn't allow.
 * Kids profiles get a simplified home without the upcoming row.
 */
internal fun filterContinueWatchingForProfile(state: HomeUiState, parentalControls: ParentalControls): HomeUiState {
    val restrictions = parentalControls.activeRestrictions.value
    if (!restrictions.isRestricted) return state
    val main = parentalControls.filterAllowed(state.continueWatchingItems) { it.certificationQuery() }
    val upcoming = if (restrictions.isKidsProfile) {
        emptyList()
    } else {
        parentalControls.filterAllowed(state.upcomingItems) { it.certificationQuery() }
    }
    if (main.size == state.continueWatchingItems.size && upcoming.size == state.upcomingItems.size) return state
    return state.copy(continueWatchingItems = main, upcomingItems = upcoming)
}
