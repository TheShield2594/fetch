@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.fetch.tv.ui.screens.stream

import androidx.compose.material.icons.filled.Extension
import com.fetch.tv.ui.components.StateAction
import com.fetch.tv.ui.components.ErrorState
import com.fetch.tv.ui.components.EmptyScreenState
import com.fetch.tv.ui.theme.FetchMotion
import com.fetch.tv.ui.components.PlayLaunchOrigin
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.graphics.graphicsLayer
import com.fetch.tv.ui.theme.FetchRadii
import com.fetch.tv.core.streams.ranking.StreamTitleParser
import com.fetch.tv.core.streams.ranking.ExclusionReason
import com.fetch.tv.core.streams.ranking.label
import com.fetch.tv.core.torrent.TorrentService
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import android.view.KeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import coil3.request.ImageRequest
import coil3.request.crossfade
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import com.fetch.tv.core.party.PartySource
import com.fetch.tv.core.party.matches
import com.fetch.tv.ui.util.contentTextDirection
import com.fetch.tv.ui.util.toAbsoluteAlignment
import com.fetch.tv.ui.util.isPlaceholderEpisodeTitle
import com.fetch.tv.ui.util.localizeEpisodeTitle
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.fetch.tv.core.player.ExternalPlayerLauncher
import com.fetch.tv.core.streams.StreamBadgePlacement
import com.fetch.tv.core.streams.StreamBadgeSettings
import com.fetch.tv.data.local.PlayerPreference
import com.fetch.tv.domain.model.Stream
import com.fetch.tv.ui.components.SourceChipItem
import com.fetch.tv.ui.components.SourceChipStatus
import com.fetch.tv.ui.components.P2pConsentDialog
import com.fetch.tv.ui.components.StreamBadgeChips
import com.fetch.tv.ui.components.StreamsSkeletonList
import com.fetch.tv.ui.screens.player.LoadingOverlay
import com.fetch.tv.ui.screens.player.AddonFilterChips
import com.fetch.tv.ui.theme.FetchTheme
import com.fetch.tv.ui.navigation.sourceSelectionRestoreTarget
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay as coroutineDelay
import kotlinx.coroutines.launch as coroutineLaunch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import com.fetch.tv.R
import android.util.Log


