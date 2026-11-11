package com.fetch.tv.ui.screens.search

import com.fetch.tv.ui.components.FetchMediaCard
import com.fetch.tv.ui.theme.FetchTheme
import com.fetch.tv.ui.screens.home.HeroBackdropState

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.FilterChipDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.ui.components.EmptyScreenState
import com.fetch.tv.ui.components.ErrorState
import com.fetch.tv.ui.components.GridContentCard
import com.fetch.tv.ui.components.LoadingIndicator
import com.fetch.tv.ui.components.PosterCardStyle
import com.fetch.tv.ui.components.StateAction
import com.fetch.tv.ui.screens.detail.requestFocusAfterFrames
import com.fetch.tv.ui.util.contentTextDirection
import com.fetch.tv.ui.util.dpadVerticalFastScroll
import com.fetch.tv.ui.util.localizedContentType
import com.fetch.tv.ui.util.localizedGenreLabel

/** The filters behind a chip that opens a row of choices. Type has its own chips. */
private enum class DiscoverFilterKind { GENRE, YEAR, RATING, SORT }

/** One choice in an open filter row. */
private data class DiscoverChoice(
    val label: String,
    val selected: Boolean,
    val onSelect: () -> Unit
)

/** Start paging this many cards before the end of the grid, so the next page is usually there in time. */
private const val DISCOVER_PREFETCH_DISTANCE = 12

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun DiscoverSection(
    uiState: SearchUiState,
    posterCardStyle: PosterCardStyle,
    watchedMovieIds: Set<String> = emptySet(),
    watchedSeriesIds: Set<String> = emptySet(),
    focusResults: Boolean,
    showBuiltInHeader: Boolean = true,
    firstItemFocusRequester: FocusRequester,
    focusedItemIndex: Int,
    shouldRestoreFocusedItem: Boolean,
    blockFilterFocus: Boolean = false,
    /** Entering Discover lands on the selected type chip, so something is focused on arrival. */
    requestInitialFocus: Boolean = false,
    onInitialFocusHandled: () -> Unit = {},
    onRestoreFocusedItemHandled: () -> Unit,
    onNavigateToDetail: (String, String, String) -> Unit,
    onDiscoverItemFocused: (Int) -> Unit,
    onSelectType: (String) -> Unit,
    onSelectGenre: (String?) -> Unit,
    onSelectYearRange: (DiscoverYearRange?) -> Unit,
    onSelectMinRating: (Int?) -> Unit,
    onSelectSort: (DiscoverSort) -> Unit,
    onClearFilters: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onItemLongPress: (MetaPreview, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val localContext = LocalContext.current
    val types = remember(uiState.discoverCatalogs) { discoverTypes(uiState.discoverCatalogs) }
    val genres = remember(uiState.discoverCatalogs, uiState.selectedDiscoverType) {
        discoverGenres(uiState.discoverCatalogs, uiState.selectedDiscoverType)
    }
    val currentYear = remember { java.time.Year.now().value }
    val yearRanges = remember(currentYear) { discoverYearRanges(currentYear) }
    val filters = uiState.discoverFilters

    var openFilter by remember { mutableStateOf<DiscoverFilterKind?>(null) }
    val typeFocusRequester = remember { FocusRequester() }
    val filterChipRequesters = remember { DiscoverFilterKind.entries.associateWith { FocusRequester() } }
    val optionFocusRequester = remember { FocusRequester() }
    var gridHasFocus by remember { mutableStateOf(false) }

    fun closeFilter(returnFocus: Boolean) {
        val kind = openFilter ?: return
        if (returnFocus) runCatching { filterChipRequesters.getValue(kind).requestFocus() }
        openFilter = null
    }

    androidx.activity.compose.BackHandler(enabled = openFilter != null) { closeFilter(returnFocus = true) }
    androidx.activity.compose.BackHandler(enabled = gridHasFocus && openFilter == null) {
        runCatching { typeFocusRequester.requestFocus() }
    }

    // Discover opens on its filters, so the remote starts on something visible. Returning from a
    // title restores the grid instead. The chips only exist once the catalogs have loaded.
    val canTakeInitialFocus = requestInitialFocus && types.isNotEmpty() && !blockFilterFocus
    LaunchedEffect(canTakeInitialFocus) {
        if (!canTakeInitialFocus) return@LaunchedEffect
        if (typeFocusRequester.requestFocusAfterFrames(frames = 1)) onInitialFocusHandled()
    }

    fun yearLabel(range: DiscoverYearRange?): String = when {
        range == null -> localContext.getString(R.string.discover_year_any)
        range.from != null && range.from == range.to -> range.from.toString()
        range.from == null && range.to != null -> localContext.getString(R.string.discover_year_before, range.to + 1)
        else -> localContext.getString(R.string.discover_year_decade, range.from ?: 0)
    }
    fun ratingLabel(minRating: Int?): String =
        if (minRating == null) {
            localContext.getString(R.string.discover_rating_any)
        } else {
            localContext.getString(R.string.discover_rating_min, minRating)
        }
    fun sortLabel(sort: DiscoverSort): String = localContext.getString(
        when (sort) {
            DiscoverSort.CATALOG -> R.string.discover_sort_catalog
            DiscoverSort.RATING -> R.string.discover_sort_rating
            DiscoverSort.NEWEST -> R.string.discover_sort_newest
            DiscoverSort.TITLE -> R.string.discover_sort_title
        }
    )
    val allGenresLabel = stringResource(R.string.discover_genre_default)
    val selectedGenreLabel = uiState.selectedDiscoverGenre?.let { localizedGenreLabel(it) } ?: allGenresLabel

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
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                types.forEach { type ->
                    val isSelected = type == uiState.selectedDiscoverType
                    DiscoverChip(
                        selected = isSelected,
                        focusEnabled = !blockFilterFocus,
                        onClick = {
                            closeFilter(returnFocus = false)
                            onSelectType(type)
                        },
                        modifier = if (isSelected) Modifier.focusRequester(typeFocusRequester) else Modifier
                    ) { contentColor ->
                        Text(
                            text = localizedContentType(localContext, type),
                            style = MaterialTheme.typography.labelLarge,
                            color = contentColor
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .padding(horizontal = FetchTheme.spacing.xs)
                        .width(FetchTheme.spacing.hairline)
                        .height(FetchTheme.spacing.xl)
                        .background(FetchTheme.colors.Border)
                )

                val visibleKinds = DiscoverFilterKind.entries.filter { kind ->
                    kind != DiscoverFilterKind.GENRE || genres.isNotEmpty()
                }
                visibleKinds.forEach { kind ->
                    val (title, value, active) = when (kind) {
                        DiscoverFilterKind.GENRE -> Triple(
                            stringResource(R.string.discover_filter_genre),
                            selectedGenreLabel,
                            uiState.selectedDiscoverGenre != null
                        )
                        DiscoverFilterKind.YEAR -> Triple(
                            stringResource(R.string.discover_filter_year),
                            yearLabel(filters.yearRange),
                            filters.yearRange != null
                        )
                        DiscoverFilterKind.RATING -> Triple(
                            stringResource(R.string.discover_filter_rating),
                            ratingLabel(filters.minRating),
                            filters.minRating != null
                        )
                        DiscoverFilterKind.SORT -> Triple(
                            stringResource(R.string.discover_filter_sort),
                            sortLabel(filters.sort),
                            filters.sort != DiscoverSort.CATALOG
                        )
                    }
                    val expanded = openFilter == kind
                    DiscoverChip(
                        selected = active,
                        focusEnabled = !blockFilterFocus,
                        onClick = { openFilter = if (expanded) null else kind },
                        modifier = Modifier.focusRequester(filterChipRequesters.getValue(kind))
                    ) { contentColor ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xs)
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelMedium,
                                color = contentColor.copy(alpha = 0.7f)
                            )
                            Text(
                                text = value,
                                style = MaterialTheme.typography.labelLarge.copy(
                                    textDirection = value.contentTextDirection()
                                ),
                                color = contentColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Icon(
                                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = if (expanded) {
                                    stringResource(R.string.cd_collapse, title)
                                } else {
                                    stringResource(R.string.cd_expand, title)
                                },
                                modifier = Modifier.size(FetchTheme.sizes.icons.sm),
                                tint = contentColor
                            )
                        }
                    }
                }
            }
        }

        openFilter?.let { kind ->
            val choices: List<DiscoverChoice> = when (kind) {
                DiscoverFilterKind.GENRE -> buildList {
                    add(DiscoverChoice(allGenresLabel, uiState.selectedDiscoverGenre == null) { onSelectGenre(null) })
                    genres.forEach { genre ->
                        add(
                            DiscoverChoice(
                                label = localizedGenreLabel(genre),
                                selected = genre.equals(uiState.selectedDiscoverGenre, ignoreCase = true)
                            ) { onSelectGenre(genre) }
                        )
                    }
                }
                DiscoverFilterKind.YEAR -> (listOf<DiscoverYearRange?>(null) + yearRanges).map { range ->
                    DiscoverChoice(yearLabel(range), range == filters.yearRange) { onSelectYearRange(range) }
                }
                DiscoverFilterKind.RATING -> (listOf<Int?>(null) + DISCOVER_RATING_THRESHOLDS).map { rating ->
                    DiscoverChoice(ratingLabel(rating), rating == filters.minRating) { onSelectMinRating(rating) }
                }
                DiscoverFilterKind.SORT -> DiscoverSort.entries.map { sort ->
                    DiscoverChoice(sortLabel(sort), sort == filters.sort) { onSelectSort(sort) }
                }
            }
            key(kind) {
                DiscoverChoiceRow(
                    choices = choices,
                    focusRequester = optionFocusRequester,
                    onChoose = { choice ->
                        closeFilter(returnFocus = true)
                        choice.onSelect()
                    },
                    onFocusLeft = { if (openFilter == kind) openFilter = null }
                )
            }
            // Every choice is composed (a plain scrolling row), so one request lands on the
            // current selection once the row is laid out.
            LaunchedEffect(kind) {
                optionFocusRequester.requestFocusAfterFrames(frames = 1)
            }
        }

        if (uiState.discoverSourceCount > 0 && uiState.discoverResults.isNotEmpty()) {
            Text(
                text = pluralStringResource(
                    R.plurals.discover_source_count,
                    uiState.discoverSourceCount,
                    uiState.discoverSourceCount
                ),
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
                        focusedItemIndex = focusedItemIndex,
                        shouldRestoreFocusedItem = shouldRestoreFocusedItem,
                        onRestoreFocusedItemHandled = onRestoreFocusedItemHandled,
                        onItemFocused = onDiscoverItemFocused,
                        canLoadMore = uiState.discoverHasMore,
                        isLoadingMore = uiState.discoverLoadingMore,
                        autoLoadPaused = uiState.discoverPagingPaused,
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
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = FetchTheme.spacing.xl),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator()
                }
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

