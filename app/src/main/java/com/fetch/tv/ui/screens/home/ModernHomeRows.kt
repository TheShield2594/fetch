@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

@file:OptIn(ExperimentalFoundationApi::class, kotlinx.coroutines.FlowPreview::class)

package com.fetch.tv.ui.screens.home

import com.fetch.tv.ui.components.focusRequesterFor
import com.fetch.tv.ui.theme.FetchMotion
import com.fetch.tv.ui.components.FetchCardDefaults
import com.fetch.tv.ui.theme.FetchTheme

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.constrain
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.tv.material3.Border
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.CachePolicy
import coil3.request.crossfade
import coil3.request.transformations
import com.fetch.tv.R
import com.fetch.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.ContinueWatchingCardStyle
import com.fetch.tv.domain.model.PLACEHOLDER_IMAGE_URL
import com.fetch.tv.domain.model.isPlaceholder
import com.fetch.tv.ui.components.ContinueWatchingCard
import com.fetch.tv.ui.components.continueWatchingArtworkWidth
import com.fetch.tv.ui.components.continueWatchingImageCacheKey
import com.fetch.tv.ui.components.continueWatchingImageModel
import com.fetch.tv.ui.components.continueWatchingShouldBlur
import com.fetch.tv.ui.components.continueWatchingUsesEpisodeThumbnails
import com.fetch.tv.ui.components.MonochromePosterPlaceholder
import com.fetch.tv.ui.components.TrailerPlayer
import com.fetch.tv.ui.components.WatchedMarker
import com.fetch.tv.ui.components.placeholderCardShimmer
import com.fetch.tv.ui.components.LightweightCard
import com.fetch.tv.ui.components.rememberPlaceholderShimmerOffsetState
import com.fetch.tv.LocalSidebarExpanded
import kotlin.math.abs
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import com.fetch.tv.ui.util.recompositionHighlighter
import com.fetch.tv.ui.util.StableMap
import com.fetch.tv.ui.util.StableRef
import com.fetch.tv.ui.util.asStable
import com.fetch.tv.ui.util.contentTextDirection
import com.fetch.tv.ui.util.rememberLongPressKeyTracker
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import com.fetch.tv.ui.screens.settings.MemoryBudget
import kotlinx.coroutines.flow.debounce
import com.fetch.tv.ui.util.isSelectKey

private const val MODERN_HORIZONTAL_FOCUS_DEBOUNCE_MS = 140L
private const val POSTER_PREFETCH_DISTANCE = 3
private const val LOW_RAM_POSTER_PREFETCH_DISTANCE = 1

/** Cards to warm past the right edge of the active row; one on low-RAM devices. */
private fun posterPrefetchDistance(): Int =
    if (MemoryBudget.isLowRamTier) LOW_RAM_POSTER_PREFETCH_DISTANCE else POSTER_PREFETCH_DISTANCE
private const val NESTED_PREFETCH_COUNT = 2
/** Inactive row titles, close to TextSecondary over the dark Home backdrop (#819). */
private const val INACTIVE_ROW_TITLE_ALPHA = 0.65f

internal val LocalVerticalRowsScrolling = compositionLocalOf<State<Boolean>> { mutableStateOf(false) }

/**
 * True while the user is actively "fast-scrolling" — i.e. holding DPAD_LEFT/RIGHT or
 * DPAD_UP/DOWN and the LazyColumn-level key handler has taken over to drag the list
 * programmatically instead of letting [androidx.compose.ui.focus.FocusManager.moveFocus]
 * pull focus card-by-card. Cards use this to suppress their focus chrome (border / glow /
 * GIF) during the drag; the chrome snaps back onto whichever card focus lands on when
 * the user releases the key. Defaults to `false`, so any card used outside a modern
 * home row keeps its normal focus visuals.
 */
internal val LocalFastScrollActive = compositionLocalOf<State<Boolean>> { mutableStateOf(false) }

/**
 * The hero's details for TalkBack while the hero shows the card with [itemKey], else null. Derived,
 * so a focus move recomposes only the cards gaining or losing them, not every card on screen.
 */
