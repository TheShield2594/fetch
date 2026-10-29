package com.fetch.tv.ui.screens.tmdb

import com.fetch.tv.core.tmdb.TmdbEntityBrowseData

sealed interface TmdbEntityBrowseUiState {
    data object Loading : TmdbEntityBrowseUiState
    data class Error(val message: String) : TmdbEntityBrowseUiState
    data class Success(val data: TmdbEntityBrowseData) : TmdbEntityBrowseUiState
}

internal fun TmdbEntityBrowseUiState.filteredForProfile(
    parentalControls: com.fetch.tv.core.parental.ParentalControls
): TmdbEntityBrowseUiState {
    if (this !is TmdbEntityBrowseUiState.Success || !parentalControls.filtersContent()) return this
    val rails = data.rails.mapNotNull { rail ->
        val items = parentalControls.filterPreviews(rail.items)
        when {
            items === rail.items -> rail
            // A rail with nothing left to show is dropped rather than drawn empty.
            items.isEmpty() -> null
            else -> rail.copy(items = items)
        }
    }
    return copy(data = data.copy(rails = rails))
}
