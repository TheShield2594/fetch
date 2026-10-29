package com.fetch.tv.ui.screens.cast

import com.fetch.tv.domain.model.PersonDetail

sealed interface CastDetailUiState {
    data object Loading : CastDetailUiState
    data class Success(val personDetail: PersonDetail) : CastDetailUiState
    data class Error(val message: String) : CastDetailUiState
}

internal fun CastDetailUiState.filteredForProfile(
    parentalControls: com.fetch.tv.core.parental.ParentalControls
): CastDetailUiState {
    if (this !is CastDetailUiState.Success || !parentalControls.filtersContent()) return this
    val movies = parentalControls.filterPreviews(personDetail.movieCredits)
    val shows = parentalControls.filterPreviews(personDetail.tvCredits)
    if (movies === personDetail.movieCredits && shows === personDetail.tvCredits) return this
    return copy(personDetail = personDetail.copy(movieCredits = movies, tvCredits = shows))
}
