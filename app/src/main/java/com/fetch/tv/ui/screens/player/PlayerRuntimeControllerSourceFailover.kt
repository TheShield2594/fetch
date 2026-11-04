package com.fetch.tv.ui.screens.player

import android.util.Log
import com.fetch.tv.R
import com.fetch.tv.core.player.StreamAutoPlaySelector
import com.fetch.tv.data.local.PlayerSettings
import com.fetch.tv.domain.model.Stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
internal fun PlayerRuntimeController.tryNextSourceAfterFailure(showError: () -> Unit): Boolean {
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
        val label = (next.name?.takeIf { it.isNotBlank() } ?: next.addonName).trim()
        Log.i(PlayerRuntimeController.TAG, "Stream failed to open; trying the next source: $label")
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
        switchToSourceStream(next)
        hidePlayerEngineSwitchInfoJob = scope.launch {
            delay(2600)
            _uiState.update { state -> state.copy(showPlayerEngineSwitchInfo = false) }
        }
    }
    return true
}

/**
 * The next source to play, ready to open: a media-server or YouTube stream is prepared here,
 * and one that can't be is counted as failed and passed over. Null when nothing is left.
 */
private suspend fun PlayerRuntimeController.nextPlayableSource(
    request: SourceStreamsRequest,
    requestKey: String
): Stream? {
    val settings = playerSettingsDataStore.playerSettings.first()
    val installedAddonNames = streamAddonsFor(request.videoId).map { it.displayName }.toSet()
    var streams = streamsForSourceFailover(request, requestKey, allowPartial = true)
    var complete = false
    while (failedSourceKeys.size < MAX_NEXT_SOURCE_ATTEMPTS) {
        val next = pickNextSource(streams, settings, installedAddonNames)
        if (next == null) {
            // The cached answers may predate slower addons: ask them all once before giving up.
            if (complete) return null
            complete = true
            streams = streamsForSourceFailover(request, requestKey, allowPartial = false)
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
    allowPartial: Boolean
): List<Stream> {
    val state = _uiState.value
    if (requestKey == sourceStreamsCacheRequestKey && sourceStreamsFetchCompleted && state.sourceAllStreams.isNotEmpty()) {
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
    loadSourceStreams(forceRefresh = false)
    withTimeoutOrNull(SOURCE_LOOKUP_TIMEOUT_MS) { sourceStreamsJob?.join() }
    return if (sourceStreamsCacheRequestKey == requestKey) _uiState.value.sourceAllStreams else emptyList()
}
