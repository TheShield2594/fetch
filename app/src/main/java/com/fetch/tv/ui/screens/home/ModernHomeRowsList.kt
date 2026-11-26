@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.fetch.tv.ui.screens.home

import com.fetch.tv.domain.model.catalogRowLegacyKey
import com.fetch.tv.ui.components.focusRequesterFor
import com.fetch.tv.ui.theme.FetchTheme

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.compose.ui.ExperimentalComposeUiApi
import coil3.imageLoader
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import com.fetch.tv.domain.model.ContinueWatchingCardStyle
import com.fetch.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.isPlaceholder
import com.fetch.tv.ui.util.StableList
import com.fetch.tv.ui.util.StableMap
import com.fetch.tv.ui.util.StableRef
import com.fetch.tv.ui.util.dpadVerticalFastScroll
import com.fetch.tv.ui.util.recompositionHighlighter
import com.fetch.tv.ui.components.rememberPlaceholderShimmerOffsetState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import com.fetch.tv.ui.screens.settings.MemoryBudget

private const val VERTICAL_PREFETCH_MAX_ITEMS_PER_ROW = 7

@OptIn(
    ExperimentalFoundationApi::class,
    ExperimentalTvMaterial3Api::class,
    ExperimentalComposeUiApi::class,
    kotlinx.coroutines.FlowPreview::class
)
@Composable
internal fun ModernHomeRowsList(
    isVerticalRowsScrollingState: State<Boolean>,
    carouselRows: StableList<HeroCarouselRow>,
    verticalRowListState: LazyListState,
    /** Rows report focus here, and read the active row, the pending request and the expanded card. */
    focusController: HomeFocusController,
    rowListStates: StableRef<MutableMap<String, LazyListState>>,
    loadMoreRequestedTotals: StableRef<MutableMap<String, Int>>,
    focusState: HomeScreenFocusState,
    contentFocusRequester: FocusRequester,
    rowsViewportHeight: Dp,
    catalogBottomPadding: Dp,
    trailerContentAlpha: () -> Float,
    verticalRowBringIntoViewSpec: BringIntoViewSpec,
    onRowItemFocusedInternal: (String, Int, Boolean) -> Unit,
    onNavigateToDetail: (String, String, String) -> Unit,
    onNavigateToFolderDetail: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    onSeeAllRow: (HeroCarouselRow) -> Unit = {},
    onYouTubeVideoClick: (com.fetch.tv.data.youtube.YouTubeVideo) -> Unit = {},
    onLoadMoreCatalog: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingOptions: (ContinueWatchingItem) -> Unit,
    onRequestLazyCatalogLoad: (String) -> Unit,
    isCatalogItemWatched: (MetaPreview) -> Boolean,
    onCatalogItemLongPress: (MetaPreview, String, String?) -> Unit,
    onItemFocus: (MetaPreview) -> Unit,
    onPreloadAdjacentItem: (MetaPreview) -> Unit,
    enrichedPreviews: State<StableMap<String, MetaPreview>>,
    trailerPreviewUrls: StableMap<String, String>,
    trailerPreviewAudioUrls: StableMap<String, String>,
    useLandscapePosters: Boolean,
    alwaysShowLandscapeClearlogo: Boolean = false,
    showLabels: Boolean,
    posterCardCornerRadius: Dp,
    focusedPosterBackdropTrailerMuted: Boolean,
    effectiveExpandEnabled: Boolean,
    effectiveAutoplayEnabled: Boolean,
    trailerPlaybackTarget: FocusedPosterTrailerPlaybackTarget,
    expandedTrailerPreviewUrl: () -> String?,
    expandedTrailerPreviewAudioUrl: () -> String?,
    portraitCatalogCardWidth: Dp,
    portraitCatalogCardHeight: Dp,
    landscapeCatalogCardWidth: Dp,
    landscapeCatalogCardHeight: Dp,
    continueWatchingCardWidth: Dp,
    continueWatchingCardHeight: Dp,
    blurUnwatchedEpisodes: Boolean,
    useEpisodeThumbnails: Boolean,
    continueWatchingCardStyle: ContinueWatchingCardStyle,
    continueWatchingCornerRadius: Dp,
    blockLeftOnFirstExpandedItem: Boolean = false,
) {
    val focusedItemByRow = focusController.stableFocusedItemByRow
    val focusedItemByRowMap = focusController.focusedItemByRow
    val activeRowKey = focusController.activeRowKey
    val activeItemIndex = focusController.activeItemIndex
    val expandedCatalogFocusKey = focusController.expandedCatalogFocusKey
    val onFastScrollingChanged = remember(focusController) { { scrolling: Boolean -> focusController.setFastScrolling(scrolling) } }
    val onPendingRowFocusCleared = remember(focusController) { { focusController.clearPendingRowFocus() } }
    val onBackdropInteraction = remember(focusController) { { focusController.onBackdropInteraction() } }
    val onExpandedCatalogFocusKeyChange = remember(focusController) {
        { key: String? -> focusController.setExpandedCatalogFocusKey(key) }
    }

    val rowFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val stableItemFocusRequestersByRow = remember { mutableMapOf<String, StableRef<MutableMap<Int, FocusRequester>>>() }

    val density = LocalDensity.current
    val context = LocalContext.current
    val layoutDirection = LocalLayoutDirection.current
    val verticalPrefetchImageLoader = context.imageLoader
    val latestCarouselRowsForImagePrefetch = rememberUpdatedState(carouselRows)

    LaunchedEffect(
        verticalPrefetchImageLoader,
        verticalRowListState,
        density,
        useLandscapePosters,
        portraitCatalogCardWidth,
        portraitCatalogCardHeight,
        landscapeCatalogCardWidth,
        landscapeCatalogCardHeight
    ) {
        val prefetchAheadRows = 1
        // A screenful of the next row, so a move down lands on loaded cards, not just the first one.
        val lowRam = MemoryBudget.isLowRamTier
        val viewportWidthPx = context.resources.displayMetrics.widthPixels
        snapshotFlow {
            verticalRowListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        }
            .distinctUntilChanged()
            .debounce(240L) // VERTICAL_PREFETCH_DEBOUNCE_MS
            .collect { lastVisibleRowIndex ->
                // enqueue is already asynchronous: run the sizing on Main, no IO hop.
                for (rowOffset in 1..prefetchAheadRows) {
                    val row = latestCarouselRowsForImagePrefetch.value.list
                        .getOrNull(lastVisibleRowIndex + rowOffset) ?: continue
                    var prefetchItemsPerRow = 1
                    var sized = false
                    for (i in 0 until row.items.list.size) {
                        if (i >= prefetchItemsPerRow) break
                        val item = row.items.list[i]
                        // Continue Watching cards use a different size and cache key; the row sits first anyway.
                        if (item.payload is ModernPayload.ContinueWatching) continue
                        val url = item.imageUrl ?: continue
                        // Collapsed card size and key, matching what the card itself requests.
                        val metrics = item.catalogCardMetrics(
                            useLandscapePosters = useLandscapePosters,
                            portraitCardWidth = portraitCatalogCardWidth,
                            portraitCardHeight = portraitCatalogCardHeight,
                            landscapeCardWidth = landscapeCatalogCardWidth,
                            landscapeCardHeight = landscapeCatalogCardHeight
                        )
                        val wPx = with(density) { metrics.width.roundToPx() }
                        val hPx = with(density) { metrics.height.roundToPx() }
                        if (wPx <= 0 || hPx <= 0) continue
                        if (!sized && !lowRam) {
                            sized = true
                            prefetchItemsPerRow = (viewportWidthPx / wPx + 1)
                                .coerceIn(1, VERTICAL_PREFETCH_MAX_ITEMS_PER_ROW)
                        }
                        val cacheKey = catalogCardImageCacheKey(url, wPx, hPx)
                        val extras = item.catalogCardImageCacheExtras(imageUrl = url, isLandscapeCustomPoster = false)
                        if (verticalPrefetchImageLoader.memoryCache?.get(MemoryCache.Key(cacheKey, extras)) != null) continue
                        verticalPrefetchImageLoader.enqueue(
                            ImageRequest.Builder(context)
                                .data(url)
                                .memoryCacheKey(cacheKey)
                                .memoryCacheKeyExtras(extras)
                                .size(width = wPx, height = hPx)
                                .build()
                        )
                    }
                }
            }
    }

    val latestOnRequestLazyCatalogLoad = rememberUpdatedState(onRequestLazyCatalogLoad)
    val latestCarouselRowsForLazy = rememberUpdatedState(carouselRows)
    LaunchedEffect(verticalRowListState) {
        val prefetchAheadForLazy = 2
        snapshotFlow {
            val info = verticalRowListState.layoutInfo
            val firstVisible = info.visibleItemsInfo.firstOrNull()?.index ?: -1
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            firstVisible to lastVisible
        }.collectLatest { (firstVisible, lastVisible) ->
            if (lastVisible < 0) return@collectLatest
            // Debounce: restarts on every new emission during rapid scroll.
            // Only fires when visible indices stabilize for 240ms.
            delay(240)
            val rows = latestCarouselRowsForLazy.value
            for (idx in firstVisible.coerceAtLeast(0)..(lastVisible + prefetchAheadForLazy)) {
                val row = rows.list.getOrNull(idx) ?: continue
                if (row.isLoading && row.items.list.firstOrNull()?.imageUrl.isPlaceholder()) {
                    val legacyKey = catalogRowLegacyKey(
                        row.addonId ?: continue,
                        row.apiType ?: continue,
                        row.catalogId ?: continue
                    )
                    latestOnRequestLazyCatalogLoad.value(legacyKey)
                }
            }
        }
    }

    val focusRestorerRequester = remember(focusController) {
        {
            activeRowKey.value?.let { rowFocusRequesters[it] } ?: FocusRequester.Default
        }
    }

    val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current

    // Only run the shared shimmer while a row on screen actually draws it: rememberInfiniteTransition
    // keeps waking the Compose frame clock on every frame for as long as it is composed, even when
    // nothing reads its value. Rows below the fold stay placeholders until they are scrolled to, so
    // the check has to be on the visible rows, not on the whole list. Keyed on the list state for
    // the same reason isVerticalRowsScrollingState is in ModernHomeContent: rememberLazyListState
    // is saveable-backed and can hand back a new instance, and a derived state still holding the
    // old one would read a layout that has stopped updating.
    val needsPlaceholderShimmer by remember(verticalRowListState) {
        derivedStateOf {
            val rows = latestCarouselRowsForLazy.value.list
            verticalRowListState.layoutInfo.visibleItemsInfo.any { visibleRow ->
                rows.getOrNull(visibleRow.index)?.showsPlaceholderShimmer() == true
            }
        }
    }
    val sharedPlaceholderShimmerOffsetState = if (needsPlaceholderShimmer) {
        rememberPlaceholderShimmerOffsetState(label = "sharedRowShimmer")
    } else {
        null
    }
    // Key rows by identity so inserting or removing one keeps the rows below it (scroll, focus,
    // decoded images); a repeated key gets an occurrence suffix since LazyColumn keys must be unique.
    val rowListKeys = remember(carouselRows) {
        val occurrences = HashMap<String, Int>()
        carouselRows.list.map { row ->
            val occurrence = occurrences.merge(row.key, 1, Int::plus) ?: 1
            if (occurrence == 1) row.key else "${row.key}#$occurrence"
        }
    }

    CompositionLocalProvider(
        LocalBringIntoViewSpec provides verticalRowBringIntoViewSpec,
        LocalFastScrollActive provides focusController.isFastScrolling,
        LocalVerticalRowsScrolling provides isVerticalRowsScrollingState
    ) {
        LazyColumn(
            state = verticalRowListState,
            modifier = modifier
                .fillMaxWidth()
                .recompositionHighlighter()
                .height(rowsViewportHeight)
                .padding(bottom = catalogBottomPadding)
                .clipToBounds()
                .graphicsLayer { alpha = trailerContentAlpha() }
                .focusRequester(contentFocusRequester)
                .focusRestorer { focusRestorerRequester() }
                .onPreviewKeyEvent { event ->
                    val firstRowKey = carouselRows.list.firstOrNull()?.key
                    val lastRowKey = carouselRows.list.lastOrNull()?.key
                    if (event.type == KeyEventType.KeyDown &&
                        event.key == Key.DirectionUp &&
                        effectiveExpandEnabled &&
                        expandedCatalogFocusKey.value != null &&
                        activeRowKey.value == firstRowKey
                    ) return@onPreviewKeyEvent true
                    if (event.type == KeyEventType.KeyDown &&
                        event.key == Key.DirectionDown &&
                        effectiveExpandEnabled &&
                        expandedCatalogFocusKey.value != null &&
                        activeRowKey.value == lastRowKey
                    ) return@onPreviewKeyEvent true
                    val blockKey = if (layoutDirection == LayoutDirection.Rtl)
                        Key.DirectionRight else Key.DirectionLeft
                    if (blockLeftOnFirstExpandedItem &&
                        event.type == KeyEventType.KeyDown &&
                        event.key == blockKey &&
                        effectiveExpandEnabled &&
                        expandedCatalogFocusKey.value != null &&
                        activeItemIndex.value == 0
                    ) return@onPreviewKeyEvent true
                    false
                }
                .dpadVerticalFastScroll(
                    scrollableState = verticalRowListState,
                    onFastScrollingChanged = onFastScrollingChanged,
                    shouldHaltForward = {
                        val info = verticalRowListState.layoutInfo
                        val lastIdx = carouselRows.list.size - 1
                        val lastVisible = info.visibleItemsInfo.lastOrNull { it.index == lastIdx }
                        lastIdx >= 0 && lastVisible != null &&
                            lastVisible.offset + lastVisible.size <= info.viewportEndOffset
                    },
                    resolveVerticalLanding = { sign ->
                        val layoutInfo = verticalRowListState.layoutInfo
                        val visibleItems = layoutInfo.visibleItemsInfo
                        val lastIdx = carouselRows.list.size - 1
                        val viewportEnd = layoutInfo.viewportEndOffset
                        val lastRowAtBottom = lastIdx >= 0 &&
                            visibleItems.lastOrNull { it.index == lastIdx }?.let {
                                it.offset + it.size <= viewportEnd
                            } == true
                        val upwardTopRow: LazyListItemInfo? = if (sign < 0) {
                            visibleItems.firstOrNull()?.takeIf {
                                it.offset > -it.size / 2
                            }
                        } else null
                        val targetRowIndex = when {
                            lastRowAtBottom -> lastIdx
                            upwardTopRow != null -> upwardTopRow.index
                            else ->
                                visibleItems.firstOrNull { it.offset >= 0 }?.index
                                    ?: visibleItems.firstOrNull()?.index
                                    ?: verticalRowListState.firstVisibleItemIndex
                        }
                        val targetRow = carouselRows.list.getOrNull(targetRowIndex)
                        if (targetRow == null) null
                        else {
                            val savedIdx = (focusedItemByRowMap[targetRow.key] ?: 0)
                                .coerceIn(0, targetRow.maxFocusIndex())
                            focusController.landOnRow(targetRow.key, savedIdx)

                            val targetItemKey = targetRow.items.list.getOrNull(savedIdx)?.key
                                ?: "${targetRow.key}_$savedIdx"
                            targetItemKey
                        }
                    },
                ),
            contentPadding = PaddingValues(bottom = rowsViewportHeight),
            verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xl)
        ) {
            itemsIndexed(
                items = carouselRows.list,
                key = { index, row -> rowListKeys.getOrNull(index) ?: "${row.key}_$index" },
                contentType = { _, row -> modernHomeRowContentType(row) }
            ) { _, row ->
                val stableOnContinueWatchingOptions = remember(onContinueWatchingOptions) {
                    { item: ContinueWatchingItem -> onContinueWatchingOptions(item) }
                }
                val stableOnRowItemFocused = remember {
                    { rowKey: String, index: Int, isContinueWatchingRow: Boolean ->
                        // The See all card (index items.size) has no title behind it.
                        val isSeeAll = latestCarouselRowsForImagePrefetch.value.list
                            .firstOrNull { it.key == rowKey }
                            ?.let { index >= it.items.list.size } == true
                        focusController.onRowItemFocused(rowKey, index, isContinueWatchingRow, isSeeAll)
                        onRowItemFocusedInternal(rowKey, index, isContinueWatchingRow)
                    }
                }
                val isActiveRowLambda = remember(row.key) {
                    { row.key == activeRowKey.value }
                }
                val stableOnCatalogSelectionFocused = remember(focusController) {
                    { selection: FocusedCatalogSelection -> focusController.onCatalogSelectionFocused(selection) }
                }
                ModernRowSection(
                    row = row,
                    isActiveRow = isActiveRowLambda,
                    rowFocusRequester = rowFocusRequesters.focusRequesterFor(row.key),
                    rowTitleBottom = MODERN_ROW_TITLE_BOTTOM,
                    defaultBringIntoViewSpec = defaultBringIntoViewSpec,
                    focusStateCatalogRowScrollIndex = focusState.catalogRowScrollStates[row.key] ?: 0,
                    focusStateCatalogRowScrollAnchor = focusState.catalogRowScrollAnchors[row.key],
                    focusedItemByRow = focusedItemByRow,
                    rowListStates = rowListStates,
                    loadMoreRequestedTotals = loadMoreRequestedTotals,
                    pendingRowFocusKey = focusController.pendingRowFocusKey,
                    pendingRowFocusIndex = focusController.pendingRowFocusIndex,
                    pendingRowFocusNonce = focusController.pendingRowFocusNonce,
                    onPendingRowFocusCleared = onPendingRowFocusCleared,
                    onRowItemFocused = stableOnRowItemFocused,
                    useLandscapePosters = useLandscapePosters,
                    alwaysShowLandscapeClearlogo = alwaysShowLandscapeClearlogo,
                    showLabels = showLabels,
                    posterCardCornerRadius = posterCardCornerRadius,
                    focusedPosterBackdropTrailerMuted = focusedPosterBackdropTrailerMuted,
                    effectiveExpandEnabled = effectiveExpandEnabled,
                    effectiveAutoplayEnabled = effectiveAutoplayEnabled,
                    trailerPlaybackTarget = trailerPlaybackTarget,
                    expandedCatalogFocusKey = expandedCatalogFocusKey,
                    expandedTrailerPreviewUrl = expandedTrailerPreviewUrl,
                    expandedTrailerPreviewAudioUrl = expandedTrailerPreviewAudioUrl,
                    portraitCatalogCardWidth = portraitCatalogCardWidth,
                    portraitCatalogCardHeight = portraitCatalogCardHeight,
                    landscapeCatalogCardWidth = landscapeCatalogCardWidth,
                    landscapeCatalogCardHeight = landscapeCatalogCardHeight,
                    continueWatchingCardWidth = continueWatchingCardWidth,
                    continueWatchingCardHeight = continueWatchingCardHeight,
                    blurUnwatchedEpisodes = blurUnwatchedEpisodes,
                    useEpisodeThumbnails = useEpisodeThumbnails,
                    continueWatchingCardStyle = continueWatchingCardStyle,
                    continueWatchingCornerRadius = continueWatchingCornerRadius,
                    onContinueWatchingClick = onContinueWatchingClick,
                    onContinueWatchingOptions = stableOnContinueWatchingOptions,
                    isCatalogItemWatched = isCatalogItemWatched,
                    onCatalogItemLongPress = onCatalogItemLongPress,
                    onItemFocus = onItemFocus,
                    onPreloadAdjacentItem = onPreloadAdjacentItem,
                    enrichedPreviews = enrichedPreviews,
                    onCatalogSelectionFocused = stableOnCatalogSelectionFocused,
                    onNavigateToDetail = onNavigateToDetail,
                    onNavigateToFolderDetail = onNavigateToFolderDetail,
                    onSeeAllRow = onSeeAllRow,
                    onYouTubeVideoClick = onYouTubeVideoClick,
                    onLoadMoreCatalog = onLoadMoreCatalog,
                    onBackdropInteraction = onBackdropInteraction,
                    onExpandedCatalogFocusKeyChange = onExpandedCatalogFocusKeyChange,
                    sharedPlaceholderShimmerOffsetState = sharedPlaceholderShimmerOffsetState,
                    isVerticalRowsScrollingState = isVerticalRowsScrollingState,
                    itemFocusRequesters = stableItemFocusRequestersByRow.getOrPut(row.key) {
                        StableRef(mutableMapOf())
                    }
                )
            }
        }
    }
}
