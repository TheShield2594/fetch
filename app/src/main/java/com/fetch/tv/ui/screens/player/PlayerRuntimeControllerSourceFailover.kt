package com.fetch.tv.ui.screens.player

import android.util.Log
import com.fetch.tv.R
import com.fetch.tv.core.network.NetworkResult
import com.fetch.tv.core.player.StreamAutoPlaySelector
import com.fetch.tv.data.local.PlayerSettings
import com.fetch.tv.domain.model.Stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Streams tried automatically for one title before the error is left on screen. */
private const val MAX_NEXT_SOURCE_ATTEMPTS = 5

/** How long to wait for the addons when the title's sources aren't known yet. */
private const val SOURCE_LOOKUP_TIMEOUT_MS = 30_000L

/**
 * "Try the next stream when one fails": the stream on screen never played (the other player
 * and the retries didn't help either), so the next one in the ranking plays instead, as the
 * desktop app does. Returns false when it doesn't apply, and the caller shows its error now.
 * Returning true, it looks the sources up and runs [showError] itself if nothing is left.
 */
internal fun PlayerRuntimeController.tryNextSourceAfterFailure(
    showError: () -> Unit,
    httpStatus: Int? = null,
    isSourceError: Boolean = false
): Boolean {
    // A stream that had started playing, or a replacement for one, recovers at its position.
    if (tryRecoverAfterPlaybackStarted(httpStatus, isSourceError, showError)) return true
    if (!nextSourceOnFailureEnabled) return false
    // Only a stream that never started: one that fails mid-film keeps its error and position.
    // A retry re-creates the player and clears hasRenderedFirstFrame, so the url is checked too.
    if (hasRenderedFirstFrame || firstFramePlayedUrl == currentStreamUrl) return false
    if (nextSourceJob?.isActive == true) return true
    val request = currentSourceStreamsRequest() ?: return false
    val requestKey = buildSourceRequestKey(request.type, request.videoId, request.season, request.episode)
    if (failedSourcesRequestKey != requestKey) {
        failedSourcesRequestKey = requestKey
        failedSourceKeys.clear()
        failedSourceUrls.clear()
    }
    val failedUrl = currentStreamUrl
    if (failedUrl.isNotBlank()) failedSourceUrls += failedUrl
    // failedSourceKeys holds the streams this fallback has tried for the title.
    if (failedSourceKeys.size >= MAX_NEXT_SOURCE_ATTEMPTS) return false

    showRecoveryOverlay()
    nextSourceJob = scope.launch {
        val next = try {
            nextPlayableSource(request, requestKey)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(PlayerRuntimeController.TAG, "Next-source lookup failed: ${error.message}")
            null
        }
        nextSourceJob = null
        // The user switched stream or episode while the sources were looked up.
        val stillOnTitle = currentSourceStreamsRequest()?.let {
            buildSourceRequestKey(it.type, it.videoId, it.season, it.episode)
        } == requestKey
        if (currentStreamUrl != failedUrl || !stillOnTitle) return@launch
        if (next == null) {
            showError()
            return@launch
        }
        Log.i(PlayerRuntimeController.TAG, "Stream failed to open; trying the next source: ${next.sourceLabel()}")
        switchToRecoverySource(next, resumePositionMs = null)
    }
    return true
}

private fun Stream.sourceLabel(): String = (name?.takeIf { it.isNotBlank() } ?: addonName).trim()

/** Plays [next] in place of a failed stream, saying so on screen; at [resumePositionMs] when given. */
private fun PlayerRuntimeController.switchToRecoverySource(next: Stream, resumePositionMs: Long?) {
    val label = next.sourceLabel()
    hidePlayerEngineSwitchInfoJob?.cancel()
    _uiState.update {
        it.copy(
            showPlayerEngineSwitchInfo = true,
            playerEngineSwitchInfoText = if (label.isBlank()) {
                context.getString(R.string.player_next_source_trying_generic)
            } else {
                context.getString(R.string.player_next_source_trying, label)
            }
        )
    }
    // A fresh start on the user's own player, so the engine fallback applies to it again.
    startupEngineFailoverTriggered = false
    switchToSourceStream(next, resumePositionMs = resumePositionMs)
    hidePlayerEngineSwitchInfoJob = scope.launch {
        delay(2600)
        _uiState.update { state -> state.copy(showPlayerEngineSwitchInfo = false) }
    }
}

