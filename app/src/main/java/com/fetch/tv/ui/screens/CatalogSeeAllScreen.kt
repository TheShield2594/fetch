@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.fetch.tv.ui.screens

import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fetch.tv.ui.theme.cardCornerRadius
import com.fetch.tv.ui.components.PosterGridSkeleton
import com.fetch.tv.domain.model.catalogRowLegacyKey
import com.fetch.tv.ui.theme.FetchTheme

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.fetch.tv.ui.util.dpadRepeatThrottle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.data.mediaserver.messageRes
import com.fetch.tv.ui.components.EmptyScreenState
import com.fetch.tv.ui.components.ErrorState
import com.fetch.tv.ui.components.GridContentCard
import com.fetch.tv.ui.components.LoadingIndicator
import com.fetch.tv.ui.components.PosterCardDefaults
import com.fetch.tv.ui.components.PosterCardStyle
import com.fetch.tv.ui.screens.home.HomeEvent
import com.fetch.tv.ui.screens.home.HomeViewModel
import com.fetch.tv.ui.screens.search.SearchEvent
import com.fetch.tv.ui.screens.search.SearchViewModel
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.legacyKey
import com.fetch.tv.domain.model.stableItemKeys
import com.fetch.tv.domain.model.stableKey
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

/** Stable focus key for a catalog poster (survives list reloads; not a grid index). */
private fun catalogItemFocusKey(item: MetaPreview): String = "${item.apiType}:${item.id}"

