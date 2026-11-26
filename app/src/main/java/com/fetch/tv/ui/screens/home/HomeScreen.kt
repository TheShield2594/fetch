package com.fetch.tv.ui.screens.home

import com.fetch.tv.ui.components.FetchButton
import com.fetch.tv.ui.theme.FetchMotion
import com.fetch.tv.ui.theme.FetchTheme

import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import android.util.Log
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.ui.components.ErrorState
import com.fetch.tv.ui.components.EmptyScreenState
import com.fetch.tv.ui.components.StateAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.ui.unit.Dp
import com.fetch.tv.ui.components.LocalStartupLoadingState
import com.fetch.tv.ui.components.LocalStartupSplashEnabled
import com.fetch.tv.ui.components.shouldShowHomeStartupLoader
import androidx.compose.ui.res.stringResource
import com.fetch.tv.R
import com.fetch.tv.data.local.StartupAuthNotice
import kotlinx.coroutines.delay
import com.fetch.tv.ui.theme.FetchStatusTones

private const val HOME_STABLE_GATE_TIMEOUT_MS = 5_000L

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    posterOptionsViewModel: com.fetch.tv.ui.components.posteroptions.PosterOptionsViewModel = hiltViewModel(),
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit = { item ->
        onNavigateToDetail(
            when (item) {
                is ContinueWatchingItem.InProgress -> item.progress.contentId
                is ContinueWatchingItem.NextUp -> item.info.contentId
            },
            when (item) {
                is ContinueWatchingItem.InProgress -> item.progress.contentType
                is ContinueWatchingItem.NextUp -> item.info.contentType
            },
            ""
        )
    },
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit = onContinueWatchingClick,
    onContinueWatchingPlayManually: (ContinueWatchingItem) -> Unit = onContinueWatchingClick,
    onNavigateToCatalogSeeAll: (String, String, String) -> Unit = { _, _, _ -> },
    onNavigateToFolderDetail: (String, String) -> Unit = { _, _ -> },
    onNavigateToAddons: () -> Unit = {},
    /** Opens Customise Home, on the given row (its order key) when there is one. */
    onCustomiseHome: (String?) -> Unit = {},
    /** Opens the YouTube screen, which plays the video a YouTube card asked for. */
    onOpenYouTube: () -> Unit = {},
    /** Play/Pause on a catalog card: starts the title (id, type, addon) without its detail page (#812). */
    onPlayCatalogTitle: (String, String, String) -> Unit = { _, _, _ -> }
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Home was the only major screen without a lifecycle observer, so nothing ever told it to
    // look at its catalogs again.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.beginShuffleHomeVisit()
                viewModel.refreshHomeCatalogsIfStale()
            }
            // Leaving Home, or the app, carries out a removal still in its undo window (#816).
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.onEvent(HomeEvent.OnCommitPendingRemoval)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val modernPresentation by viewModel.modernHomePresentation.collectAsStateWithLifecycle()
    val initialCwResolved by viewModel.initialCwResolved.collectAsStateWithLifecycle()
    val scrollToTopTrigger by viewModel.scrollToTopTrigger.collectAsStateWithLifecycle()
    val effectiveAutoplayEnabled by viewModel.effectiveAutoplayEnabled.collectAsStateWithLifecycle(
        initialValue = false
    )
    val hasCatalogContent = uiState.catalogRows.any { it.items.isNotEmpty() }
    val hasCollectionContent = uiState.homeRows.any { it is HomeRow.CollectionRow }
    val modernPresentationReady = modernPresentation.rows.list.isNotEmpty()
    var showHomeContentWithAnimation by rememberSaveable { mutableStateOf(false) }
    var hasShownInitialHomeContent by rememberSaveable { mutableStateOf(false) }
    // Once we've shown stable home content, never go back to loading gate.
    var homeStableGateReleased by rememberSaveable { mutableStateOf(false) }
    // Track that catalog loading has started at least once (isLoading went true→false).
    var catalogLoadingStarted by rememberSaveable { mutableStateOf(false) }
    // The long-press menu is the shared one (Search, Library, Discover, See-all, Cast), so Home
    // gets the same TMDB→IMDb canonicalisation and failure reporting.
    val posterOptionsController = posterOptionsViewModel.controller
    val posterOptionsState by posterOptionsController.state.collectAsStateWithLifecycle()
    /** Order key of the row the open menu's poster was long-pressed in, for pinning that row. */
    var posterOptionsRowKey by remember { mutableStateOf<String?>(null) }

    // Notify ViewModel of locale changes after activity recreation
    LaunchedEffect(Unit) {
        viewModel.notifyLocaleChanged()
    }

    val movieWatchedStatus = uiState.movieWatchedStatus
    // Use a stable lambda whose identity NEVER changes. The lambda captures
    // movieWatchedStatus via rememberUpdatedState so it always reads the latest
    // value without forcing downstream recomposition from lambda identity change.
    val latestMovieWatchedStatus = androidx.compose.runtime.rememberUpdatedState(movieWatchedStatus)
    val isCatalogItemWatched: (MetaPreview) -> Boolean = remember {
        { item: MetaPreview -> latestMovieWatchedStatus.value[homeItemStatusKey(item.id, item.apiType)] == true }
    }
    val onCatalogItemLongPress: (MetaPreview, String, String?) -> Unit = remember {
        { item, addonBaseUrl, rowKey ->
            posterOptionsRowKey = rowKey
            posterOptionsController.show(item, addonBaseUrl)
        }
    }

    val onNavigateToDetailStable = remember(onNavigateToDetail) { onNavigateToDetail }
    val onContinueWatchingClickStable = remember(onContinueWatchingClick) { onContinueWatchingClick }
    val onContinueWatchingStartFromBeginningStable = remember(onContinueWatchingStartFromBeginning) { onContinueWatchingStartFromBeginning }
    val onContinueWatchingPlayManuallyStable = remember(onContinueWatchingPlayManually) { onContinueWatchingPlayManually }
    val onNavigateToFolderDetailStable = remember(onNavigateToFolderDetail) { onNavigateToFolderDetail }
    val onSeeAllRow = remember(onNavigateToCatalogSeeAll) {
        { row: HeroCarouselRow ->
            val catalogId = row.catalogId
            val addonId = row.addonId
            val apiType = row.apiType
            if (catalogId != null && addonId != null && apiType != null) {
                onNavigateToCatalogSeeAll(catalogId, addonId, apiType)
            }
        }
    }
    // Home says "Hold OK for options" in the hero for a few sessions instead of a one-off toast (#812).
    val onCardFocused = remember(viewModel) { { viewModel.onHomeCardFocused() } }

    LaunchedEffect(
        uiState.isLoading,
        hasCatalogContent,
        hasCollectionContent,
        initialCwResolved,
        modernPresentationReady
    ) {
        // Track that addons are known (even if isLoading flipped too fast to catch).
        if (uiState.installedAddonsCount > 0) {
            catalogLoadingStarted = true
        }
        // Wait until catalog loading has completed with content AND the CW
        // pipeline has completed its first emission.
        if (!homeStableGateReleased &&
            catalogLoadingStarted &&
            !uiState.isLoading &&
            initialCwResolved &&
            modernPresentationReady &&
            // When addons are installed, require at least one catalog row.
            (hasCatalogContent || uiState.installedAddonsCount == 0)
        ) {
            Log.d("HomeGate", "RELEASE: catalogs=$hasCatalogContent cwResolved=$initialCwResolved cwItems=${uiState.continueWatchingItems.size} addons=${uiState.installedAddonsCount}")
            homeStableGateReleased = true
        }
    }

    LaunchedEffect(Unit) {
        // Safety timeout — if catalogs and CW haven't loaded within this
        // window, show whatever is available.  Covers edge cases like
        // clean cache (addons loading from remote sync) and users with
        // no addons at all.
        delay(HOME_STABLE_GATE_TIMEOUT_MS)
        if (!homeStableGateReleased) {
            Log.d("HomeGate", "RELEASE timeout: isLoading=${uiState.isLoading} cwResolved=$initialCwResolved catalogs=$hasCatalogContent cwItems=${uiState.continueWatchingItems.size}")
            homeStableGateReleased = true
        }
    }

    // A card held for undo still counts, so hiding the last one doesn't swap Home for the empty
    // state and take the Undo banner with it (#816).
    val hasAnyContent = uiState.catalogRows.isNotEmpty() ||
        (uiState.continueWatchingEnabled && uiState.continueWatchingItems.isNotEmpty()) ||
        hasCollectionContent ||
        viewModel.pendingContinueWatchingRemoval.collectAsStateWithLifecycle().value != null
    val showOffline = uiState.layoutPreferencesReady && shouldShowHomeOffline(
        reach = uiState.networkReach,
        hasAnyContent = hasAnyContent,
        isLoading = uiState.isLoading,
        errorKind = uiState.errorKind
    )
    val showStartupLoader = when {
        !uiState.layoutPreferencesReady -> true
        showOffline -> false
        uiState.isLoading && !hasAnyContent -> true
        uiState.errorKind == HomeErrorKind.NO_ADDONS && uiState.catalogRows.isEmpty() -> !homeStableGateReleased
        uiState.errorKind == HomeErrorKind.NO_CATALOG_ADDONS && uiState.catalogRows.isEmpty() && !hasCollectionContent -> !homeStableGateReleased
        uiState.errorKind != null && uiState.catalogRows.isEmpty() -> false
        !uiState.isLoading && !hasAnyContent -> !homeStableGateReleased
        else -> !homeStableGateReleased || !modernPresentationReady || !showHomeContentWithAnimation
    }

    // Reports the home screen as fully drawn once it leaves the loading state so startup timing is measurable and post-launch work can be deferred.
    ReportDrawnWhen { !showStartupLoader }

    val startupLoadingState = LocalStartupLoadingState.current
    val showHomeLoader = shouldShowHomeStartupLoader(
        loading = showStartupLoader,
        sharedSplashEnabled = LocalStartupSplashEnabled.current,
        startupComplete = startupLoadingState?.complete != false
    )
    LaunchedEffect(showStartupLoader, startupLoadingState) {
        if (!showStartupLoader) {
            startupLoadingState?.complete = true
        }
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        when {
            !uiState.layoutPreferencesReady -> {
                Unit
            }

            // No network: say so, offer Retry and a media server's library, and come back on
            // its own when the network does (#447).
            showOffline -> {
                HomeOfflineState(
                    reach = uiState.networkReach,
                    serverLibrary = uiState.offlineServerLibrary,
                    onRetry = { viewModel.onEvent(HomeEvent.OnRetry) },
                    onOpenServerLibrary = { library ->
                        onNavigateToCatalogSeeAll(library.catalogId, library.addonId, library.type)
                    }
                )
            }

            uiState.isLoading && !hasAnyContent -> {
                Unit
            }

            uiState.errorKind == HomeErrorKind.NO_ADDONS && uiState.catalogRows.isEmpty() -> {
                if (!homeStableGateReleased) {
                    Unit
                } else {
                    EmptyScreenState(
                        title = stringResource(R.string.home_empty_no_addons_title),
                        subtitle = stringResource(R.string.home_empty_no_addons_subtitle),
                        icon = Icons.Default.Extension,
                        modifier = Modifier.fillMaxSize(),
                        height = Dp.Unspecified,
                        action = StateAction(stringResource(R.string.empty_state_add_addon), onNavigateToAddons)
                    )
                }
            }

            uiState.errorKind == HomeErrorKind.NO_CATALOG_ADDONS && uiState.catalogRows.isEmpty() && !hasCollectionContent -> {
                if (!homeStableGateReleased) {
                    Unit
                } else {
                    EmptyScreenState(
                        title = stringResource(R.string.home_empty_no_catalog_addons_title),
                        subtitle = stringResource(R.string.home_empty_no_catalog_addons_subtitle),
                        icon = Icons.Default.Extension,
                        modifier = Modifier.fillMaxSize(),
                        height = Dp.Unspecified,
                        action = StateAction(stringResource(R.string.empty_state_add_addon), onNavigateToAddons)
                    )
                }
            }

            uiState.errorKind != null && uiState.catalogRows.isEmpty() -> {
                ErrorState(
                    message = uiState.error ?: stringResource(R.string.error_generic),
                    onRetry = { viewModel.onEvent(HomeEvent.OnRetry) },
                    requestInitialFocus = true
                )
            }

            !uiState.isLoading && !hasAnyContent -> {
                // Don't show "no catalogs" until the stable gate has released —
                // addons may still be loading from remote after a cache clear.
                if (!homeStableGateReleased) {
                    Unit
                } else {
                    EmptyScreenState(
                        title = stringResource(R.string.home_empty_no_catalogs_title),
                        subtitle = stringResource(R.string.home_empty_no_catalogs_subtitle),
                        icon = Icons.Default.Extension,
                        modifier = Modifier.fillMaxSize(),
                        height = Dp.Unspecified,
                        action = StateAction(stringResource(R.string.empty_state_add_addon), onNavigateToAddons)
                    )
                }
            }

            else -> {
                // On first launch, wait for stable content before revealing home.
                // Once released, never go back to loading (homeStableGateReleased is rememberSaveable).
                if (!homeStableGateReleased) {
                    Unit
                } else if (!modernPresentationReady) {
                    Unit
                } else {
                    // Flip showHomeContentWithAnimation on the next frame so
                    // AnimatedVisibility can run its enter transition.
                    LaunchedEffect(Unit) {
                        if (!showHomeContentWithAnimation) {
                            kotlinx.coroutines.yield()
                            showHomeContentWithAnimation = true
                        }
                    }
                    LaunchedEffect(showHomeContentWithAnimation) {
                        if (showHomeContentWithAnimation) {
                            hasShownInitialHomeContent = true
                        }
                    }
                    // Keep loading visible during the single-frame gap before animation starts.
                    if (!showHomeContentWithAnimation) {
                        Unit
                    }
                    AnimatedVisibility(
                        visible = showHomeContentWithAnimation,
                        enter = if (hasShownInitialHomeContent) {
                            EnterTransition.None
                        } else if (FetchTheme.fullMotion) {
                            fadeIn(animationSpec = tween(FetchMotion.tokens.durations.medium)) +
                                slideInVertically(
                                    initialOffsetY = { it / 24 },
                                    animationSpec = tween(FetchMotion.tokens.durations.medium)
                                )
                        } else {
                            fadeIn(animationSpec = tween(FetchMotion.tokens.durations.medium))
                        }
                    ) {
                        ModernHomeRoute(
                            viewModel = viewModel,
                            uiState = uiState,
                            onNavigateToDetail = onNavigateToDetailStable,
                            onContinueWatchingClick = onContinueWatchingClickStable,
                            onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginningStable,
                            onContinueWatchingPlayManually = onContinueWatchingPlayManuallyStable,
                            showContinueWatchingManualPlayOption = effectiveAutoplayEnabled,
                            onNavigateToFolderDetail = onNavigateToFolderDetailStable,
                            onSeeAllRow = onSeeAllRow,
                            onCardFocused = onCardFocused,
                            onOpenYouTube = onOpenYouTube,
                            isCatalogItemWatched = isCatalogItemWatched,
                            onCatalogItemLongPress = onCatalogItemLongPress,
                            onPlayCatalogTitle = onPlayCatalogTitle
                        )
                    }
                }
            }
        }

        if (showHomeLoader) {
            ModernHomeSkeleton(
                uiState = uiState,
                modifier = Modifier
                    .fillMaxSize()
                    .background(FetchTheme.colors.Background)
            )
        }

        // Hiding the last card for undo can leave Home with no rows: the content unmounts and the
        // skeleton comes back. The banner is drawn here, after the skeleton, so it stays on top
        // and usable until the undo window ends (#816).
        if (homeStableGateReleased) {
            val heldRemoval = viewModel.pendingContinueWatchingRemoval.collectAsStateWithLifecycle().value
            if (showsStandaloneUndoBanner(modernPresentationReady, heldRemoval)) {
                StandaloneContinueWatchingUndoBanner(
                    pending = checkNotNull(heldRemoval),
                    onUndo = { viewModel.onEvent(HomeEvent.OnUndoRemoveContinueWatching) }
                )
            }
        }

        val startupAuthNotice = uiState.startupAuthNotice
        if (startupAuthNotice != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = FetchTheme.spacing.xl)
                    .background(
                        color = FetchStatusTones.errorBanner,
                        shape = RoundedCornerShape(FetchTheme.radii.sm)
                    )
                    .padding(horizontal = FetchTheme.spacing.lg, vertical = FetchTheme.spacing.md)
                    .semantics { liveRegion = LiveRegionMode.Polite }
            ) {
                Text(
                    text = when (startupAuthNotice) {
                        StartupAuthNotice.TRAKT -> stringResource(R.string.auth_notice_trakt_logged_out)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = FetchTheme.colors.TextPrimary
                )
            }
        }
    }

    val pinnedRowKeys by viewModel.pinnedRowKeys.collectAsStateWithLifecycle()
    com.fetch.tv.ui.components.posteroptions.PosterOptionsHost(
        state = posterOptionsState,
        controller = posterOptionsController,
        onNavigateToDetail = onNavigateToDetail,
        showNotInterested = true,
        extraActions = {
            val rowKey = posterOptionsRowKey
            // Before Pin, so reordering a row from Home stays a few presses away.
            HomeMenuButton(
                text = stringResource(R.string.customise_home_title),
                onClick = {
                    posterOptionsController.dismiss()
                    onCustomiseHome(rowKey)
                }
            )
            if (rowKey != null) {
                HomePinRowButton(
                    isRowPinned = rowKey in pinnedRowKeys,
                    onClick = {
                        viewModel.toggleRowPinned(rowKey)
                        posterOptionsController.dismiss()
                    }
                )
            }
        }
    )
}