/** The open filter's choices as a row of chips under the filter bar. */
@Composable
private fun DiscoverChoiceRow(
    choices: List<DiscoverChoice>,
    focusRequester: FocusRequester,
    onChoose: (DiscoverChoice) -> Unit,
    onFocusLeft: () -> Unit
) {
    // The row closes when focus leaves it (Up to the filters, Down to the grid), but not before
    // focus has first arrived.
    var hadFocus by remember { mutableStateOf(false) }
    val focusIndex = choices.indexOfFirst { it.selected }.coerceAtLeast(0)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .onFocusChanged { state ->
                if (hadFocus && !state.hasFocus) onFocusLeft()
                hadFocus = state.hasFocus
            },
        horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        choices.forEachIndexed { index, choice ->
            DiscoverChip(
                selected = choice.selected,
                onClick = { onChoose(choice) },
                modifier = if (index == focusIndex) Modifier.focusRequester(focusRequester) else Modifier
            ) { contentColor ->
                Text(
                    text = choice.label,
                    style = MaterialTheme.typography.labelLarge.copy(
                        textDirection = choice.label.contentTextDirection()
                    ),
                    color = contentColor,
                    maxLines = 1
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DiscoverChip(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusEnabled: Boolean = true,
    content: @Composable (Color) -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val isSelected = selected
    val contentColor = when {
        isFocused -> FetchTheme.colors.OnHighlight
        isSelected -> FetchTheme.colors.TextPrimary
        else -> FetchTheme.colors.TextSecondary
    }
    val chipShape = RoundedCornerShape(FetchTheme.radii.xl)
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier
            .focusProperties { canFocus = focusEnabled }
            .onFocusChanged { isFocused = it.isFocused }
            .semantics { selected = isSelected },
        colors = FilterChipDefaults.colors(
            containerColor = FetchTheme.colors.BackgroundCard,
            focusedContainerColor = FetchTheme.colors.Highlight,
            selectedContainerColor = FetchTheme.colors.FocusBackground,
            focusedSelectedContainerColor = FetchTheme.colors.Highlight,
            contentColor = contentColor,
            focusedContentColor = contentColor,
            selectedContentColor = contentColor,
            focusedSelectedContentColor = contentColor
        ),
        border = FilterChipDefaults.border(
            border = Border(
                border = BorderStroke(FetchTheme.spacing.hairline, FetchTheme.colors.Border),
                shape = chipShape
            ),
            focusedBorder = Border(
                border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs),
                shape = chipShape
            ),
            selectedBorder = Border(
                border = BorderStroke(FetchTheme.spacing.hairline, FetchTheme.colors.Primary),
                shape = chipShape
            ),
            focusedSelectedBorder = Border(
                border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs),
                shape = chipShape
            )
        ),
        shape = FilterChipDefaults.shape(shape = chipShape)
    ) {
        content(contentColor)
    }
}

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
    var localRestoreFocusedItemIndex by remember { mutableStateOf(-1) }
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
    val adaptiveStyle = remember(posterCardStyle) {
        val cardWidth = posterCardStyle.width
        posterCardStyle.copy(
            width = cardWidth,
            height = cardWidth * 1.5f
        )
    }

    LaunchedEffect(effectiveShouldRestoreFocusedItem, effectiveFocusedItemIndex, totalCells) {
        if (!effectiveShouldRestoreFocusedItem) return@LaunchedEffect
        if (effectiveFocusedItemIndex !in 0 until totalCells) {
            if (localShouldRestoreFocusedItem) {
                localShouldRestoreFocusedItem = false
                localRestoreFocusedItemIndex = -1
            } else {
                onRestoreFocusedItemHandled()
            }
            return@LaunchedEffect
        }
        try {
            restoreFocusRequester.requestFocus()
        } catch (_: Exception) {
        }
        repeat(2) { withFrameNanos { } }
        try {
            restoreFocusRequester.requestFocus()
        } catch (_: Exception) {
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
        itemFocusRequesters.getOrPut(focusedItemIndex) { FocusRequester() }
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

    // Column the user was navigating in at the moment fast-scroll engaged.
    // Captured on drag-start (see onFastScrollingChanged below) because the
    // originally-focused card will have scrolled out of visibleItemsInfo by
    // the time landing runs — looking it up at landing time is unreliable
    // and was causing focus to jump to a different column after long drags.
    var fastScrollStartColumn by remember { mutableStateOf<Int?>(null) }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(
            minSize = if (globalLandscape) posterCardStyle.height else adaptiveStyle.width
        ),
        modifier = Modifier.fillMaxSize()
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
            val itemFocusReq = remember(index) { itemFocusRequesters.getOrPut(index) { FocusRequester() } }
            val focusReq = when {
                effectiveShouldRestoreFocusedItem && index == effectiveFocusedItemIndex -> restoreFocusRequester
                focusResults && index == 0 -> firstItemFocusRequester
                else -> itemFocusReq
            }
            GridContentCard(
                item = item,
                onClick = { onItemClick(index, item) },
                onLongPress = { onItemLongPress(item) },
                posterCardStyle = adaptiveStyle,
                isWatched = run {
                    val isSeries = item.apiType.equals("series", ignoreCase = true) || item.apiType.equals("tv", ignoreCase = true)
                    if (isSeries) item.id in watchedSeriesIds else item.id in watchedMovieIds
                },
                modifier = Modifier
                    // Headroom so the focused card's scale isn't clipped by the grid's top edge.
                    .padding(top = FetchTheme.spacing.xs)
                    .width(adaptiveStyle.width),
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
                    posterCardStyle = adaptiveStyle,
                    modifier = Modifier
                        .padding(top = FetchTheme.spacing.xs)
                        .width(adaptiveStyle.width),
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

    Column(
        modifier = modifier.then(if (globalLandscape) Modifier.fillMaxWidth() else Modifier.width(posterCardStyle.width))
    ) {
        FetchMediaCard(
            onClick = onClick,
            modifier = Modifier
                .then(
                    if (globalLandscape) Modifier.fillMaxWidth().aspectRatio(com.fetch.tv.domain.model.PosterShape.LANDSCAPE.aspectRatio())
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
                .then(if (globalLandscape) Modifier.fillMaxWidth() else Modifier.width(posterCardStyle.width))
                .padding(top = FetchTheme.spacing.sm)
                .height(MaterialTheme.typography.titleMedium.lineHeight.value.dp)
        )
    }
}
