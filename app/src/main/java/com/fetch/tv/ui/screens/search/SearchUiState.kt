package com.fetch.tv.ui.screens.search

import com.fetch.tv.domain.model.THEME_CARD_CORNER_RADIUS
import androidx.compose.runtime.Immutable
import com.fetch.tv.domain.model.Addon
import com.fetch.tv.domain.model.CatalogRow
import com.fetch.tv.domain.model.DiscoverLocation
import com.fetch.tv.domain.model.MetaPreview

internal const val MIN_SEARCH_QUERY_LENGTH = 2

internal fun submittedSearchQuery(rawQuery: String): String =
    rawQuery.trim().takeIf { it.length >= MIN_SEARCH_QUERY_LENGTH }.orEmpty()

internal fun shouldShowDiscoverInSearch(
    discoverLocation: DiscoverLocation,
    query: String,
    submittedQuery: String
): Boolean =
    discoverLocation == DiscoverLocation.IN_SEARCH &&
        query.trim().length < MIN_SEARCH_QUERY_LENGTH &&
        submittedQuery.isBlank()

@Immutable
data class SearchUiState(
    val query: String = "",
    val submittedQuery: String = "",
    /** Type with the system keyboard rather than the on-screen grid beside the results (#484). */
    val useSystemKeyboard: Boolean = false,
    val isSearching: Boolean = false,
    val error: String? = null,
    val catalogRows: List<CatalogRow> = emptyList(),
    val installedAddons: List<Addon> = emptyList(),
    val discoverLocation: DiscoverLocation = DiscoverLocation.IN_SIDEBAR,
    val discoverInitialized: Boolean = false,
    val discoverLoading: Boolean = false,
    val discoverLoadingMore: Boolean = false,
    val discoverCatalogs: List<DiscoverCatalog> = emptyList(),
    val selectedDiscoverType: String = "movie",
    val selectedDiscoverGenre: String? = null,
    /** Year, rating and sort, applied to the merged catalog items on the device. */
    val discoverFilters: DiscoverFilters = DiscoverFilters(),
    /** How many catalogs the current Discover results are merged from. */
    val discoverSourceCount: Int = 0,
    /** Merged, filtered and sorted results across every catalog of the selected type. */
    val discoverResults: List<MetaPreview> = emptyList(),
    /** At least one catalog can still page further. */
    val discoverHasMore: Boolean = true,
    /** Every catalog failed on the first page; shown with a Retry instead of "No content". */
    val discoverError: String? = null,
    /**
     * Scroll paging has stopped asking on its own: the last page failed everywhere, or several
     * pages in a row added nothing that passes the filters. A Load more cell resumes it, so an
     * offline grid or a narrow filter doesn't page through every catalog in a tight loop.
     */
    val discoverPagingPaused: Boolean = false,
    val posterLabelsEnabled: Boolean = true,
    val catalogAddonNameEnabled: Boolean = true,
    val catalogTypeSuffixEnabled: Boolean = true,
    val posterCardWidthDp: Int = 126,
    val posterCardHeightDp: Int = 189,
    val posterCardCornerRadiusDp: Int = THEME_CARD_CORNER_RADIUS,
    val recentSearches: List<String> = emptyList(),
    val suggestions: List<String> = emptyList(),
    /** People matching the query on TMDB (when TMDB is on), each opening the cast screen. */
    val peopleResults: List<com.fetch.tv.core.tmdb.PersonSearchResult> = emptyList(),
    /** Which titles the merged results show; see [SearchTypeFilter]. */
    val typeFilter: SearchTypeFilter = SearchTypeFilter.ALL,
    /**
     * [catalogRows] merged across addons: one ranked row per type and a top result (roadmap D3).
     * Built by the view model from the profile-filtered rows; the screen shows these.
     */
    val mergedResults: MergedSearchResults = MergedSearchResults(),
    /** Whether the library has anything in it, so "In my library" is worth offering. */
    val hasLibraryItems: Boolean = false
)

@Immutable
data class DiscoverCatalog(
    val key: String,
    val addonId: String,
    val addonName: String,
    val addonBaseUrl: String,
    val catalogId: String,
    val catalogName: String,
    val type: String,
    val genres: List<String>,
    val supportsSkip: Boolean,
    val skipStep: Int,
    /** The catalog can't be browsed without a genre, so Discover only asks it for a genre it lists. */
    val genreRequired: Boolean = false,
    /** Genre sent when [genreRequired] and none is chosen: the manifest default, else the first option. */
    val defaultGenre: String? = null,
    /** Values for any other required extras, sent with every request. */
    val requiredExtraArgs: Map<String, String> = emptyMap()
)