/**
 * Recovery for a stream that fails after it started playing (an expired debrid link after a long
 * pause is the usual case), at the position it reached: the same source is looked up again so its
 * addon mints a fresh link, then the next ranked sources are tried, and only then [showError] runs,
 * with Retry, Try next source and All sources on the error screen.
 *
 * Returns false when this isn't such a failure and the caller carries on. While a recovery is under
 * way for the title, any failure of a replacement moves it on to the next step.
 */
internal fun PlayerRuntimeController.tryRecoverAfterPlaybackStarted(
    httpStatus: Int?,
    isSourceError: Boolean,
    showError: () -> Unit
): Boolean {
    val request = currentSourceStreamsRequest() ?: return false
    val requestKey = buildSourceRequestKey(request.type, request.videoId, request.season, request.episode)
    val recovery = midPlaybackRecovery?.takeIf { it.requestKey == requestKey } ?: run {
        val started = hasRenderedFirstFrame || firstFramePlayedUrl == currentStreamUrl
        if (!started || !MidPlaybackRecoveryPolicy.applies(httpStatus, isSourceError)) return false
        val position = currentPlaybackPositionMs()?.takeIf { it > 0L }
            ?: _playbackTimeline.value.currentPosition.takeIf { it > 0L }
            ?: lastSavedPosition
        MidPlaybackRecovery(requestKey = requestKey, positionMs = position.coerceAtLeast(0L))
    }
    midPlaybackRecovery = recovery
    if (nextSourceJob?.isActive == true) return true
    continueMidPlaybackRecovery(request, recovery, showError)
    return true
}

