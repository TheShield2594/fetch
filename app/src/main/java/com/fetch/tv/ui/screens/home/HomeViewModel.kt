@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.fetch.tv.ui.screens.home

import com.fetch.tv.core.util.runSuspendCatching
import com.fetch.tv.data.local.hides
import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fetch.tv.LocaleCache
import com.fetch.tv.core.player.StreamAutoPlayPolicy
import com.fetch.tv.core.recommendations.TvRecommendationManager
import com.fetch.tv.core.tmdb.MetaPreviewEnricher
import com.fetch.tv.core.tmdb.TmdbMetadataService
import com.fetch.tv.core.tmdb.TmdbService
import com.fetch.tv.data.local.AuthSessionNoticeDataStore
import com.fetch.tv.data.local.CollectionsDataStore
import com.fetch.tv.data.local.LayoutPreferenceDataStore
import com.fetch.tv.data.local.PlayerSettingsDataStore
import com.fetch.tv.data.local.StartupAuthNotice
import com.fetch.tv.data.local.MDBListSettingsDataStore
import com.fetch.tv.data.local.TmdbSettingsDataStore
import com.fetch.tv.data.local.TraktSettingsDataStore
import com.fetch.tv.data.local.ContinueWatchingEnrichmentCache
import com.fetch.tv.data.mediaserver.ServerCatalog
import com.fetch.tv.data.trailer.TrailerService
import com.fetch.tv.domain.model.Addon
import com.fetch.tv.domain.model.CatalogDescriptor
import com.fetch.tv.domain.model.CatalogRow
import com.fetch.tv.domain.model.Collection
import com.fetch.tv.domain.model.ContinueWatchingSortMode
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.data.repository.MDBListRepository
import com.fetch.tv.domain.model.MDBListSettings
import com.fetch.tv.domain.model.TmdbSettings
import com.fetch.tv.ui.screens.settings.MemoryBudget
import com.fetch.tv.domain.repository.AddonRepository
import com.fetch.tv.domain.repository.CatalogRepository
import com.fetch.tv.domain.repository.LibraryRepository
import com.fetch.tv.domain.repository.MetaRepository
import com.fetch.tv.domain.repository.WatchProgressRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import com.fetch.tv.core.parental.certificationQuery
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@OptIn(kotlinx.coroutines.FlowPreview::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext internal val appContext: Context,
    internal val addonRepository: AddonRepository,
    private val addonRefreshService: com.fetch.tv.core.sync.AddonRefreshService,
    internal val catalogRepository: CatalogRepository,
    internal val watchProgressRepository: WatchProgressRepository,
    internal val libraryRepository: LibraryRepository,
    internal val metaRepository: MetaRepository,
    internal val episodeShuffleStore: com.fetch.tv.data.local.EpisodeShuffleStore,
    internal val episodeShuffle: com.fetch.tv.domain.model.EpisodeShuffle,
    internal val collectionsDataStore: CollectionsDataStore,
    internal val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    internal val playerSettingsDataStore: PlayerSettingsDataStore,
    internal val tmdbSettingsDataStore: TmdbSettingsDataStore,
    internal val mdbListSettingsDataStore: MDBListSettingsDataStore,
    internal val traktSettingsDataStore: TraktSettingsDataStore,
    internal val authSessionNoticeDataStore: AuthSessionNoticeDataStore,
    internal val tmdbService: TmdbService,
    internal val tmdbMetadataService: TmdbMetadataService,
    internal val mdbListRepository: MDBListRepository,
    internal val imdbEpisodeRatingsRepository: com.fetch.tv.data.repository.ImdbEpisodeRatingsRepository,
    internal val trailerService: TrailerService,
    internal val watchedSeriesStateHolder: com.fetch.tv.data.local.WatchedSeriesStateHolder,
    internal val cwEnrichmentCache: ContinueWatchingEnrichmentCache,
    internal val profileManager: com.fetch.tv.core.profile.ProfileManager,
    internal val tvRecommendationManager: TvRecommendationManager,
    internal val serverCatalog: ServerCatalog,
    internal val parentalControls: com.fetch.tv.core.parental.ParentalControls,
    /** "Not interested" titles and "Hide watched" (#650), applied to catalog rows after the profile limit. */
    internal val titleVisibility: com.fetch.tv.core.hidden.TitleVisibilityFilter,
    internal val bestMatchCacheDataStore: com.fetch.tv.data.local.BestMatchCacheDataStore,
    internal val episodeQueueStore: com.fetch.tv.data.local.EpisodeQueueStore,
    internal val becauseYouWatchedService: com.fetch.tv.core.recommendations.BecauseYouWatchedService,
    internal val readyChecker: HomeReadyChecker,
    internal val youTubeRow: HomeYouTubeRow,
    internal val connectivityMonitor: com.fetch.tv.core.network.ConnectivityMonitor,
    internal val metaPreviewEnricher: MetaPreviewEnricher = MetaPreviewEnricher(
        tmdbService,
        tmdbMetadataService,
        mdbListRepository
    )
) : ViewModel() {
    companion object {
        internal const val TAG = "HomeViewModel"
        /** Home sessions that show "Hold OK for options" in the hero before it goes quiet (#812). */
        internal const val HOME_HOLD_HINT_SESSIONS = 3
        internal const val STARTUP_GRACE_PERIOD_MS = 1_500L
        /** Longest a catalog-row rebuild is held back while arrivals keep restarting its debounce. */
        internal const val CATALOG_UPDATE_MAX_WAIT_MS = 600L
        internal const val CONTINUE_WATCHING_ENRICHMENT_GRACE_PERIOD_MS = 1_000L
        private const val CONTINUE_WATCHING_WINDOW_MS = 30L * 24 * 60 * 60 * 1000
        private const val MAX_RECENT_PROGRESS_ITEMS = 300
        private const val MAX_NEXT_UP_LOOKUPS = 24
        private const val MAX_NEXT_UP_CONCURRENCY = 4
        // A permit is held for the whole load, so with 3 the 4th eager row and the hero-only
        // catalogs queue behind the first three. Devices with headroom take one more at a time.
        private const val LOW_RAM_CATALOG_LOAD_CONCURRENCY = 3
        private const val CATALOG_LOAD_CONCURRENCY = 4

        /** How long a home catalog is left alone before a return to Home re-requests it. */
        private const val HOME_CATALOG_REFRESH_TTL_MS = 15L * 60L * 1000L
        internal const val EXTERNAL_META_PREFETCH_FOCUS_DEBOUNCE_MS = 220L
        internal const val EXTERNAL_META_PREFETCH_ADJACENT_DEBOUNCE_MS = 120L
        private const val MAX_ENRICHMENT_CACHE_SIZE = 64
        private const val MAX_PREFETCH_CACHE_SIZE = 64
        private const val MAX_CW_CACHE_SIZE = 64
        /**
         * Next-up resolutions, one per seed episode and unaired setting. A negative entry stops a
         * caught-up series being looked up again each run, so the cap sits well above the 300
         * recent seeds a run considers; it only bounds a session that keeps adding seeds.
         */
        private const val MAX_CW_NEXT_UP_RESOLUTION_CACHE_SIZE = 600
        private val SHARED_WATCH_HISTORY_STARTED =
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000L, replayExpirationMillis = 0L)

        private fun <K, V> createLruMap(maxSize: Int): MutableMap<K, V> {
            val lru = object : LinkedHashMap<K, V>(maxSize + 4, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean =
                    size > maxSize
            }
            return java.util.Collections.synchronizedMap(lru)
        }
    }

    /*
     * Watch history, collected once per Home (TheShield2594/fetch#824). The repository builds a
     * new chain per collector, and Continue Watching, Because you watched, Episode Shuffle and
     * the series-watched badges each collected these, so every progress save ran the merge
     * (and the per-chain metadata reset) several times over. Shared with a replay of the latest
     * value; stopped a few seconds after the last collector leaves, without keeping a stale
     * replay, so a later collector always starts from current data.
     */
    internal val sharedAllProgress: kotlinx.coroutines.flow.Flow<List<com.fetch.tv.domain.model.WatchProgress>> by lazy {
        watchProgressRepository.allProgress.sharedPerProfile()
    }
    internal val sharedWatchedItems: kotlinx.coroutines.flow.Flow<List<com.fetch.tv.domain.model.WatchedItem>> by lazy {
        watchProgressRepository.watchedItems.sharedPerProfile()
    }
    internal val sharedNextUpSeeds: kotlinx.coroutines.flow.Flow<List<com.fetch.tv.domain.model.WatchProgress>> by lazy {
        watchProgressRepository.observeNextUpSeeds().sharedPerProfile()
    }

    /**
     * Shares [this] once, restarted for each profile and tagged with it. The replayed value can be
     * the previous profile's right after a switch, so collectors only see values tagged with the
     * profile active now: a pipeline restarted for the new profile never runs on (or saves) the
     * old profile's history.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun <T> kotlinx.coroutines.flow.Flow<T>.sharedPerProfile(): kotlinx.coroutines.flow.Flow<T> {
        val upstream = this
        val shared = profileManager.activeProfileId
            .flatMapLatest { profileId -> upstream.map { profileId to it } }
            .shareIn(viewModelScope, SHARED_WATCH_HISTORY_STARTED, replay = 1)
        return shared
            .filter { (profileId, _) -> profileId == profileManager.currentProfileId() }
            .map { (_, value) -> value }
    }

    internal val _uiState = MutableStateFlow(HomeUiState())
    /** A Continue Watching removal waiting out its undo window (#816). */
    internal val _pendingCwRemoval = MutableStateFlow<PendingContinueWatchingRemoval?>(null)
    val pendingContinueWatchingRemoval: StateFlow<PendingContinueWatchingRemoval?> = _pendingCwRemoval.asStateFlow()
    internal var cwRemovalCommitJob: Job? = null
    internal var cwRemovalTokenCounter = 0L
    internal val shuffleHomeRefresh = MutableStateFlow(HomeShuffleRefresh())
    val uiState: StateFlow<HomeUiState> by lazy { createShuffleHomeState() }

    /**
     * Watchlist movies Ready tonight may show, newest first and within the profile's limits.
     * Starts empty so Home never waits on the library.
     */
    internal val readyWatchlistCandidates: kotlinx.coroutines.flow.Flow<List<com.fetch.tv.domain.model.LibraryEntry>> by lazy {
        kotlinx.coroutines.flow.combine(
            libraryRepository.libraryItems,
            libraryRepository.listTabs,
            libraryRepository.sourceMode,
            parentalControls.filterRevision
        ) { entries, tabs, mode, _ ->
            parentalControls.filterAllowed(watchlistMovies(entries, tabs, mode)) { it.toMetaPreview().certificationQuery() }
        }
            .onStart { emit(emptyList()) }
            .distinctUntilChanged()
    }

    /** Background ready check for Continue Watching and the watchlist; see [HomeReadyChecker]. */
    suspend fun runReadyCheck(items: List<ContinueWatchingItem>) =
        readyChecker.check(items, readyWatchlistCandidates.first())

    fun beginShuffleHomeVisit() {
        shuffleHomeRefresh.update { it.copy(visit = it.visit + 1) }
    }

    /** Last Best match per video; the hero reads only this, so focus moves query no addon. */
    val cachedBestMatches: kotlinx.coroutines.flow.Flow<Map<String, com.fetch.tv.data.local.CachedBestMatch>>
        get() = bestMatchCacheDataStore.entries

    /** Videos in the profile's episode queue, for the Continue Watching long-press option. */
    val queuedVideoIds: kotlinx.coroutines.flow.Flow<Set<String>>
        get() = episodeQueueStore.items.map { items -> items.mapTo(HashSet()) { it.videoId } }

    fun toggleQueued(item: ContinueWatchingItem) {
        val queueItem = item.queueItemOrNull() ?: return
        viewModelScope.launch {
            val queued = episodeQueueStore.items.first().any { it.videoId == queueItem.videoId }
            if (queued) episodeQueueStore.remove(queueItem.videoId) else episodeQueueStore.add(queueItem)
        }
    }

    internal val _modernHomePresentation = MutableStateFlow(ModernHomePresentationState())
    val modernHomePresentation: StateFlow<ModernHomePresentationState> = _modernHomePresentation.asStateFlow()

    internal val _movieWatchedStatus = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val movieWatchedStatus: StateFlow<Map<String, Boolean>> = _movieWatchedStatus.asStateFlow()

    // Pending batch of watched status updates — debounced before emission.
    internal val _pendingWatchedBatch = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    /** True once the CW pipeline has completed its first emission (items or empty). */
    internal val _initialCwResolved = MutableStateFlow(false)
    val initialCwResolved: StateFlow<Boolean> = _initialCwResolved.asStateFlow()
    val effectiveAutoplayEnabled = playerSettingsDataStore.playerSettings
        .map(StreamAutoPlayPolicy::isEffectivelyEnabled)
        .distinctUntilChanged()
    internal val _fullCatalogRows = MutableStateFlow<List<CatalogRow>>(emptyList())
    val fullCatalogRows: StateFlow<List<CatalogRow>> = _fullCatalogRows.asStateFlow()

    private val _focusState = MutableStateFlow(HomeScreenFocusState())
    val focusState: StateFlow<HomeScreenFocusState> = _focusState.asStateFlow()

    private val _scrollToTopTrigger = MutableStateFlow(0)
    val scrollToTopTrigger: StateFlow<Int> = _scrollToTopTrigger.asStateFlow()

    /** The profile's Movies / Shows filter above the rows (#814). */
    val homeContentFilter: StateFlow<HomeContentFilter> = layoutPreferenceDataStore.homeContentFilter
        .map(HomeContentFilter::fromStored)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, HomeContentFilter.ALL)

    /**
     * The filter last picked, written one at a time. Conflated, so quick Left/Right presses write
     * only what is still wanted, and the last pick is always the one that ends up saved.
     */
    private val requestedContentFilter = MutableStateFlow<HomeContentFilter?>(null)

    @Suppress("unused")
    private val contentFilterWriter = viewModelScope.launch {
        requestedContentFilter.filterNotNull().collect { filter ->
            runSuspendCatching {
                layoutPreferenceDataStore.setHomeContentFilter(filter.takeIf { it != HomeContentFilter.ALL }?.name)
            }
        }
    }

    fun setHomeContentFilter(filter: HomeContentFilter) {
        requestedContentFilter.value = filter
    }

    /**
     * Play/Pause on a catalog card starts it without the detail page (#812). A kids profile and
     * one with parental limits keep OK's behaviour, as the detail page is where those checks show.
     */
    val catalogQuickPlayAllowed: StateFlow<Boolean> = parentalControls.activeRestrictions
        .map { !it.isKidsProfile && !it.isRestricted }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _showHoldHint = MutableStateFlow(false)

    /** "Hold OK for options" in the hero, for a profile's first [HOME_HOLD_HINT_SESSIONS] Home sessions (#812). */
    val showHoldHint: StateFlow<Boolean> = _showHoldHint.asStateFlow()

    private var holdHintChecked = false
    private var holdHintJob: Job? = null

    /** A new profile gets its own hold-hint sessions, checked on its first card focus. */
    internal fun resetHoldHintForProfile() {
        holdHintJob?.cancel()
        holdHintJob = null
        holdHintChecked = false
        _showHoldHint.value = false
        requestedContentFilter.value = null
    }

    /** Call once a card on Home has focus: counts this session towards the hold hint's few. */
    fun onHomeCardFocused() {
        if (holdHintChecked) return
        holdHintChecked = true
        holdHintJob = viewModelScope.launch {
            val sessions = runSuspendCatching { layoutPreferenceDataStore.homeHoldHintSessions.first() }.getOrNull()
                ?: return@launch
            if (sessions >= HOME_HOLD_HINT_SESSIONS) return@launch
            _showHoldHint.value = true
            runSuspendCatching { layoutPreferenceDataStore.recordHomeHoldHintSession() }
        }
    }

    internal val _currentLocaleTag = MutableStateFlow(LocaleCache.localeTag)

    fun notifyLocaleChanged() {
        val tag = LocaleCache.localeTag
        if (_currentLocaleTag.value != tag) {
            _currentLocaleTag.value = tag
        }
    }

    fun requestScrollToTop() {
        clearFocusState()
        _scrollToTopTrigger.value++
    }

    internal val _loadingCatalogs = MutableStateFlow<Set<String>>(emptySet())

    internal val _enrichingItemId = MutableStateFlow<String?>(null)
    val enrichingItemId: StateFlow<String?> = _enrichingItemId.asStateFlow()
    internal fun setEnrichingItemId(id: String?) { _enrichingItemId.value = id }

    internal val _lastEnrichedPreview = MutableStateFlow<MetaPreview?>(null)
    val lastEnrichedPreview: StateFlow<MetaPreview?> = _lastEnrichedPreview.asStateFlow()

    internal val _enrichedPreviews = MutableStateFlow<Map<String, MetaPreview>>(emptyMap())
    val enrichedPreviews: StateFlow<Map<String, MetaPreview>> = _enrichedPreviews.asStateFlow()

    internal fun addEnrichedPreview(id: String, preview: MetaPreview) {
        _enrichedPreviews.update { current ->
            val updated = current + (id to preview)
            if (updated.size <= MAX_ENRICHMENT_CACHE_SIZE) updated
            else LinkedHashMap(updated).apply { while (size > MAX_ENRICHMENT_CACHE_SIZE) remove(keys.first()) }
        }
    }

    /** Items for which enrichment was attempted but produced no enriched data. */
    internal val _failedEnrichmentIds = MutableStateFlow<Set<String>>(emptySet())
    val failedEnrichmentIds: StateFlow<Set<String>> = _failedEnrichmentIds.asStateFlow()

    /**
     * Bounded like the enrichment and prefetch caches beside it. This set is only a hint for the
     * hero, telling it to stop waiting and render catalog data, so evicting the oldest entry costs
     * at most one frame of the loading state the next time that item is focused.
     *
     * Insertion order is part of the contract even though the type is a plain Set: eviction drops
     * the oldest entry, and both this and clearEnrichmentFailure preserve order.
     */
    internal fun markEnrichmentFailed(id: String) {
        _failedEnrichmentIds.update { current ->
            if (id in current) return@update current
            val updated = LinkedHashSet(current).apply { add(id) }
            while (updated.size > MAX_ENRICHMENT_CACHE_SIZE) {
                updated.remove(updated.first())
            }
            updated
        }
    }

    /** Enrichment succeeded after a previous failure, so the item is no longer a failed one. */
    internal fun clearEnrichmentFailure(id: String) {
        _failedEnrichmentIds.update { current -> if (id in current) current - id else current }
    }

    /** Drops every failure marker, for the resets that clear the caches beside this one. */
    internal fun clearEnrichmentFailures() {
        _failedEnrichmentIds.value = emptySet()
    }

    internal val catalogStateLock = Any()
    internal val catalogsMap = linkedMapOf<String, CatalogRow>()
    internal val catalogItemKeyIndex = mutableMapOf<String, MutableSet<String>>()
    internal val catalogOrder = mutableListOf<String>()
    internal var addonsCache: List<Addon> = emptyList()
    internal var collectionsCache: List<Collection> = emptyList()
    internal var homeCatalogOrderKeys: List<String> = emptyList()
    internal var disabledHomeCatalogKeys: Set<String> = emptySet()
    internal var pinnedHomeCatalogKeys: List<String> = emptyList()
    internal var followAddonsOrderEnabled: Boolean = false
    internal var customCatalogTitles: Map<String, String> = emptyMap()
    internal var catalogUpdateJob: Job? = null
    internal var hasRenderedFirstCatalog = false
    internal val catalogLoadSemaphore = Semaphore(
        if (MemoryBudget.isLowRamTier) LOW_RAM_CATALOG_LOAD_CONCURRENCY else CATALOG_LOAD_CONCURRENCY
    )
    internal var pendingCatalogLoads = 0
    /** When the oldest not-yet-run [scheduleUpdateCatalogRows] request was made; 0 when none waits. */
    internal var catalogUpdateFirstRequestedAtMs = 0L
    internal val activeCatalogLoadJobs = mutableSetOf<Job>()
    internal var activeCatalogLoadSignature: String? = null
    internal var catalogLoadGeneration: Long = 0L
    internal var catalogsLoadInProgress: Boolean = false
    // Written on Main (focus requests) and on IO (the lookup's completion and enrichment merges),
    // so both sets must be safe for concurrent access (#810).
    internal val trailerPreviewLoadingIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    internal val trailerPreviewNegativeCache: MutableSet<String> = ConcurrentHashMap.newKeySet()
    internal val trailerPreviewUrlsState = mutableStateMapOf<String, String>()
    internal val trailerPreviewAudioUrlsState = mutableStateMapOf<String, String>()
    @Volatile internal var activeTrailerPreviewItemId: String? = null
    @Volatile internal var trailerPreviewRequestVersion: Long = 0L
    internal var trailerPreviewJob: Job? = null
    internal var currentTmdbSettings: TmdbSettings = TmdbSettings()
    internal var currentMdbListSettings: MDBListSettings = MDBListSettings()
    internal val modernCarouselRowBuildCache = ModernCarouselRowBuildCache()
    internal val prefetchedExternalMetaIds: MutableSet<String> = Collections.newSetFromMap(createLruMap(MAX_PREFETCH_CACHE_SIZE))
    internal val backgroundMetaPrefetchedIds: MutableSet<String> = Collections.newSetFromMap(createLruMap(MAX_PREFETCH_CACHE_SIZE))
    internal val externalMetaPrefetchInFlightIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    internal var externalMetaPrefetchJob: Job? = null
    internal var pendingExternalMetaPrefetchItemId: String? = null
    internal val prefetchedTmdbIds: MutableSet<String> = Collections.newSetFromMap(createLruMap(MAX_PREFETCH_CACHE_SIZE))

    /** Items an enrichment merge was applied for. */
    internal val enrichmentMergedIds: MutableSet<String> = Collections.newSetFromMap(createLruMap(MAX_PREFETCH_CACHE_SIZE))

    internal val mdbBatchNegativeIds = MdbListNegativeCache()
    internal val mdbBatchInFlightIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile internal var mdbBatchFocusedRowKey: String? = null
    internal var mdbBatchRowFocusJob: Job? = null
    @Volatile internal var mdbBatchHasFired: Boolean = false
    internal val cwMetaCache: MutableMap<String, CwMetaSummary?> = createLruMap(MAX_CW_CACHE_SIZE)
    internal val cwMetaNegativeCacheTimestamps: MutableMap<String, Long> = createLruMap(MAX_CW_CACHE_SIZE)
    /** When each cwMetaCache hit was fetched, so episode lists refresh while the app stays open. */
    internal val cwMetaCachedAtMs: MutableMap<String, Long> = createLruMap(MAX_CW_CACHE_SIZE)
    /** Ultra-light cache for badge evaluation: contentId → set of aired (season, episode) pairs. */
    internal val cwBadgeEpisodeCache: MutableMap<String, Set<Pair<Int, Int>>?> = createLruMap(MAX_CW_CACHE_SIZE)
    /** Per-series earliest upcoming season release date (epochMs) for smart TTL scheduling. */
    internal val cwBadgeNextSeasonMs: MutableMap<String, Long> = createLruMap(MAX_CW_CACHE_SIZE)
    /** Snapshot of watchedShowEpisodes keys from the last badge evaluation cycle. */
    @Volatile
    internal var cwLastBadgeEpisodeKeys: Set<String> = emptySet()
    /** Cached show ID siblings from the last badge evaluation cycle (for anime ID expansion). */
    @Volatile
    internal var cwLastShowIdSiblings: Map<String, Set<String>> = emptyMap()
    internal val cwTmdbIdCache: MutableMap<String, String?> = createLruMap(MAX_CW_CACHE_SIZE)
    // Bounded LRU (TheShield2594/fetch#826). Access-ordered: iterate only under the map's lock and
    // never `get` while iterating its keys.
    // The recheck of processed older seeds keys on this cache's "no next episode" entries. An
    // evicted one is remembered in cwEvictedNegativeNextUpContentIds, so the seed is still
    // rechecked (and its deadline still scheduled) once its validation goes stale.
    internal val cwNextUpResolutionCache: MutableMap<String, NextUpResolution?> =
        boundedLruMap(MAX_CW_NEXT_UP_RESOLUTION_CACHE_SIZE) { key, value ->
            val contentId = key.substringBefore('|')
            // Only processed older seeds are rechecked, so only they need remembering; this keeps
            // the set within cwProcessedOlderSeedContentIds (one entry per series in the history).
            if (value == null && contentId in cwProcessedOlderSeedContentIds) {
                cwEvictedNegativeNextUpContentIds.add(contentId)
            }
        }
    /** Processed older seeds whose "no next episode" entry the bounded cache evicted. */
    internal val cwEvictedNegativeNextUpContentIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    internal val cwNextUpNegativeCacheTimestamps: MutableMap<String, Long> =
        boundedLruMap(MAX_CW_NEXT_UP_RESOLUTION_CACHE_SIZE)
    internal val discoveredOlderNextUpItems = Collections.synchronizedList(mutableListOf<ContinueWatchingItem.NextUp>())
    internal val cwLastProcessedNextUpContentIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    internal val cwProcessedOlderSeedContentIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Shows hidden from Up Next at the last pipeline run, to spot ones restored since. */
    @Volatile internal var cwLastNextUpDismissalIds: Set<String>? = null
    /** Shows restored to Up Next that the older-seed pass hasn't tried yet. */
    internal val cwRestoredNextUpIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    internal val cwEnrichedNextUpOverlay = ConcurrentHashMap<String, NextUpInfo>()
    /** In-memory cache of enriched InProgress items per contentId+episode key. */
    internal val cwEnrichedInProgressOverlay = ConcurrentHashMap<String, ContinueWatchingItem.InProgress>()
    /** Bumped to force the CW pipeline to re-run (e.g. after cache clear). */
    internal val cwPipelineRefreshTrigger = kotlinx.coroutines.flow.MutableStateFlow(0)
    /** Tracks the active CW pipeline coroutine so it can be cancelled on profile switch. */
    internal var cwPipelineJob: Job? = null
    /** Reruns the CW pipeline when the next caught-up series' revalidation deadline passes. */
    @Volatile
    internal var cwOlderSeedRecheckJob: Job? = null
    internal val fullyWatchedSeriesIds get() = watchedSeriesStateHolder
    internal var tmdbEnrichFocusJob: Job? = null
    // Claimed on Main, compared and released on IO by the focus enrichment job (#810).
    @Volatile internal var pendingTmdbEnrichItemId: String? = null
    /** Item that was focused during startup grace period — will be enriched once grace ends. */
    internal var deferredEnrichItem: MetaPreview? = null
    internal var adjacentItemPrefetchJob: Job? = null
    @Volatile internal var pendingAdjacentPrefetchItemId: String? = null
    internal val movieWatchedObserverJobs = mutableMapOf<String, Job>()
    internal var movieWatchedBatchJob: Job? = null
    internal var lastMovieWatchedItemKeys: Set<String> = emptySet()
    internal var seriesWatchedObserverJob: Job? = null
    /** Series the running [seriesWatchedObserverJob] covers; null forces the next reconcile to restart it. */
    internal var lastSeriesWatchedItemKeys: Map<String, String>? = null
    @Volatile
    internal var externalMetaPrefetchEnabled: Boolean = false
    @Volatile
    internal var continueWatchingSortMode: ContinueWatchingSortMode = ContinueWatchingSortMode.DEFAULT
    internal val startupStartedAtMs: Long = SystemClock.elapsedRealtime()
    @Volatile
    internal var startupGracePeriodActive: Boolean = true
    internal var startupAuthNoticeJob: Job? = null

    // Lazy catalog loading
    internal val eagerCatalogLoadCount: Int = 4
    internal val lazyLoadRequestedKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()
    internal val pendingLazyCatalogs = linkedMapOf<String, Pair<Addon, CatalogDescriptor>>()
    /** Rows whose load failed and that are waiting for a retry, by catalog key (#799). */
    internal val failedHomeCatalogs = ConcurrentHashMap<String, FailedHomeCatalog>()
    internal val failedCatalogRetryJobs = ConcurrentHashMap<String, Job>()
    internal val retryingHomeCatalogKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Restructured page 1 of the focused row, by row stable key, applied once focus leaves. */
    internal val deferredFocusedRowRefreshes = ConcurrentHashMap<String, DeferredHomeRowRefresh>()
    /** All placeholder descriptors for homeRow construction. */
    internal data class PlaceholderDescriptor(
        val catalogKey: String,
        val addonId: String,
        val addonName: String,
        val addonBaseUrl: String,
        val catalogId: String,
        val catalogName: String,
        val apiType: String,
        val displayTitle: String
    )
    internal val placeholderDescriptors = mutableListOf<PlaceholderDescriptor>()
    val trailerPreviewUrls: Map<String, String>
        get() = trailerPreviewUrlsState
    val trailerPreviewAudioUrls: Map<String, String>
        get() = trailerPreviewAudioUrlsState

    init {
        // Accumulates individual watched status changes and flushes them as a single
        // update after 150ms of inactivity, preventing N separate recompositions.
        viewModelScope.launch {
            _pendingWatchedBatch
                .debounce(150L)
                .collect { batch ->
                    if (batch.isNotEmpty()) {
                        val snapshot = _pendingWatchedBatch.value
                        _pendingWatchedBatch.value = emptyMap()
                        if (snapshot.isNotEmpty()) {
                            _movieWatchedStatus.update { current -> current + snapshot }
                        }
                    }
                }
        }

        observeStartupAuthNotice()
        observeNetworkReach()
        viewModelScope.launch {
            profileManager.activeProfileReady.first { it }
            observeLayoutPreferences()
            observeModernHomePresentation()
            loadContinueWatching()
            watchedSeriesStateHolder.loadFromDisk(profileManager.currentProfileId())
            observeExternalMetaPrefetchPreference()
            observeContinueWatchingSortMode()
            loadHomeCatalogOrderPreference()
            loadFollowAddonsOrder()
            loadDisabledHomeCatalogPreference()
            loadPinnedHomeCatalogPreference()
            loadCustomCatalogTitles()
            observeTmdbSettings()
            observeBecauseYouWatchedPipeline()
            observeMdbListSettings()
            observeBlurUnwatchedEpisodes()
            observeProgressSourceChanges()
            observeCollections()
            observeInstalledAddons()
            observeManualAddonRefresh()
            viewModelScope.launch {
                // Rows are filtered by the profile limit; refilter when it or a lookup changes.
                parentalControls.filterRevision.drop(1).collect { scheduleUpdateCatalogRows() }
            }
            viewModelScope.launch {
                // And by the profile's hidden titles and "Hide watched" (#650).
                titleVisibility.visibility.drop(1).collect { scheduleUpdateCatalogRows() }
            }

            // Clear CW state when profile changes so items don't leak between profiles.
            var previousProfileId = profileManager.activeProfileId.value
            profileManager.activeProfileId.collect { newId ->
                if (newId != previousProfileId) {
                    previousProfileId = newId
                    resetHoldHintForProfile()
                    // Cancel old pipeline — prevents racing writes from stale coroutines.
                    cwPipelineJob?.cancel()
                    cwPipelineJob = null
                    // Clear all in-memory CW caches so data from the previous
                    // profile doesn't leak into the new one.
                    cwMetaCache.clear()
                    cwMetaNegativeCacheTimestamps.clear()
                    cwMetaCachedAtMs.clear()
                    cwBadgeEpisodeCache.clear()
                    cwBadgeNextSeasonMs.clear()
                    cwTmdbIdCache.clear()
                    cwNextUpResolutionCache.clear()
                    cwNextUpNegativeCacheTimestamps.clear()
                    discoveredOlderNextUpItems.clear()
                    cwLastProcessedNextUpContentIds.clear()
                    cwProcessedOlderSeedContentIds.clear()
                    cwEvictedNegativeNextUpContentIds.clear()
                    cwRestoredNextUpIds.clear()
                    cwEnrichedNextUpOverlay.clear()
                    cwEnrichedInProgressOverlay.clear()
                    cwLastBadgeEpisodeKeys = emptySet()
                    cwLastShowIdSiblings = emptyMap()
                    _uiState.update {
                        it.copy(
                            layoutPreferencesReady = false,
                            continueWatchingItems = emptyList(),
                            becauseYouWatchedRows = emptyList()
                        )
                    }
                    clearFocusState()
                                // Reset so the new profile's pipeline signals first completion correctly.
                    _initialCwResolved.value = false
                    loadContinueWatching()
                    // Clear watched badges so they don't leak between profiles.
                    watchedSeriesStateHolder.clearInMemory()
                    watchedSeriesStateHolder.loadFromDisk(profileId = newId)
                    _movieWatchedStatus.value = emptyMap()
                    _pendingWatchedBatch.value = emptyMap()
                    _uiState.update { it.copy(movieWatchedStatus = emptyMap()) }
                    // The running observer reconciles and saves for the previous profile.
                    seriesWatchedObserverJob?.cancel()
                    seriesWatchedObserverJob = null
                    lastSeriesWatchedItemKeys = null
                }
            }
        }
        viewModelScope.launch {
            delay(STARTUP_GRACE_PERIOD_MS)
            startupGracePeriodActive = false
            // Trigger enrichment for the initial focused item once grace ends.
            deferredEnrichItem?.let { item ->
                deferredEnrichItem = null
                onItemFocusPipeline(item)
            }
        }

        // Observe manual cache clear from Advanced settings.
        viewModelScope.launch {
            var lastSeen = cwEnrichmentCache.cacheCleared.value
            cwEnrichmentCache.cacheCleared.collect { version ->
                if (version != lastSeen) {
                    lastSeen = version
                    clearAllCwInMemoryCaches()
                }
            }
        }
    }

    private fun clearAllCwInMemoryCaches() {
        cwMetaCache.clear()
        cwMetaNegativeCacheTimestamps.clear()
        cwMetaCachedAtMs.clear()
        cwBadgeEpisodeCache.clear()
        cwBadgeNextSeasonMs.clear()
        cwTmdbIdCache.clear()
        cwNextUpResolutionCache.clear()
        cwNextUpNegativeCacheTimestamps.clear()
        discoveredOlderNextUpItems.clear()
        cwLastProcessedNextUpContentIds.clear()
        cwProcessedOlderSeedContentIds.clear()
        cwEvictedNegativeNextUpContentIds.clear()
        cwRestoredNextUpIds.clear()
        cwEnrichedNextUpOverlay.clear()
        cwEnrichedInProgressOverlay.clear()
        cwLastBadgeEpisodeKeys = emptySet()
        cwLastShowIdSiblings = emptyMap()
        watchedSeriesStateHolder.clearValidationState(profileManager.currentProfileId())
        _uiState.update { it.copy(continueWatchingItems = emptyList(), upcomingItems = emptyList()) }
        // Bump trigger so the pipeline's collectLatest restarts with fresh state.
        cwPipelineRefreshTrigger.value++
    }

    internal fun remainingContinueWatchingEnrichmentGraceMs(
        nowMs: Long = SystemClock.elapsedRealtime()
    ): Long {
        return (CONTINUE_WATCHING_ENRICHMENT_GRACE_PERIOD_MS - (nowMs - startupStartedAtMs))
            .coerceAtLeast(0L)
    }

    private fun observeLayoutPreferences() = observeLayoutPreferencesPipeline()

    private fun observeModernHomePresentation() = observeModernHomePresentationPipeline()

    private fun observeExternalMetaPrefetchPreference() = observeExternalMetaPrefetchPreferencePipeline()

    private fun observeContinueWatchingSortMode() {
        viewModelScope.launch {
            var initial = true
            layoutPreferenceDataStore.continueWatchingSortMode
                .distinctUntilChanged()
                .collect { mode ->
                    continueWatchingSortMode = mode
                    if (initial) {
                        initial = false
                        return@collect
                    }
                    // Clear caches so the new sort is applied immediately on next pipeline run
                    clearAllCwInMemoryCaches()
                }
        }
    }

    private fun observeBlurUnwatchedEpisodes() {
        viewModelScope.launch {
            layoutPreferenceDataStore.blurContinueWatchingNextUp
                .distinctUntilChanged()
                .collect { enabled ->
                    _uiState.update { it.copy(blurUnwatchedEpisodes = enabled) }
                }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.useEpisodeThumbnailsInCw
                .distinctUntilChanged()
                .collect { enabled ->
                    _uiState.update { it.copy(useEpisodeThumbnailsInCw = enabled) }
                }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.continueWatchingEnabled
                .distinctUntilChanged()
                .collect { enabled ->
                    _uiState.update { it.copy(continueWatchingEnabled = enabled) }
                }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.continueWatchingCardStyle
                .distinctUntilChanged()
                .collect { style ->
                    _uiState.update { it.copy(continueWatchingCardStyle = style) }
                }
        }
        viewModelScope.launch {
            var initialPattern = true
            layoutPreferenceDataStore.customPosterUrlPattern
                .distinctUntilChanged()
                .collect { pattern ->
                    _uiState.update { it.copy(customPosterUrlPattern = pattern) }
                    if (initialPattern) {
                        initialPattern = false
                    } else {
                        refreshVisibleCatalogsPipeline(forceReplace = true)
                    }
                }
        }
        viewModelScope.launch {
            var initialScreens = true
            layoutPreferenceDataStore.customPosterEnabledScreens
                .distinctUntilChanged()
                .collect { screens ->
                    _uiState.update { it.copy(customPosterEnabledScreens = screens) }
                    if (initialScreens) {
                        initialScreens = false
                    } else {
                        refreshVisibleCatalogsPipeline(forceReplace = true)
                    }
                }
        }
        // When "next up from furthest episode" changes, clear CW caches and retrigger pipeline
        viewModelScope.launch {
            var initial = true
            layoutPreferenceDataStore.nextUpFromFurthestEpisode
                .collect {
                    if (initial) {
                        initial = false
                        return@collect
                    }
                    clearAllCwInMemoryCaches()
                }
        }
        // Episode artwork is chosen while enriching, so cached items must be rebuilt when the setting changes.
        viewModelScope.launch {
            var initial = true
            layoutPreferenceDataStore.useEpisodeThumbnailsInCw
                .distinctUntilChanged()
                .collect {
                    if (initial) {
                        initial = false
                        return@collect
                    }
                    clearAllCwInMemoryCaches()
                }
        }
    }

    fun requestTrailerPreview(item: MetaPreview) = requestTrailerPreviewPipeline(item)

    fun requestTrailerPreview(
        itemId: String,
        title: String,
        releaseInfo: String?,
        apiType: String
    ) = requestTrailerPreviewPipeline(
        itemId = itemId,
        title = title,
        releaseInfo = releaseInfo,
        apiType = apiType
    )

    fun onItemFocus(item: MetaPreview) = onItemFocusPipeline(item)

    /** Order keys of the rows pinned to the top of Home. */
    val pinnedRowKeys: StateFlow<List<String>> by lazy {
        layoutPreferenceDataStore.pinnedHomeCatalogKeys
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    }

    fun toggleRowPinned(rowKey: String) {
        viewModelScope.launch { layoutPreferenceDataStore.togglePinnedHomeCatalogKey(rowKey) }
    }

    fun preloadAdjacentItem(item: MetaPreview) = preloadAdjacentItemPipeline(item)

    private fun loadHomeCatalogOrderPreference() = loadHomeCatalogOrderPreferencePipeline()

    private fun loadFollowAddonsOrder() = loadFollowAddonsOrderPipeline()

    private fun loadDisabledHomeCatalogPreference() = loadDisabledHomeCatalogPreferencePipeline()

    private fun loadPinnedHomeCatalogPreference() = loadPinnedHomeCatalogPreferencePipeline()

    private fun loadCustomCatalogTitles() = loadCustomCatalogTitlesPipeline()

    private fun observeTmdbSettings() = observeTmdbSettingsPipeline()

    private fun observeMdbListSettings() {
        viewModelScope.launch {
            mdbListSettingsDataStore.settings
                .distinctUntilChanged()
                .collectLatest { settings ->
                    currentMdbListSettings = settings
                    _uiState.update {
                        it.copy(
                            mdbListShowOnHero = settings.showOnHero,
                            mdbListRatingOrder = settings.enabledRatingOrder()
                        )
                    }
                }
        }
    }

    /**
     * When the watch-progress source changes (e.g. Trakt login/logout, or
     * switching between Trakt and Fetch Sync), clear the CW disk cache and
     * in-memory state so items from the old source don't leak into the new one.
     */
    private fun observeProgressSourceChanges() {
        viewModelScope.launch {
            var previousSource: com.fetch.tv.data.local.WatchProgressSource? = null
            var previousProfileId: Int? = null
            traktSettingsDataStore.watchProgressSource
                .collect { source ->
                    val currentProfileId = profileManager.activeProfileId.value
                    val profileChanged = previousProfileId != null && previousProfileId != currentProfileId
                    if (previousSource != null && previousSource != source && !profileChanged) {
                        // Genuine in-profile source change — clear CW caches to prevent mixing.
                        cwMetaCache.clear()
                        cwEnrichedNextUpOverlay.clear()
                        cwEnrichedInProgressOverlay.clear()
                        discoveredOlderNextUpItems.clear()
                        cwLastProcessedNextUpContentIds.clear()
                        // Clear disk cache for current profile.
                        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching { cwEnrichmentCache.saveNextUpSnapshot(emptyList(), force = true) }
                            runCatching { cwEnrichmentCache.saveInProgressSnapshot(emptyList(), force = true) }
                        }
                        // Reload CW from fresh source.
                        loadContinueWatching()
                    }
                    previousSource = source
                    previousProfileId = currentProfileId
                }
        }
    }

    private fun observeStartupAuthNotice() {
        viewModelScope.launch {
            authSessionNoticeDataStore.pendingNotice.collect { notice ->
                if (notice == null) return@collect
                _uiState.update { state ->
                    if (state.startupAuthNotice == notice) state else state.copy(startupAuthNotice = notice)
                }
                startupAuthNoticeJob?.cancel()
                // Long enough to read from the couch; the notice is consumed and won't show again.
                startupAuthNoticeJob = viewModelScope.launch {
                    delay(8000)
                    clearStartupAuthNotice(notice)
                }
                authSessionNoticeDataStore.consumeNotice(notice)
            }
        }
    }

    private fun clearStartupAuthNotice(notice: StartupAuthNotice) {
        _uiState.update { state ->
            if (state.startupAuthNotice == notice) {
                state.copy(startupAuthNotice = null)
            } else {
                state
            }
        }
    }

    fun onEvent(event: HomeEvent) {
        when (event) {
            is HomeEvent.OnLoadMoreCatalog -> loadMoreCatalogItems(event.catalogId, event.addonId, event.type)
            is HomeEvent.OnRemoveContinueWatching -> requestContinueWatchingRemoval(
                contentId = event.contentId,
                season = event.season,
                episode = event.episode,
                isNextUp = event.isNextUp
            )
            HomeEvent.OnUndoRemoveContinueWatching -> undoContinueWatchingRemoval()
            HomeEvent.OnCommitPendingRemoval -> commitPendingContinueWatchingRemoval()
            HomeEvent.OnRetry -> viewModelScope.launch { loadAllCatalogs(addonsCache, forceReload = true) }
        }
    }

    private fun loadContinueWatching() {
        // Immediately restore last known CW from disk cache for instant display.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // Both snapshots from the same profile, even if it changes between the two reads.
            val profileId = profileManager.currentProfileId()
            val cachedInProgress = runCatching { cwEnrichmentCache.getInProgressSnapshot(profileId) }.getOrDefault(emptyList())
            val cachedNextUp = runCatching { cwEnrichmentCache.getNextUpSnapshot(profileId) }.getOrDefault(emptyList())
            if (cachedInProgress.isEmpty() && cachedNextUp.isEmpty()) return@launch
            val dismissedNextUp = traktSettingsDataStore.nextUpDismissals.first()
            // Render cached items immediately — don't wait for Trakt/allProgress.
            // The pipeline will replace these with live data once it completes.
            val inProgressItems = cachedInProgress
                .filter { !watchProgressRepository.isDroppedShow(it.contentId) }
                .map { cached ->
                    ContinueWatchingItem.InProgress(
                        progress = com.fetch.tv.domain.model.WatchProgress(
                            contentId = cached.contentId,
                            contentType = cached.contentType,
                            name = cached.name,
                            poster = cached.poster,
                            backdrop = cached.backdrop,
                            logo = cached.logo,
                            videoId = cached.videoId,
                            season = cached.season,
                            episode = cached.episode,
                            episodeTitle = cached.episodeTitle,
                            position = cached.position,
                            duration = cached.duration,
                            lastWatched = cached.lastWatched,
                            progressPercent = cached.progressPercent
                        ),
                        episodeThumbnail = cached.episodeThumbnail,
                        episodeDescription = cached.episodeDescription,
                        episodeImdbRating = cached.episodeImdbRating,
                        genres = cached.genres,
                        releaseInfo = cached.releaseInfo
                    )
                }
            val nextUpItems = cachedNextUp
                .filter { !watchProgressRepository.isDroppedShow(it.contentId) }
                .filter { !dismissedNextUp.hides(it) }
                .map { cached ->
                ContinueWatchingItem.NextUp(
                    info = NextUpInfo(
                        contentId = cached.contentId,
                        contentType = cached.contentType,
                        name = cached.name,
                        poster = cached.poster,
                        backdrop = cached.backdrop,
                        logo = cached.logo,
                        videoId = cached.videoId,
                        season = cached.season,
                        episode = cached.episode,
                        episodeTitle = cached.episodeTitle,
                        episodeDescription = cached.episodeDescription,
                        thumbnail = cached.thumbnail,
                        released = cached.released,
                        hasAired = cached.hasAired,
                        airDateLabel = cached.airDateLabel,
                        lastWatched = cached.lastWatched,
                        imdbRating = cached.imdbRating,
                        genres = cached.genres,
                        releaseInfo = cached.releaseInfo,
                        sortTimestamp = cached.sortTimestamp,
                        releaseTimestamp = cached.releaseTimestamp,
                        isReleaseAlert = cached.isReleaseAlert,
                        isNewSeasonRelease = cached.isNewSeasonRelease,
                        seedSeason = cached.seedSeason,
                        seedEpisode = cached.seedEpisode,
                        contentLanguage = cached.contentLanguage
                    )
                )
            }
            val sortMode = layoutPreferenceDataStore.continueWatchingSortMode.first()
            val items = mergeContinueWatchingItems(
                inProgressItems = inProgressItems,
                nextUpItems = nextUpItems,
                mode = sortMode
            )
            // A profile switch while this ran has already cleared Home for the new profile.
            if (items.isNotEmpty() && profileManager.currentProfileId() == profileId) {
                val (mainItems, upcomingOnly) = splitUpcomingItems(items, sortMode)
                _uiState.update { it.copy(continueWatchingItems = mainItems, upcomingItems = upcomingOnly) }
                _initialCwResolved.value = true
            }
        }
        loadContinueWatchingPipeline()
    }


    private fun observeCollections() = observeCollectionsPipeline()

    private fun observeInstalledAddons() = observeInstalledAddonsPipeline()

    /**
     * Set when the catalogs were last loaded or refreshed, so returning to Home right after
     * the initial load does not immediately re-request everything. Monotonic, so a clock
     * correction cannot block or force a refresh.
     */
    internal var lastHomeCatalogRefreshAtMs: Long = 0L

    /**
     * Row the user currently has focus on, reported by the Home content as it changes. Kept out
     * of [focusState] on purpose: that one is passed down to the Home composables, and emitting
     * on every vertical move would recompose the whole row list.
     *
     * It deliberately survives leaving Home: the refresh runs on the way back in, before the row
     * list has had a chance to report focus again, so clearing it there would shield nothing.
     */
    @Volatile
    internal var liveFocusedRowKey: String? = null

    /** Called by the Home content when the focused row changes. */
    fun setLiveFocusedRowKey(rowKey: String?) {
        liveFocusedRowKey = rowKey
        onFocusedRowChangedForMdbBatch(rowKey)
        applyDeferredRowRefreshes(rowKey)
    }

    /**
     * Called when Home comes back to the foreground, whether from another screen or from
     * outside the app.  Re-requests page 1 of the catalogs already loaded when the last
     * refresh is older than [HOME_CATALOG_REFRESH_TTL_MS], at most once per interval, and
     * merges each result into the row that is already on screen.
     */
    /**
     * The user pressed refresh in addon settings. Home is still on the back stack, so its
     * catalogs are re-requested right away rather than waiting for the interval or for the
     * user to come back. The same merge rules apply, so a row is never rebuilt under focus.
     */
    private fun observeManualAddonRefresh() {
        viewModelScope.launch {
            addonRefreshService.manualAddonRefreshes.collect {
                if (addonsCache.isEmpty()) return@collect
                lastHomeCatalogRefreshAtMs = android.os.SystemClock.elapsedRealtime()
                refreshVisibleCatalogsPipeline(requestedByUser = true)
            }
        }
    }

    fun refreshHomeCatalogsIfStale() {
        youTubeRow.onHomeVisible()
        if (addonsCache.isEmpty()) return
        // Failed rows are retried on every return, not only once the TTL has passed: there is
        // nothing on screen to refresh, so waiting would only keep the row missing (#799).
        retryFailedHomeCatalogsPipeline()
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastHomeCatalogRefreshAtMs < HOME_CATALOG_REFRESH_TTL_MS) return
        lastHomeCatalogRefreshAtMs = now
        refreshVisibleCatalogsPipeline()
    }

    /**
     * Tracks the network so Home can say it is offline rather than show a raw error, and
     * reloads on its own once the network is back (#447).
     */
    private fun observeNetworkReach() {
        viewModelScope.launch {
            var previous: com.fetch.tv.core.network.NetworkReach? = null
            // Server connections are per profile, so the link is worked out again whenever they
            // change, not only when the network does.
            kotlinx.coroutines.flow.combine(
                connectivityMonitor.reach,
                serverCatalog.addons.onStart { emit(emptyList()) }
            ) { reach, _ -> reach }.collect { reach ->
                val serverLibrary = if (reach == com.fetch.tv.core.network.NetworkReach.ONLINE) {
                    null
                } else {
                    com.fetch.tv.core.util.runSuspendCatching { serverCatalog.titleLibraries().firstOrNull() }.getOrNull()?.let { ref ->
                        HomeServerLibraryLink(
                            title = ref.title,
                            catalogId = ref.library.id,
                            addonId = com.fetch.tv.data.mediaserver.ServerCatalog.addonId(ref.connection.id),
                            type = ref.library.kind.contentType
                        )
                    }
                }
                _uiState.update {
                    if (it.networkReach == reach && it.offlineServerLibrary == serverLibrary) it
                    else it.copy(networkReach = reach, offlineServerLibrary = serverLibrary)
                }
                val state = _uiState.value
                if (reach != previous && addonsCache.isNotEmpty()) {
                    when (homeReloadOnReach(previous, reach, state.catalogRows.isNotEmpty(), state.errorKind)) {
                        HomeReachReload.FULL -> {
                            android.util.Log.d(TAG, "Network back ($previous -> $reach); reloading Home catalogs")
                            loadAllCatalogs(addonsCache, forceReload = true)
                        }
                        HomeReachReload.VISIBLE_ROWS -> {
                            // Rows kept from the cache stay; refresh them in place.
                            lastHomeCatalogRefreshAtMs = android.os.SystemClock.elapsedRealtime()
                            refreshVisibleCatalogsPipeline(requestedByUser = true)
                        }
                        HomeReachReload.NONE -> Unit
                    }
                }
                previous = reach
            }
        }
    }

    private suspend fun loadAllCatalogs(addons: List<Addon>, forceReload: Boolean = false) =
        loadAllCatalogsPipeline(addons, forceReload)

    private fun loadCatalog(addon: Addon, catalog: CatalogDescriptor, generation: Long) =
        loadCatalogPipeline(addon, catalog, generation)

    private fun loadMoreCatalogItems(catalogId: String, addonId: String, type: String) =
        loadMoreCatalogItemsPipeline(catalogId, addonId, type)

    internal fun scheduleUpdateCatalogRows() {
        catalogUpdateJob?.cancel()
        // Each call restarts the debounce, so a steady trickle of arrivals could otherwise put
        // the rebuild off indefinitely. Cap how long the first waiting request is held.
        val now = android.os.SystemClock.elapsedRealtime()
        val firstRequestedAt = catalogUpdateFirstRequestedAtMs.takeIf { it > 0L } ?: now
        catalogUpdateFirstRequestedAtMs = firstRequestedAt
        catalogUpdateJob = viewModelScope.launch {
            val debounceMs = when {
                // First render: use a moderate debounce so near-simultaneous
                // catalog arrivals are batched into a single heavy update pass.
                !hasRenderedFirstCatalog && hasAnyCatalogRows() -> {
                    hasRenderedFirstCatalog = true
                    150L
                }
                // During bulk loading, batch aggressively — placeholders are
                // already visible so the user won't notice the delay.
                pendingCatalogLoads > 8 -> 300L
                pendingCatalogLoads > 3 -> 250L
                pendingCatalogLoads > 0 -> 200L
                else -> 80L
            }
            val waitedMs = android.os.SystemClock.elapsedRealtime() - firstRequestedAt
            delay(debounceMs.coerceAtMost((CATALOG_UPDATE_MAX_WAIT_MS - waitedMs).coerceAtLeast(0L)))
            catalogUpdateFirstRequestedAtMs = 0L
            updateCatalogRows()
        }
    }

    /**
     * Called from the UI when a placeholder catalog row becomes visible.
     */
    fun requestLazyCatalogLoad(catalogKey: String) {
        if (catalogKey in lazyLoadRequestedKeys) {
            return
        }
        val pair = synchronized(catalogStateLock) {
            pendingLazyCatalogs.remove(catalogKey)
        }
        if (pair == null) {
            return
        }
        if (!lazyLoadRequestedKeys.add(catalogKey)) {
            return
        }
        val (addon, catalog) = pair
        val generation = catalogLoadGeneration
        pendingCatalogLoads = (pendingCatalogLoads + 1)
        loadCatalogPipeline(addon, catalog, generation)
    }

    private suspend fun updateCatalogRows() = updateCatalogRowsPipeline()

    internal var posterStatusReconcileJob: Job? = null

    /**
     * Saves the current focus and scroll state for restoration when returning to this screen.
     */
    // When true, the next saveFocusState call is suppressed and the flag
    // is reset.  Used during layout switches to prevent the outgoing
    // layout's onDispose from poisoning the incoming layout's focus state.
    internal var suppressFocusSave: Boolean = false

    fun saveFocusState(
        verticalScrollIndex: Int,
        verticalScrollOffset: Int,
        focusedRowKey: String?,
        focusedItemKeyByRow: Map<String, String>,
        catalogRowScrollStates: Map<String, Int>,
        catalogRowScrollAnchors: Map<String, String>,
        focusedRowIndex: Int = 0,
        focusedItemIndex: Int = 0
    ) {
        if (suppressFocusSave) {
            suppressFocusSave = false
            return
        }
        val nextState = _focusState.value.copy(
            verticalScrollIndex = verticalScrollIndex,
            verticalScrollOffset = verticalScrollOffset,
            focusedRowKey = focusedRowKey,
            focusedItemKeyByRow = focusedItemKeyByRow,
            catalogRowScrollStates = catalogRowScrollStates,
            catalogRowScrollAnchors = catalogRowScrollAnchors,
            focusedRowIndex = focusedRowIndex,
            focusedItemIndex = focusedItemIndex,
            hasSavedFocus = true
        )
        if (_focusState.value == nextState) return
        _focusState.value = nextState
    }

    /**
     * Clears the saved focus state.
     */
    fun clearFocusState() {
        _focusState.value = HomeScreenFocusState()
    }

    override fun onCleared() {
        // Leaving the app commits a removal still in its undo window. The view model's own scope
        // is already cancelled here, so the writes run on a scope of their own.
        commitPendingContinueWatchingRemoval(
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        )
        startupAuthNoticeJob?.cancel()
        posterStatusReconcileJob?.cancel()
        movieWatchedBatchJob?.cancel()
        seriesWatchedObserverJob?.cancel()
        cancelInFlightCatalogLoads()
        movieWatchedObserverJobs.values.forEach { it.cancel() }
        movieWatchedObserverJobs.clear()
        super.onCleared()
    }
}
