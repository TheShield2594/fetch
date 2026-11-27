package com.fetch.tv.ui.screens.search

import com.fetch.tv.ui.components.focusSafeHorizontalScroll
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
    posterCardStyle: PosterCardStyle,
    modifier: Modifier = Modifier,
    watchedMovieIds: Set<String> = emptySet(),
    watchedSeriesIds: Set<String> = emptySet(),
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
    var gridHasFocus by remember { mutableStateOf(false) }

    androidx.activity.compose.BackHandler(enabled = gridHasFocus && openFilter == null) {
        runCatching { typeFocusRequester.requestFocus() }
    }

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

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = FetchTheme.spacing.screen.horizontal),
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)
    ) {
        Text(
            text = stringResource(R.string.discover_title),
            style = MaterialTheme.typography.headlineMedium,
            color = if (showBuiltInHeader) FetchTheme.colors.TextPrimary else Color.Transparent
        )

        if (types.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .focusSafeHorizontalScroll(),
                horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                types.forEach { type ->
                    val isSelected = type == uiState.selectedDiscoverType
                    FetchChip(
                        label = localizedContentType(localContext, type),
                        selected = isSelected,
                        focusEnabled = !blockFilterFocus,
                        onClick = {
                            openFilter = null
                            onSelectType(type)
                        },
                        modifier = if (isSelected) Modifier.focusRequester(typeFocusRequester) else Modifier
                    )
                }

                Box(
                    modifier = Modifier
                        .padding(horizontal = FetchTheme.spacing.xs)
                        .width(FetchTheme.spacing.hairline)
                        .height(FetchTheme.spacing.xl)
                        .background(FetchTheme.colors.Border)
                )

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
            }
        }

        if (uiState.discoverSourceCount > 0 && uiState.discoverResults.isNotEmpty()) {
            val sourceLine = pluralStringResource(
                R.plurals.discover_source_count,
                uiState.discoverSourceCount,
                uiState.discoverSourceCount
            )
            // A sort orders the titles loaded, not the whole catalog, so say how many it covers.
            val summary = if (filters.sorted && uiState.discoverSortedTitleCount > 0) {
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
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = FetchTheme.colors.TextSecondary
            )
        }

        when {
            uiState.discoverResults.isNotEmpty() -> {
                Box(modifier = Modifier.onFocusChanged { gridHasFocus = it.hasFocus }) {
                    DiscoverGrid(
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
                    modifier = Modifier.padding(top = FetchTheme.spacing.xs),
                    cardWidth = posterCardStyle.width,
                    cardHeight = posterCardStyle.height
                )
            }

            uiState.discoverInitialized && uiState.discoverCatalogs.isEmpty() -> {
                EmptyScreenState(
                    title = stringResource(R.string.discover_empty_no_catalogs_title),
                    subtitle = stringResource(R.string.discover_empty_no_catalogs_subtitle),
                    icon = Icons.Default.Search
                )
            }

            uiState.discoverError != null -> {
                // A failed load isn't "No content found": say what went wrong and offer Retry.
                ErrorState(message = uiState.discoverError, onRetry = onRetry)
            }

            uiState.discoverInitialized && uiState.discoverHasMore && uiState.discoverSourceCount > 0 -> {
                // Every title loaded so far is hidden or filtered out, and later pages may hold
                // more. There is no grid to scroll, so paging needs a way on, paused or not (#789).
                EmptyScreenState(
                    title = stringResource(R.string.discover_empty_hidden_title),
                    subtitle = stringResource(R.string.discover_empty_hidden_subtitle),
                    icon = Icons.Default.Search,
                    action = StateAction(stringResource(R.string.discover_load_more), onLoadMore)
                )
            }

            uiState.discoverInitialized && filters.narrowsResults && uiState.discoverSourceCount > 0 -> {
                EmptyScreenState(
                    title = stringResource(R.string.discover_empty_filtered_title),
                    subtitle = stringResource(R.string.discover_empty_filtered_subtitle),
                    icon = Icons.Default.Search,
                    action = StateAction(stringResource(R.string.discover_clear_filters), onClearFilters)
                )
            }

            uiState.discoverInitialized -> {
                EmptyScreenState(
                    title = stringResource(R.string.discover_empty_no_content_title),
                    subtitle = stringResource(R.string.discover_empty_no_content_subtitle),
                    icon = Icons.Default.Search
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
    filterKey: String = ""
) {
    val restoreFocusRequester = remember { FocusRequester() }
    val gridState = rememberLazyGridState()
    var pendingFocusOnNewItemIndex by remember { mutableStateOf<Int?>(null) }

    // Scroll to top only when the filter combination actually changes (not on initial composition / back navigation).
    var previousFilterKey by remember { mutableStateOf(filterKey) }
    LaunchedEffect(filterKey) {
        if (filterKey != previousFilterKey) {
            gridState.scrollToItem(0, 0)
            previousFilterKey = filterKey
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
            // after the app was stopped), and focus can only go to a composed card.
            if (gridState.layoutInfo.visibleItemsInfo.none { it.index == target }) {
                runCatching { gridState.scrollToItem(target) }
            }
            restoreFocusRequester.requestFocusAfterFrames(frames = 1)
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

    // Back deep in the grid returns to its first card; only Back from the top rows goes on to the
    // filters (the section's own handler), so one press never throws away a long scroll.
    var gridHasFocus by remember { mutableStateOf(false) }
    val scrolledPastTop by remember { derivedStateOf { gridState.firstVisibleItemIndex > 0 } }
    val backScope = rememberCoroutineScope()
    androidx.activity.compose.BackHandler(enabled = gridHasFocus && scrolledPastTop) {
        backScope.launch {
            gridState.scrollToItem(0)
            val firstCard = when {
                effectiveShouldRestoreFocusedItem && effectiveFocusedItemIndex == 0 -> restoreFocusRequester
                focusResults -> firstItemFocusRequester
                else -> itemFocusRequesters.focusRequesterFor(0)
            }
            firstCard.requestFocusAfterFrames()
        }
    }

    // Column the user was navigating in at the moment fast-scroll engaged.
    // Captured on drag-start (see onFastScrollingChanged below) because the
    // originally-focused card will have scrolled out of visibleItemsInfo by
    // the time landing runs — looking it up at landing time is unreliable
    // and was causing focus to jump to a different column after long drags.
    var fastScrollStartColumn by remember { mutableStateOf<Int?>(null) }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(
            minSize = if (globalLandscape) posterCardStyle.height else posterCardStyle.width
        ),
        modifier = Modifier.fillMaxSize()
            .onFocusChanged { gridHasFocus = it.hasFocus }
            .focusRestorer { focusedItemRequester }
            .dpadVerticalFastScroll(
                scrollableState = gridState,
                onFastScrollingChanged = { active ->
                    // Only snapshot on drag-start. The modifier fires
                    // active=false BEFORE it calls resolveVerticalLanding,
                    // so clearing here would wipe the column right before
                    // landing reads it. Next drag-start will overwrite.
                    if (active) {
                        fastScrollStartColumn = gridState.layoutInfo
                            .visibleItemsInfo
                            .firstOrNull { it.index == focusedItemIndex }
                            ?.column
                    }
                },
                resolveVerticalLanding = { sign ->
                    // Keep the user on the column they were navigating in
                    // so fast-scroll feels like a single vertical strip,
                    // not a teleport to an arbitrary cell in the viewport.
                    // Exclude the tail (Loading / Load more) cell up front
                    // — it lives at index == items.size and when items.size
                    // % columns == 0 it lands at column 0 of a brand-new
                    // tail row, which would otherwise hijack every column-0
                    // landing and bounce focus to the last content cell.
                    val contentVisible = gridState.layoutInfo.visibleItemsInfo
                        .filter { it.index < items.size }
                    if (contentVisible.isEmpty()) {
                        null
                    } else {
                        val col = fastScrollStartColumn
                        val sameColumn = if (col != null) {
                            contentVisible.filter { it.column == col }
                        } else {
                            emptyList()
                        }
                        val target = when {
                            sameColumn.isNotEmpty() && sign > 0 -> sameColumn.last()
                            sameColumn.isNotEmpty() && sign < 0 -> sameColumn.first()
                            sign > 0 -> contentVisible.last()
                            else -> contentVisible.first()
                        }
                        val requester = itemFocusRequesters[target.index]
                        runCatching { requester?.requestFocus() }
                        null // Discover uses imperative requestFocus for now
                    }
                }
            ),
        contentPadding = PaddingValues(bottom = FetchTheme.spacing.xxl),
        horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.lg)
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
                // Headroom so the focused card's scale isn't clipped by the grid's top edge. No
                // width here: the card sizes itself (landscape cards are wider than posters), as
                // in the Library and See all (#842).
                modifier = Modifier.padding(top = FetchTheme.spacing.xs),
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
                    modifier = Modifier.padding(top = FetchTheme.spacing.xs),
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