@Composable
fun CatalogSeeAllScreen(
    catalogId: String,
    addonId: String,
    type: String,
    searchViewModel: SearchViewModel? = null,
    serverCatalog: ServerCatalogViewModel? = null,
    viewModel: HomeViewModel = hiltViewModel(),
    posterOptionsViewModel: com.fetch.tv.ui.components.posteroptions.PosterOptionsViewModel = hiltViewModel(),
    filtersViewModel: CatalogSeeAllFiltersViewModel = hiltViewModel(),
    onNavigateToDetail: (String, String, String) -> Unit,
    onBackPress: () -> Unit
) {
    val posterOptionsController = searchViewModel?.posterOptions ?: posterOptionsViewModel.controller
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val fullCatalogRows by viewModel.fullCatalogRows.collectAsStateWithLifecycle()
    val computedHeightDp = (uiState.posterCardWidthDp * 1.5f).roundToInt()
    val posterCardStyle = PosterCardStyle(
        width = uiState.posterCardWidthDp.dp,
        height = computedHeightDp.dp,
        cornerRadius = cardCornerRadius(uiState.posterCardCornerRadiusDp),
        focusedScale = PosterCardDefaults.Style.focusedScale
    )

    BackHandler { onBackPress() }

    val isSearchMode = searchViewModel != null
    val catalogKey = catalogRowLegacyKey(addonId, type, catalogId)

    // In search mode, get the catalog row from SearchViewModel's existing results.
    // Otherwise fall back to HomeViewModel's fullCatalogRows (home screen catalogs).
    val searchUiState = searchViewModel?.uiState?.collectAsStateWithLifecycle()
    val searchWatchedMovieIds = searchViewModel?.watchedMovieIds?.collectAsStateWithLifecycle()
    val searchWatchedSeriesIds = searchViewModel?.watchedSeriesIds?.collectAsStateWithLifecycle()
    val searchCatalogRow = searchUiState?.value?.catalogRows?.find {
        it.legacyKey() == catalogKey
    }
    val homeCatalogRow = fullCatalogRows.find {
        it.legacyKey() == catalogKey
    }
    val serverRow = serverCatalog?.row?.collectAsStateWithLifecycle()
    val serverFailure = serverCatalog?.failure?.collectAsStateWithLifecycle()
    val sourceRow = when {
        serverCatalog != null -> serverRow?.value
        isSearchMode -> searchCatalogRow
        else -> homeCatalogRow
    }

    LaunchedEffect(catalogKey, isSearchMode, sourceRow != null) {
        if (!isSearchMode && serverCatalog == null && sourceRow == null) {
            viewModel.requestLazyCatalogLoad(catalogKey)
        }
    }

    // Genre, year, rating, sort and hide-watched (#651). A genre swaps in its own paged row; the
    // rest filter whichever row is up.
    LaunchedEffect(addonId, type, catalogId, isSearchMode) {
        filtersViewModel.bind(addonId, type, catalogId, fromSearch = isSearchMode)
    }
    val filterState by filtersViewModel.state.collectAsStateWithLifecycle()
    val genreActive = filterState.genre != null
    // Home's watched map only covers titles in Home's rows; a genre page can hold others.
    val genreWatchedMovieIds by filtersViewModel.watchedMovieIds.collectAsStateWithLifecycle()
    val genreWatchedSeriesIds by filtersViewModel.watchedSeriesIds.collectAsStateWithLifecycle()
    val useWatchedSets = isSearchMode || genreActive
    val watchedMovieSet = if (isSearchMode) searchWatchedMovieIds?.value.orEmpty() else genreWatchedMovieIds
    val watchedSeriesSet = if (isSearchMode) searchWatchedSeriesIds?.value.orEmpty() else genreWatchedSeriesIds
    val isWatched: (MetaPreview) -> Boolean = { item ->
        if (useWatchedSets) {
            val isSeries = item.apiType.equals("series", ignoreCase = true) || item.apiType.equals("tv", ignoreCase = true)
            val ids = if (isSeries) watchedSeriesSet else watchedMovieSet
            item.id in ids || item.imdbId?.let { it in ids } == true
        } else {
            uiState.movieWatchedStatus[
                com.fetch.tv.ui.screens.home.homeItemStatusKey(item.id, item.apiType)
            ] == true
        }
    }
    val baseRow = if (genreActive) filterState.genreRow else sourceRow
    val watchedSnapshot = if (filterState.hideWatched) {
        if (useWatchedSets) watchedMovieSet to watchedSeriesSet else uiState.movieWatchedStatus
    } else {
        null
    }
    val catalogRow = remember(baseRow, filterState.filters, filterState.hideWatched, watchedSnapshot) {
        baseRow?.let { row ->
            val visible = seeAllVisibleItems(row.items, filterState.filters, filterState.hideWatched, isWatched)
            if (visible === row.items) row else row.copy(items = visible)
        }
    }

    // A Home or Search catalog that never arrives (it failed, or Retry / an addon change dropped
    // it) mustn't leave the skeleton up forever: once nothing is loading, wait briefly, then show
    // the empty state with Retry.
    var missingCatalogWaitExpired by remember(catalogKey) { mutableStateOf(false) }
    var missingCatalogRetries by remember(catalogKey) { mutableIntStateOf(0) }
    val sourceStillLoading = if (isSearchMode) searchUiState?.value?.isSearching == true else uiState.isLoading
    LaunchedEffect(catalogKey, sourceRow == null, sourceStillLoading, missingCatalogRetries) {
        missingCatalogWaitExpired = false
        if (serverCatalog == null && sourceRow == null && !sourceStillLoading) {
            kotlinx.coroutines.delay(MISSING_CATALOG_WAIT_MS)
            missingCatalogWaitExpired = true
        }
    }

    val gridState = rememberLazyGridState()
    val restoreFocusRequester = remember { FocusRequester() }
    // Persist the focused catalog item by stable id (not grid index) so return-from-Details
    // can re-focus the same poster even if the row reloads or the first cell steals focus.
    var focusedItemKey by rememberSaveable(catalogKey) { mutableStateOf<String?>(null) }
    var shouldRestoreFocus by rememberSaveable(catalogKey) { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current

    val focusedItemIndex = remember(catalogRow?.items, focusedItemKey) {
        val items = catalogRow?.items.orEmpty()
        if (items.isEmpty()) return@remember 0
        val key = focusedItemKey
        if (key.isNullOrBlank()) return@remember 0
        items.indexOfFirst { catalogItemFocusKey(it) == key }.takeIf { it >= 0 } ?: 0
    }

    val requestNextPage: (com.fetch.tv.domain.model.CatalogRow) -> Unit = { row ->
        if (row.hasMore && !row.isLoading) {
            if (genreActive) {
                filtersViewModel.loadMoreGenre()
            } else if (serverCatalog != null) {
                serverCatalog.loadMore()
            } else if (isSearchMode) {
                searchViewModel.onEvent(
                    SearchEvent.LoadMoreCatalog(row.catalogId, row.addonId, row.apiType)
                )
            } else {
                viewModel.onEvent(
                    HomeEvent.OnLoadMoreCatalog(row.catalogId, row.addonId, row.apiType)
                )
            }
        }
    }

    // A row whose loaded titles are all hidden ("Not interested", the profile limit) has no grid
    // to scroll, so scroll paging never asks for the next page. Fetch a few on its own, then leave
    // it to the Load more action in the empty state (#789).
    var emptyGridAutoPages by remember(catalogKey, filterState.genre, filterState.filters, filterState.hideWatched) {
        mutableIntStateOf(0)
    }
    val emptyRowWithMore = catalogRow != null && catalogRow.items.isEmpty() && catalogRow.hasMore
    LaunchedEffect(catalogKey, emptyRowWithMore, catalogRow?.isLoading) {
        val row = catalogRow ?: return@LaunchedEffect
        if (!emptyRowWithMore) {
            if (row.items.isNotEmpty()) emptyGridAutoPages = 0
            return@LaunchedEffect
        }
        if (row.isLoading || emptyGridAutoPages >= EMPTY_GRID_AUTO_PAGES) return@LaunchedEffect
        emptyGridAutoPages++
        requestNextPage(row)
    }

    // Load more when scrolling near the bottom
    LaunchedEffect(gridState, catalogRow?.items?.size) {
        snapshotFlow {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = gridState.layoutInfo.totalItemsCount
            lastVisible to total
        }
            .distinctUntilChanged()
            .collect { (lastVisible, total) ->
                if (total > 0 && lastVisible >= total - 10) {
                    catalogRow?.let(requestNextPage)
                }
            }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                shouldRestoreFocus = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(shouldRestoreFocus, catalogRow?.items?.size, focusedItemKey) {
        if (!shouldRestoreFocus) return@LaunchedEffect
        val items = catalogRow?.items.orEmpty()
        if (items.isEmpty()) return@LaunchedEffect

        val targetIndex = focusedItemIndex.coerceIn(0, items.lastIndex)
        val isTargetVisible = gridState.layoutInfo.visibleItemsInfo.any { it.index == targetIndex }
        if (!isTargetVisible) {
            gridState.animateScrollToItem(targetIndex)
        }
        repeat(2) { withFrameNanos { } }
        try {
            restoreFocusRequester.requestFocus()
            shouldRestoreFocus = false
        } catch (_: IllegalStateException) {
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = FetchTheme.spacing.xl)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = FetchTheme.spacing.screen.horizontal),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = catalogRow?.catalogName ?: stringResource(R.string.catalog_see_all_title_fallback),
                style = MaterialTheme.typography.headlineLarge,
                color = FetchTheme.colors.TextPrimary
            )
        }

        if (uiState.catalogAddonNameEnabled) {
            catalogRow?.addonName?.let { addonName ->
                Text(
                    modifier = Modifier.padding(horizontal = FetchTheme.spacing.screen.horizontal),
                    text = stringResource(R.string.catalog_see_all_from, addonName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = FetchTheme.colors.TextSecondary
                )
            }
        }

        if (sourceRow != null) {
            com.fetch.tv.ui.screens.search.SeeAllFilterBar(
                genres = filterState.genres,
                selectedGenre = filterState.genre,
                filters = filterState.filters,
                hideWatched = filterState.hideWatched,
                onSelectGenre = { genre -> filtersViewModel.selectGenre(genre, sourceRow) },
                onSelectYearRange = filtersViewModel::selectYearRange,
                onSelectMinRating = filtersViewModel::selectMinRating,
                onSelectSort = filtersViewModel::selectSort,
                onToggleHideWatched = filtersViewModel::toggleHideWatched,
                modifier = Modifier.padding(
                    start = FetchTheme.spacing.screen.horizontal,
                    end = FetchTheme.spacing.screen.horizontal,
                    top = FetchTheme.spacing.md
                )
            )
        }

        Spacer(modifier = Modifier.height(FetchTheme.spacing.xl))

        val hasItems = catalogRow?.items?.isNotEmpty() == true
        val failure = serverFailure?.value
        val isCatalogLoading = when {
            genreActive && filterState.genreFailed -> false
            genreActive -> catalogRow?.isLoading ?: true
            catalogRow != null -> catalogRow.isLoading
            serverCatalog != null -> failure == null
            else -> !missingCatalogWaitExpired
        }

        if (hasItems) {
            val seeAllItemKeys = remember(catalogRow?.items) {
                catalogRow?.stableItemKeys().orEmpty()
            }
            Box(modifier = Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(
                        minSize = if (com.fetch.tv.ui.components.LocalLandscapePosterMode.current) posterCardStyle.height else posterCardStyle.width
                    ),
                    modifier = Modifier.dpadRepeatThrottle(),
                    contentPadding = PaddingValues(
                        start = FetchTheme.spacing.screen.horizontal,
                        end = FetchTheme.spacing.screen.horizontal,
                        top = FetchTheme.spacing.md,
                        bottom = FetchTheme.spacing.xxl
                    ),
                    horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md),
                    verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.lg)
                ) {
                    itemsIndexed(
                        items = catalogRow.items,
                        key = { index, _ -> seeAllItemKeys.getOrElse(index) { "${catalogRow.stableKey()}_$index" } }
                    ) { index, item ->
                        val isWatched = isWatched(item)
                        val itemFocusKey = catalogItemFocusKey(item)
                        GridContentCard(
                            item = item,
                            posterCardStyle = posterCardStyle,
                            showLabel = uiState.posterLabelsEnabled,
                            isWatched = isWatched,
                            focusRequester = if (index == focusedItemIndex) restoreFocusRequester else null,
                            onFocused = {
                                // While restoring after Details/resume, ignore transient focus on the
                                // first/leftmost cell so it cannot overwrite the saved poster key.
                                if (shouldRestoreFocus &&
                                    focusedItemKey != null &&
                                    itemFocusKey != focusedItemKey
                                ) {
                                    return@GridContentCard
                                }
                                focusedItemKey = itemFocusKey
                            },
                            onClick = {
                                focusedItemKey = itemFocusKey
                                onNavigateToDetail(
                                    item.id,
                                    item.apiType,
                                    catalogRow.addonBaseUrl
                                )
                            },
                            onLongPress = {
                                focusedItemKey = itemFocusKey
                                posterOptionsController.show(item, catalogRow.addonBaseUrl)
                            }
                        )
                    }

                    if (catalogRow.isLoading) {
                        item(key = "loading_more") {
                            val cardShape = remember(posterCardStyle.cornerRadius) {
                                androidx.compose.foundation.shape.RoundedCornerShape(posterCardStyle.cornerRadius)
                            }
                            val globalLandscape = com.fetch.tv.ui.components.LocalLandscapePosterMode.current
                            Column(
                                modifier = Modifier
                                    .then(if (globalLandscape) Modifier.fillMaxWidth() else Modifier.width(posterCardStyle.width))
                            ) {
                                androidx.tv.material3.Card(
                                    onClick = {},
                                    modifier = Modifier
                                        .then(
                                            if (globalLandscape) Modifier.fillMaxWidth().aspectRatio(com.fetch.tv.domain.model.PosterShape.LANDSCAPE.aspectRatio())
                                            else Modifier.width(posterCardStyle.width).height(posterCardStyle.height)
                                        )
                                        .then(Modifier.focusProperties { canFocus = false }),
                                    shape = androidx.tv.material3.CardDefaults.shape(shape = cardShape),
                                    colors = androidx.tv.material3.CardDefaults.colors(
                                        containerColor = FetchTheme.colors.BackgroundCard,
                                        focusedContainerColor = FetchTheme.colors.BackgroundCard
                                    )
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(cardShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        LoadingIndicator()
                                    }
                                }
                                Spacer(
                                    modifier = Modifier
                                        .then(if (globalLandscape) Modifier.fillMaxWidth() else Modifier.width(posterCardStyle.width))
                                        .padding(top = FetchTheme.spacing.sm)
                                        .height(MaterialTheme.typography.titleMedium.lineHeight.value.dp)
                                )
                            }
                        }
                    }
                }
            }
        } else if (genreActive && filterState.genreFailed) {
            ErrorState(
                message = stringResource(R.string.catalog_see_all_genre_failed),
                onRetry = { filtersViewModel.retryGenre(sourceRow) },
                requestInitialFocus = false
            )
        } else if (catalogRow == null && failure != null && serverCatalog != null) {
            // A media server that failed to load isn't an empty catalog: say why and offer Retry.
            // Nothing else on the screen can take focus, so Retry does.
            ErrorState(
                message = stringResource(failure.messageRes()),
                onRetry = serverCatalog::reload,
                requestInitialFocus = true
            )
        } else if (isCatalogLoading) {
            PosterGridSkeleton(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = FetchTheme.spacing.screen.horizontal, top = FetchTheme.spacing.md),
                cardWidth = posterCardStyle.width,
                cardHeight = posterCardStyle.height
            )
        } else if (catalogRow != null && !catalogRow.hasMore && filterState.isFiltering &&
            (baseRow?.items?.isNotEmpty() == true || (genreActive && sourceRow?.items?.isNotEmpty() == true))
        ) {
            // The filters, not the catalog, emptied the grid.
            EmptyScreenState(
                title = stringResource(R.string.discover_empty_filtered_title),
                subtitle = stringResource(R.string.discover_empty_filtered_subtitle),
                icon = Icons.Default.GridView,
                action = com.fetch.tv.ui.components.StateAction(stringResource(R.string.discover_clear_filters)) {
                    filtersViewModel.clearFilters(sourceRow)
                }
            )
        } else if (catalogRow != null && catalogRow.hasMore) {
            // Everything loaded so far is hidden, but later pages may hold titles (#789).
            EmptyScreenState(
                title = stringResource(R.string.discover_empty_hidden_title),
                subtitle = stringResource(R.string.discover_empty_hidden_subtitle),
                icon = Icons.Default.GridView,
                action = com.fetch.tv.ui.components.StateAction(stringResource(R.string.discover_load_more)) {
                    emptyGridAutoPages = 0
                    requestNextPage(catalogRow)
                }
            )
        } else {
            EmptyScreenState(
                title = stringResource(R.string.catalog_see_all_empty_title),
                subtitle = stringResource(R.string.catalog_see_all_empty_subtitle),
                icon = Icons.Default.GridView,
                // A Home catalog that is missing altogether can be loaded again from here.
                action = if (catalogRow == null && !isSearchMode && serverCatalog == null) {
                    com.fetch.tv.ui.components.StateAction(stringResource(R.string.action_retry)) {
                        viewModel.onEvent(HomeEvent.OnRetry)
                        missingCatalogRetries++
                    }
                } else {
                    null
                }
            )
        }

        val posterOptionsState by posterOptionsController.state.collectAsStateWithLifecycle()
        com.fetch.tv.ui.components.posteroptions.PosterOptionsHost(
            state = posterOptionsState,
            controller = posterOptionsController,
            onNavigateToDetail = { id, type2, addonBaseUrl ->
                onNavigateToDetail(id, type2, addonBaseUrl)
            },
            // A media server's catalog is the user's own library, which "Not interested" leaves alone.
            showNotInterested = serverCatalog == null
        )
    }
}

/** Pages See all fetches on its own while every loaded title is hidden, before offering Load more. */
private const val EMPTY_GRID_AUTO_PAGES = 5

/** How long See-all waits for a missing catalog once nothing is loading it any more. */
private const val MISSING_CATALOG_WAIT_MS = 4_000L
