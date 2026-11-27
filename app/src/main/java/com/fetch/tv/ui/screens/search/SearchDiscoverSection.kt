package com.fetch.tv.ui.screens.search

import com.fetch.tv.ui.components.focusSafeHorizontalScroll
import com.fetch.tv.ui.components.browseGridItemMotion
import com.fetch.tv.ui.components.browseGridFastScroll
import com.fetch.tv.ui.components.restoreBrowseFocus
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.mutableIntStateOf
import com.fetch.tv.ui.components.FetchMediaCard
import com.fetch.tv.ui.components.focusRequesterFor
import com.fetch.tv.ui.theme.FetchTheme
import com.fetch.tv.ui.screens.home.HeroBackdropState

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.ui.components.EmptyScreenState
import com.fetch.tv.ui.components.ErrorState
import com.fetch.tv.ui.components.GridContentCard
import com.fetch.tv.ui.components.LoadingIndicator
import com.fetch.tv.ui.components.PosterGridSkeleton
import com.fetch.tv.ui.components.FetchChip
import com.fetch.tv.ui.components.PosterCardStyle
import com.fetch.tv.ui.components.StateAction
import com.fetch.tv.ui.screens.detail.requestFocusAfterFrames
import com.fetch.tv.ui.util.dpadVerticalFastScroll
import com.fetch.tv.ui.util.localizedContentType

