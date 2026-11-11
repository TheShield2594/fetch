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
    data class SelectDiscoverCatalog(val catalogKey: String) : SearchEvent
    data class SelectDiscoverGenre(val genre: String?) : SearchEvent
    data object LoadNextDiscoverResults : SearchEvent
    /** Reloads the selected Discover catalog from its first page after an error. */
    data object RetryDiscover : SearchEvent

    data object Retry : SearchEvent
}
