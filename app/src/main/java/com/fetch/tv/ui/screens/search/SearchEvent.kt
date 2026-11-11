package com.fetch.tv.ui.screens.search

sealed interface SearchEvent {
    data class QueryChanged(val query: String) : SearchEvent
    /** Narrows the merged results to one type, or to titles in the library. */
    data class SelectTypeFilter(val filter: SearchTypeFilter) : SearchEvent
    data object SubmitSearch : SearchEvent
    /** Moving from the text input to the results confirms the current search (see #2928 review). */
    data object RememberSearchFromTextInput : SearchEvent
    data object ClearRecentSearches : SearchEvent
    data class RemoveRecentSearch(val query: String) : SearchEvent

    data class LoadMoreCatalog(
        val catalogId: String,
        val addonId: String,
        val type: String
    ) : SearchEvent

    data class SelectDiscoverType(val type: String) : SearchEvent
    data class SelectDiscoverGenre(val genre: String?) : SearchEvent
    data class SelectDiscoverYearRange(val range: DiscoverYearRange?) : SearchEvent
    data class SelectDiscoverMinRating(val minRating: Int?) : SearchEvent
    data class SelectDiscoverSort(val sort: DiscoverSort) : SearchEvent
    /** Clears the year and rating filters; the sort stays. */
    data object ClearDiscoverFilters : SearchEvent
    /** Loads the next page from every Discover catalog that has more. */
    data object LoadNextDiscoverResults : SearchEvent
    /** Reloads Discover from the first page of every catalog after an error. */
    data object RetryDiscover : SearchEvent

    data object Retry : SearchEvent
}