/** Start paging this many cards before the end of the grid, so the next page is usually there in time. */
private const val DISCOVER_PREFETCH_DISTANCE = 12

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun DiscoverSection(
    uiState: SearchUiState,
    modifier: Modifier = Modifier,
    watchedMovieIds: Set<String> = emptySet(),
    watchedSeriesIds: Set<String> = emptySet(),
    /** Saved progress by content id, drawn along the cards' bottom edge (#854). */
    progressByContentId: Map<String, Float> = emptyMap(),
    /** The hero band over the grid (#852): the Layout setting, and the cache it fills in from. */
    heroEnabled: Boolean = false,
    heroMetaVersion: Int = 0,
    cachedMeta: (id: String, type: String) -> com.fetch.tv.domain.model.Meta? = { _, _ -> null },
    focusResults: Boolean,
    showBuiltInHeader: Boolean = true,
    firstItemFocusRequester: FocusRequester,
    /** The title last focused in the grid ([discoverItemKey]), or null for the first card. */
    focusedItemKey: String?,
    shouldRestoreFocusedItem: Boolean,
    /** Coming back from a title: the chips stay out of focus while the grid puts it back. */
    restorePending: Boolean = false,
    /** Entering Discover lands on the selected type chip, so something is focused on arrival. */
    requestInitialFocus: Boolean = false,
    onInitialFocusHandled: () -> Unit = {},
    onRestoreFocusedItemHandled: () -> Unit,
    onNavigateToDetail: (String, String, String) -> Unit,
    onDiscoverItemFocused: (MetaPreview) -> Unit,
    onSelectType: (String) -> Unit,
    onSelectGenre: (String?) -> Unit,
    onSelectYearRange: (DiscoverYearRange?) -> Unit,
    onSelectMinRating: (Int?) -> Unit,
    onSelectSort: (DiscoverSort) -> Unit,
    onClearFilters: () -> Unit,
    /** Hide watched, Include unrated and the Reset chip (#857). */
    onToggleHideWatched: () -> Unit = {},
    onToggleIncludeUnrated: () -> Unit = {},
    onResetFilters: () -> Unit = {},
    /** Ids of saved titles, for the cards' bookmark (#857). */
    libraryIds: Set<String> = emptySet(),
    /** Play/Pause on a card starts it (#857, as Home's #812); null where that isn't allowed. */
    onItemPlay: ((MetaPreview) -> Unit)? = null,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onItemLongPress: (MetaPreview, String) -> Unit = { _, _ -> },
) {
    val localContext = LocalContext.current
    val types = remember(uiState.discoverCatalogs) { discoverTypes(uiState.discoverCatalogs) }
    val genres = remember(uiState.discoverCatalogs, uiState.selectedDiscoverType) {
        discoverGenres(uiState.discoverCatalogs, uiState.selectedDiscoverType)
    }
    val filters = uiState.discoverFilters
    val results = uiState.discoverResults

    var openFilter by remember { mutableStateOf<DiscoverFilterKind?>(null) }
    val typeFocusRequester = remember { FocusRequester() }
    val gridState = rememberLazyGridState()

    // Focus comes back to a title by its key, not its position: a re-sort, a new page, a title
    // marked "Not interested" or a parental change moves titles between visits (#844).
    val focusedItemIndex = remember(results, focusedItemKey) {
        focusedItemKey?.let { key -> results.indexOfFirst { discoverItemKey(it) == key } } ?: -1
    }
    // Until the first results are in (after process death the view model starts over), there is
    // nothing to compare the key against yet.
    val restoreSettled = uiState.discoverInitialized && !uiState.discoverLoading &&
        !(results.isEmpty() && uiState.discoverLoadingMore)
    val restoreTargetMissing = shouldRestoreFocusedItem && restoreSettled && focusedItemIndex < 0
    // The chips are held back only while the grid really has the title to give focus to; an
    // empty grid or a title that is gone leaves them focusable.
    val blockFilterFocus = restorePending && focusedItemIndex >= 0
    LaunchedEffect(restoreTargetMissing) {
        if (!restoreTargetMissing) return@LaunchedEffect
        // Nothing in the grid to return to: the selected type chip takes focus instead. The
        // restore is cleared only afterwards, since clearing it ends this effect.
        if (types.isNotEmpty()) typeFocusRequester.requestFocusAfterFrames(frames = 1)
        onInitialFocusHandled()
        onRestoreFocusedItemHandled()
    }

    // Discover opens on its filters, so the remote starts on something visible. Returning from a
    // title restores the grid instead. The chips only exist once the catalogs have loaded.
    val canTakeInitialFocus = requestInitialFocus && types.isNotEmpty() && !blockFilterFocus && !restorePending
    LaunchedEffect(canTakeInitialFocus) {
        if (!canTakeInitialFocus) return@LaunchedEffect
        if (typeFocusRequester.requestFocusAfterFrames(frames = 1)) onInitialFocusHandled()
    }

    // "120 titles · 7 catalogs"; a sort orders the titles loaded, not the whole catalog, so it
    // says how many it covers (#843, #850).
    val summary = if (uiState.discoverSourceCount > 0 && results.isNotEmpty()) {
        val sourceLine = pluralStringResource(R.plurals.library_title_count, results.size, results.size) + " · " +
            pluralStringResource(R.plurals.discover_source_count, uiState.discoverSourceCount, uiState.discoverSourceCount)
        if (filters.sorted && uiState.discoverSortedTitleCount > 0) {
            stringResource(
                R.string.discover_summary_sorted,
                sourceLine,
                pluralStringResource(
                    R.plurals.discover_sort_coverage,
                    uiState.discoverSortedTitleCount,
                    uiState.discoverSortedTitleCount
                )
            )
        } else {
            sourceLine
        }
    } else {
        null
    }
    val collapsed = com.fetch.tv.ui.components.rememberBrowseHeaderCollapsed(gridState)

    // The hero band (#852): the focused title, else the first, filled in from the cached meta once
    // the focus prefetch lands. Off in Layout and on low-RAM devices.
    val heroAllowed = heroEnabled && !FetchTheme.lowRamDevice
    val heroItem = remember(heroAllowed, results, focusedItemIndex) {
        if (!heroAllowed) null else results.getOrNull(focusedItemIndex) ?: results.firstOrNull()
    }
    val heroTypeLabel = heroItem?.let { localizedContentType(localContext, it.apiType) }
    val heroCandidate = remember(heroItem, heroMetaVersion, heroTypeLabel) {
        heroItem?.let { com.fetch.tv.ui.components.hero.browseHeroPreview(it, cachedMeta(it.id, it.apiType), heroTypeLabel) }
    }
    val heroTarget = com.fetch.tv.ui.components.hero.rememberBrowseHeroTarget(heroCandidate)
    val showHero = heroAllowed && heroItem != null && heroTarget != null

    com.fetch.tv.ui.components.BrowseScaffold(
        title = if (showBuiltInHeader) stringResource(R.string.discover_title) else null,
        collapsed = collapsed,
        modifier = modifier,
        layoutKey = uiState.selectedDiscoverType,
        heroBackdrop = heroTarget?.backdrop,
        heroPoster = heroTarget?.poster,
        hero = if (showHero) {
            @Composable { com.fetch.tv.ui.components.hero.BrowseHeroTitle(heroTarget) }
        } else {
            null
        },
        tabs = if (types.isNotEmpty()) {
            @Composable {
                types.forEach { type ->
                    com.fetch.tv.ui.components.BrowseTab(
                        label = localizedContentType(localContext, type),
                        selected = type == uiState.selectedDiscoverType,
                        selectedFocusRequester = typeFocusRequester,
                        focusEnabled = !blockFilterFocus,
                        onClick = {
                            openFilter = null
                            onSelectType(type)
                        }
                    )
                }
            }
        } else {
            null
        },
        filters = if (types.isNotEmpty()) {
            @Composable {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusSafeHorizontalScroll(),
                    horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // One way back to Discover's defaults, there only when something is set; first, so it
                    // stays in view however long the row gets (#857).
                    if (filters.anySet || uiState.selectedDiscoverGenre != null) {
                        com.fetch.tv.ui.components.FetchChip(
                            label = stringResource(R.string.discover_reset_filters),
                            selected = false,
                            focusEnabled = !blockFilterFocus,
                            onClick = {
                                openFilter = null
                                onResetFilters()
                            }
                        )
                    }
                    DiscoverFilterChips(
                        genres = genres,
                        selectedGenre = uiState.selectedDiscoverGenre,
                        filters = filters,
                        openFilter = openFilter,
                        onOpenFilterChange = { openFilter = it },
                        onSelectGenre = onSelectGenre,
                        onSelectYearRange = onSelectYearRange,
                        onSelectMinRating = onSelectMinRating,
                        onSelectSort = onSelectSort,
                        focusEnabled = !blockFilterFocus
                    )
                    // Beside the rating once one is set: keep titles that have no rating (#857).
                    if (filters.minRating != null) {
                        com.fetch.tv.ui.components.FetchChip(
                            label = stringResource(R.string.discover_include_unrated),
                            selected = filters.includeUnrated,
                            focusEnabled = !blockFilterFocus,
                            onClick = {
                                openFilter = null
                                onToggleIncludeUnrated()
                            }
                        )
                    }
                    com.fetch.tv.ui.components.FetchChip(
                        label = stringResource(R.string.catalog_see_all_hide_watched),
                        selected = filters.hideWatched,
                        focusEnabled = !blockFilterFocus,
                        onClick = {
                            openFilter = null
                            onToggleHideWatched()
                        }
                    )
                }
            }
        } else {
            null
        },
        summary = summary
    ) { gridViewport ->
        val posterCardStyle = com.fetch.tv.ui.components.rememberBrowseCardStyle(
            preferredWidthDp = uiState.posterCardWidthDp,
            cornerRadiusDp = uiState.posterCardCornerRadiusDp,
            gridViewport = gridViewport
        )
        // States other than the grid sit inside the screen's side margins, as the grid's cards do.
        val stateModifier = Modifier.padding(horizontal = FetchTheme.spacing.screen.horizontal)
        // The first page fades in over where the skeleton was (#853), but coming back from a
        // title the grid is simply there.
        var sawSkeleton by remember { mutableStateOf(false) }
        val showingSkeleton = uiState.discoverResults.isEmpty() && (uiState.discoverLoading || uiState.discoverLoadingMore)
        LaunchedEffect(showingSkeleton) { if (showingSkeleton) sawSkeleton = true }
        when {
            uiState.discoverResults.isNotEmpty() -> {
                val fadeIn = sawSkeleton
                LaunchedEffect(Unit) { sawSkeleton = false }
                Box {
                    DiscoverGrid(
                        gridState = gridState,
                        // The browse Back ladder ends on the selected type tab (#850).
                        onFocusHeader = { typeFocusRequester.requestFocusAfterFrames(frames = 0) },
                        backEnabled = openFilter == null,
                        fadeInOnEnter = fadeIn,
                        progressByContentId = progressByContentId,
                        libraryIds = libraryIds,
                        onItemPlay = onItemPlay,
                        items = uiState.discoverResults,
                        posterCardStyle = posterCardStyle,
                        watchedMovieIds = watchedMovieIds,
                        watchedSeriesIds = watchedSeriesIds,
                        focusResults = focusResults,
                        firstItemFocusRequester = firstItemFocusRequester,
                        focusedItemIndex = focusedItemIndex.coerceAtLeast(0),
                        shouldRestoreFocusedItem = shouldRestoreFocusedItem && focusedItemIndex >= 0,
                        onRestoreFocusedItemHandled = onRestoreFocusedItemHandled,
                        onItemFocused = { index -> results.getOrNull(index)?.let(onDiscoverItemFocused) },
                        canLoadMore = uiState.discoverHasMore,
                        isLoadingMore = uiState.discoverLoadingMore,
                        // A sorted grid adds a sorted batch only when asked, below what is shown (#843).
                        autoLoadPaused = uiState.discoverPagingPaused || filters.sorted,
                        onLoadMore = onLoadMore,
                        onItemClick = { _, item ->
                            HeroBackdropState.update(item.backdropUrl)
                            onNavigateToDetail(item.id, item.apiType, item.sourceAddonBaseUrl.orEmpty())
                        },
                        onItemLongPress = { item ->
                            onItemLongPress(item, item.sourceAddonBaseUrl.orEmpty())
                        },
                        filterKey = "${uiState.selectedDiscoverType}|${uiState.selectedDiscoverGenre}|$filters"
                    )
                }
            }

            uiState.discoverLoading || uiState.discoverLoadingMore -> {
                // The grid's own shape while it loads (a sorted batch takes a few pages), so the
                // screen doesn't change shape when the titles arrive (#843, #848).
                PosterGridSkeleton(
                    modifier = stateModifier.padding(top = com.fetch.tv.ui.components.BrowseDefaults.gridTopPadding),
                    cardWidth = posterCardStyle.width,
                    cardHeight = posterCardStyle.height
                )
            }

            uiState.discoverInitialized && uiState.discoverCatalogs.isEmpty() -> {
                EmptyScreenState(
                    title = stringResource(R.string.discover_empty_no_catalogs_title),
                    subtitle = stringResource(R.string.discover_empty_no_catalogs_subtitle),
                    icon = Icons.Default.Search,
                    height = com.fetch.tv.ui.components.BrowseDefaults.EmptyStateHeight,
                    modifier = stateModifier
                )
            }

            uiState.discoverError != null -> {
                // A failed load isn't "No content found": say what went wrong and offer Retry.
                ErrorState(
                    message = uiState.discoverError,
                    onRetry = onRetry,
                    modifier = stateModifier.height(com.fetch.tv.ui.components.BrowseDefaults.EmptyStateHeight)
                )
            }

            uiState.discoverInitialized && uiState.discoverHasMore && uiState.discoverSourceCount > 0 -> {
                // Every title loaded so far is hidden or filtered out, and later pages may hold
                // more. There is no grid to scroll, so paging needs a way on, paused or not (#789).
                EmptyScreenState(
                    title = stringResource(R.string.discover_empty_hidden_title),
                    subtitle = stringResource(R.string.discover_empty_hidden_subtitle),
                    icon = Icons.Default.Search,
                    action = StateAction(stringResource(R.string.discover_load_more), onLoadMore),
                    height = com.fetch.tv.ui.components.BrowseDefaults.EmptyStateHeight,
                    modifier = stateModifier
                )
            }

            uiState.discoverInitialized && filters.narrowsResults && uiState.discoverSourceCount > 0 -> {
                EmptyScreenState(
                    title = stringResource(R.string.discover_empty_filtered_title),
                    subtitle = stringResource(R.string.discover_empty_filtered_subtitle),
                    icon = Icons.Default.Search,
                    action = StateAction(stringResource(R.string.discover_clear_filters), onClearFilters),
                    height = com.fetch.tv.ui.components.BrowseDefaults.EmptyStateHeight,
                    modifier = stateModifier
                )
            }

            uiState.discoverInitialized -> {
                EmptyScreenState(
                    title = stringResource(R.string.discover_empty_no_content_title),
                    subtitle = stringResource(R.string.discover_empty_no_content_subtitle),
                    icon = Icons.Default.Search,
                    height = com.fetch.tv.ui.components.BrowseDefaults.EmptyStateHeight,
                    modifier = stateModifier
                )
            }
        }
    }
}