private fun PlayerRuntimeController.continueMidPlaybackRecovery(
    request: SourceStreamsRequest,
    recovery: MidPlaybackRecovery,
    showError: () -> Unit
) {
    val step = MidPlaybackRecoveryPolicy.nextStep(
        refreshTried = recovery.refreshTried,
        nextSourcesTried = recovery.nextSourcesTried,
        nextSourceEnabled = nextSourceOnFailureEnabled
    )
    if (step == MidPlaybackRecoveryPolicy.Step.SHOW_ERROR) {
        showMidPlaybackError(showError)
        return
    }
    val advanced = if (step == MidPlaybackRecoveryPolicy.Step.REFRESH_SAME_SOURCE) {
        recovery.copy(refreshTried = true)
    } else {
        recovery.copy(nextSourcesTried = recovery.nextSourcesTried + 1)
    }
    midPlaybackRecovery = advanced
    rememberFailedSource(advanced.requestKey)
    val failedUrl = currentStreamUrl
    showRecoveryOverlay()
    nextSourceJob = scope.launch {
        val replacement = try {
            if (step == MidPlaybackRecoveryPolicy.Step.REFRESH_SAME_SOURCE) {
                refreshedSameSource(request)
            } else {
                // The refresh just asked every addon past the cache, so their cached answers are fresh.
                nextPlayableSource(request, advanced.requestKey, skipPanelList = true)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(PlayerRuntimeController.TAG, "Mid-playback recovery lookup failed: ${error.message}")
            null
        }
        nextSourceJob = null
        // The user switched stream or title while the sources were looked up.
        if (currentStreamUrl != failedUrl || midPlaybackRecovery !== advanced) return@launch
        if (replacement == null) {
            continueMidPlaybackRecovery(request, advanced, showError)
            return@launch
        }
        Log.i(
            PlayerRuntimeController.TAG,
            "Stream failed mid-playback at ${advanced.positionMs}ms; step=$step source=${replacement.sourceLabel()}"
        )
        switchToRecoverySource(replacement, resumePositionMs = advanced.positionMs)
    }
}

/** Counts the stream on screen as failed for the title, so the next-source pick passes over it. */
private fun PlayerRuntimeController.rememberFailedSource(requestKey: String) {
    if (failedSourcesRequestKey != requestKey) {
        failedSourcesRequestKey = requestKey
        failedSourceKeys.clear()
        failedSourceUrls.clear()
    }
    currentStreamUrl.takeIf { it.isNotBlank() }?.let { failedSourceUrls += it }
}

private fun PlayerRuntimeController.showMidPlaybackError(showError: () -> Unit) {
    showError()
    _uiState.update { it.copy(showErrorRecoveryActions = true) }
}

/**
 * The failed stream, freshly answered by its addon (which mints a new link), ready to open.
 * Asks the addons again past every cache and stops at the first answer that lists it; null when
 * none does in time.
 */
private suspend fun PlayerRuntimeController.refreshedSameSource(request: SourceStreamsRequest): Stream? {
    val state = _uiState.value
    val addonName = state.currentStreamAddonName ?: currentAddonName
    val name = state.currentStreamName
    val description = currentStreamDescription
    val infoHash = state.currentStreamInfoHash
    val fileIdx = state.currentStreamFileIdx
    val filename = currentFilename
    val same = withTimeoutOrNull(SOURCE_LOOKUP_TIMEOUT_MS) {
        streamRepository.searchStreamsFromAllSources(
            type = request.type,
            videoId = request.videoId,
            season = request.season,
            episode = request.episode,
            forceRefresh = true
        ).mapNotNull { update ->
            val streams = (update.result as? NetworkResult.Success)?.data?.flatMap { it.streams } ?: return@mapNotNull null
            MidPlaybackRecoveryPolicy.findSameSource(streams, addonName, name, description, infoHash, fileIdx, filename)
        }.firstOrNull()
    } ?: return null
    return when {
        same.serverTarget != null && same.getStreamUrl().isNullOrBlank() -> prepareServerStream(same)
        same.youTubeIdToResolve() != null -> resolveYouTubeStream(same)
        else -> same
    }
}

/** "Try next source" on the error screen: the next ranked source, at the saved position. */
internal fun PlayerRuntimeController.tryNextSourceFromError() {
    val request = currentSourceStreamsRequest() ?: return
    val requestKey = buildSourceRequestKey(request.type, request.videoId, request.season, request.episode)
    val recovery = midPlaybackRecovery?.takeIf { it.requestKey == requestKey }
        ?: MidPlaybackRecovery(
            requestKey = requestKey,
            positionMs = (_playbackTimeline.value.currentPosition.takeIf { it > 0L } ?: lastSavedPosition).coerceAtLeast(0L),
            refreshTried = true
        )
    nextSourceJob?.cancel()
    nextSourceJob = null
    rememberFailedSource(requestKey)
    // Each press asks for one more source: the automatic limit doesn't apply to the user's own pick.
    val manual = recovery.copy(refreshTried = true)
    midPlaybackRecovery = manual
    _uiState.update { it.copy(error = null, showErrorRecoveryActions = false) }
    showRecoveryOverlay()
    val failedUrl = currentStreamUrl
    nextSourceJob = scope.launch {
        val next = try {
            nextPlayableSource(request, requestKey, skipPanelList = true)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(PlayerRuntimeController.TAG, "Next-source lookup failed: ${error.message}")
            null
        }
        nextSourceJob = null
        if (currentStreamUrl != failedUrl || midPlaybackRecovery !== manual) return@launch
        if (next == null) {
            _uiState.update {
                it.copy(
                    error = context.getString(R.string.player_error_no_more_sources),
                    showErrorRecoveryActions = true,
                    isBuffering = false,
                    showLoadingOverlay = false,
                    showPauseOverlay = false
                )
            }
            return@launch
        }
        switchToRecoverySource(next, resumePositionMs = manual.positionMs.takeIf { it > 0L })
    }
}

/** Playback is healthy again: a later failure starts a new recovery from its own position. */
internal fun PlayerRuntimeController.clearMidPlaybackRecovery() {
    midPlaybackRecovery = null
}

/**
 * The next source to play, ready to open: a media-server or YouTube stream is prepared here,
 * and one that can't be is counted as failed and passed over. Null when nothing is left.
 */
private suspend fun PlayerRuntimeController.nextPlayableSource(
    request: SourceStreamsRequest,
    requestKey: String,
    /** Mid-playback the sources panel's list may be hours old, its links expired like the one that failed. */
    skipPanelList: Boolean = false
): Stream? {
    val settings = playerSettingsDataStore.playerSettings.first()
    val installedAddonNames = streamAddonsFor(request.videoId).map { it.displayName }.toSet()
    var streams = streamsForSourceFailover(request, requestKey, allowPartial = true, skipPanelList = skipPanelList)
    var complete = false
    while (failedSourceKeys.size < MAX_NEXT_SOURCE_ATTEMPTS) {
        val next = pickNextSource(streams, settings, installedAddonNames)
        if (next == null) {
            // The cached answers may predate slower addons: ask them all once before giving up.
            if (complete) return null
            complete = true
            streams = streamsForSourceFailover(request, requestKey, allowPartial = false, skipPanelList = skipPanelList)
            continue
        }
        failedSourceKeys += next.stableKey()
        val ready = when {
            next.serverTarget != null && next.getStreamUrl().isNullOrBlank() -> prepareServerStream(next)
            next.youTubeIdToResolve() != null -> resolveYouTubeStream(next)
            else -> next
        }
        if (ready != null) return ready
    }
    return null
}

private fun PlayerRuntimeController.pickNextSource(
    streams: List<Stream>,
    settings: PlayerSettings,
    installedAddonNames: Set<String>
): Stream? = StreamAutoPlaySelector.nextAfterFailure(
    streams = streams,
    mode = settings.streamAutoPlayMode,
    installedAddonNames = installedAddonNames,
    selectedAddons = settings.streamAutoPlaySelectedAddons,
    failed = { stream -> isFailedSource(stream) },
    regexPattern = settings.streamAutoPlayRegex,
    rankingRules = settings.streamRankingRules
)

/** A stream this fallback already tried, or the one on screen (the player keeps its url normalized). */
private fun PlayerRuntimeController.isFailedSource(stream: Stream): Boolean {
    if (stream.stableKey() in failedSourceKeys) return true
    val url = stream.getStreamUrl()?.takeIf { it.isNotBlank() } ?: return false
    if (url in failedSourceUrls) return true
    val headers = PlayerMediaSourceFactory.sanitizeHeaders(stream.behaviorHints?.proxyHeaders?.request)
    return PlayerMediaSourceFactory.normalizePlaybackRequest(url, headers).url in failedSourceUrls
}

/**
 * The title's sources: the ones the sources panel already has, else (with [allowPartial]) the
 * addons' cached answers, else a lookup that waits for every addon and fills the sources panel.
 */
private suspend fun PlayerRuntimeController.streamsForSourceFailover(
    request: SourceStreamsRequest,
    requestKey: String,
    allowPartial: Boolean,
    skipPanelList: Boolean = false
): List<Stream> {
    val state = _uiState.value
    if (!skipPanelList && requestKey == sourceStreamsCacheRequestKey && sourceStreamsFetchCompleted && state.sourceAllStreams.isNotEmpty()) {
        return state.sourceAllStreams
    }
    if (allowPartial) {
        streamRepository.getCachedStreamsFromAllAddons(
            type = request.type,
            videoId = request.videoId,
            season = request.season,
            episode = request.episode
        )?.takeIf { cached -> cached.any { it.streams.isNotEmpty() } }?.let { cached ->
            val installedOrder = streamAddonsFor(request.videoId).map { it.displayName }
            val preferredServerNames = serverStreams.preferredSourceNames(request.type, request.videoId)
            return StreamAutoPlaySelector.orderAddonStreams(cached, installedOrder, preferredServerNames).flatMap { it.streams }
        }
    }
    loadSourceStreams(forceRefresh = skipPanelList)
    withTimeoutOrNull(SOURCE_LOOKUP_TIMEOUT_MS) { sourceStreamsJob?.join() }
    return if (sourceStreamsCacheRequestKey == requestKey) _uiState.value.sourceAllStreams else emptyList()
}
