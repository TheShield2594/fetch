package com.fetch.tv.ui.screens.player

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.fetch.tv.data.local.toTrackPreference

internal fun PlayerRuntimeController.preparePlaybackBeforeStart(
    url: String,
    headers: Map<String, String>,
    loadSavedProgress: Boolean
) {
    val playbackRequest = PlayerMediaSourceFactory.normalizePlaybackRequest(url, headers)
    val playbackUrl = playbackRequest.url
    val playbackHeaders = playbackRequest.headers
    if (playbackUrl != currentStreamUrl || playbackHeaders != currentHeaders) {
        currentStreamUrl = playbackUrl
        currentHeaders = playbackHeaders
        _uiState.update { it.copy(currentStreamUrl = playbackUrl) }
    }

    val clickElapsedMs = launchStartedAtElapsedMs
        ?.let { (SystemClock.elapsedRealtime() - it).coerceAtLeast(0L) }
        ?: -1L
    queuePlaybackRawEventLine(
        "PREPARE_PLAYBACK: clickElapsedMs=$clickElapsedMs host=${playbackUrl.safeLogHost()} " +
            "loadSavedProgress=$loadSavedProgress currentSeason=${currentSeason ?: -1} " +
            "currentEpisode=${currentEpisode ?: -1} streamName=${_uiState.value.currentStreamName ?: "n/a"}"
    )
    clearPendingEngineSwitchTrackPreference()
    playbackPreparationJob?.cancel()

    // Fire-and-forget: warm the Trakt episode mapping in the background.
    traktMappingJob?.cancel()
    traktMappingJob = scope.launch {
        warmTraktEpisodeMappingForCurrentPlayback()
    }

    playbackPreparationJob = scope.launch {
        setLoadingStatus(
            phase = "preparing_metadata",
            message = context.getString(com.fetch.tv.R.string.player_loading_preparing)
        )
        refreshScrobbleItem()
        if (persistedTrackPreference == null) {
            contentId?.let { id ->
                val loaded = trackPreferenceDataStore.load(id)?.toTrackPreference()
                Log.d(
                    PlayerRuntimeController.TAG,
                    "TRACK_PREF load: contentId=$id S${currentSeason}E${currentEpisode} " +
                        "result=${if (loaded == null) "null (no saved preference)" else "audio=${loaded.audio?.language}/${loaded.audio?.name} subtitle=${loaded.subtitle?.javaClass?.simpleName}"}"
                )
                persistedTrackPreference = loaded
                loadedTrackPreference = loaded
            } ?: run {
                loadedTrackPreference = null
                Log.d(PlayerRuntimeController.TAG, "TRACK_PREF load: skipped (contentId is null)")
            }
        } else {
            Log.d(
                PlayerRuntimeController.TAG,
                "TRACK_PREF load: skipped (persistedTrackPreference already set: " +
                    "audio=${persistedTrackPreference?.audio?.language}/${persistedTrackPreference?.audio?.name} " +
                    "subtitle=${persistedTrackPreference?.subtitle?.javaClass?.simpleName})"
            )
        }
        // Subtitle delay is keyed per video and release, so it is loaded on every
        // preparation (a source switch too), separately from the track selection above. This happens before
        // initializePlayer() because the renderers factory snapshots
        // subtitleDelayUs from _uiState.value.subtitleDelayMs on build.
        subtitleDelayStorageKey()?.let { vid ->
            // Apply unconditionally: with no saved value the delay goes back
            // to 0, so an earlier episode's offset can't linger here.
            val savedDelayMs = trackPreferenceDataStore.loadSubtitleDelayMs(vid) ?: 0
            subtitleDelayUs.set(savedDelayMs.toLong() * 1000L)
            _uiState.update { it.copy(subtitleDelayMs = savedDelayMs) }
            if (isUsingMpvEngine()) mpvView?.setSubtitleDelayMs(savedDelayMs)
            Log.d(
                PlayerRuntimeController.TAG,
                "TRACK_PREF load: restored subtitleDelayMs=$savedDelayMs for videoId=$vid"
            )
        }
        contentId?.takeIf { it.isNotBlank() }?.let { id ->
            trackPreferenceDataStore.loadPlaybackSpeed(id)?.let { speed ->
                _uiState.update { it.copy(playbackSpeed = speed) }
            }
        }
        // Load saved watch progress BEFORE player init.
        // This eliminates the race condition where ExoPlayer's STATE_READY
        // callback fired before the DB read completed, causing the resume
        // seek to be silently skipped — the player would start from 0:00
        // or hang in buffering after a late seek.
        if (loadSavedProgress) {
            recordLoadingDiagnosticEvent(
                phase = "loading_saved_progress",
                message = context.getString(com.fetch.tv.R.string.player_loading_preparing)
            )
            loadSavedProgressSuspend(currentSeason, currentEpisode)
        }
        recordLoadingDiagnosticEvent(
            phase = "initializing_player",
            message = context.getString(com.fetch.tv.R.string.player_loading_building)
        )
        initializePlayer(playbackUrl, playbackHeaders)
    }
}

internal suspend fun PlayerRuntimeController.warmTraktEpisodeMappingForCurrentPlayback() {
    if (!traktEpisodeMappingService.isTraktAuthenticated()) {
        return
    }

    val normalizedType = contentType?.lowercase()
    if (normalizedType !in listOf("series", "tv")) {
        return
    }

    val resolvedContentId = contentId?.takeIf { it.isNotBlank() } ?: return
    val season = currentSeason ?: return
    val episode = currentEpisode ?: return

    // Warms the service's mapping cache, which scrobbling reads.
    withTimeoutOrNull(12_000L) {
        traktEpisodeMappingService.prefetchEpisodeMapping(
            contentId = resolvedContentId,
            contentType = contentType,
            videoId = currentVideoId,
            season = season,
            episode = episode
        )
    }
}