internal fun discoverYearLabel(resources: android.content.res.Resources, range: DiscoverYearRange?): String = when {
    range == null -> resources.getString(R.string.discover_year_any)
    range.from != null && range.from == range.to -> range.from.toString()
    range.from == null && range.to != null -> resources.getString(R.string.discover_year_before, range.to + 1)
    else -> resources.getString(R.string.discover_year_decade, range.from ?: 0)
}

internal fun discoverRatingLabel(resources: android.content.res.Resources, minRating: Int?): String =
    if (minRating == null) {
        resources.getString(R.string.discover_rating_any)
    } else {
        resources.getString(R.string.discover_rating_min, minRating)
    }

internal fun discoverSortLabel(resources: android.content.res.Resources, sort: DiscoverSort): String =
    resources.getString(
        when (sort) {
            DiscoverSort.CATALOG -> R.string.discover_sort_catalog
            DiscoverSort.RATING -> R.string.discover_sort_rating
            DiscoverSort.NEWEST -> R.string.discover_sort_newest
            DiscoverSort.TITLE -> R.string.discover_sort_title
        }
    )

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun DiscoverGrid(
    items: List<MetaPreview>,
    posterCardStyle: PosterCardStyle,
    watchedMovieIds: Set<String> = emptySet(),
    watchedSeriesIds: Set<String> = emptySet(),
    focusResults: Boolean,
    firstItemFocusRequester: FocusRequester,
    focusedItemIndex: Int,
    shouldRestoreFocusedItem: Boolean,
    onRestoreFocusedItemHandled: () -> Unit,
    onItemFocused: (Int) -> Unit,
    canLoadMore: Boolean,
    isLoadingMore: Boolean,
    /** Scroll paging has paused (a failed page, or pages the filters keep nothing from): show Load more. */
    autoLoadPaused: Boolean = false,
    onLoadMore: () -> Unit,
    onItemClick: (Int, MetaPreview) -> Unit,
    onItemLongPress: (MetaPreview) -> Unit = {},
    filterKey: String = "",
    libraryIds: Set<String> = emptySet(),
    onItemPlay: ((MetaPreview) -> Unit)? = null,
    progressByContentId: Map<String, Float> = emptyMap(),
    /** Fade the grid in as it first shows, in place of the loading skeleton (#853). */
    fadeInOnEnter: Boolean = false,
    /** The section's grid state, so its header folds as the grid scrolls (#850). */
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState = rememberLazyGridState(),
    /** The last step of the Back ladder: focus the header's tab row (#850). */
    onFocusHeader: suspend () -> Unit = {},
    /** False while a filter panel owns Back. */
    backEnabled: Boolean = true
) {
    val restoreFocusRequester = remember { FocusRequester() }
    // A new type or filter crossfades the grid's content in, the chips staying still (#853).
    val fadeContent = FetchTheme.fullMotion && !FetchTheme.lowRamDevice
    val contentAlpha = remember { androidx.compose.animation.core.Animatable(if (fadeContent && fadeInOnEnter) 0f else 1f) }
    LaunchedEffect(Unit) { contentAlpha.animateTo(1f, com.fetch.tv.ui.theme.FetchMotion.enterTween()) }
    var pendingFocusOnNewItemIndex by remember { mutableStateOf<Int?>(null) }

    // Scroll to top only when the filter combination actually changes (not on initial composition / back navigation).
    var previousFilterKey by remember { mutableStateOf(filterKey) }
    LaunchedEffect(filterKey) {
        if (filterKey != previousFilterKey) {
            gridState.scrollToItem(0, 0)
            previousFilterKey = filterKey
            if (fadeContent) {
                contentAlpha.snapTo(0f)
                contentAlpha.animateTo(1f, com.fetch.tv.ui.theme.FetchMotion.enterTween())
            }
        }
    }
    var localRestoreFocusedItemIndex by remember { mutableIntStateOf(-1) }
    var localShouldRestoreFocusedItem by remember { mutableStateOf(false) }
    val effectiveFocusedItemIndex = if (localShouldRestoreFocusedItem) {
        localRestoreFocusedItemIndex
    } else {
        focusedItemIndex
    }
    val effectiveShouldRestoreFocusedItem = shouldRestoreFocusedItem || localShouldRestoreFocusedItem
    // Paging is automatic; the tail cell only shows a page loading, or Load more once paging paused.
    val actionType = when {
        isLoadingMore -> DiscoverGridAction.Loading
        canLoadMore && autoLoadPaused -> DiscoverGridAction.LoadMore
        else -> DiscoverGridAction.None
    }
    val hasActionCell = actionType != DiscoverGridAction.None
    val totalCells = items.size + if (hasActionCell) 1 else 0

    val globalLandscape = com.fetch.tv.ui.components.LocalLandscapePosterMode.current

    LaunchedEffect(effectiveShouldRestoreFocusedItem, effectiveFocusedItemIndex, totalCells) {
        if (!effectiveShouldRestoreFocusedItem) return@LaunchedEffect
        val target = effectiveFocusedItemIndex
        if (target in 0 until totalCells) {
            // A card far down the grid may not be composed yet (a long scroll, or a grid rebuilt
            // after the app was stopped): the browse screens' one restore step scrolls it in (#850).
            gridState.restoreBrowseFocus(target, restoreFocusRequester)
        }
        if (localShouldRestoreFocusedItem) {
            localShouldRestoreFocusedItem = false
            localRestoreFocusedItemIndex = -1
        } else {
            onRestoreFocusedItemHandled()
        }
    }

    // Per-item FocusRequesters for focusRestorer on the grid.
    // Pre-create the requester for the focused item so focusRestorer can use it
    // even before the grid items are composed (first return from navigation).
    val itemFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    val focusedItemRequester = remember(focusedItemIndex) {
        itemFocusRequesters.focusRequesterFor(focusedItemIndex)
    }

    // A load that ends without new items (failed or empty) drops the pending hand-off. When the
    // tail cell that held focus is gone too, focus falls back to the last card.
    LaunchedEffect(isLoadingMore) {
        val targetIndex = pendingFocusOnNewItemIndex ?: return@LaunchedEffect
        if (!isLoadingMore && items.size <= targetIndex) {
            pendingFocusOnNewItemIndex = null
            if (!hasActionCell && items.isNotEmpty()) {
                runCatching { itemFocusRequesters[items.size - 1]?.requestFocus() }
            }
        }
    }

    LaunchedEffect(items.size, pendingFocusOnNewItemIndex) {
        val targetIndex = pendingFocusOnNewItemIndex ?: return@LaunchedEffect
        if (items.size <= targetIndex) return@LaunchedEffect
        pendingFocusOnNewItemIndex = null
        localRestoreFocusedItemIndex = targetIndex
        localShouldRestoreFocusedItem = true
    }

    // Scroll paging: ask for the next page from every catalog once focus or scrolling nears the end.
    val shouldAutoLoad = canLoadMore && !isLoadingMore && !autoLoadPaused && items.isNotEmpty()
    LaunchedEffect(gridState.firstVisibleItemIndex, items.size, shouldAutoLoad) {
        if (!shouldAutoLoad) return@LaunchedEffect
        val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@LaunchedEffect
        if (lastVisible >= items.size - DISCOVER_PREFETCH_DISTANCE) {
            onLoadMore()
        }
    }

    // The browse Back ladder (#850): deep in the grid, back to its first card; from the top rows,
    // to the type tabs; then Discover leaves.
    var gridHasFocus by remember { mutableStateOf(false) }
    com.fetch.tv.ui.components.BrowseBackHandler(
        gridState = gridState,
        gridHasFocus = gridHasFocus,
        enabled = backEnabled,
        onFocusFirstCard = {
            val firstCard = when {
                effectiveShouldRestoreFocusedItem && effectiveFocusedItemIndex == 0 -> restoreFocusRequester
                focusResults -> firstItemFocusRequester
                else -> itemFocusRequesters.focusRequesterFor(0)
            }
            firstCard.requestFocusAfterFrames()
        },
        onFocusHeader = onFocusHeader
    )
    val itemIndexByKey = remember(items) { items.withIndex().associate { (index, item) -> discoverItemKey(item) to index } }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(
            minSize = if (globalLandscape) posterCardStyle.height else posterCardStyle.width
        ),
        modifier = Modifier.fillMaxSize()
            .graphicsLayer { alpha = contentAlpha.value }
            .onFocusChanged { gridHasFocus = it.hasFocus }
            .focusRestorer { focusedItemRequester }
            // Held Up / Down: the browse screens' one fast scroll, landing in the same column (#850).
            .browseGridFastScroll(
                gridState = gridState,
                focusedKey = { items.getOrNull(focusedItemIndex)?.let(::discoverItemKey) },
                requesterFor = { key -> itemIndexByKey[key]?.let { itemFocusRequesters[it] } }
            ),
        contentPadding = com.fetch.tv.ui.components.BrowseDefaults.gridPadding(),
        horizontalArrangement = Arrangement.spacedBy(com.fetch.tv.ui.components.BrowseDefaults.columnGap),
        verticalArrangement = Arrangement.spacedBy(com.fetch.tv.ui.components.BrowseDefaults.rowGap)
    ) {
        itemsIndexed(
            items = items,
            // Results are de-duplicated on this key, so it is unique, and it follows a title when
            // a re-sort or a new page moves it.
            key = { _, item -> discoverItemKey(item) },
            contentType = { _, _ -> "content_card" }
        ) { index, item ->
            val itemFocusReq = remember(index) { itemFocusRequesters.focusRequesterFor(index) }
            val focusReq = when {
                effectiveShouldRestoreFocusedItem && index == effectiveFocusedItemIndex -> restoreFocusRequester
                focusResults && index == 0 -> firstItemFocusRequester
                else -> itemFocusReq
            }
            GridContentCard(
                item = item,
                onClick = { onItemClick(index, item) },
                onLongPress = { onItemLongPress(item) },
                posterCardStyle = posterCardStyle,
                isWatched = run {
                    val isSeries = item.apiType.equals("series", ignoreCase = true) || item.apiType.equals("tv", ignoreCase = true)
                    if (isSeries) item.id in watchedSeriesIds else item.id in watchedMovieIds
                },
                // No width here: the card sizes itself (landscape cards are wider than posters), as
                // in the Library and See all (#842). The grid's top padding gives the focused
                // card room to grow (#850).
                modifier = browseGridItemMotion(),
                progress = progressByContentId[item.id],
                inLibrary = isLibraryItem(item, libraryIds),
                onPlay = onItemPlay?.let { play -> { play(item) } },
                focusRequester = focusReq,
                onFocused = {
                    onItemFocused(index)
                }
            )
        }

        if (hasActionCell) {
            item(
                key = "discover_action",
                contentType = "action_card"
            ) {
                val actionIndex = items.size
                val focusReq = when {
                    effectiveShouldRestoreFocusedItem && actionIndex == effectiveFocusedItemIndex -> restoreFocusRequester
                    else -> null
                }
                DiscoverActionCard(
                    actionType = actionType,
                    posterCardStyle = posterCardStyle,
                    focusRequester = focusReq,
                    onFocused = {
                        onItemFocused(actionIndex)
                        // Focus that reaches the loading cell moves on to the first new card.
                        if (actionType == DiscoverGridAction.Loading) pendingFocusOnNewItemIndex = items.size
                    },
                    onClick = {
                        if (actionType == DiscoverGridAction.LoadMore) {
                            pendingFocusOnNewItemIndex = items.size
                            onLoadMore()
                        }
                    }
                )
            }
        }
    }
}