@Composable
private fun ModernHomeRoute(
    viewModel: HomeViewModel,
    uiState: HomeUiState,
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit,
    onContinueWatchingPlayManually: (ContinueWatchingItem) -> Unit,
    showContinueWatchingManualPlayOption: Boolean,
    onNavigateToFolderDetail: (String, String) -> Unit = { _, _ -> },
    onSeeAllRow: (HeroCarouselRow) -> Unit = {},
    onCardFocused: () -> Unit = {},
    onOpenYouTube: () -> Unit = {},
    isCatalogItemWatched: (MetaPreview) -> Boolean,
    onCatalogItemLongPress: (MetaPreview, String, String?) -> Unit,
    onPlayCatalogTitle: (String, String, String) -> Unit = { _, _, _ -> }
) {
    val focusState by viewModel.focusState.collectAsStateWithLifecycle()
    val catalogQuickPlayAllowed by viewModel.catalogQuickPlayAllowed.collectAsStateWithLifecycle()
    val showHoldHint by viewModel.showHoldHint.collectAsStateWithLifecycle()
    // Null on a kids Home or a profile with parental limits: Play/Pause then opens the card like OK.
    val onCatalogItemPlay: ((MetaPreview, String) -> Unit)? =
        remember(catalogQuickPlayAllowed, uiState.kidsHome, onPlayCatalogTitle) {
            if (!catalogQuickPlayAllowed || uiState.kidsHome) {
                null
            } else {
                { item: MetaPreview, addonBaseUrl: String -> onPlayCatalogTitle(item.id, item.apiType, addonBaseUrl) }
            }
        }
    val scrollToTopTrigger by viewModel.scrollToTopTrigger.collectAsStateWithLifecycle()
    val modernPresentation by viewModel.modernHomePresentation.collectAsStateWithLifecycle()
    // Not delegated: ModernHomeContent reads these inside the hero, so this scope doesn't recompose.
    val enrichingItemId = viewModel.enrichingItemId.collectAsStateWithLifecycle()
    val enrichedPreviews = viewModel.enrichedPreviews.collectAsStateWithLifecycle()
    val failedEnrichmentIds = viewModel.failedEnrichmentIds.collectAsStateWithLifecycle()
    val requestTrailerPreview = remember(viewModel) {
        { itemId: String, title: String, releaseInfo: String?, apiType: String ->
            viewModel.requestTrailerPreview(itemId, title, releaseInfo, apiType)
        }
    }
    val loadMoreCatalog = remember(viewModel) {
        { catalogId: String, addonId: String, type: String ->
            viewModel.onEvent(HomeEvent.OnLoadMoreCatalog(catalogId, addonId, type))
        }
    }
    val removeContinueWatching = remember(viewModel) {
        { contentId: String, season: Int?, episode: Int?, isNextUp: Boolean ->
            viewModel.onEvent(HomeEvent.OnRemoveContinueWatching(contentId, season, episode, isNextUp))
        }
    }
    val saveModernFocusState = remember(viewModel) {
        { vi: Int, vo: Int, rk: String?, ikm: Map<String, String>, m: Map<String, Int>, ma: Map<String, String>, ri: Int, ii: Int ->
            viewModel.saveFocusState(vi, vo, rk, ikm, m, ma, ri, ii)
            // Authoritative: this is the row that actually held focus when Home went away.
            viewModel.setLiveFocusedRowKey(rk)
        }
    }
    val preloadAdjacentItem = remember(viewModel) {
        { item: MetaPreview ->
            viewModel.preloadAdjacentItem(item)
        }
    }
    val cachedBestMatches by remember(viewModel) { viewModel.cachedBestMatches }
        .collectAsStateWithLifecycle(initialValue = emptyMap())
    val queuedVideoIds by remember(viewModel) { viewModel.queuedVideoIds }
        .collectAsStateWithLifecycle(initialValue = emptySet())
    // Checks a few titles in the background once Continue Watching settles. Leaving Home (to the
    // player, say) cancels it, so it never competes with playback.
    // A hidden Continue Watching row is never checked.
    // A kids Home shows nothing about sources, so it checks none.
    val readyCheckItems = if (uiState.continueWatchingEnabled && !uiState.kidsHome) uiState.continueWatchingItems else emptyList()
    LaunchedEffect(readyCheckItems) {
        delay(READY_CHECK_SETTLE_MS)
        viewModel.runReadyCheck(readyCheckItems)
    }
    val shownBestMatches = if (uiState.kidsHome) emptyMap() else cachedBestMatches
    // No Home card draws the ready dot (both cards ignore isReady), so Home no longer runs a
    // readiness ticker or provides LocalReadyBestMatchKeys for it (TheShield2594/fetch#826).
    // "Ready tonight" keeps its own timer in the presentation pipeline, and the ready check above
    // still runs because that row reads what it saves.
    val pendingCwRemoval by viewModel.pendingContinueWatchingRemoval.collectAsStateWithLifecycle()
    ModernHomeContent(
        uiState = uiState,
        pendingContinueWatchingRemoval = pendingCwRemoval,
        onUndoRemoveContinueWatching = remember(viewModel) {
            { viewModel.onEvent(HomeEvent.OnUndoRemoveContinueWatching) }
        },
        cachedBestMatches = shownBestMatches,
        queuedVideoIds = queuedVideoIds,
        onToggleQueue = remember(viewModel) { { item: ContinueWatchingItem -> viewModel.toggleQueued(item) } },
        modernPresentation = modernPresentation,
        focusState = focusState,
        scrollToTopTrigger = scrollToTopTrigger,
        enrichingItemId = enrichingItemId,
        enrichedPreviews = enrichedPreviews,
        failedEnrichmentIds = failedEnrichmentIds,
        trailerPreviewUrls = viewModel.trailerPreviewUrls,
        trailerPreviewAudioUrls = viewModel.trailerPreviewAudioUrls,
        onNavigateToDetail = onNavigateToDetail,
        onContinueWatchingClick = onContinueWatchingClick,
        onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginning,
        onContinueWatchingPlayManually = onContinueWatchingPlayManually,
        showContinueWatchingManualPlayOption = showContinueWatchingManualPlayOption,
        onRequestTrailerPreview = requestTrailerPreview,
        onLoadMoreCatalog = loadMoreCatalog,
        onRemoveContinueWatching = removeContinueWatching,
        isCatalogItemWatched = isCatalogItemWatched,
        onCatalogItemLongPress = onCatalogItemLongPress,
        onCatalogItemPlay = onCatalogItemPlay,
        showHoldHint = showHoldHint && !uiState.kidsHome,
        onContentFilterSelected = remember(viewModel) { { filter: HomeContentFilter -> viewModel.setHomeContentFilter(filter) } },
        onNavigateToFolderDetail = onNavigateToFolderDetail,
        onSeeAllRow = onSeeAllRow,
        onYouTubeVideoClick = remember(viewModel, onOpenYouTube) {
            { video: com.fetch.tv.data.youtube.YouTubeVideo ->
                viewModel.youTubeRow.play(video)
                onOpenYouTube()
            }
        },
        onItemFocus = remember(viewModel, onCardFocused) {
            { item ->
                viewModel.onItemFocus(item)
                onCardFocused()
            }
        },
        onPreloadAdjacentItem = preloadAdjacentItem,
        onSaveFocusState = saveModernFocusState,
        onFocusedRowKeyChanged = remember(viewModel) {
            { key: String? -> viewModel.setLiveFocusedRowKey(key) }
        },
        onRequestLazyCatalogLoad = remember(viewModel) {
            { catalogKey: String -> viewModel.requestLazyCatalogLoad(catalogKey) }
        }
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HomeMenuButton(
    text: String,
    onClick: () -> Unit
) {
    FetchButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HomePinRowButton(
    isRowPinned: Boolean,
    onClick: () -> Unit
) {
    FetchButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            if (isRowPinned) {
                stringResource(R.string.home_poster_unpin_row)
            } else {
                stringResource(R.string.home_poster_pin_row)
            }
        )
    }
}
