@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    kotlinx.coroutines.FlowPreview::class
)

@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.fetch.tv.ui.screens.home

import com.fetch.tv.core.util.runSuspendCatching
import com.fetch.tv.ui.theme.cardCornerRadius
import com.fetch.tv.ui.components.PlayLaunchOrigin
import com.fetch.tv.ui.screens.settings.MemoryBudget
import com.fetch.tv.ui.theme.FetchMotion
import com.fetch.tv.ui.theme.FetchTheme

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.metrics.performance.PerformanceMetricsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import coil3.request.CachePolicy
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import kotlin.math.abs
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.fetch.tv.R
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.fetch.tv.domain.model.ContinueWatchingCardStyle
import com.fetch.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.ui.components.LoadingIndicator
import com.fetch.tv.ui.components.ContinueWatchingOptionsDialog
import com.fetch.tv.LocalSidebarExpanded
import com.fetch.tv.LocalContentFocusRequester
import com.fetch.tv.ui.util.LocalRecompositionHighlighterEnabled
import com.fetch.tv.ui.util.StableList
import com.fetch.tv.ui.util.StableRef
import com.fetch.tv.ui.util.asStable
import com.fetch.tv.ui.util.formatHeroRuntime
import com.fetch.tv.ui.util.recompositionHighlighter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Height of the wide card as a fraction of its width, matching the 2.5:1 shape of the mobile card.

private class FixedState<T>(override val value: T) : State<T>

internal val NoEnrichingItemId: State<String?> = FixedState(null)
internal val NoEnrichedPreviews: State<Map<String, MetaPreview>> = FixedState(emptyMap())
internal val NoFailedEnrichmentIds: State<Set<String>> = FixedState(emptySet())

private class ItemIdentitySnapshot(
    var byRow: Map<String, StableList<String>> = emptyMap()
)

internal fun findRelocatedItemIndex(
    previousIdentities: List<String>?,
    currentIdentities: List<String>,
    storedIndex: Int?
): Int? {
    if (storedIndex == null) return null
    val previousIdentity = previousIdentities?.getOrNull(storedIndex) ?: return null
    return currentIdentities.indexOf(previousIdentity).takeIf { it >= 0 }
}

