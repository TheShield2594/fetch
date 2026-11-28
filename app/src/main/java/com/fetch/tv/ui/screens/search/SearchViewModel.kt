package com.fetch.tv.ui.screens.search

import com.fetch.tv.domain.model.catalogRowLegacyKey
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fetch.tv.R
import com.fetch.tv.core.network.NetworkResult
import com.fetch.tv.data.local.DiscoverSelectionDataStore
import com.fetch.tv.data.local.LayoutPreferenceDataStore
import com.fetch.tv.data.local.SearchHistoryDataStore
import com.fetch.tv.data.mediaserver.ServerCatalog
import com.fetch.tv.domain.model.Addon
import com.fetch.tv.domain.model.CatalogDescriptor
import com.fetch.tv.domain.model.CatalogRow
import com.fetch.tv.domain.model.DiscoverLocation
import com.fetch.tv.domain.model.catalogRowStableKey
import com.fetch.tv.domain.model.mergeCatalogPage
import com.fetch.tv.domain.model.nextCatalogSkip
import com.fetch.tv.domain.model.skipStep
import com.fetch.tv.domain.model.stableKey
import com.fetch.tv.domain.model.supportsExtra
import com.fetch.tv.domain.model.requiredBrowseExtraArgs
import com.fetch.tv.domain.model.requiresExtra
import com.fetch.tv.core.util.filterReleasedItems
import com.fetch.tv.core.util.isUnreleased
import com.fetch.tv.domain.repository.AddonRepository
import java.time.LocalDate
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.PosterShape
import com.fetch.tv.domain.model.enabledAddons
import com.fetch.tv.domain.model.PLACEHOLDER_IMAGE_URL
import com.fetch.tv.domain.repository.CatalogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
    private val metaRepository: com.fetch.tv.domain.repository.MetaRepository,
    private val discoverSelectionDataStore: DiscoverSelectionDataStore,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val searchHistoryDataStore: SearchHistoryDataStore,
    private val watchProgressRepository: com.fetch.tv.domain.repository.WatchProgressRepository,
    private val watchedSeriesStateHolder: com.fetch.tv.data.local.WatchedSeriesStateHolder,
    val posterOptions: com.fetch.tv.ui.components.posteroptions.PosterOptionsController,
    private val serverCatalog: ServerCatalog,
    private val parentalControls: com.fetch.tv.core.parental.ParentalControls,
    private val remoteCommandBus: com.fetch.tv.core.remote.RemoteCommandBus,
    @ApplicationContext private val context: Context,
    private val peopleSearch: com.fetch.tv.core.tmdb.TmdbPeopleSearch,
    private val libraryRepository: com.fetch.tv.domain.repository.LibraryRepository,
    /** "Not interested" titles (#650), left out of results, Discover and completions. */
    private val titleVisibility: com.fetch.tv.core.hidden.TitleVisibilityFilter
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())

    // The last merge, reused while its inputs are unchanged (see withMergedResults).
    // Declared before uiState, which starts collecting as soon as it is created.
    private var lastMergeRows: List<CatalogRow>? = null
    private var lastMergeQuery: String? = null
    private var lastMergeFilter: SearchTypeFilter? = null
    private var lastMergeLibrary: Set<String>? = null
    private var lastMerged = MergedSearchResults()

    /** Ids of the titles in the library, for the "In my library" filter. */
    internal val libraryIds: StateFlow<Set<String>> = libraryRepository.libraryItems
        .map { entries -> libraryIdSet(entries.map { LibraryIds(it.id, it.type, it.imdbId, it.tmdbId) }) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    // Results are filtered by the profile's certification limit on the way out, and
    // refiltered when the limit changes or more certifications are known. The merged view is
    // built from the filtered rows, so a hidden title never becomes the top result.
    val uiState: StateFlow<SearchUiState> =
        combine(_uiState, parentalControls.filterRevision, libraryIds, titleVisibility.visibility) { state, _, inLibrary, visibility ->
            state.filteredForProfile(parentalControls)
                .withoutNotInterested(visibility)
                .withMergedResults(inLibrary)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SearchUiState())

    /**
     * Folds the per-catalog rows into one ranked row per type (roadmap D3). Rebuilt only when the
     * rows, query, filter or library change, not on every unrelated state update (a keystroke
     * that hasn't produced results yet, a suggestion, a layout preference).
     */
    private fun SearchUiState.withMergedResults(inLibrary: Set<String>): SearchUiState {
        val query = submittedQuery.trim()
        // Equality rather than identity: a restricted profile's filter copies the rows each time.
        val unchanged = catalogRows == lastMergeRows && query == lastMergeQuery &&
            typeFilter == lastMergeFilter && inLibrary == lastMergeLibrary
        if (!unchanged) {
            lastMerged = SearchResultMerger.merge(
                rows = catalogRows,
                query = query,
                filter = typeFilter,
                isInLibrary = { item -> isLibraryItem(item, inLibrary) },
                rowTitle = ::mergedRowTitle,
                externalIds = { item -> serverCatalog.searchExternalIds(item.id) }
            )
            lastMergeRows = catalogRows
            lastMergeQuery = query
            lastMergeFilter = typeFilter
            lastMergeLibrary = inLibrary
        }
        return copy(mergedResults = lastMerged, hasLibraryItems = inLibrary.isNotEmpty())
    }

    private fun mergedRowTitle(typeKey: String): String = when (typeKey) {
        "movie" -> context.getString(R.string.type_movies)
        "series" -> context.getString(R.string.type_series)
        "other" -> context.getString(R.string.search_results_other)
        else -> addonTypeDisplayName(typeKey)
    }

    /** Saved focus state for restoring scroll/focus position after returning from details. */
    var savedFocusRowKey: String? = null
    var savedFocusItemIndex: Int = -1
    var savedRowScrollPositions: Map<String, Pair<Int, Int>> = emptyMap()
    var hasSavedSearchFocus: Boolean = false

    private val _watchedMovieIds = MutableStateFlow<Set<String>>(emptySet())
    val watchedMovieIds: StateFlow<Set<String>> = _watchedMovieIds.asStateFlow()
    val watchedSeriesIds: StateFlow<Set<String>> = watchedSeriesStateHolder.fullyWatchedSeriesIds

    /** Saved watch progress (0..1) by content id, for the bar along a Discover card's edge (#854). */
    val progressByContentId: StateFlow<Map<String, Float>> =
        flow { emitAll(watchProgressRepository.continueWatching) }
            .map { com.fetch.tv.ui.screens.library.libraryProgressByContentId(it) }
            .distinctUntilChanged()
            .flowOn(kotlinx.coroutines.Dispatchers.Default)
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val catalogsMap = linkedMapOf<String, CatalogRow>()
    private val catalogOrder = mutableListOf<String>()

    private var activeSearchJobs: List<Job> = emptyList()

    /**
     * Load-more jobs, tracked separately from [activeSearchJobs] because that list is replaced
     * wholesale by each search run. Cancelled alongside it in [cancelSearchRun].
     */
    private val activeLoadMoreJobs: MutableSet<Job> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()
    /** Rows with a load-more page in flight, so cancelling the run can clear their spinners. */
    private val loadMoreRowKeys: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private var searchRunJob: Job? = null
    private var activeSearchQuery: String? = null
    private var searchGeneration = 0L
    private var discoverJob: Job? = null
    private var catalogRowsUpdateJob: Job? = null
    private var suggestionJob: Job? = null
    private var liveSearchJob: Job? = null
    private var lastRequestKey: String? = null
    private var lastCompletedRequestKey: String? = null
    private var hasRenderedFirstCatalog = false
    private var pendingCatalogResponses = 0
    /** Holds a run's first paint until its catalogs have answered (see scheduleCatalogRowsUpdate). */
    private var firstRenderJob: Job? = null

    /** One catalog Discover pages through, and where its next page starts. */
    private data class DiscoverSourceCursor(
        val catalog: DiscoverCatalog,
        val nextSkip: Int = 0,
        val hasMore: Boolean = true,
        val failures: Int = 0,
        /** Whether a page from this catalog has had titles, and whether any of them had a rating (#864). */
        val sawTitles: Boolean = false,
        val sawRating: Boolean = false
    ) {
        /** Titles have come, none rated: a Rating filter (without "Include unrated") keeps none of them. */
        val sendsNoRatings: Boolean get() = sawTitles && !sawRating

        /**
         * Whether paging this catalog can add anything [filters] keep. Under a Rating filter, a
         * catalog that sends no ratings is left out of paging, so it cannot stall the others (#864).
         */
        fun pageable(filters: DiscoverFilters): Boolean =
            hasMore && !(filters.excludesUnrated && sendsNoRatings)
    }

    /** Whether any catalog can still page for [filters]. */
    private fun discoverCanFetch(filters: DiscoverFilters = _uiState.value.discoverFilters): Boolean =
        discoverCursors.any { it.pageable(filters) }

    /** The catalogs merged into the current Discover results, in catalog order. */
    private var discoverCursors: List<DiscoverSourceCursor> = emptyList()

    /** Every title loaded so far, merged across catalogs, before the year, rating and sort filters. */
    private var discoverMergedItems: List<MetaPreview> = emptyList()

    /**
     * Under a Rating, Newest or Title sort (#843): the grid as shown, batch after sorted batch, and
     * how many of [discoverMergedItems] those batches cover. Titles loaded past that wait for the
     * next batch, so new titles are only ever appended below, never sorted in above.
     */
    private var discoverSortedShown: List<MetaPreview> = emptyList()
    private var discoverSortedCount = 0
    /** Where each placed sorted batch ends in the merged items, partial batches included (#857). */
    private val discoverSortedBatchEnds = mutableListOf<Int>()

    /** Paging rounds in a row that added nothing the filters keep. */
    private var discoverUnproductiveRounds = 0
    private var hideUnreleasedContent = false

    /** Set once a catalog list has been built, so a later addon change rebuilds it (#845). */
    private var discoverCatalogsLoaded = false

    /** The grid a keep-grid refresh in flight would put back if it fails (#845). */
    private var discoverRefreshSnapshot: DiscoverSnapshot? = null

    /** When the current Discover results were first loaded, for the refetch on entry (#845). */
    private var discoverLoadedAtMs: Long? = null

    /** The clock the stale-results check reads; tests move it on. */
    internal var discoverClockMs: () -> Long = { System.currentTimeMillis() }

    private companion object {
        /** Below this many filtered results, Discover pages on its own to fill the grid. */
        const val DISCOVER_MIN_RESULTS = 30

        /** Extra rounds one load may run to fill the grid before waiting for the user to scroll. */
        const val DISCOVER_MAX_FILL_ROUNDS = 3

        /** Rounds in a row that add nothing visible before scroll paging pauses. */
        const val DISCOVER_MAX_UNPRODUCTIVE_ROUNDS = 3

        /**
         * Titles one sorted batch covers: a Rating, Newest or Title sort loads this many (or all
         * there are) before sorting once, and Load more adds another batch below.
         */
        const val DISCOVER_SORT_BATCH_SIZE = 300

        /** Most paging rounds one sorted batch may take; the batch is sorted with what came. */
        const val DISCOVER_SORT_MAX_ROUNDS = 12

        /** Failed pages in a row before a catalog is left out of further paging. */
        const val DISCOVER_MAX_SOURCE_FAILURES = 2

        /** Results older than this are fetched again when Discover is entered (#845). */
        const val DISCOVER_STALE_AFTER_MS = 30 * 60 * 1000L
        const val SUGGESTION_DEBOUNCE_MS = 150L

        /**
         * Live search fires while typing, but each run fans out to every enabled addon catalog, so
         * it waits longer than the suggestion debounce to avoid a request storm per keystroke.
         */
        const val LIVE_SEARCH_DEBOUNCE_MS = 350L

        /**
         * How long a search's first answer waits for the other catalogs before it is shown anyway,
         * so the first results on screen are already ranked across addons.
         */
        const val FIRST_RESULTS_SETTLE_MS = 800L

        const val MAX_SUGGESTIONS = 8
        /** Splits titles and queries into words. */
        private val WORD_SEPARATOR = Regex("[^\\p{L}\\p{N}]+")
        const val MAX_RECENT_SEARCHES = 8
    }

    /** Whether a Discover title is watched, for Hide watched (#857). */
    private val discoverIsWatched: (com.fetch.tv.domain.model.MetaPreview) -> Boolean = { item ->
        val isSeries = item.apiType.equals("series", ignoreCase = true) || item.apiType.equals("tv", ignoreCase = true)
        val watchedIds = if (isSeries) watchedSeriesIds.value else _watchedMovieIds.value
        // Watched entries can be stored under the IMDb id, as See all also checks.
        item.id in watchedIds || item.imdbId?.let { it in watchedIds } == true
    }

    init {
        posterOptions.bind(viewModelScope)
        viewModelScope.launch {
            watchProgressRepository.observeWatchedMovieIds()
                .collect { ids ->
                    _watchedMovieIds.value = ids
                    refilterDiscoverForWatched()
                }
        }
        viewModelScope.launch {
            watchedSeriesIds.drop(1).collect { refilterDiscoverForWatched() }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.discoverLocation.distinctUntilChanged().collectLatest { location ->
                _uiState.update { it.copy(discoverLocation = location) }
                if (location == DiscoverLocation.OFF) {
                    discoverJob?.cancel()
                    discoverJob = null
                    discoverCursors = emptyList()
                    discoverMergedItems = emptyList()
                    resetDiscoverSortedBatches()
                    discoverUnproductiveRounds = 0
                    discoverCatalogsLoaded = false
                    discoverLoadedAtMs = null
                    discoverRefreshSnapshot = null
                    _uiState.update {
                        it.copy(
                            discoverInitialized = false,
                            discoverLoading = false,
                            discoverLoadingMore = false,
                            discoverCatalogs = emptyList(),
                            selectedDiscoverType = "movie",
                            selectedDiscoverGenre = null,
                            discoverFilters = DiscoverFilters(),
                            discoverSourceCount = 0,
                            discoverResults = emptyList(),
                            discoverHasMore = true,
                            discoverError = null,
                            discoverPagingPaused = false,
                            discoverUnratedSourceCount = 0
                        )
                    }
                }
            }
        }
        // Addons or media-server catalogs installed, removed, enabled or reordered reach Discover
        // without a restart (#845): a changed list of browsable catalogs is rebuilt and reloaded,
        // keeping the type, genre and filters where they still apply.
        viewModelScope.launch {
            discoverAddons()
                .map { addons -> addons to buildDiscoverCatalogs(addons) }
                .distinctUntilChanged { old, new -> old.second == new.second }
                .catch { /* Keep what is on screen; Retry reads the addons again. */ }
                .collectLatest { (addons, catalogs) ->
                    val state = _uiState.value
                    if (!discoverCatalogsLoaded || state.discoverLocation == DiscoverLocation.OFF) return@collectLatest
                    if (catalogs == state.discoverCatalogs) return@collectLatest
                    applyDiscoverCatalogs(addons, catalogs)
                }
        }
        // Combine all layout preference flows into a single collector to reduce coroutine overhead
        viewModelScope.launch {
            combine(
                layoutPreferenceDataStore.posterCardWidthDp,
                layoutPreferenceDataStore.posterLabelsEnabled,
                layoutPreferenceDataStore.catalogAddonNameEnabled,
                layoutPreferenceDataStore.posterCardHeightDp,
                layoutPreferenceDataStore.posterCardCornerRadiusDp
            ) { widthDp, labelsEnabled, addonNameEnabled, heightDp, cornerRadiusDp ->
                LayoutPrefs(widthDp, labelsEnabled, addonNameEnabled, heightDp, cornerRadiusDp)
            }.collectLatest { prefs ->
                _uiState.update {
                    it.copy(
                        posterCardWidthDp = prefs.widthDp,
                        posterLabelsEnabled = prefs.labelsEnabled,
                        catalogAddonNameEnabled = prefs.addonNameEnabled,
                        posterCardHeightDp = prefs.heightDp,
                        posterCardCornerRadiusDp = prefs.cornerRadiusDp
                    )
                }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.searchSystemKeyboard.distinctUntilChanged().collectLatest { enabled ->
                _uiState.update {
                    if (it.useSystemKeyboard == enabled && it.keyboardPreferenceLoaded) it
                    else it.copy(useSystemKeyboard = enabled, keyboardPreferenceLoaded = true)
                }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.catalogTypeSuffixEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(catalogTypeSuffixEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.hideUnreleasedContent.collectLatest { enabled ->
                val changed = enabled != hideUnreleasedContent
                hideUnreleasedContent = enabled
                scheduleCatalogRowsUpdate()
                // Discover drops unreleased titles as pages arrive, so a change reloads it, with
                // the grid kept on screen until the new first page is in (#845).
                if (changed && discoverCatalogsLoaded && _uiState.value.discoverLocation != DiscoverLocation.OFF) {
                    fetchDiscoverContent(reset = true, keepGrid = true)
                }
            }
        }
        viewModelScope.launch {
            searchHistoryDataStore.recentSearches.collectLatest { recent ->
                _uiState.update { it.copy(recentSearches = recent.take(MAX_RECENT_SEARCHES)) }
            }
        }
    }

    private data class LayoutPrefs(
        val widthDp: Int,
        val labelsEnabled: Boolean,
        val addonNameEnabled: Boolean,
        val heightDp: Int,
        val cornerRadiusDp: Int
    )

    /**
     * Loads Discover the first time it is shown. Later entries refetch results older than
     * [DISCOVER_STALE_AFTER_MS], keeping the grid until the new first page is in (#845);
     * [allowRefresh] is false when returning from a title, so the focused card stays put.
     */
    fun ensureDiscoverLoaded(allowRefresh: Boolean = true) {
        val state = _uiState.value
        if (state.discoverLocation == DiscoverLocation.OFF) return
        if (state.discoverLoading) return
        if (!state.discoverInitialized) {
            viewModelScope.launch { loadDiscoverCatalogs() }
            return
        }
        if (allowRefresh && discoverResultsStale() && discoverJob?.isActive != true) {
            fetchDiscoverContent(reset = true, keepGrid = true)
        }
    }

    private fun discoverResultsStale(): Boolean {
        val loadedAt = discoverLoadedAtMs ?: return false
        return discoverClockMs() - loadedAt >= DISCOVER_STALE_AFTER_MS
    }

    private val metaPrefetchedIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private var metaPrefetchJob: Job? = null

    /**
     * Prefetch meta from addons in background when an item receives focus.
     * Warms the MetaRepository cache so the detail screen loads instantly.
     * Debounced to avoid flooding the network during rapid scrolling.
     */
    fun prefetchMetaOnFocus(id: String, type: String) {
        if (id.isBlank() || id in metaPrefetchedIds) return
        metaPrefetchJob?.cancel()
        metaPrefetchJob = viewModelScope.launch {
            delay(150)
            if (id in metaPrefetchedIds) return@launch
            metaRepository.getMetaFromAllAddons(type = type, id = id)
                .first { it !is com.fetch.tv.core.network.NetworkResult.Loading }
            watchProgressRepository.getAllEpisodeProgress(id.substringBefore(":")).first()
            // Marked only once done, so a prefetch cancelled by the next focus is retried.
            metaPrefetchedIds.add(id)
            // Discover's hero band re-reads the cache now it has the title's full meta (#852).
            _metaCacheVersion.update { it + 1 }
        }
    }

    private val _metaCacheVersion = MutableStateFlow(0)

    /** Goes up each time a focus prefetch lands, so the hero band can fill in what it now has. */
    val metaCacheVersion: StateFlow<Int> = _metaCacheVersion.asStateFlow()

    /** The full meta of a title, when a prefetch or the detail page has cached it. */
    fun cachedMeta(id: String, type: String): com.fetch.tv.domain.model.Meta? =
        metaRepository.getCachedMeta(type, id)

    /**
     * Whether Play/Pause on a Discover card may start it without its detail page (#857): not on a
     * kids profile or one with parental limits, as on Home (#812).
     */
    val quickPlayAllowed: StateFlow<Boolean> by lazy {
        parentalControls.activeRestrictions
            .map { !it.isKidsProfile && !it.isRestricted }
            .distinctUntilChanged()
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), false)
    }

    /** The Layout setting for the hero band over Discover's grid (#852); read only once Discover asks. */
    val heroEnabled: StateFlow<Boolean> by lazy {
        layoutPreferenceDataStore.browseHeroEnabled
            .distinctUntilChanged()
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), false)
    }

    /**
     * Returns the cached backdrop URL from a previously prefetched meta, or null.
     */
    fun getCachedBackdrop(id: String, type: String): String? {
        return metaRepository.getCachedMeta(type, id)?.backdropUrl
    }

    private var remoteSearchJob: Job? = null

    /**
     * Takes "Search on TV" queries from the phone remote. Only the Search screen calls this:
     * Discover and Home hold their own instances of this view model, and one of them taking the
     * query would leave it sitting there, hidden, blocking every Discover fetch (Retry included).
     */
    fun acceptRemoteSearches() {
        if (remoteSearchJob != null) return
        remoteSearchJob = viewModelScope.launch {
            remoteCommandBus.pendingSearch.collect { pending ->
                if (pending == null) return@collect
                val query = remoteCommandBus.consumePendingSearch() ?: return@collect
                onQueryChanged(query)
                submitSearch()
            }
        }
    }

    fun onEvent(event: SearchEvent) {
        when (event) {
            is SearchEvent.QueryChanged -> onQueryChanged(event.query)
            is SearchEvent.SelectTypeFilter -> _uiState.update { it.copy(typeFilter = event.filter) }
            SearchEvent.SubmitSearch -> submitSearch()
            SearchEvent.RememberSearchFromTextInput -> rememberSearchFromTextInput()
            SearchEvent.ClearRecentSearches -> clearRecentSearches()
            is SearchEvent.RemoveRecentSearch -> removeRecentSearch(event.query)
            is SearchEvent.LoadMoreCatalog -> loadMoreCatalogItems(
                catalogId = event.catalogId,
                addonId = event.addonId,
                type = event.type
            )
            is SearchEvent.SelectDiscoverType -> selectDiscoverType(event.type)
            is SearchEvent.SelectDiscoverGenre -> selectDiscoverGenre(event.genre)
            is SearchEvent.SelectDiscoverYearRange -> updateDiscoverFilters { it.copy(yearRange = event.range) }
            is SearchEvent.SelectDiscoverMinRating -> updateDiscoverFilters { it.copy(minRating = event.minRating) }
            is SearchEvent.SelectDiscoverSort -> updateDiscoverFilters { it.copy(sort = event.sort) }
            SearchEvent.ToggleDiscoverHideWatched -> updateDiscoverFilters { it.copy(hideWatched = !it.hideWatched) }
            SearchEvent.ToggleDiscoverIncludeUnrated -> updateDiscoverFilters { it.copy(includeUnrated = !it.includeUnrated) }
            SearchEvent.ResetDiscoverFilters -> resetDiscoverFilters()
            SearchEvent.LoadNextDiscoverResults -> loadNextDiscoverResults()
            // With no catalogs (their load failed), Retry reads the addons again (#845).
            SearchEvent.RetryDiscover -> if (_uiState.value.discoverCatalogs.isEmpty()) {
                if (!_uiState.value.discoverLoading) viewModelScope.launch { loadDiscoverCatalogs() }
            } else {
                fetchDiscoverContent(reset = true)
            }
            SearchEvent.Retry -> {
                // An explicit retry must refetch even though nothing about the request changed.
                lastRequestKey = null
                lastCompletedRequestKey = null
                cancelSearchRun()
                performSearch(_uiState.value.submittedQuery.ifBlank { _uiState.value.query })
            }
        }
    }

    private fun onQueryChanged(query: String) {
        _uiState.update {
            val trimmedInput = query.trim()
            // Narrow the strip to what still matches before anything is fetched, so a letter
            // that rules a title out drops it on that keystroke rather than a fetch later.
            // Narrowing does not clear the strip when every current title stops matching. The
            // fetch stays authoritative for an empty result.
            val narrowed = rankedSuggestions(it.suggestions, trimmedInput.lowercase())
            it.copy(
                query = query,
                suggestions = if (narrowed.isEmpty()) it.suggestions else narrowed,
                error = null,
                isSearching = false,
                // Keep whatever is on screen while a keystroke waits to run. Clearing here flashed
                // the no-results state on every letter, because on a remote each letter outlasts the
                // debounce. The screen renders skeleton rows for this window instead.
                catalogRows = if (trimmedInput.length < MIN_SEARCH_QUERY_LENGTH) emptyList() else it.catalogRows
            )
        }

        // Drop in-flight requests for the previous keystroke before scheduling the next run.
        cancelSearchRun()

        // Live search: results follow what you type, like mobile. Debounced because each run hits
        // every enabled addon catalog.
        liveSearchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length >= MIN_SEARCH_QUERY_LENGTH) {
            liveSearchJob = viewModelScope.launch {
                kotlinx.coroutines.delay(LIVE_SEARCH_DEBOUNCE_MS)
                performSearch(query, keepSuggestions = true)
            }
        } else {
            // Emptying the field has to retire the submitted query too. Leaving it set kept the
            // screen in its results state with nothing to show, instead of falling back to recent
            // searches, until the screen was rebuilt by navigating away and back.
            performSearch(query)
        }

        fetchSuggestions(trimmed)
    }

    /** Match rank for [title], lower being better, or null when it does not match [queryLower]. */
    private fun suggestionRank(title: String, queryLower: String): Int? {
        val titleLower = title.lowercase()
        if (titleLower == queryLower) return 0
        if (titleLower.startsWith(queryLower)) return 1
        if (titleLower.contains(queryLower)) return 2

        // Allow multi-word matches such as "wolf wall" -> "The Wolf of Wall Street".
        // Each query word must consume a different title word.
        val queryWords = queryLower.split(WORD_SEPARATOR).filter { it.isNotEmpty() }
        if (queryWords.size < 2) return null
        val unmatchedTitleWords = titleLower.split(WORD_SEPARATOR).filterTo(mutableListOf()) { it.isNotEmpty() }
        val everyWordMatches = queryWords.all { word ->
            val index = unmatchedTitleWords.indexOfFirst { it.startsWith(word) }
            if (index >= 0) unmatchedTitleWords.removeAt(index)
            index >= 0
        }
        return if (everyWordMatches) 3 else null
    }

    /**
     * The strip contents for [names], best match first, capped at [MAX_SUGGESTIONS].
     *
     * Titles of the same rank keep the order of [names], which is the addon's own relevance order.
     * Sorting them alphabetically put five titles ahead of "Jurassic Park" for "juras", and of
     * "Slow Horses" for "slo", past the few completions a TV keyboard shows.
     */
    private fun rankedSuggestions(names: Collection<String>, queryLower: String): List<String> =
        names
            .mapNotNull { name -> suggestionRank(name, queryLower)?.let { name to it } }
            .sortedBy { it.second }
            .map { it.first }
            .take(MAX_SUGGESTIONS)

    /**
     * Every catalog's titles, taken one position at a time: each catalog's first title, then each
     * one's second, and so on, in catalog order. Each catalog's best matches lead, and the result
     * does not depend on which catalog happened to answer first.
     */
    private fun mergedCatalogNames(namesByCatalog: Map<Int, List<String>>, catalogCount: Int): List<String> {
        val catalogs = (0 until catalogCount).mapNotNull { namesByCatalog[it] }
        val merged = LinkedHashSet<String>()
        for (position in 0 until (catalogs.maxOfOrNull { it.size } ?: 0)) {
            catalogs.forEach { names -> names.getOrNull(position)?.let(merged::add) }
        }
        return merged.toList()
    }

    private fun fetchSuggestions(query: String) {
        suggestionJob?.cancel()

        if (query.length < MIN_SEARCH_QUERY_LENGTH) {
            _uiState.update { it.copy(suggestions = emptyList()) }
            return
        }

        // Already searched, so a fetch would repeat itself and the strip is current. Leave it
        // standing: live search submits as the user types, so typing a space between words
        // trims back to the submitted query and lands here mid-query.
        if (query == _uiState.value.submittedQuery.trim() && _uiState.value.catalogRows.isNotEmpty()) {
            return
        }

        suggestionJob = viewModelScope.launch {
            kotlinx.coroutines.delay(SUGGESTION_DEBOUNCE_MS)

            val addons = try {
                searchAddons()
            } catch (_: Exception) {
                return@launch
            }

            val allTargets = buildSearchTargets(addons)
            val firstAddonId = allTargets.firstOrNull()?.first?.id
            val searchTargets = if (firstAddonId != null) allTargets.filter { it.first.id == firstAddonId } else emptyList()
            if (searchTargets.isEmpty()) {
                _uiState.update { it.copy(suggestions = emptyList()) }
                return@launch
            }

            // Each catalog's titles in the order it returned them, keyed by its place in searchTargets.
            val namesByCatalog = java.util.concurrent.ConcurrentHashMap<Int, List<String>>()
            val queryLower = query.lowercase()
            val suggestionJobs = searchTargets.mapIndexed { catalogIndex, (addon, catalog) ->
                launch {
                    try {
                        catalogRepository.getCatalog(
                            addonBaseUrl = addon.baseUrl,
                            addonId = addon.id,
                            addonName = addon.displayName,
                            catalogId = catalog.id,
                            catalogName = catalog.name,
                            type = catalog.apiType,
                            skip = 0,
                            skipStep = 100,
                            extraArgs = mapOf("search" to query),
                            supportsSkip = false,
                            posterScreen = com.fetch.tv.core.poster.CustomPosterScreen.SEARCH
                        ).collect { result ->
                            if (result is NetworkResult.Success && _uiState.value.query.trim() == query) {
                                // Restricted profiles must not see blocked titles as keyboard
                                // completions any more than as result cards.
                                val names = titleVisibility.visibility.value
                                    .filterSuggestions(parentalControls.filterPreviews(result.data.items))
                                    .map { it.name }
                                val previous = namesByCatalog.put(catalogIndex, names)
                                // Catalog results arrive independently and are merged, so a batch
                                // whose titles all fail the filter ranks to nothing while the batch
                                // holding the match is still in flight. Only the settle below may
                                // empty the strip: an empty push tells the keyboard there are no
                                // completions, and it does not always take them back when the next
                                // batch lands.
                                if (names != previous) {
                                    val ranked = rankedSuggestions(
                                        mergedCatalogNames(namesByCatalog, searchTargets.size),
                                        queryLower
                                    )
                                    if (ranked.isNotEmpty()) {
                                        _uiState.update { it.copy(suggestions = ranked) }
                                    }
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        // The settle below treats joinAll() as "collection is over".
                        throw e
                    } catch (_: Exception) {
                        // Ignore per-catalog errors for suggestions
                    }
                }
            }

            suggestionJobs.joinAll()

            // Every catalog job has completed, so this is the first point the query is known
            // to have no suggestions. Until here the strip keeps the previous query's titles
            // rather than blinking on every keystroke. It must be cleared here or it would go
            // on captioning text the field no longer contains.
            if (_uiState.value.query.trim() == query) {
                val ranked = rankedSuggestions(mergedCatalogNames(namesByCatalog, searchTargets.size), queryLower)
                _uiState.update { it.copy(suggestions = ranked) }
            }
        }
    }

    private fun submitSearch() {
        // An explicit submit just skips the remaining debounce; the live run would land anyway.
        liveSearchJob?.cancel()
        performSearch(_uiState.value.query, rememberToHistory = true)
    }

    /**
     * Moving from the text input into the results confirms the current query the same way
     * Done/submit does. Live search may already have results on screen without an explicit
     * submit; saving here
     * avoids losing useful history while still not recording every keystroke prefix.
     */
    private fun rememberSearchFromTextInput() {
        val state = _uiState.value
        val query = state.submittedQuery.trim().ifBlank { state.query.trim() }
        if (query.length < MIN_SEARCH_QUERY_LENGTH) return
        val hasRealResults = state.catalogRows.any { row ->
            row.items.any { item -> !item.id.startsWith("__placeholder_") }
        } || catalogsMap.values.any { row ->
            row.items.any { item -> !item.id.startsWith("__placeholder_") }
        }
        if (!hasRealResults) return
        viewModelScope.launch {
            searchHistoryDataStore.saveRecentSearch(query, MAX_RECENT_SEARCHES)
        }
    }

    private fun clearRecentSearches() {
        viewModelScope.launch {
            searchHistoryDataStore.clearRecentSearches()
        }
    }

    private fun removeRecentSearch(query: String) {
        viewModelScope.launch {
            searchHistoryDataStore.removeRecentSearch(query)
        }
    }

    private fun resetCatalogAccumulator() {
        catalogsMap.clear()
        catalogOrder.clear()
        hasRenderedFirstCatalog = false
        pendingCatalogResponses = 0
        firstRenderJob?.cancel()
        firstRenderJob = null
    }

    private var peopleJob: Job? = null
    /** The query [peopleJob] is answering or has answered; a repeat of it isn't re-requested. */
    private var peopleQuery: String? = null

    /**
     * Looks the query up as a person name on TMDB, alongside the addon search. Like the title
     * rows, the previous query's people stay up until this query's answer replaces them.
     */
    private fun searchPeople(query: String) {
        if (query == peopleQuery) return
        peopleQuery = query
        peopleJob?.cancel()
        peopleJob = viewModelScope.launch {
            val people = try {
                peopleSearch.search(query)
            } catch (e: CancellationException) {
                if (peopleQuery == query) peopleQuery = null
                throw e
            } catch (e: Exception) {
                android.util.Log.w("SearchViewModel", "People search failed: ${e.message}")
                // Forget the query so a retry asks again.
                if (peopleQuery == query) peopleQuery = null
                emptyList()
            }
            if (_uiState.value.submittedQuery.trim() == query) {
                _uiState.update { it.copy(peopleResults = people) }
            }
        }
    }

    private fun cancelSearchRun() {
        searchGeneration++
        searchRunJob?.cancel()
        searchRunJob = null
        firstRenderJob?.cancel()
        firstRenderJob = null
        activeSearchJobs.forEach { it.cancel() }
        activeSearchJobs = emptyList()
        // Snapshot first: a cancelled job's completion handler removes its key.
        val interruptedRowKeys = loadMoreRowKeys.toList()
        activeLoadMoreJobs.forEach { it.cancel() }
        activeLoadMoreJobs.clear()
        // A cancelled page load never reaches clearRowLoading, and a run that is then found
        // already satisfied keeps these rows, so drop their spinners here.
        if (interruptedRowKeys.isNotEmpty()) {
            interruptedRowKeys.forEach { key ->
                catalogsMap[key]?.takeIf { it.isLoading }?.let { catalogsMap[key] = it.copy(isLoading = false) }
            }
            loadMoreRowKeys.clear()
            scheduleCatalogRowsUpdate()
        }
        activeSearchQuery = null
    }

    /**
     * Identifies a search by everything that changes what it would return: the query, the released
     * filter, and the exact set of catalogs it would hit. Enabling an addon or flipping the filter
     * changes the key, so those still refetch.
     */
    private fun buildRequestKey(
        query: String,
        searchTargets: List<Pair<Addon, CatalogDescriptor>>
    ): String = buildString {
        append(query.lowercase())
        append('|')
        append(hideUnreleasedContent)
        append('|')
        append(
            searchTargets.joinToString(separator = "|") { (addon, catalog) ->
                "${addon.baseUrl}:${catalog.apiType}:${catalog.id}"
            }
        )
    }


    /**
     * @param keepSuggestions live search runs this on every keystroke, while the field is still
     * being typed into and the suggestion strip is the whole point. Those runs leave the strip
     * alone. A submit or a retry replaces the screen with results, which retires it.
     */
    private fun performSearch(
        rawQuery: String,
        rememberToHistory: Boolean = false,
        keepSuggestions: Boolean = false
    ) {
        val query = rawQuery.trim()
        if (!keepSuggestions) {
            suggestionJob?.cancel()
        }
        _uiState.update {
            val submitted = submittedSearchQuery(query)
            it.copy(
                submittedQuery = submitted,
                query = rawQuery,
                suggestions = if (keepSuggestions) it.suggestions else emptyList(),
                // A new search starts on All: a filter picked for the last query could hide
                // everything this one finds, with its chip no longer offered.
                typeFilter = if (submitted == it.submittedQuery) it.typeFilter else SearchTypeFilter.ALL
            )
        }

        if (query.length < MIN_SEARCH_QUERY_LENGTH) {
            cancelSearchRun()
            catalogRowsUpdateJob?.cancel()
            resetCatalogAccumulator()
            lastRequestKey = null
            lastCompletedRequestKey = null
            peopleJob?.cancel()
            peopleQuery = null
            _uiState.update {
                it.copy(
                    isSearching = false,
                    error = null,
                    catalogRows = emptyList(),
                    peopleResults = emptyList()
                )
            }
            ensureDiscoverLoaded()
            return
        }

        searchPeople(query)

        // Submit can immediately follow the debounced live-search launch. Reuse an active run for
        // the same query, but cancel a different query's entire scope before starting this one.
        if (activeSearchQuery == query && searchRunJob?.isActive == true) return
        cancelSearchRun()
        val generation = searchGeneration
        activeSearchQuery = query

        val job = viewModelScope.launch {
            val addons = try {
                searchAddons()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == searchGeneration && activeSearchQuery == query) {
                    _uiState.update {
                        it.copy(
                            isSearching = false,
                            error = errorWithFix(R.string.search_error_load_addons_failed, R.string.error_state_fix_retry_soon)
                        )
                    }
                }
                return@launch
            }

            if (generation != searchGeneration || activeSearchQuery != query) return@launch

            val searchTargets = buildSearchTargets(addons)

            // Same query against the same catalogs, and that run either finished or is still
            // arriving, so there is nothing new to fetch. Without this, pressing Done after live
            // search had already run the query tore the rows down and refetched everything, and
            // deleting a letter then retyping it did the same. A run that was cancelled part way
            // is deliberately not counted, so it gets to finish rather than staying half filled.
            val requestKey = buildRequestKey(query, searchTargets)
            val alreadySatisfied = requestKey == lastRequestKey &&
                (requestKey == lastCompletedRequestKey || activeSearchJobs.any { it.isActive })
            if (alreadySatisfied) {
                // An explicit submit that lands on a query the live search already finished still
                // counts as a search the user confirmed, so remember it instead of skipping out
                // of the history save below.
                if (rememberToHistory && catalogsMap.values.any { row -> row.items.isNotEmpty() }) {
                    viewModelScope.launch {
                        searchHistoryDataStore.saveRecentSearch(query, MAX_RECENT_SEARCHES)
                    }
                }
                return@launch
            }
            lastRequestKey = requestKey

            // Committed to a new run: drop the previous query's work and accumulated rows.
            activeSearchJobs.forEach { it.cancel() }
            activeSearchJobs = emptyList()
            catalogRowsUpdateJob?.cancel()
            resetCatalogAccumulator()

            // Rows are left alone here. Clearing them produced an empty frame between the old
            // results and the placeholders below, which is the flash this screen used to show.
            _uiState.update { it.copy(isSearching = true, error = null, installedAddons = addons) }

            if (searchTargets.isEmpty()) {
                _uiState.update {
                    it.copy(
                        isSearching = false,
                        error = context.getString(R.string.search_error_no_catalogs),
                        catalogRows = emptyList()
                    )
                }
                return@launch
            }

            // Preserve addon manifest order.
            searchTargets.forEach { (addon, catalog) ->
                val key = catalogKey(
                    addonId = addon.id,
                    addonBaseUrl = addon.baseUrl,
                    type = catalog.apiType,
                    catalogId = catalog.id
                )
                if (key !in catalogOrder) {
                    catalogOrder.add(key)
                }
            }

            // Emit placeholder rows with shimmer items so the UI shows
            // skeleton rows immediately instead of a spinner.
            val placeholderRows = searchTargets.map { (addon, catalog) ->
                val key = catalogKey(
                    addonId = addon.id,
                    addonBaseUrl = addon.baseUrl,
                    type = catalog.apiType,
                    catalogId = catalog.id
                )
                val fakeItems = (0 until 8).map { i ->
                    MetaPreview(
                        id = "__placeholder_${key}_$i",
                        type = ContentType.fromString(catalog.apiType),
                        rawType = catalog.apiType,
                        name = " ",
                        poster = PLACEHOLDER_IMAGE_URL,
                        posterShape = PosterShape.POSTER,
                        background = null,
                        logo = null,
                        description = null,
                        releaseInfo = " ",
                        imdbRating = null,
                        genres = emptyList()
                    )
                }
                CatalogRow(
                    addonId = addon.id,
                    addonName = addon.displayName,
                    addonBaseUrl = addon.baseUrl,
                    catalogId = catalog.id,
                    catalogName = catalog.name,
                    type = ContentType.fromString(catalog.apiType),
                    rawType = catalog.apiType,
                    items = fakeItems,
                    isLoading = true,
                    hasMore = false,
                    currentPage = 0,
                    supportsSkip = false,
                    skipStep = 0,
                    extraArgs = emptyMap()
                )
            }
            // Only shimmer when there is nothing real to look at. If the previous query's results
            // are still up, they stay until this query's results replace them, so refining a search
            // is a single swap rather than results -> shimmer -> results on every letter.
            _uiState.update { state ->
                val showingRealRows = state.catalogRows.any { row ->
                    row.items.firstOrNull()?.id?.startsWith("__placeholder_") != true
                }
                if (showingRealRows) state else state.copy(catalogRows = placeholderRows)
            }

            val jobs = searchTargets.map { (addon, catalog) ->
                launch {
                    loadCatalog(addon, catalog, query, generation)
                }
            }
            pendingCatalogResponses = jobs.size
            activeSearchJobs = jobs

            // Wait for all jobs to complete so we can stop showing the global loading state.
            try {
                jobs.joinAll()
            } finally {
                if (
                    generation == searchGeneration &&
                    activeSearchQuery == query &&
                    _uiState.value.submittedQuery.trim() == query
                ) {
                    lastCompletedRequestKey = requestKey
                    _uiState.update { it.copy(isSearching = false) }
                    // Only explicit submit (or moving into results from the text input — see
                    // rememberSearchFromTextInput)
                    // writes history. Live search fires per keystroke and would otherwise record
                    // every prefix ("do", "dog") even when the user never confirmed the search.
                    if (rememberToHistory && catalogsMap.values.any { row -> row.items.isNotEmpty() }) {
                        viewModelScope.launch {
                            searchHistoryDataStore.saveRecentSearch(query, MAX_RECENT_SEARCHES)
                        }
                    }
                }
            }
        }
        searchRunJob = job
    }

    private suspend fun loadCatalog(
        addon: Addon,
        catalog: CatalogDescriptor,
        query: String,
        generation: Long
    ) {
        val supportsSkip = catalog.supportsExtra("skip")
        val skipStep = catalog.skipStep()
        catalogRepository.getCatalog(
            addonBaseUrl = addon.baseUrl,
            addonId = addon.id,
            addonName = addon.displayName,
            catalogId = catalog.id,
            catalogName = catalog.name,
            type = catalog.apiType,
            skip = 0,
            skipStep = skipStep,
            extraArgs = mapOf("search" to query),
            supportsSkip = supportsSkip,
            posterScreen = com.fetch.tv.core.poster.CustomPosterScreen.SEARCH
        ).collect { result ->
            when (result) {
                is NetworkResult.Success -> {
                    if (!isCurrentSearch(generation, query)) return@collect
                    val key = catalogKey(
                        addonId = addon.id,
                        addonBaseUrl = addon.baseUrl,
                        type = catalog.apiType,
                        catalogId = catalog.id
                    )
                    catalogsMap[key] = result.data
                    pendingCatalogResponses = (pendingCatalogResponses - 1).coerceAtLeast(0)
                    scheduleCatalogRowsUpdate()
                }
                is NetworkResult.Error -> {
                    if (!isCurrentSearch(generation, query)) return@collect
                    pendingCatalogResponses = (pendingCatalogResponses - 1).coerceAtLeast(0)
                    // Ignore per-catalog errors unless we have nothing to show.
                    if (catalogsMap.isEmpty()) {
                        _uiState.update {
                            it.copy(error = errorWithFix(R.string.search_error_failed, R.string.error_state_fix_addon_generic))
                        }
                    }
                    scheduleCatalogRowsUpdate()
                }
                NetworkResult.Loading -> {
                    // No-op; screen shows global loading when empty.
                }
            }
        }
    }

    private fun isCurrentSearch(generation: Long, query: String): Boolean =
        generation == searchGeneration && _uiState.value.submittedQuery.trim() == query

    /**
     * Pages one row of the current search. The page belongs to the search run that created it:
     * it is keyed on [SearchUiState.submittedQuery] rather than the live field, and every
     * assignment back into [catalogsMap] is gated on that run still being current, so a late page
     * cannot merge into a different query's rows.
     */
    private fun loadMoreCatalogItems(catalogId: String, addonId: String, type: String) {
        val (key, currentRow) = catalogsMap.entries.firstOrNull { (_, row) ->
            row.addonId == addonId && row.apiType == type && row.catalogId == catalogId
        }?.let { it.key to it.value } ?: return

        if (currentRow.isLoading || !currentRow.hasMore) {
            return
        }

        val generation = searchGeneration
        val query = _uiState.value.submittedQuery.trim()
        if (query.isBlank()) {
            return
        }

        catalogsMap[key] = currentRow.copy(isLoading = true)
        scheduleCatalogRowsUpdate()

        val job = viewModelScope.launch {
            val addon = _uiState.value.installedAddons.find { it.id == addonId && it.baseUrl == currentRow.addonBaseUrl }
                ?: _uiState.value.installedAddons.find { it.id == addonId } ?: run {
                clearRowLoading(key, generation, query)
                return@launch
            }

            val nextSkip = currentRow.nextCatalogSkip()
            catalogRepository.getCatalog(
                addonBaseUrl = addon.baseUrl,
                addonId = addon.id,
                addonName = addon.displayName,
                catalogId = catalogId,
                catalogName = currentRow.catalogName,
                type = currentRow.apiType,
                skip = nextSkip,
                skipStep = currentRow.skipStep,
                extraArgs = mapOf("search" to query),
                supportsSkip = currentRow.supportsSkip,
                posterScreen = com.fetch.tv.core.poster.CustomPosterScreen.SEARCH
            ).collect { result ->
                when (result) {
                    is NetworkResult.Success -> {
                        if (!isCurrentSearch(generation, query)) return@collect
                        val latestRow = catalogsMap[key] ?: return@collect
                        catalogsMap[key] = latestRow.mergeCatalogPage(result.data)
                        scheduleCatalogRowsUpdate()
                    }
                    is NetworkResult.Error -> {
                        clearRowLoading(key, generation, query)
                    }
                    NetworkResult.Loading -> Unit
                }
            }
        }
        activeLoadMoreJobs.add(job)
        loadMoreRowKeys.add(key)
        job.invokeOnCompletion {
            activeLoadMoreJobs.remove(job)
            loadMoreRowKeys.remove(key)
        }
    }

    /** Drops the loading flag for a row, but only while its search run is still current. */
    private fun clearRowLoading(key: String, generation: Long, query: String) {
        if (!isCurrentSearch(generation, query)) return
        val row = catalogsMap[key] ?: return
        if (!row.isLoading) return
        catalogsMap[key] = row.copy(isLoading = false)
        scheduleCatalogRowsUpdate()
    }

    private fun scheduleCatalogRowsUpdate() {
        // A run's first paint waits for every catalog to answer, or FIRST_RESULTS_SETTLE_MS after
        // the first one does. Painting the first answer at once put the fastest addon's
        // popularity order on screen, then re-ranked it a moment later as the rest arrived, so
        // the top result changed under the user. Until then the previous query's rows (or the
        // shimmer) stay up.
        if (!hasRenderedFirstCatalog && catalogsMap.isNotEmpty()) {
            if (pendingCatalogResponses > 0) {
                // An update scheduled earlier (after an error, before any results) would otherwise
                // paint these rows before the window ends.
                catalogRowsUpdateJob?.cancel()
                if (firstRenderJob?.isActive != true) {
                    firstRenderJob = viewModelScope.launch {
                        kotlinx.coroutines.delay(FIRST_RESULTS_SETTLE_MS)
                        if (!hasRenderedFirstCatalog) {
                            hasRenderedFirstCatalog = true
                            catalogRowsUpdateJob?.cancel()
                            updateCatalogRowsNow()
                        }
                    }
                }
                return
            }
            firstRenderJob?.cancel()
            firstRenderJob = null
            catalogRowsUpdateJob?.cancel()
            hasRenderedFirstCatalog = true
            updateCatalogRowsNow()
            return
        }
        catalogRowsUpdateJob?.cancel()
        catalogRowsUpdateJob = viewModelScope.launch {
            val debounceMs = when {
                pendingCatalogResponses > 5 -> 220L
                pendingCatalogResponses > 0 -> 140L
                else -> 90L
            }
            kotlinx.coroutines.delay(debounceMs)
            updateCatalogRowsNow()
        }
    }

    private fun updateCatalogRowsNow() {
        _uiState.update { state ->
            val orderedRows = catalogOrder.map { key ->
                catalogsMap[key]
                    ?: state.catalogRows.find {
                        it.stableKey() == key
                    }
            }.filterNotNull().filter { row ->
                // Keep placeholder rows (shimmer) and rows with real items.
                // Drop rows that came back empty from the API.
                val isPlaceholder = row.isLoading &&
                    row.items.firstOrNull()?.id?.startsWith("__placeholder_") == true
                isPlaceholder || row.items.isNotEmpty()
            }
            val filteredRows = if (hideUnreleasedContent) {
                val today = LocalDate.now()
                orderedRows.map { row ->
                    if (row.isLoading && row.items.firstOrNull()?.id?.startsWith("__placeholder_") == true) {
                        row
                    } else {
                        row.filterReleasedItems(today)
                    }
                }
            } else {
                orderedRows
            }
            state.copy(
                catalogRows = filteredRows
            )
        }
    }

    /**
     * A search error as shown on screen: a fixed issue plus what to try, never the raw exception or
     * HTTP status text, which doesn't tell the user how to recover.
     */
    private fun errorWithFix(issueRes: Int, fixRes: Int): String =
        context.getString(issueRes) + "\n\n" +
            context.getString(R.string.error_state_possible_fix, context.getString(fixRes))

    private suspend fun searchAddons(): List<Addon> =
        addonRepository.getInstalledAddons().first().enabledAddons() + serverCatalog.searchAddons.first()

    /** Enabled addons and media-server catalogs, as Discover browses them. */
    private fun discoverAddons(): kotlinx.coroutines.flow.Flow<List<Addon>> =
        combine(
            addonRepository.getInstalledAddons().map { it.enabledAddons() },
            serverCatalog.searchAddons
        ) { installed, server -> installed + server }

    private suspend fun loadDiscoverCatalogs() {
        if (_uiState.value.discoverLocation == DiscoverLocation.OFF) return
        _uiState.update { it.copy(discoverLoading = true, discoverError = null) }
        val addons = try {
            discoverAddons().first()
        } catch (error: CancellationException) {
            _uiState.update { it.copy(discoverLoading = false) }
            throw error
        } catch (_: Exception) {
            // A failed read is an error with Retry, not "no catalogs installed" (#845).
            _uiState.update {
                it.copy(
                    discoverInitialized = true,
                    discoverLoading = false,
                    discoverError = errorWithFix(R.string.search_error_load_addons_failed, R.string.error_state_fix_retry_soon)
                )
            }
            return
        }
        applyDiscoverCatalogs(addons, buildDiscoverCatalogs(addons))
    }

    private fun buildDiscoverCatalogs(addons: List<Addon>): List<DiscoverCatalog> =
        addons.flatMap { addon ->
            addon.catalogs
                .filter { catalog ->
                    !(catalog.supportsExtra("search") &&
                        catalog.extra.any { it.name.equals("search", ignoreCase = true) && it.isRequired })
                }
                // A catalog with a required extra Discover has no value for can't be browsed.
                .mapNotNull { catalog -> catalog.requiredBrowseExtraArgs()?.let { catalog to it } }
                .map { (catalog, requiredArgs) ->
                    val genres = catalog.extra
                        .firstOrNull { it.name.equals("genre", ignoreCase = true) }
                        ?.options
                        .orEmpty()
                    val genreRequired = catalog.requiresExtra("genre")
                    DiscoverCatalog(
                        key = catalogRowLegacyKey(addon.id, catalog.apiType, catalog.id),
                        addonId = addon.id,
                        addonName = addon.displayName,
                        addonBaseUrl = addon.baseUrl,
                        catalogId = catalog.id,
                        catalogName = catalog.name,
                        type = catalog.apiType,
                        genres = genres,
                        supportsSkip = catalog.supportsExtra("skip"),
                        skipStep = catalog.skipStep(),
                        genreRequired = genreRequired,
                        defaultGenre = requiredArgs.entries
                            .firstOrNull { it.key.equals("genre", ignoreCase = true) }
                            ?.value,
                        requiredExtraArgs = requiredArgs.filterKeys { !it.equals("genre", ignoreCase = true) }
                    )
                }
        }

    /**
     * Puts [discoverCatalogs] on screen and loads them. The first time, the type, genre and filters
     * come from what this profile last used; on a rebuild (an addon change, #845) the ones on
     * screen are kept where the new catalogs still offer them.
     */
    private suspend fun applyDiscoverCatalogs(addons: List<Addon>, discoverCatalogs: List<DiscoverCatalog>) {
        val previousType = _uiState.value.selectedDiscoverType
        val previousGenre = _uiState.value.selectedDiscoverGenre
        val selectedType = resolveDiscoverType(
            types = discoverTypes(discoverCatalogs),
            preferredType = discoverSelectionDataStore.getSelectedType(),
            currentType = _uiState.value.selectedDiscoverType
        ) ?: "movie"
        // The sort and filters last used on this profile, the first time Discover loads (#851).
        // The filters and genre last used for this type on this profile (#851, #857); the genre
        // only when the catalogs still offer it.
        val stored = com.fetch.tv.core.util.runSuspendCatching { discoverSelectionDataStore.getFilters(selectedType) }.getOrNull()
        val storedFilters = if (discoverCatalogsLoaded) {
            _uiState.value.discoverFilters
        } else {
            stored?.toDiscoverFilters() ?: DiscoverFilters()
        }
        val keptGenre = (_uiState.value.selectedDiscoverGenre ?: stored?.genre)?.takeIf { genre ->
            discoverGenres(discoverCatalogs, selectedType).any { it.equals(genre, ignoreCase = true) }
        }

        _uiState.update {
            it.copy(
                installedAddons = addons,
                discoverCatalogs = discoverCatalogs,
                selectedDiscoverType = selectedType,
                selectedDiscoverGenre = keptGenre,
                discoverFilters = storedFilters,
                discoverInitialized = true,
                discoverLoading = false,
                discoverError = null,
                discoverHasMore = true
            )
        }
        // A rebuild for the same type and genre keeps the grid until the new first page is in.
        val keepGrid = discoverCatalogsLoaded && selectedType == previousType && keptGenre == previousGenre
        discoverCatalogsLoaded = true
        fetchDiscoverContent(reset = true, keepGrid = keepGrid)
    }

    /**
     * Each type keeps its own genre and filters (#857): Series opens with what was last chosen for
     * Series. A type never browsed since then starts from the current filters and genre, the genre
     * only when its catalogs offer it.
     */
    private fun selectDiscoverType(type: String) {
        val state = _uiState.value
        if (type == state.selectedDiscoverType) return
        val offered = discoverGenres(state.discoverCatalogs, type)
        fun offeredGenre(genre: String?) = genre?.takeIf { g -> offered.any { it.equals(g, ignoreCase = true) } }
        _uiState.update { it.copy(selectedDiscoverType = type, selectedDiscoverGenre = offeredGenre(state.selectedDiscoverGenre)) }
        discoverFiltersJob?.cancel()
        discoverFiltersJob = viewModelScope.launch {
            discoverSelectionDataStore.setSelectedType(type)
            val stored = com.fetch.tv.core.util.runSuspendCatching { discoverSelectionDataStore.getFilters(type) }.getOrNull()
            if (stored != null && _uiState.value.selectedDiscoverType == type) {
                discoverUnproductiveRounds = 0
                resetDiscoverSortedBatches()
                _uiState.update { it.copy(discoverFilters = stored.toDiscoverFilters(), selectedDiscoverGenre = offeredGenre(stored.genre)) }
            }
            fetchDiscoverContent(reset = true)
        }
    }
    private var discoverFiltersJob: kotlinx.coroutines.Job? = null

    private fun selectDiscoverGenre(genre: String?) {
        val normalized = genre?.takeIf { it.isNotBlank() }
        if (normalized == _uiState.value.selectedDiscoverGenre) return
        _uiState.update { it.copy(selectedDiscoverGenre = normalized) }
        saveDiscoverFilters(_uiState.value.discoverFilters)
        fetchDiscoverContent(reset = true)
    }

    /** Remembers [filters] and the genre for the type on screen, on this profile (#851, #857). */
    private fun saveDiscoverFilters(filters: DiscoverFilters) {
        val state = _uiState.value
        val stored = filters.toStored().copy(genre = state.selectedDiscoverGenre)
        val type = state.selectedDiscoverType
        viewModelScope.launch {
            com.fetch.tv.core.util.runSuspendCatching { discoverSelectionDataStore.setFilters(stored, type) }
        }
    }

    /** The Reset chip (#857): every filter and the genre back to their defaults. */
    private fun resetDiscoverFilters() {
        if (_uiState.value.selectedDiscoverGenre == null) {
            updateDiscoverFilters { DiscoverFilters() }
            return
        }
        discoverUnproductiveRounds = 0
        resetDiscoverSortedBatches()
        _uiState.update { it.copy(selectedDiscoverGenre = null, discoverFilters = DiscoverFilters(), discoverSortedTitleCount = 0) }
        saveDiscoverFilters(DiscoverFilters())
        fetchDiscoverContent(reset = true)
    }

    /**
     * Watched titles changed: with Hide watched on, the grid drops them at once (and takes back a
     * title marked unwatched). A sorted grid rebuilds the batches it has placed, in the same order,
     * so nothing already shown moves except the titles that changed.
     */
    private fun refilterDiscoverForWatched() {
        val filters = _uiState.value.discoverFilters
        if (!filters.hideWatched || !_uiState.value.discoverInitialized) return
        // During a keep-grid refresh the merged titles are reset and the grid on screen is the old
        // one; the new first page, or the restore if it fails, applies the watched titles (#845).
        if (discoverRefreshSnapshot != null) return
        if (!filters.sorted) {
            _uiState.update { it.copy(discoverResults = applyDiscoverFilters(discoverMergedItems, filters, discoverIsWatched)) }
            return
        }
        // Replays the batches exactly as they were placed, partial ones included, so a title from a
        // later batch can never move ahead of one already shown.
        val merged = discoverMergedItems
        val ends = discoverSortedBatchEnds.toList()
        resetDiscoverSortedBatches()
        var start = 0
        for (recordedEnd in ends) {
            val end = recordedEnd.coerceIn(start, merged.size)
            discoverSortedShown = appendDiscoverSortedBatch(
                shown = discoverSortedShown,
                batch = merged.subList(start, end),
                filters = filters,
                isWatched = discoverIsWatched
            )
            discoverSortedBatchEnds += end
            start = end
        }
        discoverSortedCount = start
        _uiState.update { it.copy(discoverResults = discoverSortedShown) }
    }

    /**
     * Year, rating and sort run on the titles already loaded. In catalog order they apply at once.
     * A Rating, Newest or Title sort orders one batch of [DISCOVER_SORT_BATCH_SIZE] titles: when
     * fewer are loaded and more can come, the rest load behind the loading state first, so the
     * order is set once rather than reshuffled as pages arrive (#843).
     */
    private fun updateDiscoverFilters(transform: (DiscoverFilters) -> DiscoverFilters) {
        val current = _uiState.value.discoverFilters
        val next = transform(current)
        if (next == current) return
        discoverUnproductiveRounds = 0
        resetDiscoverSortedBatches()
        saveDiscoverFilters(next)
        val state = _uiState.value
        val canLoadMore = state.discoverInitialized && discoverCanFetch(next)
        if (next.sorted) {
            val needsFill = canLoadMore && discoverMergedItems.size < DISCOVER_SORT_BATCH_SIZE
            if (!needsFill) placeDiscoverSortedBatch(next)
            _uiState.update {
                it.copy(
                    discoverFilters = next,
                    discoverResults = discoverSortedShown,
                    discoverSortedTitleCount = discoverSortedCount,
                    discoverHasMore = needsFill || discoverHasMoreTitles(next),
                    // A load already running carries on into the fill; it reads the new filters.
                    discoverLoading = needsFill || it.discoverLoading,
                    discoverPagingPaused = false
                )
            }
            if (needsFill) fetchDiscoverContent(reset = false)
            return
        }
        _uiState.update {
            it.copy(
                discoverFilters = next,
                discoverResults = applyDiscoverFilters(discoverMergedItems, next, discoverIsWatched),
                discoverSortedTitleCount = 0,
                // A Rating filter leaves catalogs without ratings out of paging, and clearing it
                // brings them back (#864).
                discoverHasMore = if (it.discoverInitialized) discoverCanFetch(next) else it.discoverHasMore,
                discoverPagingPaused = false
            )
        }
        // A narrower filter can leave the grid short; top it up from the catalogs still paging.
        val updated = _uiState.value
        if (discoverJob?.isActive != true &&
            canLoadMore &&
            discoverShownCount(updated.discoverResults) < DISCOVER_MIN_RESULTS
        ) {
            fetchDiscoverContent(reset = false)
        }
    }

    private fun resetDiscoverSortedBatches() {
        discoverSortedShown = emptyList()
        discoverSortedCount = 0
        discoverSortedBatchEnds.clear()
    }

    /**
     * Sorts the next batch of loaded titles, at most [DISCOVER_SORT_BATCH_SIZE], and appends it
     * below the last. A round can load more than that (several catalogs with large pages); the rest
     * waits for the next batch.
     */
    private fun placeDiscoverSortedBatch(filters: DiscoverFilters) {
        val merged = discoverMergedItems
        val start = discoverSortedCount.coerceAtMost(merged.size)
        val end = minOf(merged.size, start + DISCOVER_SORT_BATCH_SIZE)
        discoverSortedShown = appendDiscoverSortedBatch(
            shown = discoverSortedShown,
            batch = merged.subList(start, end),
            filters = filters,
            isWatched = discoverIsWatched
        )
        if (end > start) discoverSortedBatchEnds += end
        discoverSortedCount = end
    }

    /** Titles loaded but not yet placed in a sorted batch. */
    private fun discoverBufferedCount(): Int =
        (discoverMergedItems.size - discoverSortedCount).coerceAtLeast(0)

    /** Whether more can come: a catalog has more pages, or (sorted) loaded titles wait for a batch. */
    private fun discoverHasMoreTitles(filters: DiscoverFilters): Boolean =
        discoverCanFetch(filters) || (filters.sorted && discoverBufferedCount() > 0)

    private fun loadNextDiscoverResults() {
        val state = _uiState.value
        if (state.discoverLoading || state.discoverLoadingMore) return
        val filters = state.discoverFilters
        val canFetch = discoverCanFetch(filters)
        // A full batch already loaded, or the last titles once the catalogs are done, is placed
        // without a request.
        if (filters.sorted && (discoverBufferedCount() >= DISCOVER_SORT_BATCH_SIZE || (!canFetch && discoverBufferedCount() > 0))) {
            placeDiscoverSortedBatch(filters)
            _uiState.update {
                it.copy(
                    discoverResults = discoverSortedShown,
                    discoverSortedTitleCount = discoverSortedCount,
                    discoverHasMore = discoverHasMoreTitles(filters),
                    discoverPagingPaused = false
                )
            }
            return
        }
        if (!canFetch) return
        // Scroll paging never asks while paused, so a request now is the Load more cell resuming it.
        if (state.discoverPagingPaused) discoverUnproductiveRounds = 0
        fetchDiscoverContent(reset = false)
    }

    /**
     * Loads Discover from every catalog of the selected type (and genre) at once. [reset] starts
     * over from each catalog's first page; otherwise the next page comes from each catalog that has
     * more. Either way, rounds repeat while the filtered grid is still short.
     */
    private fun fetchDiscoverContent(reset: Boolean, keepGrid: Boolean = false) {
        if (!reset && discoverJob?.isActive == true) return
        discoverJob?.cancel()
        discoverJob = viewModelScope.launch {
            val state = _uiState.value
            if (state.query.trim().isNotEmpty()) return@launch
            // A refetch with the grid kept (#845): the grid as it was, put back if the new first
            // round fails everywhere, so a failed refresh never empties a working screen.
            var kept: DiscoverSnapshot? = null
            if (reset) {
                val sources = selectDiscoverSources(
                    catalogs = state.discoverCatalogs,
                    type = state.selectedDiscoverType,
                    genre = state.selectedDiscoverGenre
                )
                val keep = keepGrid && sources.isNotEmpty() && state.discoverResults.isNotEmpty()
                // A refresh replacing one still in flight keeps the first one's snapshot: by now
                // the merged titles and cursors are already reset, and only the grid is left.
                kept = if (keep) discoverRefreshSnapshot ?: discoverSnapshot() else null
                discoverRefreshSnapshot = kept
                discoverCursors = sources.map { DiscoverSourceCursor(catalog = it) }
                discoverMergedItems = emptyList()
                resetDiscoverSortedBatches()
                discoverUnproductiveRounds = 0
                _uiState.update {
                    it.copy(
                        discoverLoading = sources.isNotEmpty(),
                        discoverLoadingMore = false,
                        discoverResults = if (keep) it.discoverResults else emptyList(),
                        discoverSortedTitleCount = if (keep) it.discoverSortedTitleCount else 0,
                        discoverSourceCount = sources.size,
                        discoverHasMore = sources.isNotEmpty(),
                        discoverError = null,
                        discoverPagingPaused = false,
                        discoverUnratedSourceCount = 0
                    )
                }
                if (sources.isEmpty()) return@launch
            }

            var firstRound = reset
            var rounds = 0
            while (true) {
                if (!firstRound) {
                    _uiState.update { it.copy(discoverLoadingMore = true, discoverPagingPaused = false) }
                }
                val keepFilling = runDiscoverRound(
                    firstRound = firstRound,
                    genre = state.selectedDiscoverGenre,
                    lastSortedRound = rounds + 1 >= DISCOVER_SORT_MAX_ROUNDS,
                    keptGrid = if (firstRound) kept else null
                )
                firstRound = false
                rounds++
                if (!keepFilling) break
                // A sorted batch runs until it is full (runDiscoverRound says when). In catalog
                // order, an empty grid keeps paging past the fill cap: with every loaded title
                // hidden there is nothing to scroll, so nothing else would fetch the next page. The
                // unproductive-round pause still bounds it, and the empty state then offers Load
                // more (#789).
                if (!_uiState.value.discoverFilters.sorted &&
                    rounds > DISCOVER_MAX_FILL_ROUNDS &&
                    discoverShownCount(_uiState.value.discoverResults) > 0
                ) {
                    break
                }
            }
        }
    }

    /**
     * Which catalog a cursor pages. The row key leaves out the addon's URL, and two configurations
     * of one addon share its id, so the URL is part of it.
     */
    private fun DiscoverCatalog.sourceIdentity() = listOf(addonBaseUrl, addonId, type, catalogId)

    /** Discover's paging state and grid, so a failed refetch can put them back (#845). */
    private class DiscoverSnapshot(
        val cursors: List<DiscoverSourceCursor>,
        val merged: List<MetaPreview>,
        val sortedShown: List<MetaPreview>,
        val sortedCount: Int,
        val sortedBatchEnds: List<Int>,
        val state: SearchUiState
    )

    private fun discoverSnapshot() = DiscoverSnapshot(
        cursors = discoverCursors,
        merged = discoverMergedItems,
        sortedShown = discoverSortedShown,
        sortedCount = discoverSortedCount,
        sortedBatchEnds = discoverSortedBatchEnds.toList(),
        state = _uiState.value
    )

    /**
     * Puts back the paging state and grid a failed refetch replaced. Filters or Hide unreleased
     * changed while it ran are applied to the restored titles, so the grid matches what is set.
     */
    private fun restoreDiscoverSnapshot(snapshot: DiscoverSnapshot) {
        val filters = _uiState.value.discoverFilters
        val merged = if (hideUnreleasedContent) {
            val today = LocalDate.now()
            snapshot.merged.filterNot { it.isUnreleased(today) }
        } else {
            snapshot.merged
        }
        discoverCursors = snapshot.cursors
        discoverMergedItems = merged
        val old = snapshot.state
        val unchanged = filters == old.discoverFilters && merged.size == snapshot.merged.size
        resetDiscoverSortedBatches()
        val results = when {
            unchanged -> {
                discoverSortedShown = snapshot.sortedShown
                discoverSortedCount = snapshot.sortedCount
                discoverSortedBatchEnds += snapshot.sortedBatchEnds
                old.discoverResults
            }
            filters.sorted -> {
                placeDiscoverSortedBatch(filters)
                discoverSortedShown
            }
            else -> applyDiscoverFilters(merged, filters, discoverIsWatched)
        }
        _uiState.update {
            it.copy(
                discoverLoading = false,
                discoverLoadingMore = false,
                discoverResults = results,
                discoverSortedTitleCount = if (filters.sorted) discoverSortedCount else 0,
                discoverHasMore = discoverHasMoreTitles(filters),
                discoverError = null,
                discoverPagingPaused = if (unchanged) old.discoverPagingPaused else false,
                discoverUnratedSourceCount = snapshot.cursors.count { c -> c.sendsNoRatings }
            )
        }
        // Titles watched while the refresh ran leave the restored grid too, batches kept in place.
        refilterDiscoverForWatched()
    }

    /**
     * Fetches one page from every catalog that has more, appends them in catalog order, and
     * re-applies the filters. Returns whether the grid is still short and another round should run.
     * Under a sort, the grid only changes once a batch is full; [lastSortedRound] closes the batch
     * with what has come.
     */
    private suspend fun runDiscoverRound(
        firstRound: Boolean,
        genre: String?,
        lastSortedRound: Boolean = false,
        keptGrid: DiscoverSnapshot? = null
    ): Boolean {
        val cursors = discoverCursors
        val roundFilters = _uiState.value.discoverFilters
        val activeIndices = cursors.indices.filter { cursors[it].pageable(roundFilters) }
        if (activeIndices.isEmpty()) {
            _uiState.update {
                it.copy(discoverLoading = false, discoverLoadingMore = false, discoverHasMore = false)
            }
            return false
        }

        val results = coroutineScope {
            activeIndices.map { index -> async { fetchDiscoverPage(cursors[index], genre) } }.awaitAll()
        }
        if (_uiState.value.discoverLocation == DiscoverLocation.OFF) return false

        val mergedBefore = discoverMergedItems
        val seenBefore = mergedBefore.mapTo(HashSet()) { discoverItemKey(it) }
        val nextCursors = cursors.toMutableList()
        val pages = ArrayList<List<MetaPreview>>(activeIndices.size)
        var successes = 0
        var firstError: String? = null
        activeIndices.forEachIndexed { position, index ->
            val cursor = cursors[index]
            when (val result = results[position]) {
                is NetworkResult.Success -> {
                    successes++
                    val row = result.data
                    // Results from several addons share one grid, so each title keeps its own source.
                    val items = row.items.map { item ->
                        if (item.sourceAddonBaseUrl == null) {
                            item.copy(sourceAddonBaseUrl = cursor.catalog.addonBaseUrl)
                        } else {
                            item
                        }
                    }
                    val addsNew = items.any { discoverItemKey(it) !in seenBefore }
                    nextCursors[index] = cursor.copy(
                        nextSkip = row.nextSkip,
                        // A page with nothing new (an addon ignoring skip) ends that catalog.
                        hasMore = row.hasMore && addsNew && row.nextSkip > cursor.nextSkip,
                        failures = 0,
                        sawTitles = cursor.sawTitles || items.isNotEmpty(),
                        sawRating = cursor.sawRating || items.any { it.imdbRating != null }
                    )
                    pages.add(items)
                }
                is NetworkResult.Error -> {
                    if (firstError == null) firstError = result.message
                    // A failed page says nothing about whether the catalog has more, so it gets
                    // another try next round, but a catalog that keeps failing is left out.
                    val failures = cursor.failures + 1
                    nextCursors[index] = cursor.copy(
                        failures = failures,
                        hasMore = failures < DISCOVER_MAX_SOURCE_FAILURES
                    )
                }
                NetworkResult.Loading -> Unit
            }
        }
        val allFailed = successes == 0
        if (keptGrid != null) discoverRefreshSnapshot = null
        // A refetch that failed everywhere keeps the grid it was refreshing (#845), but only for
        // the same catalogs: after an addon change the old cursors could page a removed catalog,
        // so a failed rebuild shows the error with Retry instead.
        if (keptGrid != null && allFailed &&
            keptGrid.cursors.mapTo(HashSet()) { it.catalog.sourceIdentity() } ==
            cursors.mapTo(HashSet()) { it.catalog.sourceIdentity() }
        ) {
            restoreDiscoverSnapshot(keptGrid)
            return false
        }
        // A first page that came from somewhere dates the results, for the refetch on entry.
        if (firstRound && !allFailed) discoverLoadedAtMs = discoverClockMs()
        discoverCursors = nextCursors
        val unratedSources = nextCursors.count { it.sendsNoRatings }

        val releasedPages = if (hideUnreleasedContent) {
            val today = LocalDate.now()
            pages.map { page -> page.filterNot { it.isUnreleased(today) } }
        } else {
            pages
        }
        discoverMergedItems = appendDiscoverRound(mergedBefore, releasedPages)

        val filters = _uiState.value.discoverFilters
        val hasMore = nextCursors.any { it.pageable(filters) }
        if (filters.sorted) {
            // A round counts when it loads titles: the batch is sorted once it is full, so how
            // many the filters keep cannot tell yet, and catalogs without ratings still count.
            if (!firstRound) {
                if (discoverMergedItems.size > mergedBefore.size) discoverUnproductiveRounds = 0 else discoverUnproductiveRounds++
            }
            val paused = hasMore && (allFailed || discoverUnproductiveRounds >= DISCOVER_MAX_UNPRODUCTIVE_ROUNDS)
            val batchFull = discoverMergedItems.size - discoverSortedCount >= DISCOVER_SORT_BATCH_SIZE
            if (!hasMore || paused || batchFull || lastSortedRound) {
                placeDiscoverSortedBatch(filters)
                val error = firstError
                _uiState.update {
                    it.copy(
                        discoverLoading = false,
                        discoverLoadingMore = false,
                        discoverResults = discoverSortedShown,
                        discoverSortedTitleCount = discoverSortedCount,
                        discoverHasMore = discoverHasMoreTitles(filters),
                        discoverError = if (firstRound && allFailed) error else null,
                        discoverPagingPaused = paused,
                        discoverUnratedSourceCount = unratedSources
                    )
                }
                return false
            }
            _uiState.update { it.copy(discoverHasMore = hasMore, discoverUnratedSourceCount = unratedSources) }
            return true
        }

        val previousShown = discoverShownCount(_uiState.value.discoverResults)
        val visible = applyDiscoverFilters(discoverMergedItems, _uiState.value.discoverFilters, discoverIsWatched)
        // Counted as shown: "Not interested" titles and titles above the profile's limit are left
        // out on the way to the grid (#650, #789), so a round of hidden titles adds nothing.
        val shown = discoverShownCount(visible)
        if (!firstRound) {
            if (shown > previousShown) discoverUnproductiveRounds = 0 else discoverUnproductiveRounds++
        }
        val paused = hasMore && (allFailed || discoverUnproductiveRounds >= DISCOVER_MAX_UNPRODUCTIVE_ROUNDS)
        val error = firstError
        _uiState.update {
            it.copy(
                discoverLoading = false,
                discoverLoadingMore = false,
                discoverResults = visible,
                discoverHasMore = hasMore,
                // Only a first page that failed everywhere is an error screen; a later failure
                // keeps the grid and pauses paging instead.
                discoverError = if (firstRound && allFailed) error else null,
                discoverPagingPaused = paused,
                discoverUnratedSourceCount = unratedSources
            )
        }
        return hasMore && !paused && shown < DISCOVER_MIN_RESULTS
    }

    /** How many of [results] reach the grid after the profile limit and "Not interested". */
    private fun discoverShownCount(results: List<MetaPreview>): Int {
        val allowed = if (parentalControls.filtersContent()) results.filter(parentalControls::isAllowed) else results
        return titleVisibility.visibility.value.filterSuggestions(allowed).size
    }

    private suspend fun fetchDiscoverPage(
        cursor: DiscoverSourceCursor,
        genre: String?
    ): NetworkResult<CatalogRow> {
        val catalog = cursor.catalog
        val extraArgs = buildMap<String, String> {
            putAll(catalog.requiredExtraArgs)
            discoverGenreArg(catalog, genre)?.let { put("genre", it) }
        }
        return try {
            catalogRepository.getCatalog(
                addonBaseUrl = catalog.addonBaseUrl,
                addonId = catalog.addonId,
                addonName = catalog.addonName,
                catalogId = catalog.catalogId,
                catalogName = catalog.catalogName,
                type = catalog.type,
                skip = cursor.nextSkip,
                skipStep = catalog.skipStep,
                extraArgs = extraArgs,
                supportsSkip = catalog.supportsSkip,
                posterScreen = com.fetch.tv.core.poster.CustomPosterScreen.SEARCH
            ).first { it !is NetworkResult.Loading }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            NetworkResult.Error(context.getString(R.string.search_error_failed))
        }
    }

    private fun buildSearchTargets(addons: List<Addon>): List<Pair<Addon, CatalogDescriptor>> {
        val allSearchTargets = addons.flatMap { addon ->
            addon.catalogs
                .filter { catalog -> catalog.isSearchable() }
                .map { catalog -> addon to catalog }
        }

        return allSearchTargets
    }

    /**
     * A catalog is only searchable if a search is all it needs. One that also requires something we
     * cannot supply, a mandatory genre for instance, answers with an error or nothing at all, so
     * querying it just costs a request per keystroke and leaves a row that never fills in.
     */
    private fun CatalogDescriptor.isSearchable(): Boolean =
        supportsExtra("search") &&
            extra.none { property ->
                property.isRequired && !property.name.equals("search", ignoreCase = true)
            }

    private fun catalogKey(addonId: String, addonBaseUrl: String, type: String, catalogId: String): String {
        return catalogRowStableKey(addonId, addonBaseUrl, type, catalogId)
    }
}