@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun StreamScreen(
    viewModel: StreamScreenViewModel = hiltViewModel(),
    startFromBeginning: Boolean = false,
    restoreSourceSelection: Boolean = false,
    onSourceSelectionRestoreHandled: () -> Unit = {},
    onBackPress: () -> Unit,
    onStreamSelected: (StreamPlaybackInfo) -> Unit,
    onAutoPlayResolved: (StreamPlaybackInfo) -> Unit,
    onNavigateToAddons: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val sharedStream by viewModel.sharedStream.collectAsStateWithLifecycle()
    val partySource by viewModel.partySource.collectAsStateWithLifecycle()
    val playerPreference by viewModel.playerPreference.collectAsStateWithLifecycle(
        initialValue = null
    )
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    var focusedStreamIndex by rememberSaveable { mutableStateOf(0) }
    var restoreFocusedStream by rememberSaveable { mutableStateOf(false) }
    var pendingRestoreOnResume by rememberSaveable { mutableStateOf(false) }
    var showPlayerChoiceDialog by remember { mutableStateOf(false) }
    var sourceOptions by remember { mutableStateOf<List<SourceRule>?>(null) }
    var pendingPlaybackInfo by remember { mutableStateOf<StreamPlaybackInfo?>(null) }
    var showP2pConsentDialog by remember { mutableStateOf(false) }
    var pendingTorrentPlaybackInfo by remember { mutableStateOf<StreamPlaybackInfo?>(null) }
    val p2pEnabled by viewModel.p2pEnabled.collectAsStateWithLifecycle(initialValue = false)
    val streamBadgeSettings by viewModel.streamBadgeSettings.collectAsStateWithLifecycle(
        initialValue = StreamBadgeSettings()
    )
    val scope = rememberCoroutineScope()

    LaunchedEffect(restoreSourceSelection) {
        if (restoreSourceSelection) {
            pendingRestoreOnResume = false
            restoreFocusedStream = true
        }
    }

    fun launchExternalPlayer(playbackInfo: StreamPlaybackInfo) {
        val url = playbackInfo.url ?: if (playbackInfo.isTorrent) "torrent://${playbackInfo.infoHash}" else return
        scope.coroutineLaunch {
            viewModel.launchExternalPlayer(
                playbackInfo = playbackInfo,
                url = url,
                startFromBeginning = startFromBeginning,
                context = context
            )
        }
    }

    fun openExternalInBrowser(playbackInfo: StreamPlaybackInfo): Boolean {
        if (!playbackInfo.isExternal) return false
        val url = playbackInfo.url?.takeIf { it.isNotBlank() } ?: return false
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        runCatching {
            context.startActivity(browserIntent)
        }.onFailure {
            ExternalPlayerLauncher.launch(
                context = context,
                url = url,
                title = playbackInfo.title,
                headers = playbackInfo.headers,
                startFromBeginning = startFromBeginning
            )
        }
        return true
    }

    fun launchInternalPlayer(playbackInfo: StreamPlaybackInfo) {
        viewModel.onInternalPlayerLaunching()
        onStreamSelected(playbackInfo)
    }

    fun routePlayback(playbackInfo: StreamPlaybackInfo) {
        if (openExternalInBrowser(playbackInfo)) {
            return
        }
        val preference = if (playbackInfo.isServerStream) PlayerPreference.INTERNAL else playerPreference ?: return
        if (playbackInfo.isTorrent && !p2pEnabled && TorrentService.ENGINE_AVAILABLE) {
            pendingTorrentPlaybackInfo = playbackInfo
            showP2pConsentDialog = true
            return
        }
        // With no torrent engine, an unresolved torrent only plays in an external player, so
        // offer the player choice instead of letting the internal player fail on it.
        if (playbackInfo.isTorrent && !TorrentService.ENGINE_AVAILABLE && preference == PlayerPreference.INTERNAL) {
            pendingPlaybackInfo = playbackInfo
            showPlayerChoiceDialog = true
            return
        }
        when (preference) {
            PlayerPreference.INTERNAL -> {
                launchInternalPlayer(playbackInfo)
            }
            PlayerPreference.EXTERNAL -> {
                if (playbackInfo.url != null || playbackInfo.isTorrent) {
                    launchExternalPlayer(playbackInfo)
                }
            }
            PlayerPreference.ASK_EVERY_TIME -> {
                pendingPlaybackInfo = playbackInfo
                showPlayerChoiceDialog = true
            }
        }
    }

    fun routeAutoPlay(playbackInfo: StreamPlaybackInfo) {
        if (openExternalInBrowser(playbackInfo)) {
            viewModel.onEvent(StreamScreenEvent.OnAutoPlayConsumed)
            return
        }
        // Always check P2P consent for torrents, even in direct auto-play flow
        if (playbackInfo.isTorrent && !p2pEnabled && TorrentService.ENGINE_AVAILABLE) {
            pendingTorrentPlaybackInfo = playbackInfo
            showP2pConsentDialog = true
            return
        }
        val preference = if (playbackInfo.isServerStream) PlayerPreference.INTERNAL else playerPreference ?: return
        if (uiState.isDirectAutoPlayFlow) {
            // Respect player preference even in direct autoplay flow
            when (preference) {
                PlayerPreference.EXTERNAL -> {
                    val url = playbackInfo.url ?: if (playbackInfo.isTorrent) "torrent://${playbackInfo.infoHash}" else null
                    url?.let { urlString ->
                        scope.coroutineLaunch {
                            viewModel.launchExternalPlayer(
                                playbackInfo = playbackInfo,
                                url = urlString,
                                startFromBeginning = startFromBeginning,
                                autoLaunch = true,
                                context = context
                            )
                            // Delay pop so external player appears on top
                            coroutineDelay(1000)
                            viewModel.onEvent(StreamScreenEvent.OnAutoPlayConsumed)
                            onBackPress()
                        }
                    }
                }
                PlayerPreference.ASK_EVERY_TIME -> {
                    pendingPlaybackInfo = playbackInfo
                    showPlayerChoiceDialog = true
                    viewModel.onEvent(StreamScreenEvent.OnAutoPlayConsumed)
                }
                else -> {
                    viewModel.onInternalPlayerLaunching()
                    onAutoPlayResolved(playbackInfo)
                }
            }
            return
        } else {
            pendingRestoreOnResume = true
            routePlayback(playbackInfo)
            viewModel.onEvent(StreamScreenEvent.OnAutoPlayConsumed)
        }
    }

    BackHandler {
        onBackPress()
    }

    LaunchedEffect(uiState.autoPlayStream) {
        val stream = uiState.autoPlayStream ?: return@LaunchedEffect
        // User aborted the auto-next chain that navigated here — don't auto-launch; show the list.
        if (viewModel.isAutoNextContinuationAborted()) {
            viewModel.consumeAbortedAutoNextContinuation()
            viewModel.onEvent(StreamScreenEvent.OnAutoPlayConsumed)
            return@LaunchedEffect
        }
        val playbackInfo = viewModel.resolveStreamForPlayback(stream)
        if (playbackInfo == null) {
            viewModel.onEvent(StreamScreenEvent.OnAutoPlayConsumed)
            return@LaunchedEffect
        }
        // Torrent streams have url == null but carry an infoHash; navigation
        // builds a torrent:// sentinel URL downstream.
        if (playbackInfo.url != null || (playbackInfo.isTorrent && playbackInfo.infoHash != null)) {
            viewModel.awaitStreamLinkCacheSave()
            routeAutoPlay(playbackInfo)
        }
    }

    LaunchedEffect(uiState.playbackErrorMessage) {
        val message = uiState.playbackErrorMessage ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        viewModel.onPlaybackErrorShown()
    }

    // Once streams are resolved, release the MainActivity auto-next loader so it doesn't
    // mask this screen (whether it auto-launches a player or shows the manual list).
    LaunchedEffect(uiState.isLoading) {
        if (!uiState.isLoading) {
            viewModel.dismissExternalAutoNextOverlay(
                forceRelease = !uiState.isDirectAutoPlayFlow ||
                    playerPreference != PlayerPreference.EXTERNAL
            )
        }
    }

    LaunchedEffect(uiState.autoPlayPlaybackInfo) {
        val playbackInfo = uiState.autoPlayPlaybackInfo ?: return@LaunchedEffect
        // User aborted the auto-next chain that navigated here — don't auto-launch; show the list.
        if (viewModel.isAutoNextContinuationAborted()) {
            viewModel.consumeAbortedAutoNextContinuation()
            viewModel.onEvent(StreamScreenEvent.OnAutoPlayConsumed)
            return@LaunchedEffect
        }
        if (playbackInfo.url != null || (playbackInfo.isTorrent && playbackInfo.infoHash != null)) {
            // Torrent cached links still need P2P consent
            if (playbackInfo.isTorrent && !p2pEnabled && TorrentService.ENGINE_AVAILABLE) {
                pendingTorrentPlaybackInfo = playbackInfo
                showP2pConsentDialog = true
                return@LaunchedEffect
            }
            // Respect player preference for cached links too
            when (playerPreference ?: return@LaunchedEffect) {
                PlayerPreference.EXTERNAL -> {
                    val url = playbackInfo.url ?: if (playbackInfo.isTorrent) "torrent://${playbackInfo.infoHash}" else null
                    url?.let { urlString ->
                        Log.d("StreamScreen", "autoPlayPlaybackInfo EXTERNAL: launching player, will pop after 800ms")
                        viewModel.launchExternalPlayer(
                            playbackInfo = playbackInfo,
                            url = urlString,
                            startFromBeginning = startFromBeginning,
                            autoLaunch = true,
                            context = context
                        )
                    }
                    // Delay pop so external player appears on top, keep overlay visible
                    coroutineDelay(1000)
                    Log.d("StreamScreen", "autoPlayPlaybackInfo EXTERNAL: popping now")
                    viewModel.onEvent(StreamScreenEvent.OnAutoPlayConsumed)
                    onBackPress()
                }
                PlayerPreference.ASK_EVERY_TIME -> {
                    pendingPlaybackInfo = playbackInfo
                    showPlayerChoiceDialog = true
                    viewModel.onEvent(StreamScreenEvent.OnAutoPlayConsumed)
                }
                else -> {
                    viewModel.onInternalPlayerLaunching()
                    onAutoPlayResolved(playbackInfo)
                    viewModel.onEvent(StreamScreenEvent.OnAutoPlayConsumed)
                }
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // Always dismiss overlay and stop tracking on resume
                // covers both ActivityResult path and fire-and-forget path.
                viewModel.stopExternalPlayerTracking()
                viewModel.onEvent(StreamScreenEvent.OnResume)
                if (pendingRestoreOnResume) {
                    restoreFocusedStream = true
                    pendingRestoreOnResume = false
                }
            } else if (event == Lifecycle.Event.ON_STOP) {
                // Backgrounded by the external player: playback behind it is healthy,
                // so the stuck-loader timeout must not treat it as stuck.
                viewModel.onHostStopped()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val showOverlay = uiState.showDirectAutoPlayOverlay || uiState.externalPlayerOverlayVisible
    val fullMotion = FetchTheme.fullMotion
    // The Play or Resume press that opened this screen, if it was just now.
    val launchOrigin = remember { PlayLaunchOrigin.take()?.takeIf { fullMotion } }
    var rootTopLeft by remember { mutableStateOf(Offset.Zero) }
    var fetchingPanelBounds by remember { mutableStateOf<Rect?>(null) }
    val launchProgress = remember { Animatable(if (launchOrigin != null) 0f else 1f) }
    // Read in composition only when it flips; the morph reads the value itself while it draws.
    val launchDone by remember { derivedStateOf { launchProgress.value >= 1f } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { rootTopLeft = it.positionInWindow() }
    ) {
        // Full screen backdrop
        StreamBackdrop(
            backdrop = uiState.backdrop ?: uiState.poster,
            isLoading = uiState.isLoading,
            modifier = Modifier
        )

        if (!uiState.autoPlayDecided) {
            // Don't render overlay or stream list until ViewModel decides
            // whether direct autoplay is active — prevents single-frame flash.
        } else if (showOverlay) {
            val waitingForPick = uiState.showDirectAutoPlayOverlay &&
                !uiState.externalPlayerOverlayVisible &&
                uiState.autoPlayStream == null &&
                uiState.autoPlayPlaybackInfo == null &&
                uiState.sourceChips.isNotEmpty()
            val fetchingPanelBottomPadding = FetchTheme.spacing.xxxl
            var fetchingPanelHeight by remember { mutableStateOf(0.dp) }
            val density = LocalDensity.current
            LoadingOverlay(
                visible = true,
                backdropUrl = uiState.backdrop ?: uiState.poster,
                logoUrl = uiState.logo,
                title = uiState.title,
                message = if (uiState.directAutoPlayMessage != null) {
                    uiState.directAutoPlayMessage
                } else {
                    null
                },
                progress = uiState.directAutoPlayProgress,
                modifier = Modifier.fillMaxSize(),
                // Centre the logo above the Fetching panel instead of behind its top edge.
                bottomInset = if (waitingForPick && fetchingPanelHeight > 0.dp) {
                    fetchingPanelHeight + fetchingPanelBottomPadding
                } else {
                    0.dp
                }
            )
            if (waitingForPick) {
                // Drawn first, so the panel's content fades in over the shape rather than under it.
                val panelBounds = fetchingPanelBounds
                if (launchOrigin != null && panelBounds != null && !launchDone) {
                    FetchLaunchMorph(
                        from = launchOrigin.bounds.translate(-rootTopLeft),
                        to = panelBounds.translate(-rootTopLeft),
                        progress = { launchProgress.value },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                FetchingPanel(
                    sourceChips = uiState.sourceChips,
                    bestSoFar = uiState.bestSoFar,
                    deadlineElapsedMs = uiState.autoPlayDeadlineElapsedMs,
                    onPlayNow = { viewModel.onEvent(StreamScreenEvent.OnPlayBestSoFar) },
                    onShowAllSources = { viewModel.onEvent(StreamScreenEvent.OnShowAllSources) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = fetchingPanelBottomPadding, start = FetchTheme.spacing.xxxl, end = FetchTheme.spacing.xxxl)
                        .onSizeChanged { fetchingPanelHeight = with(density) { it.height.toDp() } }
                        .onGloballyPositioned { fetchingPanelBounds = it.boundsInWindow() }
                        .graphicsLayer { alpha = launchContentAlpha(launchProgress.value) }
                )
                LaunchedEffect(panelBounds != null) {
                    if (panelBounds == null || launchProgress.value >= 1f) return@LaunchedEffect
                    // A panel that shows up well after the press no longer reads as its result.
                    val pressedAtMs = launchOrigin?.atMs ?: 0L
                    if (android.os.SystemClock.elapsedRealtime() - pressedAtMs > PlayLaunchOrigin.MAX_AGE_MS) {
                        launchProgress.snapTo(1f)
                    } else {
                        launchProgress.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(
                                durationMillis = FetchMotion.tokens.durations.hero,
                                // Linear time; FetchLaunchMorph eases the growth itself.
                                easing = LinearEasing
                            )
                        )
                    }
                }
            }
        } else {
            // Content overlay
            Row(
                modifier = Modifier.fillMaxSize()
            ) {
                // Left side - Title/Logo (centered vertically)
                LeftContentSection(
                    title = uiState.title,
                    logo = uiState.logo,
                    isEpisode = uiState.isEpisode,
                    season = uiState.season,
                    episode = uiState.episode,
                    episodeName = uiState.episodeName,
                    runtime = uiState.runtime,
                    genres = uiState.genres,
                    year = uiState.year,
                    modifier = Modifier
                        .weight(0.4f)
                        .fillMaxHeight()
                )

                // Right side - Streams container
                RightStreamSection(
                    isLoading = uiState.isLoading,
                    error = uiState.error,
                    streams = uiState.filteredStreams,
                    streamExclusions = uiState.streamExclusions,
                    bestMatchFirst = uiState.bestMatchFirst,
                    torrentsNeedExternalPlayer = uiState.torrentsNeedExternalPlayer,
                    availableAddons = uiState.availableAddons,
                    sourceChips = uiState.sourceChips,
                    selectedAddonFilter = uiState.selectedAddonFilter,
                    showFileSizeBadges = streamBadgeSettings.showFileSizeBadges,
                    showAddonLogo = streamBadgeSettings.showAddonLogo,
                    badgePlacement = streamBadgeSettings.badgePlacement,
                    hasBadgeRules = streamBadgeSettings.rules.hasImport,
                    onAddonFilterSelected = { viewModel.onEvent(StreamScreenEvent.OnAddonFilterSelected(it)) },
                    onRefresh = { viewModel.onEvent(StreamScreenEvent.OnRefresh) },
                    sharedStream = sharedStream,
                    partySource = partySource,
                    onSharedStreamSelected = { stream ->
                        scope.coroutineLaunch {
                            val playbackInfo = viewModel.resolveStreamForPlayback(stream, saveLastLink = false)
                            if (playbackInfo != null) {
                                pendingRestoreOnResume = true
                                routePlayback(playbackInfo)
                                viewModel.onEvent(StreamScreenEvent.OnAutoPlayConsumed)
                            }
                        }
                    },
                    onStreamSelected = { stream ->
                        val currentIndex = uiState.filteredStreams.indexOfFirst {
                            it.url == stream.url &&
                                it.infoHash == stream.infoHash &&
                                it.ytId == stream.ytId &&
                                it.serverTarget == stream.serverTarget &&
                                it.addonName == stream.addonName
                        }
                        if (currentIndex >= 0) {
                            focusedStreamIndex = currentIndex
                        }
                        viewModel.onManualPick(stream)
                        scope.coroutineLaunch {
                            val playbackInfo = viewModel.resolveStreamForPlayback(stream)
                            if (playbackInfo != null) {
                                viewModel.persistBingeGroupForStream(stream)
                                pendingRestoreOnResume = true
                                routePlayback(playbackInfo)
                                viewModel.onEvent(StreamScreenEvent.OnAutoPlayConsumed)
                            }
                        }
                    },
                    focusedStreamIndex = focusedStreamIndex,
                    shouldRestoreFocusedStream = restoreFocusedStream,
                    onRestoreFocusedStreamHandled = {
                        restoreFocusedStream = false
                        if (restoreSourceSelection) {
                            onSourceSelectionRestoreHandled()
                        }
                    },
                    onRetry = { viewModel.onEvent(StreamScreenEvent.OnRetry) },
                    onExpandStreams = { viewModel.expandFilteredStreamsIfNeeded() },
                    sourceRuleOptions = { stream -> viewModel.sourceRuleOptions(stream) },
                    onSourceOptions = { _, options -> sourceOptions = options },
                    onNavigateToAddons = onNavigateToAddons,
                    modifier = Modifier
                        .weight(0.6f)
                        .fillMaxHeight()
                )
            }
        }

        // Player choice dialog for "Ask every time" preference
        sourceOptions?.let { options ->
            SourceOptionsDialog(
                rules = options,
                onRuleSelected = { rule ->
                    sourceOptions = null
                    viewModel.applySourceRule(rule)
                },
                onDismiss = { sourceOptions = null }
            )
        }
        if (showPlayerChoiceDialog && pendingPlaybackInfo != null) {
            PlayerChoiceDialog(
                onInternalSelected = {
                    showPlayerChoiceDialog = false
                    pendingPlaybackInfo?.let { launchInternalPlayer(it) }
                    pendingPlaybackInfo = null
                },
                onExternalSelected = {
                    showPlayerChoiceDialog = false
                    pendingPlaybackInfo?.let { info ->
                        if (info.url != null || info.isTorrent) {
                            launchExternalPlayer(info)
                        }
                    }
                    pendingPlaybackInfo = null
                },
                onDismiss = {
                    showPlayerChoiceDialog = false
                    pendingPlaybackInfo = null
                }
            )
        }

        if (showP2pConsentDialog && pendingTorrentPlaybackInfo != null) {
            P2pConsentDialog(
                onEnableP2p = {
                    viewModel.enableP2p()
                    showP2pConsentDialog = false
                    val info = pendingTorrentPlaybackInfo!!
                    pendingTorrentPlaybackInfo = null
                    routePlayback(info)
                },
                onDismiss = {
                    showP2pConsentDialog = false
                    pendingTorrentPlaybackInfo = null
                    // Cancelled P2P consent — fall back to manual stream selection
                    viewModel.onEvent(StreamScreenEvent.OnAutoPlayConsumed)
                }
            )
        }

    }
}

@Composable
internal fun StreamBackdrop(
    backdrop: String?,
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val backgroundColor = FetchTheme.colors.Background
    val backdropModel = remember(context, backdrop) {
        backdrop?.let { image ->
            ImageRequest.Builder(context)
                .data(image)
                .crossfade(false)
                .build()
        }
    }
    val imageAlpha by animateFloatAsState(
        targetValue = if (isLoading) 0.7f else 0.5f,
        animationSpec = tween(FetchMotion.tokens.durations.slow),
        label = "backdrop_image_alpha"
    )

    Box(modifier = modifier
        .fillMaxSize()
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    ) {
        // Backdrop image
        if (backdropModel != null) {
            AsyncImage(
                model = backdropModel,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = imageAlpha },
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopEnd
            )
        }

        StreamGradientLayer(
            bgColor = backgroundColor,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun StreamGradientLayer(
    bgColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .drawWithCache {
                val combinedGradient = Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0.0f to bgColor,
                        0.15f to bgColor.copy(alpha = 0.85f),
                        0.30f to bgColor.copy(alpha = 0.40f),
                        0.50f to bgColor.copy(alpha = 0.15f),
                        0.70f to bgColor.copy(alpha = 0.40f),
                        0.85f to bgColor.copy(alpha = 0.85f),
                        1.0f to bgColor
                    ),
                    startX = 0f,
                    endX = size.width
                )
                onDrawBehind {
                    drawRect(brush = combinedGradient)
                }
            }
    )
}

@Composable
private fun LeftContentSection(
    title: String,
    logo: String?,
    isEpisode: Boolean,
    season: Int?,
    episode: Int?,
    episodeName: String?,
    runtime: Int?,
    genres: String?,
    year: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var logoLoadFailed by remember(logo) { mutableStateOf(false) }
    val density = LocalDensity.current
    val logoModel = remember(context, logo) {
        logo?.let { image ->
            ImageRequest.Builder(context)
                .data(image)
                .crossfade(false)
                .build()
        }
    }
    val infoText = remember(genres, year) {
        listOfNotNull(genres, year).joinToString(" • ")
    }
    Box(
        modifier = modifier.padding(start = FetchTheme.spacing.xxxl, end = FetchTheme.spacing.xl),
        contentAlignment = Alignment.CenterStart
    ) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(0.8f)
        ) {
            if (logoModel != null && !logoLoadFailed) {
                AsyncImage(
                    model = logoModel,
                    contentDescription = title,
                    onError = { logoLoadFailed = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.Center
                )
            } else {
                Text(
                    text = title,
                    style = MaterialTheme.typography.displaySmall,
                    color = FetchTheme.colors.TextPrimary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }

            // Show episode info or movie info
            if (isEpisode && season != null && episode != null) {
                // Episode info
                Spacer(modifier = Modifier.height(FetchTheme.spacing.sm))
                Text(
                    text = stringResource(R.string.stream_episode_label, season, episode),
                    style = MaterialTheme.typography.titleLarge,
                    color = FetchTheme.extendedColors.textSecondary,
                    textAlign = TextAlign.Center
                )
                // "Episode 6" would only repeat the line above.
                if (episodeName != null && !episodeName.isPlaceholderEpisodeTitle()) {
                    Spacer(modifier = Modifier.height(FetchTheme.spacing.xs))
                    Text(
                        text = episodeName.localizeEpisodeTitle(LocalContext.current),
                        style = MaterialTheme.typography.bodyLarge,
                        color = FetchTheme.colors.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
                if (runtime != null) {
                    Spacer(modifier = Modifier.height(FetchTheme.spacing.xs))
                    val runtimeText = if (runtime >= 60) {
                        val hours = runtime / 60
                        val mins = runtime % 60
                        if (mins > 0) "${hours}h ${mins}m" else "${hours}h"
                    } else {
                        "${runtime}m"
                    }
                    Text(
                        text = runtimeText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = FetchTheme.extendedColors.textSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                // Movie info - genres and year
                Spacer(modifier = Modifier.height(FetchTheme.spacing.sm))
                if (infoText.isNotEmpty()) {
                    Text(
                        text = infoText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = FetchTheme.extendedColors.textSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RightStreamSection(
    isLoading: Boolean,
    error: String?,
    streams: List<Stream>,
    availableAddons: List<String>,
    sourceChips: List<SourceChipItem>,
    selectedAddonFilter: String?,
    showFileSizeBadges: Boolean,
    showAddonLogo: Boolean,
    badgePlacement: StreamBadgePlacement,
    hasBadgeRules: Boolean = false,
    onAddonFilterSelected: (String?) -> Unit,
    onRefresh: () -> Unit,
    sharedStream: SharedStream?,
    partySource: PartySource?,
    onSharedStreamSelected: (Stream) -> Unit,
    onStreamSelected: (Stream) -> Unit,
    focusedStreamIndex: Int,
    shouldRestoreFocusedStream: Boolean,
    onRestoreFocusedStreamHandled: () -> Unit,
    onRetry: () -> Unit,
    onExpandStreams: () -> Unit = {},
    onNavigateToAddons: () -> Unit = {},
    sourceRuleOptions: (Stream) -> List<SourceRule> = { emptyList() },
    onSourceOptions: (Stream, List<SourceRule>) -> Unit = { _, _ -> },
    streamExclusions: Map<String, ExclusionReason> = emptyMap(),
    bestMatchFirst: Boolean = false,
    torrentsNeedExternalPlayer: Boolean = false,
    modifier: Modifier = Modifier
) {
    val isRtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    var enter by remember { mutableStateOf(false) }
    var firstStreamFocusRequestId by remember { mutableStateOf(0) }
    var listHasFocus by remember { mutableStateOf(false) }
    var focusedStreamKeyInList by remember { mutableStateOf<String?>(null) }
    var userMovedFromFirstResult by remember { mutableStateOf(shouldRestoreFocusedStream) }
    var firstResultFocusAssigned by remember { mutableStateOf(shouldRestoreFocusedStream) }
    val scope = rememberCoroutineScope()
    var focusJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val orderedAddonNames = remember(availableAddons, sourceChips) {
        buildList {
            addAll(availableAddons)
            sourceChips.forEach { if (it.name !in this) add(it.name) }
        }
    }
    val sections = remember(streams, streamExclusions, bestMatchFirst, selectedAddonFilter) {
        sourceSections(streams, streamExclusions, bestMatchFirst, selectedAddonFilter)
    }
    // The first card shown: grouping can lead with a stream that isn't first in ranked order.
    val firstStreamKey = sections.firstShownIndex()?.let { streams.getOrNull(it) }?.stableKey(0)
    val refreshFocusRequester = remember { FocusRequester() }
    val allFocusRequester = remember { FocusRequester() }
    val addonFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val chipFocusRequesters = remember(orderedAddonNames) {
        // Remove stale entries for addons that no longer exist
        addonFocusRequesters.keys.retainAll(orderedAddonNames.toSet())
        buildList {
            add(refreshFocusRequester)
            add(allFocusRequester)
            orderedAddonNames.forEach { addon ->
                add(addonFocusRequesters.getOrPut(addon) { FocusRequester() })
            }
        }
    }
    fun onAddonFilterSelectedGuarded(addon: String?) {
        userMovedFromFirstResult = true
        onAddonFilterSelected(addon)
        focusJob?.cancel()
        focusJob = scope.coroutineLaunch {
            withFrameNanos {}
            val targetRequester = if (addon == null) {
                chipFocusRequesters.getOrNull(1)
            } else {
                addonFocusRequesters[addon]
            }
            runCatching { targetRequester?.requestFocus() }
        }
    }

    LaunchedEffect(Unit) {
        enter = true
    }
    LaunchedEffect(shouldRestoreFocusedStream) {
        if (shouldRestoreFocusedStream) {
            userMovedFromFirstResult = true
        }
    }
    LaunchedEffect(isLoading, firstStreamKey, userMovedFromFirstResult, firstResultFocusAssigned) {
        if (!isLoading && firstStreamKey != null && !userMovedFromFirstResult && !firstResultFocusAssigned) {
            firstResultFocusAssigned = true
            firstStreamFocusRequestId += 1
        }
    }
    var trackedFirstStreamKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(firstStreamKey, selectedAddonFilter, listHasFocus) {
        if (selectedAddonFilter != null) {
            trackedFirstStreamKey = firstStreamKey
            return@LaunchedEffect
        }
        val focusWasOnFirst = trackedFirstStreamKey != null &&
            focusedStreamKeyInList == trackedFirstStreamKey
        if (firstStreamKey != null && trackedFirstStreamKey != null &&
            firstStreamKey != trackedFirstStreamKey &&
            listHasFocus && focusWasOnFirst
        ) {
            firstStreamFocusRequestId += 1
        }
        trackedFirstStreamKey = firstStreamKey
    }
    fun requestChipFocus(index: Int) {
        if (index !in chipFocusRequesters.indices) return
        userMovedFromFirstResult = true
        focusJob?.cancel()
        focusJob = scope.coroutineLaunch {
            withFrameNanos { }
            runCatching { chipFocusRequesters[index].requestFocus() }
        }
    }

    Column(
        modifier = modifier
            .padding(top = FetchTheme.spacing.xxxl, end = FetchTheme.spacing.xxxl, bottom = FetchTheme.spacing.xxxl)
    ) {
        val chipRowHeight = FetchTheme.spacing.huge

        // Addon filter chips
        Box(modifier = Modifier.height(chipRowHeight)) {
            androidx.compose.animation.AnimatedVisibility(
                visible = sourceChips.isNotEmpty() || (!isLoading && availableAddons.isNotEmpty()),
                enter = fadeIn(animationSpec = tween(FetchMotion.tokens.durations.medium)),
                exit = fadeOut(animationSpec = tween(FetchMotion.tokens.durations.medium))
            ) {
                AddonFilterChips(
                    addons = availableAddons,
                    sourceChips = sourceChips,
                    selectedAddon = selectedAddonFilter,
                    isStillFetching = sourceChips.any { it.status == SourceChipStatus.LOADING },
                    onRefresh = {
                        userMovedFromFirstResult = false
                        firstResultFocusAssigned = false
                        onRefresh()
                    },
                    onAddonSelected = { onAddonFilterSelected(it) },
                    externalFocusRequesters = chipFocusRequesters,
                    externalOrderedNames = orderedAddonNames,
                    debugTag = "StreamScreen"
                )
            }
        }

        Spacer(modifier = Modifier.height(FetchTheme.spacing.lg))

        androidx.compose.animation.AnimatedVisibility(
            visible = enter,
            enter = fadeIn(animationSpec = tween(FetchMotion.tokens.durations.fast)) +
                slideInHorizontally(
                    animationSpec = tween(FetchMotion.tokens.durations.fast),
                    initialOffsetX = { fullWidth -> (fullWidth * 0.06f).toInt() }
                ),
            exit = fadeOut(animationSpec = tween(FetchMotion.tokens.durations.quick))
        ) {
            // Content area
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(FetchTheme.radii.md))
                    // Flat and nearly opaque: no blur, so low-end boxes keep their frame budget.
                    .background(FetchTheme.colors.BackgroundElevated.copy(alpha = 0.94f)),
                contentAlignment = Alignment.Center
            ) {
                when {
                    sharedStream == null && isLoading -> {
                        LoadingState(showAddonLogo = showAddonLogo)
                    }
                    sharedStream == null && error != null -> {
                        ErrorState(
                            message = error,
                            onRetry = onRetry
                        )
                    }
                    sharedStream == null && streams.isEmpty() -> {
                        EmptyScreenState(
                            title = stringResource(R.string.stream_no_streams),
                            subtitle = stringResource(R.string.stream_no_streams_hint),
                            icon = Icons.Default.Extension,
                            // With an addon filter on, other addons may still have streams.
                            action = if (selectedAddonFilter == null) {
                                StateAction(stringResource(R.string.empty_state_add_addon), onNavigateToAddons)
                            } else {
                                null
                            }
                        )
                    }
                    else -> {
                        StreamsList(
                            streams = streams,
                            streamExclusions = streamExclusions,
                            sections = sections,
                            torrentsNeedExternalPlayer = torrentsNeedExternalPlayer,
                            sharedStream = sharedStream,
                            partySource = partySource,
                            onSharedStreamSelected = onSharedStreamSelected,
                            onStreamSelected = onStreamSelected,
                            focusedStreamIndex = focusedStreamIndex,
                            shouldRestoreFocusedStream = shouldRestoreFocusedStream,
                            onRestoreFocusedStreamHandled = onRestoreFocusedStreamHandled,
                            firstStreamFocusRequestId = firstStreamFocusRequestId,
                            availableAddons = availableAddons,
                            selectedAddonFilter = selectedAddonFilter,
                            showFileSizeBadges = showFileSizeBadges,
                            showAddonLogo = showAddonLogo,
                            badgePlacement = badgePlacement,
                            hasBadgeRules = hasBadgeRules,
                            onAddonFilterSelected = { onAddonFilterSelectedGuarded(it) },
                            orderedAddonNames = orderedAddonNames,
                            onRequestChipFocus = { requestChipFocus(it) },
                            onUserNavigatedFromFirstResult = {
                                userMovedFromFirstResult = true
                            },
                            onFocusedStreamKeyChanged = { focusedStreamKeyInList = it },
                            onFocusChanged = { listHasFocus = it },
                            onExpandStreams = onExpandStreams,
                            sourceRuleOptions = sourceRuleOptions,
                            onSourceOptions = onSourceOptions
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LoadingState(showAddonLogo: Boolean = true) {
    StreamsSkeletonList(showAddonLogo = showAddonLogo)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun StreamsList(
    streams: List<Stream>,
    streamExclusions: Map<String, ExclusionReason>,
    /** The list's quality sections, from [sourceSections]. */
    sections: List<SourceSection>,
    torrentsNeedExternalPlayer: Boolean,
    sharedStream: SharedStream?,
    partySource: PartySource?,
    onSharedStreamSelected: (Stream) -> Unit,
    onStreamSelected: (Stream) -> Unit,
    focusedStreamIndex: Int = 0,
    shouldRestoreFocusedStream: Boolean = false,
    onRestoreFocusedStreamHandled: () -> Unit = {},
    firstStreamFocusRequestId: Int = 0,
    availableAddons: List<String> = emptyList(),
    selectedAddonFilter: String? = null,
    showFileSizeBadges: Boolean = true,
    showAddonLogo: Boolean = true,
    badgePlacement: StreamBadgePlacement = StreamBadgePlacement.BOTTOM,
    hasBadgeRules: Boolean = false,
    onAddonFilterSelected: (String?) -> Unit = {},
    orderedAddonNames: List<String> = emptyList(),
    onRequestChipFocus: (Int) -> Unit = {},
    onUserNavigatedFromFirstResult: () -> Unit = {},
    onFocusedStreamKeyChanged: (String?) -> Unit = {},
    onFocusChanged: (Boolean) -> Unit = {},
    onExpandStreams: () -> Unit = {},
    /** The rules a source's long-press menu offers; empty means the card ignores a long press. */
    sourceRuleOptions: (Stream) -> List<SourceRule> = { emptyList() },
    onSourceOptions: (Stream, List<SourceRule>) -> Unit = { _, _ -> }
) {
    val isRtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val lastKeyRepeatDispatchRef = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    val restoreFocusRequester = remember { FocusRequester() }
    val streamListState = rememberLazyListState()
    val streamKeys = remember(streams) {
        val seen = mutableMapOf<String, Int>()
        streams.map { stream ->
            val base = stream.stableKey(0)
            val occurrence = seen.getOrDefault(base, 0)
            seen[base] = occurrence + 1
            stream.stableKey(occurrence)
        }
    }
    // A single section needs no header: the list is already what it says.
    val showSectionHeaders = sections.size > 1
    val firstShownIndex = sections.firstShownIndex() ?: 0
    // Where each stream sits in the lazy list, counting the party card and section headers.
    val lazyIndexOfStream = remember(sections, showSectionHeaders, sharedStream != null) {
        val positions = IntArray(streams.size)
        var next = if (sharedStream != null) 1 else 0
        sections.forEach { section ->
            if (showSectionHeaders) next++
            section.indices.forEach { positions[it] = next++ }
        }
        positions
    }
    val firstStreamKey = streamKeys.getOrNull(firstShownIndex)
    val streamFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    remember(streamKeys) {
        val validKeys = streamKeys.toHashSet()
        streamFocusRequesters.keys.retainAll(validKeys)
    }
    var firstCardHasFocus by remember(firstStreamKey) { mutableStateOf(false) }
    val sharedFocusRequester = remember { FocusRequester() }
    var sharedCardHasFocus by remember { mutableStateOf(false) }
    val partySourceLabel = stringResource(R.string.party_source)

    var focusedStreamKey by remember { mutableStateOf<String?>(null) }
    val prevStreamKeysRef = remember { mutableStateOf(streamKeys) }
    val prevLazyIndexRef = remember { mutableStateOf(lazyIndexOfStream) }

    LaunchedEffect(streamKeys, lazyIndexOfStream) {
        val prevKeys = prevStreamKeysRef.value
        val prevLazyIndex = prevLazyIndexRef.value
        prevStreamKeysRef.value = streamKeys
        prevLazyIndexRef.value = lazyIndexOfStream
        val key = focusedStreamKey
        // Sections can move a card without the keys changing, when Best match's exclusions do.
        if (key != null && (prevKeys !== streamKeys || prevLazyIndex !== lazyIndexOfStream)) {
            val oldIndex = prevKeys.indexOf(key).let { if (it >= 0) prevLazyIndex.getOrElse(it) { -1 } else -1 }
            val newIndex = streamKeys.indexOf(key).let { if (it >= 0) lazyIndexOfStream.getOrElse(it) { -1 } else -1 }
            if (oldIndex >= 0 && newIndex >= 0 && oldIndex != newIndex && oldIndex > 0) {
                val shift = newIndex - oldIndex
                val correctedFirst = (streamListState.firstVisibleItemIndex + shift)
                    .coerceIn(0, (streamListState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
                try {
                    streamListState.scrollToItem(correctedFirst, streamListState.firstVisibleItemScrollOffset)
                } catch (_: Exception) { }
                withFrameNanos { }
                runCatching { streamFocusRequesters[key]?.requestFocus() }
            }
        }
    }

    // Reset scroll position to the top when the addon filter changes (#2538).
    LaunchedEffect(selectedAddonFilter) {
        streamListState.scrollToItem(0)
    }

    LaunchedEffect(firstStreamFocusRequestId) {
        val requestedKey = firstStreamKey
        if (firstStreamFocusRequestId <= 0 || requestedKey == null) return@LaunchedEffect
        streamListState.scrollToItem(0)
        repeat(30) {
            withFrameNanos { }
            if (firstCardHasFocus || sharedCardHasFocus) return@LaunchedEffect
            runCatching {
                if (sharedStream != null) sharedFocusRequester.requestFocus()
                else streamFocusRequesters.getValue(requestedKey).requestFocus()
            }
        }
    }

    val sharedStreamUrl = sharedStream?.stream?.url
    LaunchedEffect(sharedStreamUrl) {
        if (sharedStreamUrl == null || shouldRestoreFocusedStream) return@LaunchedEffect
        streamListState.scrollToItem(0)
        repeat(30) {
            withFrameNanos { }
            if (sharedCardHasFocus) return@LaunchedEffect
            runCatching { sharedFocusRequester.requestFocus() }
        }
    }

    LaunchedEffect(shouldRestoreFocusedStream, focusedStreamIndex, streams.size) {
        if (!shouldRestoreFocusedStream) return@LaunchedEffect
        val targetIndex = sourceSelectionRestoreTarget(focusedStreamIndex, streams.size)
        if (targetIndex == null) {
            onRestoreFocusedStreamHandled()
            return@LaunchedEffect
        }
        repeat(2) { withFrameNanos { } }
        try {
            streamListState.scrollToItem(lazyIndexOfStream.getOrElse(targetIndex) { targetIndex })
            withFrameNanos { }
            restoreFocusRequester.requestFocus()
        } catch (_: Exception) {
        }
        onRestoreFocusedStreamHandled()
    }

    // Load more streams when scrolling near the bottom of the current page.
    val lastVisibleIndex = remember(streamListState) {
        androidx.compose.runtime.derivedStateOf {
            streamListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        }
    }
    LaunchedEffect(lastVisibleIndex.value, streams.size) {
        if (lastVisibleIndex.value >= streamListState.layoutInfo.totalItemsCount - 20) {
            onExpandStreams()
        }
    }

    LazyColumn(
        state = streamListState,
        modifier = Modifier
            .fillMaxSize()
            .padding(FetchTheme.spacing.lg)
            .onFocusChanged { onFocusChanged(it.hasFocus) }
            .onKeyEvent { event ->
                if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false

                // Throttle rapid key repeats (long-press)
                if (event.nativeKeyEvent.repeatCount > 0) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastKeyRepeatDispatchRef.get() < 112L) return@onKeyEvent true
                    lastKeyRepeatDispatchRef.set(now)
                }
                if (event.key == Key.DirectionDown) {
                    onUserNavigatedFromFirstResult()
                }
                if (orderedAddonNames.isEmpty()) return@onKeyEvent false
                val allOptions = listOf<String?>(null) + orderedAddonNames
                val currentIdx = allOptions.indexOf(selectedAddonFilter)
                when (event.key) {
                    Key.DirectionLeft -> {
                        if (isRtl) {
                            if (currentIdx < allOptions.lastIndex) { onAddonFilterSelected(allOptions[currentIdx + 1]); true } else true
                        } else {
                            if (currentIdx > 0) { onAddonFilterSelected(allOptions[currentIdx - 1]); true }
                            else { true }
                        }
                    }
                    Key.DirectionRight -> {
                        if (isRtl) {
                            if (currentIdx > 0) { onAddonFilterSelected(allOptions[currentIdx - 1]); true }
                            else { true }
                        } else {
                            if (currentIdx < allOptions.lastIndex) { onAddonFilterSelected(allOptions[currentIdx + 1]); true } else true
                        }
                    }
                    else -> false
                }
            },
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md),
        contentPadding = PaddingValues(start = FetchTheme.spacing.sm, end = FetchTheme.spacing.sm, top = FetchTheme.spacing.sm, bottom = FetchTheme.spacing.xxl)
    ) {
        sharedStream?.let { shared ->
            item(key = "party_shared_stream") {
                Column(
                    modifier = Modifier.padding(vertical = FetchTheme.spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)
                ) {
                    Text(
                        text = stringResource(R.string.party_shared_by, shared.hostName),
                        style = MaterialTheme.typography.labelLarge,
                        color = FetchTheme.colors.TextSecondary,
                        modifier = Modifier.padding(start = FetchTheme.spacing.xs)
                    )
                    StreamCard(
                        stream = shared.stream,
                        showFileSizeBadges = showFileSizeBadges,
                        showAddonLogo = showAddonLogo,
                        badgePlacement = badgePlacement,
                        onClick = { onSharedStreamSelected(shared.stream) },
                        focusRequester = sharedFocusRequester,
                        onFocusChanged = { focused -> sharedCardHasFocus = focused },
                        onUpKey = {
                            val idx = if (selectedAddonFilter == null) 1
                                      else orderedAddonNames.indexOf(selectedAddonFilter) + 2
                            onRequestChipFocus(idx)
                        },
                        marked = true
                    )
                }
            }
        }
        sections.forEach { section ->
        if (showSectionHeaders) {
            item(key = "source_group_${section.group}", contentType = "source_group_header") {
                SourceGroupHeader(group = section.group, count = section.indices.size)
            }
        }
        items(section.indices, key = { index -> streamKeys[index] }) { index ->
            val stream = streams[index]
            Box(modifier = Modifier.padding(vertical = FetchTheme.spacing.xs)) {
                val isPartySource = partySource?.matches(stream) == true
                StreamCard(
                    stream = stream,
                    showFileSizeBadges = showFileSizeBadges,
                    showAddonLogo = showAddonLogo,
                    badgePlacement = badgePlacement,
                    reserveBadgeSpace = hasBadgeRules && stream.badges.isEmpty(),
                    marked = isPartySource,
                    markedLabel = partySourceLabel.takeIf { isPartySource },
                    exclusion = if (streamExclusions.isEmpty()) null else streamExclusions[stream.stableKey()],
                    needsExternalPlayer = torrentsNeedExternalPlayer && stream.isTorrent(),
                    onClick = { onStreamSelected(stream) },
                    onLongClick = {
                        val options = sourceRuleOptions(stream)
                        if (options.isNotEmpty()) onSourceOptions(stream, options)
                    },
                    focusRequester = when {
                        shouldRestoreFocusedStream && index == focusedStreamIndex.coerceIn(0, (streams.lastIndex).coerceAtLeast(0)) -> restoreFocusRequester
                        else -> streamFocusRequesters.getOrPut(streamKeys[index]) { FocusRequester() }
                    },
                    onFocusChanged = { focused ->
                        if (focused) {
                            focusedStreamKey = streamKeys.getOrNull(index)
                            onFocusedStreamKeyChanged(focusedStreamKey)
                        }
                        if (index == firstShownIndex) {
                            firstCardHasFocus = focused
                        }
                    },
                    onUpKey = if (index == firstShownIndex && sharedStream == null) {{
                        val idx = if (selectedAddonFilter == null) 1
                                  else orderedAddonNames.indexOf(selectedAddonFilter) + 2
                        onRequestChipFocus(idx)
                    }} else null
                )
            }
        }
        }
    }
}

/** A quality section's title and how many sources it holds. Not focusable: D-pad skips it. */
@Composable
private fun SourceGroupHeader(group: SourceGroup, count: Int) {
    val title = when (group) {
        SourceGroup.BEST_MATCH -> stringResource(R.string.autoplay_mode_best)
        SourceGroup.UHD -> com.fetch.tv.core.streams.ranking.StreamResolution.UHD_2160.label
        SourceGroup.FHD -> com.fetch.tv.core.streams.ranking.StreamResolution.FHD_1080.label
        SourceGroup.OTHER -> stringResource(R.string.source_group_other)
        SourceGroup.PASSED_OVER -> stringResource(R.string.source_group_passed_over)
    }
    // More space above than below, so a header reads as the start of its section.
    Row(
        modifier = Modifier.padding(start = FetchTheme.spacing.xs, top = FetchTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = FetchTheme.colors.TextPrimary
        )
        Text(
            text = count.toString(),
            style = FetchTheme.textStyles.spec,
            color = FetchTheme.colors.TextTertiary
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun StreamCard(
    stream: Stream,
    showFileSizeBadges: Boolean,
    showAddonLogo: Boolean,
    badgePlacement: StreamBadgePlacement,
    reserveBadgeSpace: Boolean = false,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    onFocusChanged: ((Boolean) -> Unit)? = null,
    onUpKey: (() -> Unit)? = null,
    marked: Boolean = false,
    markedLabel: String? = null,
    exclusion: ExclusionReason? = null,
    needsExternalPlayer: Boolean = false,
    onLongClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val unknownStreamLabel = stringResource(R.string.stream_unknown)
    val streamDescription = remember(stream) { stream.getDisplayDescription() }
    // Parsed from the addon's free-form text: the card leads with these chips and keeps the raw
    // release name for the focused card, where there is room to read it.
    val descriptor = remember(stream) { StreamTitleParser.parse(stream) }
    val headline = descriptor.resolution.label.takeIf { it.isNotEmpty() }
    val specChips = remember(descriptor) {
        descriptor.summaryLabels().filterNot { it == descriptor.resolution.label }
    }
    val cachedLabel = stringResource(R.string.best_match_cached)
    val hasParsedSpec = headline != null || specChips.isNotEmpty()
    // With chips shown, a name line like "4k DV" only repeats them.
    val streamName = remember(stream, unknownStreamLabel, hasParsedSpec) {
        val name = stream.getDisplayNameOrNull() ?: unknownStreamLabel
        if (hasParsedSpec) withoutSpecOnlyLines(name) else name
    }
    val hasBadges = stream.badges.isNotEmpty() || (showFileSizeBadges && stream.behaviorHints?.videoSize != null) || reserveBadgeSpace
    val cardShape = RoundedCornerShape(FetchTheme.radii.md)

    var isFocused by remember { mutableStateOf(false) }

    // Track whether badges transitioned from empty to non-empty while this
    // card was composed. If they did, we animate. If the card enters
    // composition with badges already present (tab switch), no animation.
    val hadBadgesOnFirstComposition = remember { stream.badges.isNotEmpty() }
    val shouldAnimateBadges = stream.badges.isNotEmpty() && !hadBadgesOnFirstComposition
    // Pre-upscale: decode at 2× target pixels so the hardware compositor
    // has enough pixel data for smooth edges inside Card RenderNodes.
    val logoDecodeSize = remember(density) {
        with(density) { FetchTheme.spacing.xxl.roundToPx() } * 2
    }
    val addonLogoModel = remember(context, stream.addonLogo, logoDecodeSize) {
        stream.addonLogo?.let { logo ->
            ImageRequest.Builder(context)
                .data(logo)
                .size(width = logoDecodeSize, height = logoDecodeSize)
                .memoryCacheKey("${logo}_${logoDecodeSize}x${logoDecodeSize}")
                .crossfade(false)
                .build()
        }
    }

    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                isFocused = it.isFocused
                onFocusChanged?.invoke(it.isFocused)
            }
            .then(if (onUpKey != null) Modifier.onKeyEvent { event ->
                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && event.key == Key.DirectionUp) {
                    onUpKey(); true
                } else false
            } else Modifier),
        colors = CardDefaults.colors(
            containerColor = FetchTheme.colors.BackgroundElevated,
            focusedContainerColor = FetchTheme.colors.BackgroundElevated
        ),
        shape = CardDefaults.shape(shape = cardShape),
        border = CardDefaults.border(
            border = markedBorder(marked, cardShape),
            focusedBorder = Border(
                border = FetchTheme.focusRing.border(FetchTheme.strokes.focus),
                shape = cardShape
            )
        ),
        scale = CardDefaults.scale(focusedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(FetchTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.lg)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xs)
            ) {
                if (hasBadges && badgePlacement == StreamBadgePlacement.TOP) {
                    if (stream.badges.isNotEmpty() || (showFileSizeBadges && stream.behaviorHints?.videoSize != null)) {
                        StreamBadgeChips(
                            badges = stream.badges,
                            fileSizeBytes = stream.behaviorHints?.videoSize,
                            showFileSizeBadge = showFileSizeBadges,
                            animate = shouldAnimateBadges,
                            focused = isFocused
                        )
                    } else {
                        Spacer(modifier = Modifier.height(20.dp))
                    }
                    Spacer(modifier = Modifier.height(FetchTheme.spacing.xxs))
                }

                if (hasParsedSpec) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xs),
                        itemVerticalAlignment = Alignment.CenterVertically
                    ) {
                        headline?.let { label ->
                            Text(
                                text = label,
                                style = MaterialTheme.typography.headlineSmall,
                                color = FetchTheme.colors.TextPrimary,
                                modifier = Modifier.padding(end = FetchTheme.spacing.xs)
                            )
                        }
                        // Cached comes first: it matters most, and the last chip is the one
                        // that wraps when the row runs out of width.
                        if (descriptor.isDebridCached) StreamSpecChip(cachedLabel, highlighted = true)
                        specChips.forEach { chip -> StreamSpecChip(chip) }
                        // The addon ends the strip rather than taking a line of its own.
                        if (showAddonLogo && stream.addonName.isNotBlank()) {
                            Text(
                                text = stream.addonName,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    textDirection = stream.addonName.contentTextDirection()
                                ),
                                color = FetchTheme.extendedColors.textTertiary,
                                maxLines = 1,
                                modifier = Modifier.padding(start = FetchTheme.spacing.xs)
                            )
                        }
                    }
                }
                exclusion?.let { reason ->
                    Text(
                        text = stringResource(R.string.best_match_excluded, reason.label(context, descriptor)),
                        style = MaterialTheme.typography.bodySmall,
                        color = FetchTheme.colors.TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // Fetch has no torrent engine, so a torrent can only open in an external player.
                // Say so here rather than failing once it is picked.
                if (needsExternalPlayer) {
                    Text(
                        text = stringResource(R.string.stream_torrent_external_player),
                        style = MaterialTheme.typography.bodySmall,
                        color = FetchTheme.colors.TextTertiary,
                        maxLines = if (isFocused) Int.MAX_VALUE else 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // With the spec strip shown, the addon's name line mostly repeats it, so it joins
                // the release name on the focused card. A party mark keeps the line visible.
                if (!hasParsedSpec || isFocused || markedLabel != null) Row(
                    modifier = Modifier.align(streamName.contentTextDirection().toAbsoluteAlignment()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)
                ) {
                    Text(
                        text = streamName,
                        modifier = Modifier.weight(1f, fill = false),
                        style = if (hasParsedSpec) {
                            MaterialTheme.typography.bodySmall.copy(textDirection = streamName.contentTextDirection())
                        } else {
                            MaterialTheme.typography.titleMedium.copy(textDirection = streamName.contentTextDirection())
                        },
                        color = if (hasParsedSpec) FetchTheme.extendedColors.textSecondary else FetchTheme.colors.TextPrimary,
                        maxLines = if (hasParsedSpec && !isFocused) 1 else Int.MAX_VALUE,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (markedLabel != null) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(FetchRadii.tokens.full))
                                .background(FetchTheme.colors.Primary.copy(alpha = 0.2f))
                                .padding(horizontal = FetchTheme.spacing.sm, vertical = FetchTheme.spacing.xs)
                        ) {
                            Text(
                                text = markedLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = FetchTheme.colors.Primary
                            )
                        }
                    }
                }

                streamDescription?.let { description ->
                    // The parsed chips already say what the description spells out; show the
                    // addon's own text only on the focused card.
                    if (description.isNotBlank() && description != streamName && (!hasParsedSpec || isFocused)) {
                        Text(
                            text = description,
                            modifier = Modifier.align(description.contentTextDirection().toAbsoluteAlignment()),
                            style = MaterialTheme.typography.bodySmall.copy(
                                textDirection = description.contentTextDirection()
                            ),
                            color = FetchTheme.extendedColors.textSecondary
                        )
                    }
                }

                if (hasBadges && badgePlacement == StreamBadgePlacement.BOTTOM) {
                    if (stream.badges.isNotEmpty() || (showFileSizeBadges && stream.behaviorHints?.videoSize != null)) {
                        StreamBadgeChips(
                            badges = stream.badges,
                            fileSizeBytes = stream.behaviorHints?.videoSize,
                            showFileSizeBadge = showFileSizeBadges,
                            animate = shouldAnimateBadges,
                            focused = isFocused,
                            modifier = Modifier.padding(top = FetchTheme.spacing.xxs)
                        )
                    } else {
                        Spacer(modifier = Modifier.height(22.dp))
                    }
                }
            }

            if (showAddonLogo) {
                Column(
                    horizontalAlignment = Alignment.End
                ) {
                    if (addonLogoModel != null) {
                        AsyncImage(
                            model = addonLogoModel,
                            contentDescription = stream.addonName,
                            modifier = Modifier
                                .size(FetchTheme.spacing.xxl)
                                .clip(RoundedCornerShape(FetchTheme.radii.xs)),
                            contentScale = ContentScale.Fit
                        )
                    }

                    // The spec strip names the addon when there is one; otherwise it stays here.
                    if (!hasParsedSpec) {
                    Spacer(modifier = Modifier.height(FetchTheme.spacing.xs))

                    Text(
                        text = stream.addonName,
                        style = MaterialTheme.typography.labelSmall.copy(
                            textDirection = stream.addonName.contentTextDirection()
                        ),
                        color = FetchTheme.extendedColors.textTertiary,
                        maxLines = 1
                    )
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamSpecChip(text: String, highlighted: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        color = if (highlighted) FetchTheme.colors.OnHighlight else FetchTheme.colors.TextPrimary,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(FetchTheme.radii.xs))
            .background(if (highlighted) FetchTheme.colors.Highlight else FetchTheme.colors.BackgroundCard)
            .padding(horizontal = FetchTheme.spacing.sm, vertical = FetchTheme.spacing.xxs)
    )
}

@Composable
private fun markedBorder(marked: Boolean, shape: RoundedCornerShape): Border =
    if (marked) {
        Border(
            border = BorderStroke(FetchTheme.spacing.hairline, FetchTheme.colors.Primary.copy(alpha = 0.65f)),
            shape = shape
        )
    } else {
        Border.None
    }

@Composable
internal fun PlayerChoiceDialog(
    onInternalSelected: () -> Unit,
    onExternalSelected: () -> Unit,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(FetchTheme.radii.xl))
                .background(FetchTheme.colors.BackgroundCard)
        ) {
            Column(
                modifier = Modifier
                    .width(400.dp)
                    .padding(FetchTheme.spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.stream_player_picker_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = FetchTheme.colors.TextPrimary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(FetchTheme.spacing.xl))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.lg),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    var internalFocused by remember { mutableStateOf(false) }
                    Card(
                        onClick = onInternalSelected,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester)
                            .onFocusChanged { internalFocused = it.isFocused },
                        colors = CardDefaults.colors(
                            containerColor = FetchTheme.colors.BackgroundElevated,
                            focusedContainerColor = FetchTheme.colors.Secondary
                        ),
                        border = CardDefaults.border(
                            focusedBorder = Border(
                                border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs),
                                shape = RoundedCornerShape(FetchTheme.radii.md)
                            )
                        ),
                        shape = CardDefaults.shape(shape = RoundedCornerShape(FetchTheme.radii.md)),
                        scale = CardDefaults.scale(focusedScale = 1.05f)
                    ) {
                        Text(
                            text = stringResource(R.string.stream_player_internal),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (internalFocused) FetchTheme.colors.OnSecondary else FetchTheme.colors.TextPrimary,
                            modifier = Modifier
                                .padding(horizontal = FetchTheme.spacing.lg, vertical = 14.dp)
                                .fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    }

                    var externalFocused by remember { mutableStateOf(false) }
                    Card(
                        onClick = onExternalSelected,
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { externalFocused = it.isFocused },
                        colors = CardDefaults.colors(
                            containerColor = FetchTheme.colors.BackgroundElevated,
                            focusedContainerColor = FetchTheme.colors.Secondary
                        ),
                        border = CardDefaults.border(
                            focusedBorder = Border(
                                border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs),
                                shape = RoundedCornerShape(FetchTheme.radii.md)
                            )
                        ),
                        shape = CardDefaults.shape(shape = RoundedCornerShape(FetchTheme.radii.md)),
                        scale = CardDefaults.scale(focusedScale = 1.05f)
                    ) {
                        Text(
                            text = stringResource(R.string.stream_player_external),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (externalFocused) FetchTheme.colors.OnSecondary else FetchTheme.colors.TextPrimary,
                            modifier = Modifier
                                .padding(horizontal = FetchTheme.spacing.lg, vertical = 14.dp)
                                .fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}