@Composable
fun ModernHomeContent(
    uiState: HomeUiState,
    modernPresentation: ModernHomePresentationState = ModernHomePresentationState(),
    focusState: HomeScreenFocusState,
    // These three are held as State and read only inside the hero's derived state, so an
    // enrichment emission on each focus stop recomposes the hero rather than the whole Home body.
    enrichingItemId: State<String?> = NoEnrichingItemId,
    enrichedPreviews: State<Map<String, MetaPreview>> = NoEnrichedPreviews,
    failedEnrichmentIds: State<Set<String>> = NoFailedEnrichmentIds,
    trailerPreviewUrls: Map<String, String> = emptyMap(),
    trailerPreviewAudioUrls: Map<String, String> = emptyMap(),
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit = {},
    onContinueWatchingPlayManually: (ContinueWatchingItem) -> Unit = {},
    showContinueWatchingManualPlayOption: Boolean = false,
    onRequestTrailerPreview: (String, String, String?, String) -> Unit,
    onLoadMoreCatalog: (String, String, String) -> Unit,
    onRemoveContinueWatching: (String, Int?, Int?, Boolean) -> Unit,
    isCatalogItemWatched: (MetaPreview) -> Boolean = { false },
    onCatalogItemLongPress: (MetaPreview, String, String?) -> Unit = { _, _, _ -> },
    /** Play/Pause on a catalog card (#812); null leaves the key alone (a kids or limited profile). */
    onCatalogItemPlay: ((MetaPreview, String) -> Unit)? = null,
    /** "Hold OK for options" in the hero, for a profile's first few sessions (#812). */
    showHoldHint: Boolean = false,
    /** A chip picked in the Movies / Shows filter above the rows (#814). */
    onContentFilterSelected: (HomeContentFilter) -> Unit = {},
    onNavigateToFolderDetail: (String, String) -> Unit = { _, _ -> },
    /** Opens a catalog row's full grid, from the See all card at the end of the row. */
    onSeeAllRow: (HeroCarouselRow) -> Unit = {},
    onYouTubeVideoClick: (com.fetch.tv.data.youtube.YouTubeVideo) -> Unit = {},
    onItemFocus: (MetaPreview) -> Unit = {},
    onPreloadAdjacentItem: (MetaPreview) -> Unit = {},
    onSaveFocusState: (Int, Int, String?, Map<String, String>, Map<String, Int>, Map<String, String>, Int, Int) -> Unit,
    onFocusedRowKeyChanged: (String?) -> Unit = {},
    scrollToTopTrigger: Int = 0,
    onRequestLazyCatalogLoad: (String) -> Unit = {},
    onRowItemFocusedCallback: (String, Int, Boolean) -> Unit = { _, _, _ -> },
    blockLeftOnFirstExpandedItem: Boolean = false,
    cachedBestMatches: Map<String, com.fetch.tv.data.local.CachedBestMatch> = emptyMap(),
    queuedVideoIds: Set<String> = emptySet(),
    onToggleQueue: (ContinueWatchingItem) -> Unit = {}
) {
    val latestCachedBestMatches = rememberUpdatedState(cachedBestMatches)
    val onRowItemFocusedPassedDown = rememberUpdatedState(onRowItemFocusedCallback)
    val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current
    val sidebarExpanded = LocalSidebarExpanded.current
    val isSidebarExpanded = remember(sidebarExpanded) { derivedStateOf { sidebarExpanded } }
    val lifecycleOwner = LocalLifecycleOwner.current
    val useLandscapePosters = uiState.modernLandscapePostersEnabled
    val alwaysShowLandscapeClearlogo = uiState.alwaysShowLandscapeClearlogo
    val fullScreenBackdrop = uiState.modernHeroFullScreenBackdropEnabled
    val trailerPlaybackTarget = uiState.focusedPosterBackdropTrailerPlaybackTarget
    val effectiveAutoplayEnabled =
        uiState.focusedPosterBackdropTrailerEnabled &&
            (useLandscapePosters || uiState.focusedPosterBackdropExpandEnabled)
    val landscapeExpandedCardMode =
        useLandscapePosters &&
            effectiveAutoplayEnabled &&
            trailerPlaybackTarget == FocusedPosterTrailerPlaybackTarget.EXPANDED_CARD
    val effectiveExpandEnabled =
        (uiState.focusedPosterBackdropExpandEnabled && !useLandscapePosters) ||
            landscapeExpandedCardMode
    val shouldActivateFocusedPosterFlow =
        effectiveExpandEnabled ||
            (effectiveAutoplayEnabled &&
                trailerPlaybackTarget == FocusedPosterTrailerPlaybackTarget.HERO_MEDIA)
    val presentation = modernPresentation
    val carouselRows = presentation.rows

    val hasCollections = remember(uiState.homeRows) {
        uiState.homeRows.any { it is HomeRow.CollectionRow }
    }
    val hasCatalogs = uiState.catalogRows.isNotEmpty()
    if (hasCollections && !hasCatalogs && uiState.installedAddonsCount > 0 && uiState.isLoading) {
        ModernHomeSkeleton(uiState = uiState, modifier = Modifier.fillMaxSize())
        return
    }

    if (carouselRows.list.isEmpty()) {
        // Rows are on their way (the presentation is built after the catalogs land).
        if (hasCatalogs || hasCollections) ModernHomeSkeleton(uiState = uiState, modifier = Modifier.fillMaxSize())
        return
    }
    // "Show Hero" off, or a kids Home: rows only, using the full height (#797).
    val showHero = uiState.heroSectionEnabled

    val carouselLookups = presentation.lookups
    val rowIndexByKey = carouselLookups.rowIndexByKey
    val rowByKey = carouselLookups.rowByKey
    val rowKeyByGlobalRowIndex = carouselLookups.rowKeyByGlobalRowIndex
    val activeRowKeys = carouselLookups.activeRowKeys
    val activeCatalogItemIds = carouselLookups.activeCatalogItemIds

    val verticalRowListState = rememberLazyListState(
        initialFirstVisibleItemIndex = focusState.verticalScrollIndex,
        initialFirstVisibleItemScrollOffset = focusState.verticalScrollOffset
    )
    val isVerticalRowsScrollingState = remember(verticalRowListState) {
        derivedStateOf { verticalRowListState.isScrollInProgress }
    }

    val rowListStates = remember { mutableStateMapOf<String, LazyListState>() }
    val loadMoreRequestedTotals = remember { mutableStateMapOf<String, Int>() }

    // Active row and card, each row's card, the pending focus request, the catalog selection and
    // the expanded card. Rows report focus through its methods.
    val focusController = remember { HomeFocusController() }
    val latestOnFocusedRowKeyChanged = rememberUpdatedState(onFocusedRowKeyChanged)
    SideEffect {
        // saveFocusState only runs on dispose, which a system Home press never triggers, so the
        // focused row is reported as it changes instead.
        focusController.onFocusedRowKeyChanged = { key -> latestOnFocusedRowKeyChanged.value(key) }
    }
    val focusedItemByRow = focusController.focusedItemByRow
    val activeRowKey = focusController.activeRowKey
    val activeItemIndex = focusController.activeItemIndex
    val focusedCatalogSelection = focusController.focusedCatalogSelection
    val expandedCatalogFocusKey = focusController.expandedCatalogFocusKey
    val itemIdentitySnapshot = remember { ItemIdentitySnapshot() }
    val stableRowListStates = remember { StableRef<MutableMap<String, LazyListState>>(rowListStates) }
    val stableLoadMoreRequestedTotals = remember { StableRef<MutableMap<String, Int>>(loadMoreRequestedTotals) }
    // Every focus move writes focusedItemByRow, so reading it observably here recomposed this
    // whole composable on each D-pad press. The restore below only cares whether the map is still
    // empty when this runs, and the map is never cleared, so the read does not need observing.
    if (focusState.hasSavedFocus && Snapshot.withoutReadObservation { focusedItemByRow.isEmpty() }) {
        // Every row is saved, so restore every row, not only the one that held focus.
        val rowsByKey = carouselRows.list.associateBy { it.key }
        focusState.focusedItemKeyByRow.forEach { (rowKey, savedItemKey) ->
            if (savedItemKey.isBlank()) return@forEach
            val row = rowsByKey[rowKey] ?: return@forEach
            val itemIndex = row.focusIndexForSavedKey(savedItemKey)
            if (itemIndex != null) focusedItemByRow[rowKey] = itemIndex
        }
    }

    val restoredFromSavedState = remember { mutableStateOf(false) }
    val optionsItem = remember { mutableStateOf<ContinueWatchingItem?>(null) }
    val isRapidHorizontalNav = remember { mutableStateOf(false) }
    var lastRequestedTrailerFocusKey by remember { mutableStateOf<String?>(null) }
    var endedCollectionHeroVideoPlaybackKey by remember { mutableStateOf<String?>(null) }

    val latestRowsForScrollToTop = rememberUpdatedState(carouselRows)

    // Moves focus to the first row and scrolls Home to the top. With [resetRows] every row starts
    // over at its first card (Home picked in the sidebar while on Home); without it the first row
    // keeps its remembered card and the other rows keep their place (Back from a lower row, #817).
    // Scrolling alone left the active row, its saved index and the hero on the old row, so the
    // sidebar's focus hand-back went to that row and scrolled back down (or found nothing).
    suspend fun focusFirstRowAtTop(resetRows: Boolean) {
        val firstRow = latestRowsForScrollToTop.value.list.firstOrNull()
        if (resetRows) focusedItemByRow.keys.toList().forEach { focusedItemByRow[it] = 0 }
        focusController.clearCatalogSelection()
        if (firstRow != null) {
            val index = if (resetRows) 0
            else (focusedItemByRow[firstRow.key] ?: 0).coerceIn(0, firstRow.maxFocusIndex())
            focusController.setActive(firstRow.key, index)
            focusedItemByRow[firstRow.key] = index
            focusController.requestRowFocus(firstRow.key, index)
        }
        verticalRowListState.scrollToItem(0, 0)
        if (resetRows) {
            rowListStates.values.toList().forEach { state ->
                runSuspendCatching { state.scrollToItem(0, 0) }
            }
        }
    }

    // Back on Home, before it opens the sidebar (#817):
    // 1. on a card past the first in a row: scroll that row to its start;
    // 2. on the first card of a row below the first: scroll Home to the top and focus the first
    //    row's remembered card;
    // 3. on the first card of the first row: not handled here, so Back opens the sidebar.
    // Disabled when the sidebar owns focus (expanded) so Back can exit the app.
    val backScrollScope = rememberCoroutineScope()
    val contentHasFocus = remember { mutableStateOf(false) }
    // The hero's full-screen trailer state, handed up from below once it exists (in a SideEffect,
    // so the derivation here follows it without this composable reading it).
    val fullscreenTrailerPlayingSource = remember { mutableStateOf<State<Boolean>>(FixedState(false)) }
    val shouldInterceptBack = remember {
        derivedStateOf {
            if (!contentHasFocus.value) return@derivedStateOf false
            if (fullscreenTrailerPlayingSource.value.value) return@derivedStateOf true
            val rowKey = activeRowKey.value ?: return@derivedStateOf false
            homeBackStep(
                focusedRowKey = rowKey,
                focusedItemIndex = focusedItemByRow[rowKey] ?: 0,
                firstRowKey = latestRowsForScrollToTop.value.list.firstOrNull()?.key
            ) != HomeBackStep.OPEN_SIDEBAR
        }
    }
    IsolatedReadScope {
        BackHandler(enabled = shouldInterceptBack.value) {
            if (fullscreenTrailerPlayingSource.value.value) {
                // Dismisses the full-screen trailer.
                focusController.clearCatalogSelection()
                return@BackHandler
            }
            val rowKey = activeRowKey.value ?: return@BackHandler
            val step = homeBackStep(
                focusedRowKey = rowKey,
                focusedItemIndex = focusedItemByRow[rowKey] ?: 0,
                firstRowKey = latestRowsForScrollToTop.value.list.firstOrNull()?.key
            )
            when (step) {
                HomeBackStep.ROW_START -> {
                    val listState = rowListStates[rowKey]
                    focusedItemByRow[rowKey] = 0
                    focusController.requestRowFocus(rowKey, 0)
                    backScrollScope.launch {
                        listState?.scrollToItem(0, 0)
                    }
                }
                HomeBackStep.TOP_ROW -> backScrollScope.launch { focusFirstRowAtTop(resetRows = false) }
                HomeBackStep.OPEN_SIDEBAR -> Unit
            }
        }
    }

    // Home picked in the sidebar while on Home: start over at the first card of the first row.
    LaunchedEffect(scrollToTopTrigger) {
        if (scrollToTopTrigger <= 0) return@LaunchedEffect
        focusFirstRowAtTop(resetRows = true)
    }

    // While focus rests on the Spotlight untouched, move to its next card every
    // SPOTLIGHT_ADVANCE_MS, so the hero walks through the featured titles (#813). Any key restarts
    // the wait (spotlightKeyNonce), and reduced motion turns it off.
    val spotlightKeyNonce = remember { mutableIntStateOf(0) }
    // Up from the Spotlight to the filter keeps Spotlight the active row, so this stops the rotation
    // taking focus back from the filter.
    val contentFilterFocused = remember { mutableStateOf(false) }
    val spotlightRotates = FetchTheme.fullMotion
    IsolatedReadScope {
        val restingOnSpotlight = spotlightRotates &&
            contentHasFocus.value &&
            !contentFilterFocused.value &&
            !isSidebarExpanded.value &&
            activeRowKey.value == MODERN_SPOTLIGHT_ROW_KEY
        LaunchedEffect(restingOnSpotlight, spotlightKeyNonce.intValue) {
            if (!restingOnSpotlight) return@LaunchedEffect
            while (true) {
                delay(SPOTLIGHT_ADVANCE_MS)
                if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) continue
                if (fullscreenTrailerPlayingSource.value.value) continue
                val row = latestRowsForScrollToTop.value.list
                    .firstOrNull { it.key == MODERN_SPOTLIGHT_ROW_KEY } ?: break
                if (row.items.size < 2) break
                val next = ((focusedItemByRow[row.key] ?: 0) + 1) % row.items.size
                focusController.requestRowFocus(row.key, next)
            }
        }
    }

    val currentView = LocalView.current
    val metricsHolder = PerformanceMetricsState.getHolderForHierarchy(currentView)
    LaunchedEffect(verticalRowListState) {
        snapshotFlow { verticalRowListState.isScrollInProgress }
            .collect { scrolling ->
                metricsHolder.state?.putState("HomeScrolling", scrolling.toString())
            }
    }
    LaunchedEffect(enrichingItemId) {
        snapshotFlow { enrichingItemId.value != null }
            .distinctUntilChanged()
            .collect { enriching -> metricsHolder.state?.putState("HeroEnriching", enriching.toString()) }
    }

    // Both effects restart when the settled selection or scroll state changes. Hosting them in their
    // own scope keeps those changes from recomposing the whole home content on every focus move.
    IsolatedReadScope {
        LaunchedEffect(
            focusedCatalogSelection.value?.focusKey,
            focusController.expansionInteractionNonce.value,
            shouldActivateFocusedPosterFlow,
            trailerPlaybackTarget,
            uiState.focusedPosterBackdropExpandDelaySeconds,
            verticalRowListState.isScrollInProgress,
            // Re-run when the sidebar takes/returns focus so a pending expand cannot
            // complete while the user is on the Home button (#2815).
            isSidebarExpanded.value
        ) {
            // Always clear first so sidebar open / selection change collapses immediately.
            focusController.setExpandedCatalogFocusKey(null)
            if (!shouldActivateFocusedPosterFlow) return@LaunchedEffect
            if (isSidebarExpanded.value) return@LaunchedEffect
            if (verticalRowListState.isScrollInProgress) return@LaunchedEffect
            val selection = focusedCatalogSelection.value ?: return@LaunchedEffect
            if (selection.payload !is ModernPayload.Catalog) return@LaunchedEffect
            val expansionDelayMs = (uiState.focusedPosterBackdropExpandDelaySeconds.coerceAtLeast(0) * 1000L).coerceAtLeast(150L)
            delay(expansionDelayMs)
            if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
            if (shouldActivateFocusedPosterFlow &&
                !isSidebarExpanded.value &&
                !verticalRowListState.isScrollInProgress &&
                focusedCatalogSelection.value?.focusKey == selection.focusKey
            ) {
                focusController.setExpandedCatalogFocusKey(selection.focusKey)
            }
        }

        LaunchedEffect(
            focusedCatalogSelection.value?.focusKey,
            effectiveAutoplayEnabled,
            verticalRowListState.isScrollInProgress
        ) {
            if (!effectiveAutoplayEnabled) {
                lastRequestedTrailerFocusKey = null
                return@LaunchedEffect
            }
            if (verticalRowListState.isScrollInProgress) return@LaunchedEffect
            val selection = focusedCatalogSelection.value ?: run {
                lastRequestedTrailerFocusKey = null
                return@LaunchedEffect
            }
            val payload = selection.payload as? ModernPayload.Catalog ?: run {
                lastRequestedTrailerFocusKey = null
                return@LaunchedEffect
            }
            if (selection.focusKey == lastRequestedTrailerFocusKey) return@LaunchedEffect
            delay(150)
            if (focusedCatalogSelection.value?.focusKey != selection.focusKey) return@LaunchedEffect
            onRequestTrailerPreview(
                payload.itemId,
                payload.trailerTitle,
                payload.trailerReleaseInfo,
                payload.trailerApiType
            )
            lastRequestedTrailerFocusKey = selection.focusKey
        }
    }

    val currentItemIdentitiesByRow = carouselLookups.itemIdentitiesByRow.map
    if (itemIdentitySnapshot.byRow !== currentItemIdentitiesByRow) {
        // Runs when the rows change, not when focus does: reading focusedItemByRow observably here
        // would subscribe this whole composable to every focus move.
        Snapshot.withoutReadObservation {
            currentItemIdentitiesByRow.forEach { (rowKey, currentIdentities) ->
                val storedIndex = focusedItemByRow[rowKey]
                val relocatedIndex = findRelocatedItemIndex(
                    previousIdentities = itemIdentitySnapshot.byRow[rowKey]?.list,
                    currentIdentities = currentIdentities.list,
                    storedIndex = storedIndex
                )
                if (relocatedIndex != null && relocatedIndex != storedIndex) {
                    focusedItemByRow[rowKey] = relocatedIndex
                    // The hero reads its own index, synced by an effect keyed on the row size, so it
                    // would land a frame late and show whatever took the old index meanwhile.
                    if (rowKey == activeRowKey.value) {
                        focusController.setActiveItemIndex(relocatedIndex)
                    }
                }
            }
        }
        itemIdentitySnapshot.byRow = currentItemIdentitiesByRow
    }

    LaunchedEffect(carouselRows, focusState.hasSavedFocus) {
        rowListStates.keys.retainAll(activeRowKeys)
        loadMoreRequestedTotals.keys.retainAll(activeRowKeys)
        val staleSelection = focusedCatalogSelection.value?.let { selection ->
            when (val payload = selection.payload) {
                is ModernPayload.Catalog -> !payload.itemId.startsWith("__placeholder_") && payload.itemId !in activeCatalogItemIds.set
                is ModernPayload.CollectionFolder -> carouselRows.list.none { row ->
                    row.items.list.any { item ->
                        (item.payload as? ModernPayload.CollectionFolder)?.focusKey == selection.focusKey
                    }
                }
                is ModernPayload.YouTubeVideo -> carouselRows.list.none { row ->
                    row.items.list.any { item ->
                        (item.payload as? ModernPayload.YouTubeVideo)?.focusKey == selection.focusKey
                    }
                }
                is ModernPayload.ContinueWatching -> true
            }
        } ?: false
        if (staleSelection) {
            focusController.clearCatalogSelection()
        }
        val currentSelection = focusedCatalogSelection.value
        val currentCatalogPayload = currentSelection?.payload as? ModernPayload.Catalog
        if (currentSelection != null && currentCatalogPayload != null && currentCatalogPayload.itemId.startsWith("__placeholder_")) {
            val activeKey = activeRowKey.value
            val activeIdx = activeItemIndex.value
            val activeRow = activeKey?.let { rowByKey.map[it] }
            val realItem = activeRow?.items?.list?.getOrNull(activeIdx)
            val realPayload = realItem?.payload as? ModernPayload.Catalog
            if (realPayload != null && !realPayload.itemId.startsWith("__placeholder_")) {
                focusController.selectCatalog(
                    FocusedCatalogSelection(focusKey = realPayload.focusKey, payload = realPayload)
                )
                lastRequestedTrailerFocusKey = null
                focusController.setExpandedCatalogFocusKey(null)
            }
        }

        carouselRows.list.forEach { row ->
            if (row.items.list.isNotEmpty() && row.key !in focusedItemByRow) {
                focusedItemByRow[row.key] = 0
            }
        }

        if (!restoredFromSavedState.value && focusState.hasSavedFocus) {
            val savedRowKey = focusState.focusedRowKey ?: when {
                focusState.focusedRowIndex == -1 && uiState.continueWatchingEnabled && uiState.continueWatchingItems.isNotEmpty() -> "continue_watching"
                focusState.focusedRowIndex >= 0 -> rowKeyByGlobalRowIndex.map[focusState.focusedRowIndex]
                else -> null
            }

            val resolvedRow = savedRowKey?.let { rowByKey.map[it] } ?: carouselRows.list.firstOrNull()
            if (resolvedRow != null) {
                val savedItemKey = focusState.focusedItemKeyByRow[resolvedRow.key]
                val resolvedIndex = if (savedItemKey != null) {
                    resolvedRow.focusIndexForSavedKey(savedItemKey) ?: 0
                } else {
                    focusState.focusedItemIndex
                        .coerceAtLeast(0)
                        .coerceAtMost(resolvedRow.maxFocusIndex())
                }

                focusController.setActive(resolvedRow.key, resolvedIndex)
                focusedItemByRow[resolvedRow.key] = resolvedIndex
                focusController.requestRowFocus(resolvedRow.key, resolvedIndex)
                restoredFromSavedState.value = true
                return@LaunchedEffect
            }
        }

        val hadActiveRow = activeRowKey.value != null
        val existingActive = activeRowKey.value?.let { rowByKey[it] }
        val firstRow = carouselRows.list.firstOrNull()

        val resolvedActive = when {
            existingActive != null -> existingActive
            hadActiveRow -> null // Wait for data to reappear
            else -> firstRow     // Initial state
        }

        if (resolvedActive != null) {
            val resolvedIndex = focusedItemByRow[resolvedActive.key]
                ?.coerceIn(0, resolvedActive.maxFocusIndex())
                ?: 0
            focusController.setActive(resolvedActive.key, resolvedIndex)
            focusedItemByRow[resolvedActive.key] = resolvedIndex

            if (!focusState.hasSavedFocus && !hadActiveRow) {
                focusController.requestRowFocus(resolvedActive.key, resolvedIndex)
            }
        }

        if (!restoredFromSavedState.value && carouselRows.list.isNotEmpty()) {
            restoredFromSavedState.value = true
        }
    }

    // A new Movies / Shows filter (#814) can take away the row focus was last on. Focus is on the
    // filter then, so point the active row (and the hero, and Down) at the new first row.
    val appliedContentFilter = remember { mutableStateOf(presentation.contentFilter) }
    LaunchedEffect(presentation.contentFilter, carouselRows) {
        if (appliedContentFilter.value == presentation.contentFilter) return@LaunchedEffect
        appliedContentFilter.value = presentation.contentFilter
        val firstRow = carouselRows.list.firstOrNull() ?: return@LaunchedEffect
        val index = (focusedItemByRow[firstRow.key] ?: 0).coerceIn(0, firstRow.maxFocusIndex())
        focusController.clearCatalogSelection()
        focusController.setActive(firstRow.key, index)
        focusedItemByRow[firstRow.key] = index
    }

    LaunchedEffect(focusState.verticalScrollIndex, focusState.verticalScrollOffset) {
        val targetIndex = focusState.verticalScrollIndex
        val targetOffset = focusState.verticalScrollOffset
        if (verticalRowListState.firstVisibleItemIndex == targetIndex &&
            verticalRowListState.firstVisibleItemScrollOffset == targetOffset
        ) {
            return@LaunchedEffect
        }
        if (targetIndex > 0 || targetOffset > 0) {
            verticalRowListState.scrollToItem(targetIndex, targetOffset)
        }
    }

    // These derive from each other inside the derivations instead of through remember keys, so
    // nothing here reads them during composition of this whole function. The focused row and item
    // change on every D-pad press, and reading them here used to recompose all of the home content.
    val activeRowState = remember(carouselRows, rowByKey) {
        derivedStateOf {
            val activeKey = activeRowKey.value
            if (activeKey == null) null
            else rowByKey[activeKey]
        }
    }
    val clampedActiveItemIndexState = remember(activeRowState) {
        derivedStateOf {
            activeRowState.value?.let { row ->
                activeItemIndex.value.coerceIn(0, (row.items.size - 1).coerceAtLeast(0))
            } ?: 0
        }
    }
    val activeHeroItemKeyState = remember(activeRowState, clampedActiveItemIndexState) {
        derivedStateOf {
            val row = activeRowState.value ?: return@derivedStateOf null
            row.items.getOrNull(clampedActiveItemIndexState.value)?.key ?: row.items.firstOrNull()?.key
        }
    }

    // Detect rapid horizontal navigation (holding DPAD left/right).
    // Activates after two successive item changes within the SAME row within 300ms.
    val lastRapidNavRowKey = remember { mutableStateOf<String?>(null) }

    IsolatedReadScope {
        val activeRow = activeRowState.value
        val activeHeroItemKey = activeHeroItemKeyState.value

        LaunchedEffect(activeRow?.key, activeRow?.items?.size) {
            val row = activeRow ?: return@LaunchedEffect
            val savedIdx = focusedItemByRow[row.key] ?: 0
            val clampedIndex = savedIdx.coerceIn(0, row.maxFocusIndex())
            if (activeItemIndex.value != clampedIndex) {
                focusController.setActiveItemIndex(clampedIndex)
            }
            focusedItemByRow[row.key] = clampedIndex
        }

        LaunchedEffect(activeHeroItemKey) {
            if (activeHeroItemKey == null) return@LaunchedEffect
            val currentRowKey = activeRowKey.value
            val timeSinceLast = System.currentTimeMillis() - focusController.lastHeroNavigationAtMs.value
            // Only activate rapid nav if we're in the same row as last navigation
            if (timeSinceLast in 1..300 && lastRapidNavRowKey.value == currentRowKey) {
                isRapidHorizontalNav.value = true
            }
            lastRapidNavRowKey.value = currentRowKey
            delay(MODERN_HERO_FOCUS_DEBOUNCE_MS)
            isRapidHorizontalNav.value = false
        }
    }

    val latestCarouselRows by rememberUpdatedState(carouselRows)
    val latestVerticalRowListState by rememberUpdatedState(verticalRowListState)
    val latestRowIndexByKey = rememberUpdatedState(rowIndexByKey)
    val latestSavedScrollAnchors by rememberUpdatedState(focusState.catalogRowScrollAnchors)
    val latestActiveRowState by rememberUpdatedState(activeRowState)
    DisposableEffect(Unit) {
        onDispose {
            val row = latestActiveRowState.value
            val focusedRowKey = row?.key
            // Only save focus state if home screen actually had focus (focusedRowKey is not null)
            // This prevents saving invalid state like row -1 when sidebar was open
            if (focusedRowKey == null) {
                // Home screen didn't have focus, don't overwrite the saved state
                return@onDispose
            }
            
            val focusedRowIndex = focusedRowKey?.let { latestRowIndexByKey.value.map[it] } ?: -1
            val focusedItemIndex = activeItemIndex.value
            
            val focusedItemKeyByRow = latestCarouselRows
                .associate { rowState ->
                    val focusedIdx = focusedItemByRow[rowState.key] ?: 0
                    val itemKey = rowState.savedFocusKeyAt(focusedIdx)
                    rowState.key to itemKey
                }

            val catalogRowScrollStates = latestCarouselRows
                .associate { rowState ->
                    val scrollIndex = rowListStates[rowState.key]?.firstVisibleItemIndex
                        ?: (focusedItemByRow[rowState.key] ?: 0)
                    rowState.key to scrollIndex
                }

            // A row not composed since the return has no state: keep the anchor it came back with.
            val liveRowKeys = latestCarouselRows.map { it.key }.toSet()
            val catalogRowScrollAnchors = latestSavedScrollAnchors.filterKeys { it in liveRowKeys } + latestCarouselRows
                .mapNotNull { rowState ->
                    val state = rowListStates[rowState.key] ?: return@mapNotNull null
                    // The card last measured there: an off-screen row is not re-measured when items land in front.
                    val anchorKey = (state.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.index == state.firstVisibleItemIndex }?.key as? String)
                        ?.takeIf { key -> rowState.items.list.any { it.key == key } }
                        ?: rowState.items.list.getOrNull(state.firstVisibleItemIndex)?.key
                        ?: return@mapNotNull null
                    rowState.key to anchorKey
                }
                .toMap()

            onSaveFocusState(
                latestVerticalRowListState.firstVisibleItemIndex,
                latestVerticalRowListState.firstVisibleItemScrollOffset,
                focusedRowKey,
                focusedItemKeyByRow,
                catalogRowScrollStates,
                catalogRowScrollAnchors,
                focusedRowIndex,
                focusedItemIndex
            )
        }
    }

    val homeLayout = rememberModernHomeLayout(uiState)
    val portraitCatalogCardWidth = homeLayout.portraitCatalogCardWidth
    val portraitCatalogCardHeight = homeLayout.portraitCatalogCardHeight
    val landscapeCatalogCardWidth = homeLayout.landscapeCatalogCardWidth
    val landscapeCatalogCardHeight = homeLayout.landscapeCatalogCardHeight
    val continueWatchingStyle = uiState.continueWatchingCardStyle
    val continueWatchingCardWidth = homeLayout.continueWatchingCardWidth
    val continueWatchingCardHeight = homeLayout.continueWatchingCardHeight

    val localConfiguration = LocalConfiguration.current
    val screenWidth = localConfiguration.screenWidthDp.dp
    val screenHeight = localConfiguration.screenHeightDp.dp

    Box(modifier = Modifier.fillMaxSize().background(FetchTheme.colors.Background)) {
            val posterCardCornerRadius = cardCornerRadius(uiState.posterCardCornerRadiusDp)
            val rowHorizontalPadding = FetchTheme.spacing.screen.horizontal

            val activeCarouselItemState = remember(carouselRows, rowByKey) {
                derivedStateOf {
                    val activeKey = activeRowKey.value
                    val row = if (activeKey == null) null
                    else rowByKey[activeKey]
                    
                    val index = activeItemIndex.value
                    val clampedIdx = row?.let {
                        index.coerceIn(0, (it.items.size - 1).coerceAtLeast(0))
                    } ?: 0
                    row?.items?.getOrNull(clampedIdx)
                }
            }

            val seeAllHeroLabel = androidx.compose.ui.res.stringResource(R.string.action_see_all)
            val resolvedHeroState = remember(activeCarouselItemState, enrichedPreviews, enrichingItemId, uiState.heroEnrichmentEnabled, uiState.homeImdbRatingsVisibility, failedEnrichmentIds, seeAllHeroLabel) {
                derivedStateOf {
                    val activeCarouselItem = activeCarouselItemState.value
                    val activeItemId = activeCarouselItem?.metaPreview?.id
                    val enrichingId = enrichingItemId.value
                    val enrichmentActive = enrichingId != null && enrichingId == activeItemId
                    
                    val enrichedItem = activeItemId?.let { enrichedPreviews.value[it] }
                    val enrichedHero = if (enrichedItem != null) {
                        HeroPreview(
                            title = enrichedItem.name,
                            logo = enrichedItem.logo,
                            description = enrichedItem.description,
                            contentTypeText = activeCarouselItem?.heroPreview?.contentTypeText,
                            isSeries = isSeriesType(enrichedItem.apiType),
                            yearText = extractYearText(enrichedItem.type, enrichedItem.releaseInfo, enrichedItem.released)
                                ?: activeCarouselItem?.heroPreview?.yearText,
                            runtimeText = formatHeroRuntime(enrichedItem.runtime)
                                ?: activeCarouselItem?.heroPreview?.runtimeText,
                            imdbText = enrichedItem.imdbRating
                                ?.let { String.format(java.util.Locale.US, "%.1f", it) },
                            ageRatingText = enrichedItem.ageRating,
                            statusText = enrichedItem.status,
                            countryText = enrichedItem.country,
                            languageText = enrichedItem.language?.uppercase(),
                            genres = enrichedItem.genres.take(3).asStable(),
                            poster = enrichedItem.poster,
                            backdrop = enrichedItem.backdropUrl,
                            imageUrl = activeCarouselItem?.heroPreview?.imageUrl,
                            mdbListRatings = enrichedItem.mdbListRatings,
                            mdbListRatingOrder = enrichedItem.mdbListRatingOrder,
                            frozenBackdropUrl = activeCarouselItem?.heroPreview?.frozenBackdropUrl,
                            frozenLogoUrl = activeCarouselItem?.heroPreview?.frozenLogoUrl,
                            resumeActionText = activeCarouselItem?.heroPreview?.resumeActionText,
                            resumeTimeLeftText = activeCarouselItem?.heroPreview?.resumeTimeLeftText,
                            resumeProgressFraction = activeCarouselItem?.heroPreview?.resumeProgressFraction,
                            bestMatchKey = activeCarouselItem?.heroPreview?.bestMatchKey,
                            resumeEpisodeTitle = activeCarouselItem?.heroPreview?.resumeEpisodeTitle,
                            eyebrowText = activeCarouselItem?.heroPreview?.eyebrowText,
                            isCatalogTitle = activeCarouselItem?.heroPreview?.isCatalogTitle == true
                        )
                    } else null

                    val resolvedHero = when {
                        activeCarouselItem == null -> null
                        enrichmentActive -> activeCarouselItem.heroPreview
                        enrichedHero != null -> enrichedHero
                        else -> activeCarouselItem.heroPreview
                    }
                    
                    // Only use the real enrichmentActive flag from the ViewModel.
                    // Additionally, if enrichment is enabled but no enriched data exists yet
                    // for this item, treat as pending to avoid showing un-enriched addon data.
                    // Exception: if enrichment already failed for this item, show addon data.
                    // Also treat as pending when activeCarouselItem is null (row not yet resolved).
                    val heroEnrichmentEnabled = uiState.heroEnrichmentEnabled
                    val enrichmentFailed = activeItemId != null && activeItemId in failedEnrichmentIds.value
                    val effectiveEnrichmentActive = activeCarouselItem == null || enrichmentActive ||
                        (enrichedHero == null && activeItemId != null && heroEnrichmentEnabled && !enrichmentFailed)
                    
                    val activeRowKeyVal = activeRowKey.value
                    val activeRow = activeRowKeyVal?.let { rowByKey[it] }
                    val activeRowFallbackBackdrop = activeRow?.items?.list?.firstNotNullOfOrNull { item ->
                        item.heroPreview.backdrop?.takeIf { it.isNotBlank() }
                    }
                    
                    val heroBackdrop = firstNonBlank(
                        resolvedHero?.backdrop,
                        resolvedHero?.imageUrl,
                        resolvedHero?.poster,
                        activeRowFallbackBackdrop
                    )
                    
                    // The See all card (index items.size) has a hero of its own: the row's title and
                    // "See all" over the backdrop already showing, not the last card's title.
                    val seeAllRow = activeRow?.takeIf {
                        it.showsSeeAllCard() && activeItemIndex.value >= it.items.size
                    }
                    if (seeAllRow != null) {
                        val seeAllHero = HeroPreview(
                            title = seeAllRow.title,
                            logo = null,
                            description = null,
                            contentTypeText = null,
                            yearText = null,
                            imdbText = null,
                            genres = emptyList<String>().asStable(),
                            poster = null,
                            backdrop = heroBackdrop,
                            imageUrl = null,
                            eyebrowText = seeAllHeroLabel
                        )
                        return@derivedStateOf Triple(heroBackdrop, seeAllHero, false)
                    }

                    Triple(heroBackdrop, resolvedHero, effectiveEnrichmentActive)
                }
            }

            val expandedFocusedSelectionState = remember {
                derivedStateOf {
                    focusedCatalogSelection.value
                        ?.takeIf { it.focusKey == expandedCatalogFocusKey.value }
                        ?.takeIf { it.payload is ModernPayload.Catalog }
                }
            }

            val heroTrailerUrlsState = remember(trailerPreviewUrls, trailerPreviewAudioUrls) {
                derivedStateOf {
                    val expandedFocusedSelection = expandedFocusedSelectionState.value
                    val itemId = (expandedFocusedSelection?.payload as? ModernPayload.Catalog)?.itemId
                    val url = itemId?.let { trailerPreviewUrls[it] }
                    val audioUrl = itemId?.let { trailerPreviewAudioUrls[it] }
                    url to audioUrl
                }
            }
            // Derived so that this composable only recomposes when the collection video actually
            // changes. Reading focusedCatalogSelection directly here recomposed all of the home
            // content each time a catalog card settled focus, where both values stay null.
            val collectionHeroVideoUrlState = remember {
                derivedStateOf {
                    (focusedCatalogSelection.value?.payload as? ModernPayload.CollectionFolder)?.heroVideoUrl
                }
            }
            val collectionHeroVideoPlaybackKeyState = remember {
                derivedStateOf {
                    val focusKey = focusedCatalogSelection.value?.focusKey
                    val url = collectionHeroVideoUrlState.value?.takeIf { it.isNotBlank() }
                    if (focusKey != null && url != null) "$focusKey::${focusController.focusedHeroMediaNonce.value}::$url" else null
                }
            }
            val collectionHeroVideoUrl = collectionHeroVideoUrlState.value
            val collectionHeroVideoPlaybackKey = collectionHeroVideoPlaybackKeyState.value
            val isScrollStoppedState = remember(verticalRowListState) {
                derivedStateOf { !verticalRowListState.isScrollInProgress }
            }
            val shouldPlayCatalogHeroTrailerState = remember(
                showHero,
                isScrollStoppedState,
                effectiveAutoplayEnabled,
                trailerPlaybackTarget,
                heroTrailerUrlsState,
                isSidebarExpanded,
                isRapidHorizontalNav
            ) {
                derivedStateOf {
                    showHero &&
                        isScrollStoppedState.value &&
                        effectiveAutoplayEnabled &&
                        !isSidebarExpanded.value &&
                        !isRapidHorizontalNav.value &&
                        trailerPlaybackTarget == FocusedPosterTrailerPlaybackTarget.HERO_MEDIA &&
                        !heroTrailerUrlsState.value.first.isNullOrBlank()
                }
            }
            val shouldPlayCollectionHeroVideoState = remember(
                showHero,
                isScrollStoppedState,
                collectionHeroVideoUrl,
                collectionHeroVideoPlaybackKey,
                endedCollectionHeroVideoPlaybackKey,
                isSidebarExpanded
            ) {
                derivedStateOf {
                    showHero &&
                        isScrollStoppedState.value &&
                        !isSidebarExpanded.value &&
                        !collectionHeroVideoUrl.isNullOrBlank() &&
                        collectionHeroVideoPlaybackKey != null &&
                        endedCollectionHeroVideoPlaybackKey != collectionHeroVideoPlaybackKey
                }
            }
            val heroMediaDataState = remember(shouldPlayCollectionHeroVideoState, collectionHeroVideoUrl, heroTrailerUrlsState, collectionHeroVideoPlaybackKey) {
                derivedStateOf {
                    val shouldPlayCollectionHeroVideo = shouldPlayCollectionHeroVideoState.value
                    val (heroTrailerUrl, heroTrailerAudioUrl) = heroTrailerUrlsState.value
                    val url = if (shouldPlayCollectionHeroVideo) collectionHeroVideoUrl else heroTrailerUrl
                    val audioUrl = if (shouldPlayCollectionHeroVideo) null else heroTrailerAudioUrl
                    val playbackKey = if (shouldPlayCollectionHeroVideo) collectionHeroVideoPlaybackKey else heroTrailerUrl
                    Triple(url, audioUrl, playbackKey)
                }
            }
            // Only while Home is resumed: a hero video must not start (or restart) behind another
            // screen or a paused activity (#787).
            val heroLifecycleState = lifecycleOwner.lifecycle.currentStateAsState()
            val shouldPlayHeroTrailerState = remember(shouldPlayCatalogHeroTrailerState, shouldPlayCollectionHeroVideoState, heroLifecycleState) {
                derivedStateOf {
                    heroLifecycleState.value.isAtLeast(Lifecycle.State.RESUMED) &&
                        (shouldPlayCatalogHeroTrailerState.value || shouldPlayCollectionHeroVideoState.value)
                }
            }
            val heroMediaMutedState = remember(uiState.focusedPosterBackdropTrailerMuted) {
                derivedStateOf { uiState.focusedPosterBackdropTrailerMuted }
            }
            var heroTrailerFirstFrameRendered by remember { mutableStateOf(false) }
            LaunchedEffect(heroMediaDataState.value.third) {
                heroTrailerFirstFrameRendered = false
            }

            // Collection hero videos should trigger the same fullscreen layout
            // and content fade as catalog trailers — otherwise the video plays
            // "behind" the collection cards instead of expanding into the hero area (#2683).
            val isTrailerPlayingFullscreenState = remember(
                fullScreenBackdrop,
                shouldPlayCatalogHeroTrailerState,
                shouldPlayCollectionHeroVideoState
            ) {
                derivedStateOf {
                    fullScreenBackdrop &&
                        (shouldPlayCatalogHeroTrailerState.value || shouldPlayCollectionHeroVideoState.value) &&
                        heroTrailerFirstFrameRendered
                }
            }
            // Hand the state (not its value) up, so the row-scroll BackHandler dismisses a
            // fullscreen trailer instead of scrolling the row back while one is visible.
            SideEffect { fullscreenTrailerPlayingSource.value = isTrailerPlayingFullscreenState }
            val liveHeroSceneState = remember(
                resolvedHeroState,
                shouldPlayHeroTrailerState,
                heroMediaDataState,
                heroMediaMutedState,
                fullScreenBackdrop
            ) {
                derivedStateOf {
                    val (heroBackdrop, resolvedHero, enrichmentActive) = resolvedHeroState.value
                    val (heroMediaUrl, heroMediaAudioUrl, heroMediaPlaybackKey) = heroMediaDataState.value
                    val preview = if (enrichmentActive) null else resolvedHero
                    ModernHeroSceneState(
                        heroBackdrop = heroBackdrop,
                        preview = preview,
                        enrichmentActive = enrichmentActive,
                        shouldPlayTrailer = shouldPlayHeroTrailerState.value,
                        trailerFirstFrameRendered = heroTrailerFirstFrameRendered,
                        trailerUrl = heroMediaUrl,
                        trailerAudioUrl = heroMediaAudioUrl,
                        trailerPlaybackKey = heroMediaPlaybackKey,
                        trailerMuted = heroMediaMutedState.value,
                        fullScreenBackdrop = fullScreenBackdrop
                    )
                }
            }
            // What the hero shows while scrolling, during a held D-pad and while enrichment is
            // pending: one presenter, its rules in resolveHeroScene. Its inputs change on every
            // focus move, so they are only read inside derived state, never by this composable.
            val heroLiveInputState = remember(liveHeroSceneState, resolvedHeroState, verticalRowListState) {
                derivedStateOf {
                    HeroLiveInput(
                        live = liveHeroSceneState.value,
                        // The resolved hero, not the scene's preview: the scene holds no preview
                        // while enrichment is pending, and the title block shows the catalog title
                        // meanwhile.
                        title = resolvedHeroState.value.second,
                        scrolling = verticalRowListState.isScrollInProgress,
                        rapidNavigation = isRapidHorizontalNav.value
                    )
                }
            }
            val heroPresenter = remember {
                HeroPresenter(
                    initialInput = heroLiveInputState,
                    initialDisplayedBackdrop = HeroBackdropState.lastDisplayedUrl,
                    initialBackdropTarget = HeroBackdropState.lastDisplayedUrl
                        ?: Snapshot.withoutReadObservation { liveHeroSceneState.value.heroBackdrop }
                )
            }
            SideEffect { heroPresenter.bind(heroLiveInputState) }
            LaunchedEffect(heroPresenter) { heroPresenter.run() }
            val heroSceneStateLambda = remember(heroPresenter) { { heroPresenter.scene.value } }
            val onBackdropDisplayedLambda = remember(heroPresenter) { { url: String -> heroPresenter.onBackdropDisplayed(url) } }

            val isFullScreenState = remember {
                derivedStateOf { liveHeroSceneState.value.fullScreenBackdrop }
            }
            val isFullScreenLambda = remember {
                { isFullScreenState.value }
            }

            val localDensity = LocalDensity.current
            val rowTitleHeight = homeLayout.rowTitleHeight
            val rowsViewportHeight = homeLayout.rowsViewportHeight
            val compactHeroText = homeLayout.compactHeroText
            val heroBackdropHeight = remember(screenHeight, rowsViewportHeight, rowTitleHeight) { (screenHeight - rowsViewportHeight + rowTitleHeight + 14.dp).coerceAtMost(screenHeight) }
            val verticalRowBringIntoViewSpec = remember(localDensity, defaultBringIntoViewSpec) {
                val topInsetPx = with(localDensity) { MODERN_ROW_HEADER_FOCUS_INSET.toPx() }
                @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
                object : BringIntoViewSpec {
                    override val scrollAnimationSpec: AnimationSpec<Float> = defaultBringIntoViewSpec.scrollAnimationSpec
                    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                        val currentLeadingEdge = offset
                        if (abs(currentLeadingEdge - topInsetPx) < 1f) return 0f
                        val distance = currentLeadingEdge - topInsetPx
                        if (distance < 0f && !verticalRowListState.canScrollBackward) return 0f
                        return distance
                    }
                }
            }
            val contentFocusRequester = LocalContentFocusRequester.current
            val heroMediaWidthPx = remember(screenWidth, localDensity, fullScreenBackdrop) {
                with(localDensity) {
                    if (fullScreenBackdrop) screenWidth.roundToPx()
                    else (screenWidth * MODERN_HERO_MEDIA_WIDTH_FRACTION).roundToPx()
                }.coerceAtLeast(1)
            }
            val heroMediaHeightPx = remember(heroBackdropHeight, screenHeight, localDensity, fullScreenBackdrop) {
                with(localDensity) {
                    if (fullScreenBackdrop) screenHeight.roundToPx()
                    else heroBackdropHeight.roundToPx()
                }.coerceAtLeast(1)
            }

            // Load the backdrops either side of the focused card, and of the card a step up or down
            // lands on, at the size the hero asks for, so those moves crossfade from the memory
            // cache instead of waiting on the network.
            val heroPreloadContext = LocalContext.current
            LaunchedEffect(showHero, rowByKey, carouselRows, heroMediaWidthPx, heroMediaHeightPx) {
                if (!showHero) return@LaunchedEffect
                snapshotFlow {
                    val activeKey = activeRowKey.value
                    val row = activeKey?.let { rowByKey[it] } ?: return@snapshotFlow emptyList()
                    val previews = enrichedPreviews.value
                    val lowRam = MemoryBudget.isLowRamTier
                    (
                        neighbourHeroBackdrops(
                            row.items.list,
                            activeItemIndex.value,
                            previews,
                            singleNeighbour = lowRam
                        ) + verticalNeighbourHeroBackdrops(
                            carouselRows.list,
                            activeKey,
                            landingIndex = { key -> focusedItemByRow[key] ?: 0 },
                            enrichedPreviews = previews,
                            singleNeighbour = lowRam
                        )
                    ).distinct()
                }
                    .distinctUntilChanged()
                    .collectLatest { urls ->
                        // Wait out a held D-pad, the same settle the hero art itself waits for.
                        delay(MODERN_HERO_FOCUS_DEBOUNCE_MS)
                        val loader = heroPreloadContext.imageLoader
                        urls.forEach { url ->
                            loader.enqueue(
                                ImageRequest.Builder(heroPreloadContext)
                                    .data(url)
                                    .size(width = heroMediaWidthPx, height = heroMediaHeightPx)
                                    .build()
                            )
                        }
                    }
            }

            val heroMediaModifier = remember(heroBackdropHeight, screenHeight, fullScreenBackdrop) {
                if (fullScreenBackdrop) {
                    Modifier.align(Alignment.TopStart).fillMaxWidth().height(screenHeight)
                } else {
                    Modifier.align(Alignment.TopEnd).offset(x = FetchTheme.spacing.huge).fillMaxWidth(MODERN_HERO_MEDIA_WIDTH_FRACTION).height(heroBackdropHeight)
                }
            }

            val fullScreenBackdropUpdated by rememberUpdatedState(fullScreenBackdrop)
            val shouldPlayCatalogHeroTrailerUpdated by rememberUpdatedState(shouldPlayCatalogHeroTrailerState.value)
            val shouldPlayCollectionHeroVideoUpdated by rememberUpdatedState(shouldPlayCollectionHeroVideoState.value)
            val heroTrailerFirstFrameRenderedUpdated by rememberUpdatedState(heroTrailerFirstFrameRendered)

            val onTrailerEndedLambda = remember {
                {
                    if (shouldPlayCollectionHeroVideoState.value && collectionHeroVideoPlaybackKey != null) {
                        endedCollectionHeroVideoPlaybackKey = collectionHeroVideoPlaybackKey
                    } else {
                        focusController.setExpandedCatalogFocusKey(null)
                    }
                }
            }
            val onFirstFrameRenderedLambda = remember { { heroTrailerFirstFrameRendered = true } }

            if (showHero) ModernHeroSection(
                heroSceneState = heroSceneStateLambda,
                isFullScreen = isFullScreenLambda,
                heroMediaWidthPx = heroMediaWidthPx,
                heroMediaHeightPx = heroMediaHeightPx,
                modifier = heroMediaModifier,
                onTrailerEnded = onTrailerEndedLambda,
                onFirstFrameRendered = onFirstFrameRenderedLambda,
                onBackdropDisplayed = onBackdropDisplayedLambda
            )

            // Fade content rows when ANY hero media (catalog trailer or collection
            // hero video) is playing in fullscreen — not just catalog trailers.
            val trailerContentAlphaState = animateFloatAsState(
                targetValue = if (fullScreenBackdropUpdated && (shouldPlayCatalogHeroTrailerUpdated || shouldPlayCollectionHeroVideoUpdated) && heroTrailerFirstFrameRenderedUpdated) 0f else 1f,
                animationSpec = tween(durationMillis = FetchMotion.tokens.durations.slow),
                label = "trailerContentFade"
            )

            val shouldPlayTrailerLambda = remember { { shouldPlayCatalogHeroTrailerUpdated || shouldPlayCollectionHeroVideoUpdated } }
            val heroTrailerRenderedLambda = remember { { heroTrailerFirstFrameRenderedUpdated } }

            val heroMetadataModifier = remember(rowHorizontalPadding, rowsViewportHeight) {
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = rowHorizontalPadding, end = FetchTheme.spacing.xxxl, bottom = FetchTheme.spacing.none + rowsViewportHeight + FetchTheme.spacing.lg)
                    .fillMaxWidth(MODERN_HERO_TEXT_WIDTH_FRACTION)
            }

            // The title and metadata follow focus at once, from the catalog's data until enrichment
            // replaces it. Only the art above waits for focus to settle and enrichment to land.
            if (showHero) HeroTitleBlock(
                // The resolved hero (catalog data until enrichment lands), or the last title
                // while focus passes over a gap.
                previewProvider = { heroPresenter.title.value },
                bestMatchFor = { key -> latestCachedBestMatches.value[key] },
                catalogQuickPlay = onCatalogItemPlay != null,
                showHoldHint = showHoldHint,
                portraitMode = !useLandscapePosters,
                compact = compactHeroText,
                showImdbRatings = uiState.homeImdbRatingsVisibility.showRatings,
                mdbListShowOnHero = uiState.mdbListShowOnHero,
                mdbListRatingOrder = uiState.mdbListRatingOrder,
                trailerPlaying = {
                    if (isRapidHorizontalNav.value) false
                    else {
                        val state = heroSceneStateLambda()
                        state.fullScreenBackdrop && shouldPlayTrailerLambda() && heroTrailerRenderedLambda()
                    }
                },
                modifier = heroMetadataModifier
            )

            // The time, top right, so a long evening on the couch has a clock without leaving.
            // Steps aside with the hero text when a trailer plays full screen.
            com.fetch.tv.ui.components.ClockText(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = FetchTheme.spacing.xl, end = FetchTheme.spacing.xxxl)
                    .graphicsLayer { alpha = trailerContentAlphaState.value }
            )

            val onContinueWatchingOptionsLambda = remember { { item: ContinueWatchingItem -> optionsItem.value = item } }
            // OK on a Continue Watching card does what the hero's Resume pill says, so it launches from there.
            val onContinueWatchingLaunch = remember(onContinueWatchingClick) {
                { item: ContinueWatchingItem ->
                    PlayLaunchOrigin.launchFromOffer()
                    onContinueWatchingClick(item)
                }
            }
            val onRowItemFocusedInternalLambda = remember(onRowItemFocusedPassedDown) {
                { rowKey: String, index: Int, isCw: Boolean ->
                    onRowItemFocusedPassedDown.value.invoke(rowKey, index, isCw)
                }
            }
            val stableTrailerContentAlphaLambda = remember { { trailerContentAlphaState.value } }
            val stableExpandedTrailerPreviewUrl = remember(heroTrailerUrlsState) { { heroTrailerUrlsState.value.first } }
            val stableExpandedTrailerPreviewAudioUrl = remember(heroTrailerUrlsState) { { heroTrailerUrlsState.value.second } }
            val stableEnrichedPreviews = remember(enrichedPreviews) { derivedStateOf { enrichedPreviews.value.asStable() } }
            val stableTrailerPreviewUrls = remember(trailerPreviewUrls) { trailerPreviewUrls.asStable() }
            val stableTrailerPreviewAudioUrls = remember(trailerPreviewAudioUrls) { trailerPreviewAudioUrls.asStable() }

            val stableOnRequestLazyCatalogLoad = remember(onRequestLazyCatalogLoad) {
                { catalogKey: String -> onRequestLazyCatalogLoad(catalogKey) }
            }
            val stableOnItemFocus = remember(onItemFocus) { { item: MetaPreview -> onItemFocus(item) } }
            val stableOnPreloadAdjacentItem = remember(onPreloadAdjacentItem) { { item: MetaPreview -> onPreloadAdjacentItem(item) } }

            // The hero's year, ratings and runtime, for the focused card to read out with its title:
            // the hero itself is display-only, so TalkBack would never reach them.
            val heroImdbRatingTemplate = androidx.compose.ui.res.stringResource(R.string.cd_hero_imdb_rating)
            val showHeroImdbRatings = uiState.homeImdbRatingsVisibility.showRatings
            val heroCardAccessibilityState = remember(
                activeCarouselItemState,
                resolvedHeroState,
                showHeroImdbRatings,
                heroImdbRatingTemplate
            ) {
                derivedStateOf {
                    val itemKey = activeCarouselItemState.value?.key
                    val hero = resolvedHeroState.value.second
                    if (itemKey == null || hero == null) {
                        null
                    } else {
                        heroAccessibilityDetails(hero, showHeroImdbRatings, heroImdbRatingTemplate)
                            ?.let { HeroCardAccessibility(itemKey = itemKey, details = it) }
                    }
                }
            }

            androidx.compose.runtime.CompositionLocalProvider(
                LocalHeroCardAccessibility provides heroCardAccessibilityState,
                LocalHomeContentFilterFocused provides contentFilterFocused
            ) {
            ModernHomeRowsList(
                carouselRows = carouselRows,
                verticalRowListState = verticalRowListState,
                focusController = focusController,
                rowListStates = stableRowListStates,
                loadMoreRequestedTotals = stableLoadMoreRequestedTotals,
                focusState = focusState,
                contentFocusRequester = contentFocusRequester,
                rowsViewportHeight = rowsViewportHeight,
                catalogBottomPadding = FetchTheme.spacing.none,
                trailerContentAlpha = stableTrailerContentAlphaLambda,
                verticalRowBringIntoViewSpec = verticalRowBringIntoViewSpec,
                onRowItemFocusedInternal = onRowItemFocusedInternalLambda,
                onNavigateToDetail = onNavigateToDetail,
                onNavigateToFolderDetail = onNavigateToFolderDetail,
                onSeeAllRow = onSeeAllRow,
                onYouTubeVideoClick = onYouTubeVideoClick,
                onLoadMoreCatalog = onLoadMoreCatalog,
                onContinueWatchingClick = onContinueWatchingLaunch,
                onContinueWatchingOptions = onContinueWatchingOptionsLambda,
                onRequestLazyCatalogLoad = stableOnRequestLazyCatalogLoad,
                onItemFocus = stableOnItemFocus,
                onPreloadAdjacentItem = stableOnPreloadAdjacentItem,
                isCatalogItemWatched = isCatalogItemWatched,
                onCatalogItemLongPress = onCatalogItemLongPress,
                onCatalogItemPlay = onCatalogItemPlay,
                enrichedPreviews = stableEnrichedPreviews,
                trailerPreviewUrls = stableTrailerPreviewUrls,
                trailerPreviewAudioUrls = stableTrailerPreviewAudioUrls,
                useLandscapePosters = useLandscapePosters,
                alwaysShowLandscapeClearlogo = alwaysShowLandscapeClearlogo,
                showLabels = uiState.posterLabelsEnabled,
                posterCardCornerRadius = posterCardCornerRadius,
                focusedPosterBackdropTrailerMuted = uiState.focusedPosterBackdropTrailerMuted,
                effectiveExpandEnabled = effectiveExpandEnabled,
                effectiveAutoplayEnabled = effectiveAutoplayEnabled,
                trailerPlaybackTarget = trailerPlaybackTarget,
                expandedTrailerPreviewUrl = stableExpandedTrailerPreviewUrl,
                expandedTrailerPreviewAudioUrl = stableExpandedTrailerPreviewAudioUrl,
                portraitCatalogCardWidth = portraitCatalogCardWidth,
                portraitCatalogCardHeight = portraitCatalogCardHeight,
                landscapeCatalogCardWidth = landscapeCatalogCardWidth,
                landscapeCatalogCardHeight = landscapeCatalogCardHeight,
                continueWatchingCardWidth = continueWatchingCardWidth,
                continueWatchingCardHeight = continueWatchingCardHeight,
                blurUnwatchedEpisodes = uiState.blurUnwatchedEpisodes,
                useEpisodeThumbnails = uiState.useEpisodeThumbnailsInCw,
                continueWatchingCardStyle = continueWatchingStyle,
                continueWatchingCornerRadius = cardCornerRadius(uiState.posterCardCornerRadiusDp),
                blockLeftOnFirstExpandedItem = blockLeftOnFirstExpandedItem,
                isVerticalRowsScrollingState = isVerticalRowsScrollingState,
                contentFilters = presentation.contentFilters,
                contentFilter = presentation.contentFilter,
                onContentFilterSelected = onContentFilterSelected,
                onContentFilterFocusChanged = { focused -> contentFilterFocused.value = focused },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .onFocusChanged { contentHasFocus.value = it.hasFocus }
                    // Any key stops the Spotlight's rotation until focus rests again (#813).
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) spotlightKeyNonce.intValue++
                        false
                    }
            )
            }
    }

    val selectedOptionsItem = optionsItem.value
    if (selectedOptionsItem != null) {
        ContinueWatchingOptionsDialog(
            item = selectedOptionsItem,
            onDismiss = { optionsItem.value = null },
            onRemove = {
                val targetIndex = if (uiState.continueWatchingItems.size <= 1) null
                else (focusController.lastFocusedContinueWatchingIndex.value).coerceAtMost(uiState.continueWatchingItems.size - 2).coerceAtLeast(0)
                focusController.requestRowFocus(if (targetIndex != null) "continue_watching" else null, targetIndex)
                onRemoveContinueWatching(
                    selectedOptionsItem.contentId(),
                    selectedOptionsItem.season(),
                    selectedOptionsItem.episode(),
                    selectedOptionsItem is ContinueWatchingItem.NextUp
                )
                optionsItem.value = null
            },
            onDetails = {
                DetailInitialSeasonState.set(selectedOptionsItem.detailSeason())
                onNavigateToDetail(selectedOptionsItem.contentId(), selectedOptionsItem.contentType(), "")
                optionsItem.value = null
            },
            onStartFromBeginning = {
                onContinueWatchingStartFromBeginning(selectedOptionsItem)
                optionsItem.value = null
            },
            showPlayManually = showContinueWatchingManualPlayOption,
            onPlayManually = {
                onContinueWatchingPlayManually(selectedOptionsItem)
                optionsItem.value = null
            },
            isQueued = selectedOptionsItem.queueItemOrNull()?.let { it.videoId in queuedVideoIds },
            onToggleQueue = {
                onToggleQueue(selectedOptionsItem)
                optionsItem.value = null
            }
        )
    }
}
@Composable
private fun ModernHeroSection(
    heroSceneState: () -> ModernHeroSceneState,
    isFullScreen: () -> Boolean,
    heroMediaWidthPx: Int,
    heroMediaHeightPx: Int,
    modifier: Modifier,
    onTrailerEnded: () -> Unit,
    onFirstFrameRendered: () -> Unit,
    onBackdropDisplayed: (String) -> Unit
) {
    val highlighterEnabled = LocalRecompositionHighlighterEnabled.current
    val bgColor = FetchTheme.colors.Background
    ModernHeroScene(
        state = heroSceneState,
        isFullScreen = isFullScreen,
        bgColor = bgColor,
        modifier = modifier.then(if (highlighterEnabled) Modifier.recompositionHighlighter() else Modifier),
        requestWidthPx = heroMediaWidthPx,
        requestHeightPx = heroMediaHeightPx,
        onTrailerEnded = onTrailerEnded,
        onFirstFrameRendered = onFirstFrameRendered,
        onBackdropDisplayed = onBackdropDisplayed,
    )
}

/**
 * Runs [content] in its own recompose scope. Snapshot state read inside it recomposes only this
 * block when it changes, instead of the composable that calls it. ModernHomeContent is large, and
 * several of its effects key on focus state that changes on every D-pad press.
 */
@Composable
private fun IsolatedReadScope(content: @Composable () -> Unit) {
    content()
}