@Composable
private fun rememberHeroAccessibilityDetails(itemKey: String): String? {
    val heroAccessibility = LocalHeroCardAccessibility.current
    val details by remember(heroAccessibility, itemKey) {
        derivedStateOf { heroAccessibility.value?.takeIf { it.itemKey == itemKey }?.details }
    }
    return details
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ModernContinueWatchingRowItem(
    payload: ModernPayload.ContinueWatching,
    requester: FocusRequester,
    modifier: Modifier = Modifier,
    isTargetItem: Boolean = false,
    cardWidth: Dp,
    imageHeight: Dp,
    blurUnwatchedEpisodes: Boolean,
    useEpisodeThumbnails: Boolean,
    continueWatchingCardStyle: ContinueWatchingCardStyle,
    continueWatchingCornerRadius: Dp,
    onFocused: () -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onShowOptions: (ContinueWatchingItem) -> Unit,
) {
    val item = payload.item
    val onClick = remember(item) { { onContinueWatchingClick(item) } }
    val onLongPress = remember(item) { { onShowOptions(item) } }
    var focusEventId by remember { mutableIntStateOf(0) }
    var isCardFocused by remember { mutableStateOf(false) }
    val latestOnFocused by rememberUpdatedState(onFocused)

    LaunchedEffect(focusEventId, isCardFocused) {
        if (focusEventId == 0 || !isCardFocused) return@LaunchedEffect
        // Update global focus state immediately so "self-claiming" logic in other items
        // knows this item is now the one in charge.
        latestOnFocused()
    }

    val contentFilterFocused = LocalHomeContentFilterFocused.current
    LaunchedEffect(isTargetItem) {
        if (isTargetItem && !isCardFocused && !contentFilterFocused.value) {
            runCatching { requester.requestFocus() }
        }
    }

    val readyKeys = LocalReadyBestMatchKeys.current
    val isReady = remember(item, readyKeys) { item.bestMatchKey()?.let { it in readyKeys } == true }
    val heroAccessibilityDetails = rememberHeroAccessibilityDetails(continueWatchingItemKey(item))

    ContinueWatchingCard(
        item = item,
        onClick = onClick,
        onLongPress = onLongPress,
        cardWidth = cardWidth,
        imageHeight = imageHeight,
        blurUnwatchedEpisodes = blurUnwatchedEpisodes,
        useEpisodeThumbnails = useEpisodeThumbnails,
        cardStyle = continueWatchingCardStyle,
        cornerRadius = continueWatchingCornerRadius,
        isFocused = isCardFocused,
        isReady = isReady,
        accessibilityDetails = heroAccessibilityDetails,
        modifier = modifier
            .focusRequester(requester)
            .onFocusChanged {
                isCardFocused = it.isFocused
                if (it.isFocused) {
                    focusEventId += 1
                }
            }
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ModernCatalogRowItem(
    item: ModernCarouselItem,
    payload: ModernPayload,
    requester: FocusRequester,
    modifier: Modifier = Modifier,
    isTargetItem: Boolean = false,
    useLandscapePosters: Boolean,
    alwaysShowLandscapeClearlogo: Boolean = false,
    placeholderShimmerOffsetState: State<Float>?,
    posterCardCornerRadius: Dp,
    portraitCatalogCardWidth: Dp,
    portraitCatalogCardHeight: Dp,
    landscapeCatalogCardWidth: Dp,
    landscapeCatalogCardHeight: Dp,
    focusedPosterBackdropTrailerMuted: Boolean,
    effectiveExpandEnabled: Boolean,
    effectiveAutoplayEnabled: Boolean,
    trailerPlaybackTarget: FocusedPosterTrailerPlaybackTarget,
    isBackdropExpanded: () -> Boolean,
    expandedTrailerPreviewUrl: () -> String?,
    expandedTrailerPreviewAudioUrl: () -> String?,
    isCatalogItemWatched: (MetaPreview) -> Boolean,
    onFocused: () -> Unit,
    onItemFocus: (MetaPreview) -> Unit,
    onPreloadAdjacentItem: () -> Unit,
    onCatalogSelectionFocused: (FocusedCatalogSelection) -> Unit,
    onNavigateToDetail: (String, String, String) -> Unit,
    onNavigateToFolderDetail: (String, String) -> Unit,
    onYouTubeVideoClick: (com.fetch.tv.data.youtube.YouTubeVideo) -> Unit,
    onLongPress: (() -> Unit)?,
    onBackdropInteraction: () -> Unit,
    onExpandedCatalogFocusKeyChange: (String?) -> Unit,
    enrichedPreviews: State<StableMap<String, MetaPreview>>,
) {
    val focusKey = when (payload) {
        is ModernPayload.Catalog -> payload.focusKey
        is ModernPayload.CollectionFolder -> payload.focusKey
        is ModernPayload.YouTubeVideo -> payload.focusKey
        is ModernPayload.ContinueWatching -> error("Unsupported payload for ModernCatalogRowItem")
    }
    // A YouTube card always draws its title over the thumbnail, as Home's cards carry no labels.
    val useLandscapeOverlayTreatment = useLandscapePosters || payload is ModernPayload.YouTubeVideo

    val metaPreview = item.metaPreview
    val isWatched = metaPreview?.let { isCatalogItemWatched(it) }
        ?: (payload as? ModernPayload.YouTubeVideo)?.video?.watchedPercent
            ?.let { it >= com.fetch.tv.data.youtube.YouTubeFeedFilter.WATCHED_PERCENT }
        ?: false
    // Only a movie's card names one video, so only movies can be ready.
    val readyKeys = LocalReadyBestMatchKeys.current
    val isReady = remember(payload, readyKeys) {
        (payload as? ModernPayload.Catalog)
            ?.takeIf { it.itemType.equals("movie", ignoreCase = true) }
            ?.let { com.fetch.tv.data.local.BestMatchCacheDataStore.contentKey(it.itemType, it.itemId) in readyKeys } == true
    }
    val enrichedMeta by remember {
        derivedStateOf { (payload as? ModernPayload.Catalog)?.itemId?.let { enrichedPreviews.value.map[it] } }
    }
    val enrichedLogoUrl = enrichedMeta?.logo
    val enrichedBackdropUrl = enrichedMeta?.backdropUrl

    var focusEventId by remember { mutableIntStateOf(0) }
    var isCardFocused by remember { mutableStateOf(false) }
    val latestOnFocused by rememberUpdatedState(onFocused)
    val latestOnItemFocus by rememberUpdatedState(onItemFocus)
    val latestOnPreloadAdjacentItem by rememberUpdatedState(onPreloadAdjacentItem)
    val latestOnCatalogSelectionFocused by rememberUpdatedState(onCatalogSelectionFocused)

    LaunchedEffect(focusEventId, isCardFocused, focusKey) {
        if (focusEventId == 0 || !isCardFocused) {
            return@LaunchedEffect
        }

        // Update global focus state immediately so "self-claiming" logic in other items
        // knows this item is now the one in charge.
        latestOnFocused()

        val targetEventId = focusEventId
        delay(MODERN_HORIZONTAL_FOCUS_DEBOUNCE_MS)
        if (!isCardFocused || focusEventId != targetEventId) {
            return@LaunchedEffect
        }

        // Heavy "settled" work (trailers, enrichment) remains debounced.
        item.metaPreview?.let { latestOnItemFocus(it) }
        latestOnPreloadAdjacentItem()
        when (payload) {
            is ModernPayload.Catalog -> {
                if (!payload.itemId.startsWith("__placeholder_")) {
                    latestOnCatalogSelectionFocused(
                        FocusedCatalogSelection(
                            focusKey = focusKey,
                            payload = payload
                        )
                    )
                }
            }
            is ModernPayload.CollectionFolder,
            // Taking the selection stops the last catalog card's trailer; a video plays none.
            is ModernPayload.YouTubeVideo -> {
                latestOnCatalogSelectionFocused(
                    FocusedCatalogSelection(
                        focusKey = focusKey,
                        payload = payload
                    )
                )
            }
            is ModernPayload.ContinueWatching -> Unit
        }
    }

    val contentFilterFocused = LocalHomeContentFilterFocused.current
    LaunchedEffect(isTargetItem) {
        if (isTargetItem && !isCardFocused && !contentFilterFocused.value) {
            runCatching { requester.requestFocus() }
        }
    }

    // Expansion is armed from a parent-level focusKey timer that can outlive real
    // card focus (e.g. user moves left into the sidebar). Never show the expanded
    // backdrop on a card that is not actually focused (#2815). A trailer playing in the hero
    // does not stop the card expanding: the two settings are independent.
    val effectiveBackdropExpanded by remember(isBackdropExpanded) {
        derivedStateOf {
            isCardFocused && isBackdropExpanded()
        }
    }

    val isSidebarExpanded = LocalSidebarExpanded.current
    val playTrailerInExpandedCard =
        effectiveAutoplayEnabled &&
            !isSidebarExpanded &&
            isCardFocused &&
            trailerPlaybackTarget == FocusedPosterTrailerPlaybackTarget.EXPANDED_CARD &&
            effectiveBackdropExpanded
    val trailerUrl = expandedTrailerPreviewUrl()
    val trailerPreviewUrl = if (playTrailerInExpandedCard) trailerUrl else null
    val trailerPreviewAudioUrl = if (playTrailerInExpandedCard) {
        expandedTrailerPreviewAudioUrl()
    } else {
        null
    }
    val cardMetrics = remember(
        item,
        useLandscapePosters,
        portraitCatalogCardWidth,
        portraitCatalogCardHeight,
        landscapeCatalogCardWidth,
        landscapeCatalogCardHeight
    ) {
        item.catalogCardMetrics(
            useLandscapePosters = useLandscapePosters,
            portraitCardWidth = portraitCatalogCardWidth,
            portraitCardHeight = portraitCatalogCardHeight,
            landscapeCardWidth = landscapeCatalogCardWidth,
            landscapeCardHeight = landscapeCatalogCardHeight
        )
    }

    ModernCarouselCard(
        item = item,
        useLandscapeOverlayTreatment = useLandscapeOverlayTreatment,
        alwaysShowLandscapeClearlogo = alwaysShowLandscapeClearlogo,
        placeholderShimmerOffsetState = placeholderShimmerOffsetState,
        cardCornerRadius = posterCardCornerRadius,
        cardWidth = cardMetrics.width,
        cardHeight = cardMetrics.height,
        modifier = modifier,
        focusedPosterBackdropExpandEnabled = effectiveExpandEnabled,
        isBackdropExpanded = effectiveBackdropExpanded,
        playTrailerInExpandedCard = playTrailerInExpandedCard,
        focusedPosterBackdropTrailerMuted = focusedPosterBackdropTrailerMuted,
        trailerPreviewUrl = trailerPreviewUrl,
        trailerPreviewAudioUrl = trailerPreviewAudioUrl,
        isWatched = isWatched,
        isReady = isReady,
        enrichedLogoUrl = enrichedLogoUrl,
        enrichedBackdropUrl = enrichedBackdropUrl,
        focusRequester = requester,
        onFocused = {
            focusEventId += 1
        },
        onFocusStateChanged = { focused ->
            isCardFocused = focused
        },
        onClick = {
            latestOnFocused()
            item.metaPreview?.let { latestOnItemFocus(it) }
            when (payload) {
                is ModernPayload.Catalog -> {
                    if (!payload.itemId.startsWith("__placeholder_")) {
                        onNavigateToDetail(
                            payload.itemId,
                            payload.itemType,
                            payload.addonBaseUrl
                        )
                    }
                }
                is ModernPayload.CollectionFolder -> {
                    onNavigateToFolderDetail(
                        payload.collectionId,
                        payload.folderId
                    )
                }
                is ModernPayload.YouTubeVideo -> onYouTubeVideoClick(payload.video)
                is ModernPayload.ContinueWatching -> Unit
            }
        },
        onLongPress = onLongPress,
        onBackdropInteraction = onBackdropInteraction,
        onTrailerEnded = { onExpandedCatalogFocusKeyChange(null) }
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ModernRowSection(
    row: HomeRowModel,
    isActiveRow: () -> Boolean,
    rowFocusRequester: FocusRequester,
    isVerticalRowsScrollingState: State<Boolean>,
    rowTitleBottom: Dp,
    defaultBringIntoViewSpec: BringIntoViewSpec,
    focusStateCatalogRowScrollIndex: Int,
    focusStateCatalogRowScrollAnchor: String?,
    focusedItemByRow: StableRef<MutableMap<String, Int>>,
    rowListStates: StableRef<MutableMap<String, LazyListState>>,
    loadMoreRequestedTotals: StableRef<MutableMap<String, Int>>,
    pendingRowFocusKey: State<String?>,
    pendingRowFocusIndex: State<Int?>,
    pendingRowFocusNonce: State<Int>,
    onPendingRowFocusCleared: () -> Unit,
    onRowItemFocused: (String, Int, Boolean) -> Unit,
    useLandscapePosters: Boolean,
    alwaysShowLandscapeClearlogo: Boolean = false,
    posterCardCornerRadius: Dp,
    focusedPosterBackdropTrailerMuted: Boolean,
    effectiveExpandEnabled: Boolean,
    effectiveAutoplayEnabled: Boolean,
    trailerPlaybackTarget: FocusedPosterTrailerPlaybackTarget,
    expandedCatalogFocusKey: State<String?>,
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
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingOptions: (ContinueWatchingItem) -> Unit,
    isCatalogItemWatched: (MetaPreview) -> Boolean,
    onCatalogItemLongPress: (MetaPreview, String, String?) -> Unit,
    onCatalogItemPlay: ((MetaPreview, String) -> Unit)? = null,
    onItemFocus: (MetaPreview) -> Unit,
    onPreloadAdjacentItem: (MetaPreview) -> Unit,
    enrichedPreviews: State<StableMap<String, MetaPreview>> = androidx.compose.runtime.mutableStateOf(StableMap()),
    onCatalogSelectionFocused: (FocusedCatalogSelection) -> Unit,
    onNavigateToDetail: (String, String, String) -> Unit,
    onNavigateToFolderDetail: (String, String) -> Unit,
    onSeeAllRow: (HomeRowModel) -> Unit = {},
    onYouTubeVideoClick: (com.fetch.tv.data.youtube.YouTubeVideo) -> Unit = {},
    onLoadMoreCatalog: (String, String, String) -> Unit,
    onBackdropInteraction: () -> Unit,
    onExpandedCatalogFocusKeyChange: (String?) -> Unit,
    sharedPlaceholderShimmerOffsetState: State<Float>?,
    itemFocusRequesters: StableRef<MutableMap<Int, FocusRequester>>
) {
    // Unwrap StableRef wrappers
    @Suppress("NAME_SHADOWING") val focusedItemByRow = focusedItemByRow.value
    @Suppress("NAME_SHADOWING") val rowListStates = rowListStates.value
    @Suppress("NAME_SHADOWING") val loadMoreRequestedTotals = loadMoreRequestedTotals.value
    @Suppress("NAME_SHADOWING") val itemFocusRequesters = itemFocusRequesters.value
    val rowKey = row.key
    // The Spotlight row is always wide cards, whatever the poster setting (#813).
    @Suppress("NAME_SHADOWING") val useLandscapePosters = useLandscapePosters || rowKey == MODERN_SPOTLIGHT_ROW_KEY

    // Per-row derived state: only invalidates when THIS row's focused index
    // changes, not when any other row's index changes in the shared map.
    val rowFocusedIndex = remember(rowKey) {
        derivedStateOf { focusedItemByRow[rowKey] ?: 0 }
    }

    // Item keys carry item identity, which the placeholder the ring sits on loses when real data
    // arrives: its key changes and Compose tears the focused node down. Lend that one card a
    // positional key for as long as the ring can be on it, so the node is reused instead.
    //
    // Armed from the item id, not a loading flag: a lazily loaded catalog composes its row
    // before loading starts and would never see the flag go up.
    val firstCardKey = remember(rowKey) { "${rowKey}__first" }
    val firstIsPlaceholder = (row.items.list.firstOrNull()?.payload as? ModernPayload.Catalog)
        ?.itemId?.startsWith("__placeholder_") == true
    // Resolved here and stored after the composition (FirstCardPin.next has the rules), so the
    // item keys below use it in this pass without a second one.
    val firstCardPinHold = remember(rowKey) { CompositionHold(FirstCardPin(pinned = firstIsPlaceholder)) }
    val firstCardPin = firstCardPinHold.value.next(
        firstIsPlaceholder = firstIsPlaceholder,
        firstItemKey = row.items.list.firstOrNull()?.key,
        isActiveRow = isActiveRow
    )
    SideEffect { firstCardPinHold.value = firstCardPin }
    val pinFirstCard = ResolvedValue(firstCardPin.pinned)
    val pinnedItemKey = ResolvedValue(firstCardPin.itemKey)
    Column {
        val titleMediumStyle = MaterialTheme.typography.titleMedium
        val rowTitleStyle = remember(titleMediumStyle) {
            titleMediumStyle.copy(fontWeight = FontWeight.SemiBold)
        }
        val rowTitle = row.title
        val textColor = FetchTheme.colors.TextPrimary
        val screenHorizontal = FetchTheme.spacing.screen.horizontal
        // The focused row's title stays at full strength and the others dim, so the active row reads
        // at a glance (#819). isActiveRow is read in the layer, so a focus move redraws the title
        // without recomposing the row.
        val textModifier = remember(rowTitleBottom, screenHorizontal, isActiveRow) {
            Modifier
                .padding(start = screenHorizontal, end = screenHorizontal, bottom = rowTitleBottom)
                .graphicsLayer { alpha = if (isActiveRow()) 1f else INACTIVE_ROW_TITLE_ALPHA }
        }
        // One line, because the hero backdrop height reserves a single title line for each row.
        val rowSubtitle = row.subtitle
        if (rowSubtitle.isNullOrBlank()) {
            Text(
                text = rowTitle,
                style = rowTitleStyle.copy(textDirection = rowTitle.contentTextDirection()),
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = textModifier
            )
        } else {
            androidx.compose.foundation.layout.Row(
                modifier = textModifier,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)
            ) {
                Text(
                    text = rowTitle,
                    style = rowTitleStyle.copy(textDirection = rowTitle.contentTextDirection()),
                    color = textColor,
                    maxLines = 1
                )
                Text(
                    text = rowSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = FetchTheme.colors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        val rowListState = rowListStates.getOrPut(row.key) {
            // Resolved when the row is built, so a refresh that already moved the card is seen.
            val restoredIndex = focusStateCatalogRowScrollAnchor
                ?.let { anchor -> row.items.list.indexOfFirst { it.key == anchor } }
                ?.takeIf { it >= 0 }
                ?: focusStateCatalogRowScrollIndex
            LazyListState(
                firstVisibleItemIndex = restoredIndex,
                prefetchStrategy = LazyListPrefetchStrategy(nestedPrefetchItemCount = NESTED_PREFETCH_COUNT)
            )
        }

        val isRowScrollingState = remember(rowListState) {
            derivedStateOf { rowListState.isScrollInProgress }
        }
        val currentRowState = rememberUpdatedState(row)
        val loadMoreCatalogId = row.catalogId
        val loadMoreAddonId = row.addonId
        val loadMoreApiType = row.apiType
        val canObserveLoadMore = row.supportsSkip &&
            row.hasMore &&
            !loadMoreCatalogId.isNullOrBlank() &&
            !loadMoreAddonId.isNullOrBlank() &&
            !loadMoreApiType.isNullOrBlank()

        LaunchedEffect(row.key, pendingRowFocusNonce.value) {
            if (pendingRowFocusKey.value != row.key) return@LaunchedEffect
            val targetIndex = (pendingRowFocusIndex.value ?: 0)
                .coerceIn(0, row.maxFocusIndex())
            if (!rowListState.isScrollInProgress) {
                runCatching { rowListState.scrollToItem(targetIndex) }
            }
        }

        if (canObserveLoadMore) {
            LaunchedEffect(
                row.key,
                rowListState,
                canObserveLoadMore
            ) {
                snapshotFlow {
                    val layoutInfo = rowListState.layoutInfo
                    val total = layoutInfo.totalItemsCount
                    val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    lastVisible to total
                }
                    .distinctUntilChanged()
                    .collect { (lastVisible, total) ->
                        if (total <= 0) return@collect
                        val rowState = currentRowState.value
                        val isNearEnd = lastVisible >= total - 4
                        if (!isNearEnd) {
                            loadMoreRequestedTotals.remove(rowState.key)
                            return@collect
                        }
                        val lastRequestedTotal = loadMoreRequestedTotals[rowState.key]
                        if (rowState.hasMore &&
                            !rowState.isLoading &&
                            lastRequestedTotal != total
                        ) {
                            loadMoreRequestedTotals[rowState.key] = total
                            onLoadMoreCatalog(
                                loadMoreCatalogId,
                                loadMoreAddonId,
                                loadMoreApiType
                            )
                        }
                    }
            }
        }

        val density = LocalDensity.current
        val rowStartPadding = FetchTheme.spacing.screen.horizontal
        val context = LocalContext.current
        val imageLoader = context.imageLoader

        val rowItemCount = row.items.list.size
        LaunchedEffect(
            row.key,
            rowItemCount,
            portraitCatalogCardWidth,
            portraitCatalogCardHeight,
            landscapeCatalogCardWidth,
            landscapeCatalogCardHeight,
            continueWatchingCardWidth,
            continueWatchingCardHeight,
            useEpisodeThumbnails,
            blurUnwatchedEpisodes
        ) {
            val cwWidthPx = with(density) {
                continueWatchingArtworkWidth(
                    continueWatchingCardStyle, continueWatchingCardWidth, continueWatchingCardHeight
                ).roundToPx()
            }
            val cwHeightPx = with(density) { continueWatchingCardHeight.roundToPx() }
            fun cardSizePx(item: ModernCarouselItem): Pair<Int, Int> = when (item.payload) {
                is ModernPayload.ContinueWatching -> cwWidthPx to cwHeightPx
                is ModernPayload.Catalog,
                is ModernPayload.YouTubeVideo,
                is ModernPayload.CollectionFolder -> {
                    val metrics = item.catalogCardMetrics(
                        useLandscapePosters = useLandscapePosters,
                        portraitCardWidth = portraitCatalogCardWidth,
                        portraitCardHeight = portraitCatalogCardHeight,
                        landscapeCardWidth = landscapeCatalogCardWidth,
                        landscapeCardHeight = landscapeCatalogCardHeight
                    )
                    with(density) { metrics.width.roundToPx() } to with(density) { metrics.height.roundToPx() }
                }
            }
            fun imageUrlAndKey(item: ModernCarouselItem): Pair<String, String>? {
                return when (val payload = item.payload) {
                    is ModernPayload.Catalog,
                    is ModernPayload.YouTubeVideo,
                    is ModernPayload.CollectionFolder -> {
                        val url = item.imageUrl ?: return null
                        val (widthPx, heightPx) = cardSizePx(item)
                        url to catalogCardImageCacheKey(url, widthPx, heightPx)
                    }
                    is ModernPayload.ContinueWatching -> {
                        // Use the same model and cache key the card computes so the prefetch warms the entry the card actually reads.
                        val model = continueWatchingImageModel(
                            payload.item, continueWatchingUsesEpisodeThumbnails(continueWatchingCardStyle, useEpisodeThumbnails),
                            continueWatchingCardStyle != ContinueWatchingCardStyle.CARD
                        ) ?: return null
                        val blur = continueWatchingShouldBlur(
                            payload.item, blurUnwatchedEpisodes, continueWatchingUsesEpisodeThumbnails(continueWatchingCardStyle, useEpisodeThumbnails),
                            continueWatchingCardStyle != ContinueWatchingCardStyle.CARD
                        )
                        model to continueWatchingImageCacheKey(model, cwWidthPx, cwHeightPx, blur)
                    }
                }
            }
            fun enqueueIfNeeded(item: ModernCarouselItem, widthPx: Int, heightPx: Int) {
                if (widthPx <= 0 || heightPx <= 0) return
                val (url, cacheKey) = imageUrlAndKey(item) ?: return
                val payload = item.payload
                val extras = if (payload is ModernPayload.ContinueWatching) {
                    emptyMap()
                } else {
                    item.catalogCardImageCacheExtras(imageUrl = url, isLandscapeCustomPoster = false)
                }
                if (imageLoader.memoryCache?.get(MemoryCache.Key(cacheKey, extras)) != null) return
                val blur = payload is ModernPayload.ContinueWatching &&
                    continueWatchingShouldBlur(
                        payload.item, blurUnwatchedEpisodes, continueWatchingUsesEpisodeThumbnails(continueWatchingCardStyle, useEpisodeThumbnails),
                            continueWatchingCardStyle != ContinueWatchingCardStyle.CARD
                    )
                imageLoader.enqueue(
                    ImageRequest.Builder(context)
                        .data(url)
                        .memoryCacheKey(cacheKey)
                        .memoryCacheKeyExtras(extras)
                        .size(width = widthPx, height = heightPx)
                        .apply {
                            if (blur) transformations(com.fetch.tv.ui.util.BlurTransformation())
                        }
                        .build()
                )
            }
            // isActiveRow and isVerticalRowsScrollingState keep the same identity for the row's
            // lifetime, so keying the effect on them ran it once at composition. Observe their
            // values instead, so the prefetch starts whenever this row becomes the active one.
            snapshotFlow { isActiveRow() && !isVerticalRowsScrollingState.value }
                .distinctUntilChanged()
                .collectLatest { ready ->
                    if (!ready) return@collectLatest
                    delay(150) // Wait before spamming image requests to survive rapid vertical D-pad scrolls!
                    snapshotFlow {
                        rowListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    }
                        .distinctUntilChanged()
                        .collect { lastVisibleIndex ->
                            val currentItems = currentRowState.value.items.list
                            // enqueue is already asynchronous; sizing a few cards is cheap, so
                            // this stays on Main rather than hopping to IO and back.
                            for (i in (lastVisibleIndex + 1)..(lastVisibleIndex + posterPrefetchDistance())) {
                                val item = currentItems.getOrNull(i) ?: continue
                                val (wPx, hPx) = cardSizePx(item)
                                enqueueIfNeeded(item, wPx, hPx)
                            }
                        }
                }
        }

        val layoutDirection = LocalLayoutDirection.current
        val isRtl = layoutDirection == LayoutDirection.Rtl
        val horizontalBringIntoViewSpec = remember(density, defaultBringIntoViewSpec, rowStartPadding, isRtl) {
            val parentStartOffsetPx = with(density) { rowStartPadding.roundToPx() }
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            object : BringIntoViewSpec {
                override val scrollAnimationSpec: AnimationSpec<Float> =
                    defaultBringIntoViewSpec.scrollAnimationSpec

                override fun calculateScrollDistance(
                    offset: Float,
                    size: Float,
                    containerSize: Float
                ): Float {
                    val childSize = abs(size)
                    if (isRtl) {
                        val childSmallerThanParent = childSize <= containerSize
                        val initialTarget = containerSize - parentStartOffsetPx.toFloat()
                        val targetForTrailingEdge =
                            if (childSmallerThanParent && initialTarget < childSize) {
                                childSize
                            } else {
                                initialTarget
                            }
                        return (offset + size) - targetForTrailingEdge
                    } else {
                        val childSmallerThanParent = childSize <= containerSize
                        val initialTarget = parentStartOffsetPx.toFloat()
                        val spaceAvailable = containerSize - initialTarget

                        val targetForLeadingEdge =
                            if (childSmallerThanParent && spaceAvailable < childSize) {
                                containerSize - childSize
                            } else {
                                initialTarget
                            }

                        return offset - targetForLeadingEdge
                    }
                }
            }
        }

        // When a poster in this row expands, ensure it scrolls fully into view.
        var isExpansionScrollActive by remember { mutableStateOf(false) }
        val expandedCardWidthPx = with(density) {
            if (useLandscapePosters) {
                landscapeCatalogCardWidth.roundToPx()
            } else {
                (portraitCatalogCardHeight * (16f / 9f)).roundToPx()
            }
        }
        LaunchedEffect(row.key, effectiveExpandEnabled, rowItemCount) {
            if (!effectiveExpandEnabled) return@LaunchedEffect
            snapshotFlow { expandedCatalogFocusKey.value }
                .collect { expandedKey ->
                    if (expandedKey == null) return@collect
                    // Find the index of the expanded item in this row
                    val expandedIndex = row.items.list.indexOfFirst { item ->
                        when (val p = item.payload) {
                            is ModernPayload.Catalog -> p.focusKey == expandedKey
                            is ModernPayload.CollectionFolder -> p.focusKey == expandedKey
                            else -> false
                        }
                    }
                    if (expandedIndex < 0) return@collect
                    // Small delay so the item is still in visible layout info
                    delay(50)
                    // Calculate overshoot using the known final expanded width rather than
                    // the mid-animation layout size which underestimates the trailing edge.
                    val layoutInfo = rowListState.layoutInfo
                    val itemInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == expandedIndex }
                        ?: return@collect
                    val viewportEnd = layoutInfo.viewportEndOffset
                    val itemEndExpanded = itemInfo.offset + expandedCardWidthPx
                    if (itemEndExpanded > viewportEnd) {
                        // Scroll just enough to reveal the trailing edge plus a small margin.
                        // Flag prevents isBackdropExpandedLambda from collapsing during this scroll.
                        val overshoot = itemEndExpanded - viewportEnd + with(density) { 15.dp.roundToPx() }
                        isExpansionScrollActive = true
                        rowListState.animateScrollBy(overshoot.toFloat())
                        isExpansionScrollActive = false
                    }
                }
        }

        CompositionLocalProvider(LocalBringIntoViewSpec provides horizontalBringIntoViewSpec) {
            val usesPlaceholderShimmer = row.showsPlaceholderShimmer()
            val placeholderShimmerOffsetState = if (usesPlaceholderShimmer) {
                sharedPlaceholderShimmerOffsetState
            } else {
                null
            }

            LazyRow(
                state = rowListState,
                modifier = Modifier
                    .recompositionHighlighter()
                    .focusRequester(rowFocusRequester)
                    .focusRestorer {
                        val savedIdx = rowFocusedIndex.value
                        itemFocusRequesters[savedIdx]
                            ?: itemFocusRequesters[0]
                            ?: FocusRequester.Default
                    }
                    .focusGroup(),
                contentPadding = PaddingValues(horizontal = rowStartPadding),
                horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)
            ) {
                itemsIndexed(
                    items = row.items.list,
                    key = { index, item ->
                        val pinned = pinnedItemKey.value
                        when {
                            !pinFirstCard.value -> item.key
                            pinned != null -> if (item.key == pinned) firstCardKey else item.key
                            index == 0 -> firstCardKey
                            else -> item.key
                        }
                    },
                    contentType = { _, item -> modernCarouselItemContentType(item) }
                ) { index, item ->
                    val requester = itemFocusRequesters.focusRequesterFor(index)
                    val isContinueWatchingRow = isContinueWatchingStyleRow(row.key)
                    val onFocused = remember(row.key, index, isContinueWatchingRow) {
                        {
                            onRowItemFocused(row.key, index, isContinueWatchingRow)
                            if (pendingRowFocusKey.value == row.key && (pendingRowFocusIndex.value ?: 0) == index) {
                                onPendingRowFocusCleared()
                            }
                        }
                    }

                    // Use derivedStateOf so only the ONE item that becomes/loses
                    // target status recomposes — not all items in all visible rows.
                    val isTargetItem by remember(row.key, index) {
                        derivedStateOf {
                            val isPending = pendingRowFocusKey.value == row.key &&
                                (pendingRowFocusIndex.value ?: 0) == index
                            val isCurrent = isActiveRow() &&
                                rowFocusedIndex.value == index
                            isPending || isCurrent
                        }
                    }

                    when (val payload = item.payload) {
                        is ModernPayload.ContinueWatching -> {
                            ModernContinueWatchingRowItem(
                                payload = payload,
                                requester = requester,
                                isTargetItem = isTargetItem,
                                cardWidth = continueWatchingCardWidth,
                                imageHeight = continueWatchingCardHeight,
                                blurUnwatchedEpisodes = blurUnwatchedEpisodes,
                                useEpisodeThumbnails = useEpisodeThumbnails,
                                continueWatchingCardStyle = continueWatchingCardStyle,
                                continueWatchingCornerRadius = continueWatchingCornerRadius,
                                onFocused = onFocused,
                                onContinueWatchingClick = onContinueWatchingClick,
                                onShowOptions = onContinueWatchingOptions
                            )
                        }

                        is ModernPayload.Catalog,
                        is ModernPayload.CollectionFolder,
                        is ModernPayload.YouTubeVideo -> {
                            val nextCatalogItem = row.items.list.getOrNull(index + 1)?.metaPreview
                            val prevCatalogItem = row.items.list.getOrNull(index - 1)?.metaPreview
                            val metaPreview = item.metaPreview
                            val isPlaceholder = payload is ModernPayload.Catalog &&
                                payload.itemId.startsWith("__placeholder_")
                            // Null for a card with no options menu, so TalkBack offers no "Options" that does nothing.
                            val onLongPress: (() -> Unit)? = when {
                                payload is ModernPayload.Catalog && metaPreview != null -> remember(metaPreview, payload.addonBaseUrl, row.key) {
                                    {
                                        onCatalogItemLongPress(metaPreview, payload.addonBaseUrl, row.orderKey())
                                        Unit
                                    }
                                }
                                else -> null
                            }
                            val expandedFocusKey = when (payload) {
                                is ModernPayload.Catalog -> payload.focusKey
                                is ModernPayload.CollectionFolder -> payload.focusKey
                                is ModernPayload.YouTubeVideo -> payload.focusKey
                            }
                            val isBackdropExpandedLambda = remember(
                                effectiveExpandEnabled,
                                isRowScrollingState,
                                expandedCatalogFocusKey,
                                expandedFocusKey
                            ) {
                                {
                                    effectiveExpandEnabled &&
                                        (!isRowScrollingState.value || isExpansionScrollActive) &&
                                        expandedCatalogFocusKey.value == expandedFocusKey
                                }
                            }
                            val placeholderFocusBlock = isPlaceholder && index > 0
                            // Play/Pause on a catalog card starts its Best match without the detail
                            // page (#812): the film, or a series' next episode.
                            val playTarget = metaPreview?.takeIf { payload is ModernPayload.Catalog && !isPlaceholder }
                            val playModifier = if (onCatalogItemPlay != null && playTarget != null) {
                                val addonBaseUrl = (payload as ModernPayload.Catalog).addonBaseUrl
                                Modifier.onPreviewKeyEvent { event ->
                                    if (!event.key.isPlayKey()) return@onPreviewKeyEvent false
                                    // Once per press: a held key must not start it again and again.
                                    if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                                        onCatalogItemPlay(playTarget, addonBaseUrl)
                                    }
                                    true
                                }
                            } else Modifier
                            Box(modifier = playModifier.then(if (placeholderFocusBlock) {
                                Modifier.focusProperties { canFocus = false }
                            } else Modifier)) {
                            ModernCatalogRowItem(
                                item = item,
                                payload = payload,
                                requester = requester,
                                isTargetItem = isTargetItem,
                                useLandscapePosters = useLandscapePosters,
                                alwaysShowLandscapeClearlogo = alwaysShowLandscapeClearlogo,
                                placeholderShimmerOffsetState = placeholderShimmerOffsetState,
                                posterCardCornerRadius = posterCardCornerRadius,
                                focusedPosterBackdropTrailerMuted = focusedPosterBackdropTrailerMuted,
                                effectiveExpandEnabled = effectiveExpandEnabled,
                                effectiveAutoplayEnabled = effectiveAutoplayEnabled,
                                trailerPlaybackTarget = trailerPlaybackTarget,
                                isBackdropExpanded = isBackdropExpandedLambda,
                                expandedTrailerPreviewUrl = expandedTrailerPreviewUrl,
                                expandedTrailerPreviewAudioUrl = expandedTrailerPreviewAudioUrl,
                                portraitCatalogCardWidth = portraitCatalogCardWidth,
                                portraitCatalogCardHeight = portraitCatalogCardHeight,
                                landscapeCatalogCardWidth = landscapeCatalogCardWidth,
                                landscapeCatalogCardHeight = landscapeCatalogCardHeight,
                                isCatalogItemWatched = isCatalogItemWatched,
                                onFocused = onFocused,
                                onItemFocus = onItemFocus,
                                onPreloadAdjacentItem = remember(nextCatalogItem, prevCatalogItem, onPreloadAdjacentItem) {
                                     {
                                         nextCatalogItem?.let(onPreloadAdjacentItem)
                                         prevCatalogItem?.let(onPreloadAdjacentItem)
                                     }
                                },
                                onCatalogSelectionFocused = onCatalogSelectionFocused,
                                onNavigateToDetail = onNavigateToDetail,
                                onNavigateToFolderDetail = onNavigateToFolderDetail,
                                onYouTubeVideoClick = onYouTubeVideoClick,
                                onLongPress = onLongPress,
                                onBackdropInteraction = onBackdropInteraction,
                                onExpandedCatalogFocusKeyChange = onExpandedCatalogFocusKeyChange,
                                enrichedPreviews = enrichedPreviews
                            )
                            } // Box
                        }
                    }
                }
                if (row.showsSeeAllCard()) {
                    item(key = "${row.key}__see_all", contentType = "modern_see_all_card") {
                        // See all sits after the last item, so it reports focus as index items.size and
                        // registers its requester there: the hero and the row's focusRestorer follow it.
                        val seeAllIndex = row.items.list.size
                        val requester = itemFocusRequesters.focusRequesterFor(seeAllIndex)
                        val isContinueWatchingRow = isContinueWatchingStyleRow(row.key)
                        val onFocused = remember(row.key, seeAllIndex, isContinueWatchingRow) {
                            {
                                onExpandedCatalogFocusKeyChange(null)
                                onRowItemFocused(row.key, seeAllIndex, isContinueWatchingRow)
                                if (pendingRowFocusKey.value == row.key && (pendingRowFocusIndex.value ?: 0) == seeAllIndex) {
                                    onPendingRowFocusCleared()
                                }
                            }
                        }
                        // A pending focus request for See all (restored after returning to Home) is
                        // honoured here the way catalog cards honour theirs via isTargetItem.
                        val isPendingTarget by remember(row.key, seeAllIndex) {
                            derivedStateOf {
                                pendingRowFocusKey.value == row.key &&
                                    (pendingRowFocusIndex.value ?: 0) == seeAllIndex
                            }
                        }
                        LaunchedEffect(isPendingTarget) {
                            if (isPendingTarget) runCatching { requester.requestFocus() }
                        }
                        ModernSeeAllCard(
                            width = if (useLandscapePosters) landscapeCatalogCardWidth else portraitCatalogCardWidth,
                            height = if (useLandscapePosters) landscapeCatalogCardHeight else portraitCatalogCardHeight,
                            cornerRadius = posterCardCornerRadius,
                            onClick = { onSeeAllRow(row) },
                            expandEnabled = effectiveExpandEnabled,
                            modifier = Modifier
                                .focusRequester(requester)
                                .onFocusChanged { if (it.isFocused) onFocused() }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Catalog rows end in a See all card that opens the full grid. Rows with no grid behind them
 * (Continue Watching, Ready tonight, Because you watched, collections) don't get one.
 */
internal fun HomeRowModel.showsSeeAllCard(): Boolean =
    !isLoading &&
        items.list.isNotEmpty() &&
        addonId != MODERN_READY_TONIGHT_ROW_KEY &&
        !isContinueWatchingStyleRow(key) &&
        orderKey() != null

/** Saved focus key for the See all card, which has no item key of its own. */
internal const val SEE_ALL_FOCUS_KEY = "__see_all__"

/** The key saved for the focused index: the item's key, [SEE_ALL_FOCUS_KEY] for See all, or "". */
internal fun HomeRowModel.savedFocusKeyAt(index: Int): String =
    items.list.getOrNull(index)?.key
        ?: if (index == items.list.size && showsSeeAllCard()) SEE_ALL_FOCUS_KEY else ""

/**
 * The index a saved focus key restores to: the item with that key, or See all when it was saved
 * there and the row still shows one. Null when the key matches nothing.
 */
internal fun HomeRowModel.focusIndexForSavedKey(key: String): Int? {
    if (key.isBlank()) return null
    if (key == SEE_ALL_FOCUS_KEY) return if (showsSeeAllCard()) items.list.size else null
    return items.list.indexOfFirst { it.key == key }.takeIf { it >= 0 }
}

/** Highest focusable index in the row: the See all card, at items.size, when the row has one. */
internal fun HomeRowModel.maxFocusIndex(): Int =
    if (showsSeeAllCard()) items.list.size else (items.list.size - 1).coerceAtLeast(0)


@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ModernCarouselCard(
    item: ModernCarouselItem,
    useLandscapeOverlayTreatment: Boolean,
    modifier: Modifier = Modifier,
    alwaysShowLandscapeClearlogo: Boolean = false,
    placeholderShimmerOffsetState: State<Float>? = null,
    cardCornerRadius: Dp,
    cardWidth: Dp,
    cardHeight: Dp,
    focusedPosterBackdropExpandEnabled: Boolean,
    isBackdropExpanded: Boolean,
    playTrailerInExpandedCard: Boolean,
    focusedPosterBackdropTrailerMuted: Boolean,
    trailerPreviewUrl: String?,
    trailerPreviewAudioUrl: String?,
    isWatched: Boolean,
    isReady: Boolean = false,
    enrichedLogoUrl: String? = null,
    enrichedBackdropUrl: String? = null,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onFocusStateChanged: (Boolean) -> Unit = {},
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
    onBackdropInteraction: () -> Unit,
    onTrailerEnded: () -> Unit,
) {
    val cardShape = remember(cardCornerRadius) { RoundedCornerShape(cardCornerRadius) }
    val context = LocalContext.current
    val density = LocalDensity.current
    val heroAccessibilityDetails = rememberHeroAccessibilityDetails(item.key)
    val expandedCardWidth = if (useLandscapeOverlayTreatment) {
        cardWidth
    } else {
        cardHeight * (16f / 9f)
    }
    val targetCardWidth = if (focusedPosterBackdropExpandEnabled && isBackdropExpanded) {
        expandedCardWidth
    } else {
        cardWidth
    }
    val animatedCardWidthState = if (focusedPosterBackdropExpandEnabled) {
        // The motion tokens' focus spring, and a snap under reduced motion (#819).
        animateDpAsState(
            targetValue = targetCardWidth,
            animationSpec = if (FetchTheme.fullMotion) FetchMotion.focusSpring() else snap(),
            label = "modernCardWidth"
        )
    } else {
        rememberUpdatedState(cardWidth)
    }
    // Freeze the logo URL for row cards - enrichment updates must not cause flickering.
    // The first non-blank value wins and is never replaced.
    // Primary source of truth is the data-layer frozen value (survives navigation);
    // the remember-state acts as a secondary guard within the same composition.
    // Resolved here (resolveCardLogoUrl has the rules) and stored after the composition.
    val frozenLogoHold = remember(item.key) {
        CompositionHold(initialCardLogoUrl(item.heroPreview.frozenLogoUrl, item.heroPreview.logo))
    }
    val frozenLogoUrl = resolveCardLogoUrl(
        held = frozenLogoHold.value,
        logo = item.heroPreview.logo,
        enrichedLogoUrl = enrichedLogoUrl,
        useLandscapeOverlayTreatment = useLandscapeOverlayTreatment
    )
    val effectiveLogoUrl = frozenLogoUrl?.takeIf { !it.isPlaceholder() }
    // Freeze the backdrop URL for landscape cards - prevents image reload when enrichment updates backdrop.
    val frozenBackdropHold = remember(item.key) {
        CompositionHold(initialCardBackdropUrl(item.heroPreview.frozenBackdropUrl, item.heroPreview.backdrop))
    }
    val frozenBackdropUrl = resolveCardBackdropUrl(
        held = frozenBackdropHold.value,
        backdrop = item.heroPreview.backdrop,
        enrichedBackdropUrl = enrichedBackdropUrl,
        useLandscapeOverlayTreatment = useLandscapeOverlayTreatment
    )
    SideEffect {
        frozenLogoHold.value = frozenLogoUrl
        frozenBackdropHold.value = frozenBackdropUrl
    }
    val effectiveBackdropUrl = frozenBackdropUrl?.takeIf { !it.isPlaceholder() }
    var isFocused by remember { mutableStateOf(false) }
    val payload = item.payload as? ModernPayload.CollectionFolder
    val isCollectionFolder = item.payload is ModernPayload.CollectionFolder
    val effectiveIgnoreLandscapePoster = alwaysShowLandscapeClearlogo
    val useLandscapeAsExpanded = focusedPosterBackdropExpandEnabled && isBackdropExpanded &&
        !alwaysShowLandscapeClearlogo &&
        !item.metaPreview?.landscapePoster.isNullOrBlank()
    val baseImageUrl = if (focusedPosterBackdropExpandEnabled && isBackdropExpanded) {
        if (useLandscapeOverlayTreatment) {
            if (effectiveIgnoreLandscapePoster) {
                effectiveBackdropUrl ?: item.heroPreview.backdrop ?: item.imageUrl ?: item.heroPreview.poster
            } else {
                item.metaPreview?.landscapePoster ?: effectiveBackdropUrl ?: item.heroPreview.backdrop ?: item.imageUrl ?: item.heroPreview.poster
            }
        } else if (useLandscapeAsExpanded) {
            item.metaPreview?.landscapePoster
        } else {
            item.heroPreview.backdrop ?: item.imageUrl ?: item.heroPreview.poster
        }
    } else if (useLandscapeOverlayTreatment && !isCollectionFolder) {
        if (effectiveIgnoreLandscapePoster) {
            effectiveBackdropUrl ?: item.heroPreview.poster
        } else {
            item.metaPreview?.landscapePoster ?: effectiveBackdropUrl ?: item.heroPreview.poster
        }
    } else if (isCollectionFolder && !payload?.coverEmoji.isNullOrBlank()) {
        // Emoji cover folders: never fall back to backdrop for the card poster
        item.imageUrl
    } else {
        item.imageUrl ?: item.heroPreview.poster ?: item.heroPreview.backdrop
    }
    val imageUrl = baseImageUrl
    // GIF overlay: shown on top of the base image only when focused and loaded
    val focusGifUrl = when {
        !FetchTheme.fullMotion -> null
        payload == null -> null
        !payload.focusGifEnabled -> null
        isFocused -> payload.focusGifUrl
        else -> null
    }
    val imageContentScale = when (item.payload) {
        is ModernPayload.CollectionFolder -> ContentScale.FillBounds
        else -> ContentScale.Crop
    }
    // Decode at the target width, not the animated one, so the request isn't recreated per frame.
    // A collapsed poster decodes at its own width; only the expanded card needs the wide image.
    val maxRequestCardWidth = if (focusedPosterBackdropExpandEnabled) {
        maxOf(cardWidth, expandedCardWidth)
    } else {
        cardWidth
    }
    val requestWidthPx = remember(targetCardWidth, density) {
        with(density) { targetCardWidth.roundToPx() }.coerceAtLeast(1)
    }
    val requestHeightPx = remember(cardHeight, density) {
        with(density) { cardHeight.roundToPx() }.coerceAtLeast(1)
    }

    val revalidationKey = com.fetch.tv.core.image.rememberImageRevalidationKey(imageUrl)
    var customPosterLoadFailed by remember(imageUrl) { mutableStateOf(false) }
    // Any failure of the card's artwork, so a poster without it still says what it is.
    var cardImageLoadFailed by remember(imageUrl) { mutableStateOf(false) }
    val imageModel = remember(context, imageUrl, requestWidthPx, requestHeightPx, revalidationKey) {
        imageUrl?.let {
            val cacheExtras = item.catalogCardImageCacheExtras(
                imageUrl = it,
                isLandscapeCustomPoster = useLandscapeOverlayTreatment && !effectiveIgnoreLandscapePoster &&
                    !item.metaPreview?.landscapePoster.isNullOrBlank()
            )
            val builder = ImageRequest.Builder(context)
                .data(it)
                .crossfade(true)
                .memoryCacheKey(catalogCardImageCacheKey(it, requestWidthPx, requestHeightPx, revalidationKey))
                .memoryCacheKeyExtras(cacheExtras)
                .size(width = requestWidthPx, height = requestHeightPx)
            if (revalidationKey > 0) {
                builder.placeholderMemoryCacheKey(
                    MemoryCache.Key(catalogCardImageCacheKey(it, requestWidthPx, requestHeightPx, revalidationKey - 1), cacheExtras)
                )
            }
            builder.build()
        }
    }
    val logoHeight = cardHeight * 0.34f
    val logoHeightPx = remember(logoHeight, density) {
        with(density) { logoHeight.roundToPx() }.coerceAtLeast(1)
    }
    val maxLogoWidthPx = remember(maxRequestCardWidth, density) {
        with(density) { (maxRequestCardWidth * 0.62f).roundToPx() }.coerceAtLeast(1)
    }

    val logoModel = remember(context, effectiveLogoUrl, maxLogoWidthPx, logoHeightPx) {
        effectiveLogoUrl?.let {
            ImageRequest.Builder(context)
                .data(it)
                .crossfade(true)
                .memoryCacheKey("${it}_${maxLogoWidthPx}x${logoHeightPx}")
                .size(width = maxLogoWidthPx, height = logoHeightPx)
                .build()
        }
    }
    var landscapeLogoLoadFailed by remember(effectiveLogoUrl) { mutableStateOf(false) }
    val shouldPlayTrailerInCard = playTrailerInExpandedCard && !trailerPreviewUrl.isNullOrBlank()
    var trailerFirstFrameRendered by remember(trailerPreviewUrl) { mutableStateOf(false) }

    // Use the image model directly — Coil's memory cache handles repeated
    // requests efficiently without needing scroll-aware request swapping.
    val hasImage = !imageUrl.isNullOrBlank()
    val hasLandscapeLogo =
        (useLandscapeOverlayTreatment || isBackdropExpanded) &&
            !isCollectionFolder &&
            !effectiveLogoUrl.isNullOrBlank() &&
            !landscapeLogoLoadFailed &&
            !(useLandscapeAsExpanded && !trailerFirstFrameRendered) &&
            (effectiveIgnoreLandscapePoster || isBackdropExpanded || item.metaPreview?.landscapePoster.isNullOrBlank() || customPosterLoadFailed)
    // Title text drawn in place of a missing or failed logo; it needs the same bottom scrim.
    // A portrait poster whose artwork is missing or failed gets it too: Home's cards carry no
    // label under them, so it was a blank tile until focused (#819).
    val portraitArtworkMissing =
        !useLandscapeOverlayTreatment &&
            !isBackdropExpanded &&
            !isCollectionFolder &&
            !item.imageUrl.isPlaceholder() &&
            (!hasImage || cardImageLoadFailed)
    val showsFallbackTitle = item.title.isNotBlank() && (portraitArtworkMissing || (
        !hasLandscapeLogo &&
            (useLandscapeOverlayTreatment || isBackdropExpanded) &&
            !isCollectionFolder &&
            !(useLandscapeAsExpanded && !trailerFirstFrameRendered) &&
            (effectiveIgnoreLandscapePoster || item.metaPreview?.landscapePoster.isNullOrBlank() || customPosterLoadFailed)
        ))
    var longPressTriggered by remember { mutableStateOf(false) }
    val longPressKeyTracker = rememberLongPressKeyTracker()
    val backgroundCardColor = FetchTheme.colors.BackgroundCard
    val focusRingBorder = FetchTheme.focusRing.border()
    val titleMedium = MaterialTheme.typography.titleMedium
    val backgroundPainter = remember(backgroundCardColor) { ColorPainter(backgroundCardColor) }
    val focusedBorder = remember(cardShape, focusRingBorder) {
        Border(
            border = focusRingBorder,
            shape = cardShape
        )
    }
    // While the user is dragging the list via held DPAD (see LazyColumn-level fast
    // scroll takeover in ModernHomeContent), hide focus chrome entirely — the list is
    // sliding like a touch swipe and showing a border / glow jittering across every
    // card the drag passes over would break that illusion. The chrome reappears the
    // moment the user releases the key, when requestFocus lands focus on whichever
    // card is visible at the leading edge.
    val isFastScrollingState = LocalFastScrollActive.current
    val isFastScrolling = isFastScrollingState.value
    val restingBorder = remember(cardShape) { FetchCardDefaults.restingBorder(cardShape) }
    // The focused card keeps the resting edge so it matches its neighbours mid-drag.
    val effectiveFocusedBorder = if (isFastScrolling) restingBorder else focusedBorder
    val titleStyle = remember(titleMedium) {
        titleMedium.copy(fontWeight = FontWeight.Medium)
    }

    Column(
        modifier = modifier
            .animatedWidth(animatedCardWidthState)
            .recompositionHighlighter(),
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)
    ) {
        LightweightCard(
            onClick = {
                if (longPressTriggered) {
                    longPressTriggered = false
                } else {
                    onClick()
                }
            },
            onLongPress = onLongPress,
            modifier = Modifier
                .fillMaxWidth()
                .height(cardHeight)
                .focusRequester(focusRequester)
                .onFocusChanged {
                    isFocused = it.isFocused
                    onFocusStateChanged(it.isFocused)
                    if (it.isFocused) {
                        onFocused()
                    }
                }
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.action == AndroidKeyEvent.ACTION_DOWN) {
                        if (focusedPosterBackdropExpandEnabled && shouldResetBackdropTimer(event.key)) {
                            onBackdropInteraction()
                        }
                        if (native.keyCode == AndroidKeyEvent.KEYCODE_MENU) {
                            longPressTriggered = true
                            onLongPress?.invoke()
                            return@onPreviewKeyEvent true
                        }
                    }
                    if (longPressKeyTracker.handle(native, ::isSelectKey) {
                            longPressTriggered = true
                            onLongPress?.invoke()
                        }
                    ) {
                        if (native.action == AndroidKeyEvent.ACTION_UP) {
                            longPressTriggered = false
                        }
                        return@onPreviewKeyEvent true
                    }
                    if (native.action == AndroidKeyEvent.ACTION_UP &&
                        longPressTriggered &&
                        (isSelectKey(native.keyCode) || native.keyCode == AndroidKeyEvent.KEYCODE_MENU)
                    ) {
                        longPressTriggered = false
                        return@onPreviewKeyEvent true
                    }
                    false
                },
            shape = cardShape,
            containerColor = backgroundCardColor,
            focusedBorder = effectiveFocusedBorder,
            restingBorder = restingBorder,
            // A card that widens into its backdrop on focus doesn't also grow; nor does one the
            // held D-pad is only sliding past.
            focusedScale = if (focusedPosterBackdropExpandEnabled || isFastScrolling) 1f else FetchTheme.focus.cardScale,
            focusedShadow = FetchTheme.focus.cardShadow,
            // Name the card itself: the artwork can be missing, and the title sits outside it.
            // The label replaces the logo and fallback title inside, so the title is read once.
            // The hero's year, ratings and runtime follow while the hero shows this card.
            label = if (heroAccessibilityDetails != null) {
                listOf(item.title, heroAccessibilityDetails).filter { it.isNotBlank() }.joinToString(", ")
            } else {
                item.title
            },
            stateDescription = if (isWatched) stringResource(R.string.episodes_cd_watched) else null
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(cardShape)
            ) {
                val drawsBottomScrim = hasLandscapeLogo || showsFallbackTitle
                val mediaLayerModifier = remember(drawsBottomScrim) {
                    if (drawsBottomScrim) {
                        Modifier
                            .fillMaxSize()
                            .drawWithCache {
                                onDrawWithContent {
                                    drawContent()
                                    drawRect(brush = MODERN_LANDSCAPE_LOGO_GRADIENT, size = size)
                                }
                            }
                    } else {
                        Modifier.fillMaxSize()
                    }
                }

                Box(modifier = mediaLayerModifier) {
                    val isPlaceholderItem = item.imageUrl.isPlaceholder()
                    if (isPlaceholderItem) {
                        // Horizontal sweeping shimmer for placeholder cards
                        val effectivePlaceholderShimmerOffsetState =
                            placeholderShimmerOffsetState ?: rememberPlaceholderShimmerOffsetState(
                                label = "placeholderShimmer"
                            )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .placeholderCardShimmer(effectivePlaceholderShimmerOffsetState)
                        )
                    } else if (hasImage) {
                        AsyncImage(
                            model = imageModel,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            placeholder = backgroundPainter,
                            error = backgroundPainter,
                            fallback = backgroundPainter,
                            contentScale = imageContentScale,
                            onSuccess = { cardImageLoadFailed = false },
                            onError = {
                                cardImageLoadFailed = true
                                if (!item.metaPreview?.landscapePoster.isNullOrBlank() || !item.metaPreview?.rawPosterUrl.isNullOrBlank()) {
                                    customPosterLoadFailed = true
                                }
                            }
                        )
                    } else if (isCollectionFolder && !payload?.coverEmoji.isNullOrBlank()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = payload!!.coverEmoji!!,
                                fontSize = 48.sp
                            )
                        }
                    } else {
                        MonochromePosterPlaceholder()
                    }

                    // GIF overlay: renders on top of image or emoji, visible only once loaded
                    if (!focusGifUrl.isNullOrBlank()) {
                        val gifModel = remember(context, focusGifUrl, requestWidthPx, requestHeightPx) {
                            ImageRequest.Builder(context)
                                .data(focusGifUrl)
                                .memoryCacheKey("${focusGifUrl}_${requestWidthPx}x${requestHeightPx}")
                                .size(width = requestWidthPx, height = requestHeightPx)
                                .build()
                        }
                        var gifLoaded by remember(focusGifUrl) { mutableStateOf(false) }
                        val gifAlpha by animateFloatAsState(
                            targetValue = if (gifLoaded) 1f else 0f,
                            animationSpec = tween(durationMillis = FetchMotion.tokens.durations.fast),
                            label = "gifFadeIn"
                        )
                        AsyncImage(
                            model = gifModel,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = gifAlpha },
                            contentScale = imageContentScale,
                            onSuccess = { gifLoaded = true }
                        )
                    }

                    if (shouldPlayTrailerInCard) {
                        key(trailerPreviewUrl) {
                            TrailerPlayer(
                                trailerUrl = trailerPreviewUrl,
                                trailerAudioUrl = trailerPreviewAudioUrl,
                                isPlaying = true,
                                onEnded = onTrailerEnded,
                                onFirstFrameRendered = { trailerFirstFrameRendered = true },
                                muted = focusedPosterBackdropTrailerMuted,
                                cropToFill = com.fetch.tv.ui.components.LocalTrailerCropToFill.current,
                                overscanZoom = MODERN_TRAILER_OVERSCAN_ZOOM,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }

                if (hasLandscapeLogo) {
                    AsyncImage(
                        model = logoModel,
                        contentDescription = null,
                        onError = { landscapeLogoLoadFailed = true },
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth(0.62f)
                            .height(cardHeight * 0.34f)
                            .padding(start = FetchTheme.spacing.sm, end = FetchTheme.spacing.sm, bottom = FetchTheme.spacing.sm),
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart
                    )
                } else if (showsFallbackTitle) {
                    Text(
                        text = item.title,
                        style = titleStyle.copy(
                            textDirection = item.title.contentTextDirection()
                        ),
                        color = Color.White,
                        maxLines = if (portraitArtworkMissing) 3 else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth(if (portraitArtworkMissing) 1f else 0.62f)
                            .padding(start = FetchTheme.spacing.sm, end = FetchTheme.spacing.sm, bottom = FetchTheme.spacing.md)
                    )
                }

                if (isWatched) {
                    WatchedMarker(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = FetchTheme.spacing.sm, top = FetchTheme.spacing.sm)
                            .zIndex(2f)
                    )
                }
            }
        }

        // Home's cards carry no labels, but a recommendation's reason is the point of its card.
        val hasRecommendationReason = !item.metaPreview?.recommendationReason.isNullOrBlank()
        if (hasRecommendationReason && !isBackdropExpanded && item.title.isNotBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FetchTheme.spacing.xs)
            ) {
                Text(
                    text = item.title,
                    style = titleStyle.copy(
                        textDirection = item.title.contentTextDirection()
                    ),
                    color = FetchTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                item.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                    Spacer(modifier = Modifier.height(FetchTheme.spacing.xxs))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelMedium.copy(
                            textDirection = subtitle.contentTextDirection()
                        ),
                        color = FetchTheme.colors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}


/**
 * Keys that should collapse an expanded poster so navigation can re-arm expand.
 *
 * Select keys (Center / Enter) are intentionally excluded: holding them opens the
 * action menu / long-press options. Resetting the expand timer on those keys made
 * the Expanded Card collapse and re-expand (and restart trailers) while the menu
 * was opening (#2574).
 */
private fun shouldResetBackdropTimer(key: Key): Boolean {
    return when (key) {
        Key.DirectionUp,
        Key.DirectionDown,
        Key.DirectionLeft,
        Key.DirectionRight -> true
        else -> false
    }
}

/**
 * Modifier.width(width.value) that reads the width while measuring. The expand-on-focus width
 * animation then re-lays out the card on each frame instead of recomposing all of it.
 */
private fun Modifier.animatedWidth(width: State<Dp>): Modifier = this then AnimatedWidthElement(width)

private data class AnimatedWidthElement(val width: State<Dp>) : ModifierNodeElement<AnimatedWidthNode>() {
    override fun create() = AnimatedWidthNode(width)

    override fun update(node: AnimatedWidthNode) {
        node.width = width
    }
}

// Measures exactly like the node behind Modifier.width: a fixed width, coerced into the incoming
// constraints, with the incoming height constraints passed through.
private class AnimatedWidthNode(var width: State<Dp>) : LayoutModifierNode, Modifier.Node() {
    private fun Density.widthPx() = width.value.roundToPx().coerceAtLeast(0)

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints.constrain(Constraints.fixedWidth(widthPx())))
        return layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurable: IntrinsicMeasurable, height: Int) = widthPx()

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurable: IntrinsicMeasurable, height: Int) = widthPx()

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int) =
        measurable.minIntrinsicHeight(width)

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int) =
        measurable.maxIntrinsicHeight(width)
}

/** The remote's Play and Play/Pause keys, which start a catalog card's title (#812). */
internal fun Key.isPlayKey(): Boolean =
    this == Key.MediaPlayPause || this == Key.MediaPlay