private enum class DiscoverGridAction { None, LoadMore, Loading }

@Composable
private fun DiscoverActionCard(
    actionType: DiscoverGridAction,
    posterCardStyle: PosterCardStyle,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
    onClick: () -> Unit
) {
    val cardShape = RoundedCornerShape(posterCardStyle.cornerRadius)
    val title = when (actionType) {
        DiscoverGridAction.LoadMore -> stringResource(R.string.discover_load_more)
        DiscoverGridAction.Loading -> stringResource(R.string.discover_loading)
        DiscoverGridAction.None -> ""
    }
    val globalLandscape = com.fetch.tv.ui.components.LocalLandscapePosterMode.current
    // As wide as the cards beside it: GridContentCard draws a landscape card at the poster height.
    val cardWidth = if (globalLandscape) posterCardStyle.height else posterCardStyle.width

    Column(modifier = modifier.width(cardWidth)) {
        FetchMediaCard(
            onClick = onClick,
            modifier = Modifier
                .then(
                    if (globalLandscape) Modifier.width(cardWidth).aspectRatio(com.fetch.tv.domain.model.PosterShape.LANDSCAPE.aspectRatio())
                    else Modifier.width(posterCardStyle.width).height(posterCardStyle.height)
                )
                // Stays focusable while loading (clicks are a no-op then), so focus stays on the
                // card until the new items take it, or after a failed/empty load.
                .onPreviewKeyEvent { event ->
                    actionType != DiscoverGridAction.None &&
                        event.nativeKeyEvent.action == AndroidKeyEvent.ACTION_DOWN &&
                        event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT
                }
                .onFocusChanged { state -> if (state.isFocused) onFocused() }
                .then(
                    if (focusRequester != null) Modifier.focusRequester(focusRequester)
                    else Modifier
                ),
            shape = cardShape,
            showRestingBorder = false
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(cardShape),
                contentAlignment = Alignment.Center
            ) {
                if (actionType == DiscoverGridAction.Loading) {
                    LoadingIndicator()
                } else {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = FetchTheme.colors.TextPrimary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        // Reserve space for label to match GridContentCard height
        Spacer(
            modifier = Modifier
                .width(cardWidth)
                .padding(top = FetchTheme.spacing.sm)
                .height(MaterialTheme.typography.titleMedium.lineHeight.value.dp)
        )
    }
}
