@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.fetch.tv.ui.screens.player

import android.os.SystemClock
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.exoplayer.SeekParameters
import com.fetch.tv.data.local.InternalPlayerEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val MPV_RESUME_SEEK_TOLERANCE_MS = 1500L

internal fun PlayerRuntimeController.attachMpvView(view: FetchMpvSurfaceView?) {
    if (mpvView === view) return
    mpvView = view

    if (view == null) return
    if (!isUsingMpvEngine()) return
    if (currentStreamUrl.isBlank()) return
    if (!mpvMediaLoadPrepared) return
    if (mpvInitializationInProgress) return

    runCatching {
        performPendingMpvHardRestartIfNeeded(view)
        view.applyHi10pGnextSoftwareFallback(shouldUseMpvHi10pGnextSoftwareFallback())
        view.applyHardwareDecodeMode(mpvHardwareDecodeModeSetting)
        view.applyDemuxerCacheLimits(mpvDemuxerCacheLimits())
        registerMpvEventRelay(view)
        view.setMedia(currentStreamUrl, currentHeaders)
        view.setPlaybackSpeed(_uiState.value.playbackSpeed)
        view.applyAudioAmplificationDb(_uiState.value.audioAmplificationDb)
        view.applyAudioLanguagePreferences(mpvPreferredAudioLanguages)
        view.applySubtitleLanguagePreferences(
            preferred = _uiState.value.subtitleStyle.preferredLanguage,
            secondary = _uiState.value.subtitleStyle.secondaryPreferredLanguage
        )
        view.applySubtitleStyle(_uiState.value.effectiveSubtitleStyle)
        view.setSubtitleDelayMs(_uiState.value.subtitleDelayMs)
        view.applyBluetoothAudioRoute(currentAudioOutputRoute?.isBluetooth == true)
        view.setAudioDelayMs(_uiState.value.audioDelayMs)
        view.applyAspectMode(_uiState.value.aspectMode)
        // initializePlayer sets userPausedManually for startPaused; honour it, or a retry while
        // paused or in the background starts audible playback (#706).
        view.setPaused(userPausedManually)
        applyPendingMpvSeekIfNeeded(view)
        hasRenderedFirstFrame = false
        endDetectionArmed = false
        mpvEofSeenClear = false
        _uiState.update {
            it.copy(
                isBuffering = true,
                isPlaying = view.isPlayingNow(),
                showLoadingOverlay = it.loadingOverlayEnabled,
                error = null
            )
        }
        cancelPauseOverlay()
        startProgressUpdates()
        startWatchProgressSaving()
        startMpvPlatformIntegration()
        updateMpvAvailableTracks()
        scheduleHideControls()
        // Started paused (#706): the scrobble starts when playback does, on resume.
        if (!userPausedManually) emitScrobbleStart()
    }.onFailure {
        val technical = it.message?.trim()?.takeIf { message -> message.isNotEmpty() }
        val explanation = context.getString(com.fetch.tv.R.string.player_error_mpv_surface_failed)
        val detailedError = if (technical == null || technical.equals(explanation, ignoreCase = true)) {
            explanation
        } else {
            "$explanation\n\n$technical"
        }
        if (
            maybeAutoSwitchInternalPlayerOnStartupError(
                detailedError = detailedError,
                allowEngineFailover = true
            )
        ) {
            return@onFailure
        }
        cancelNextEpisodeAutoPlayOnFatalError()
        _uiState.update { state ->
            state.copy(
                error = detailedError,
                showLoadingOverlay = false,
                playbackEnded = false,
                postPlayMode = null
            )
        }
    }
}

/** The user's buffer settings for mpv's demuxer cache, by the same rule as ExoPlayer's target. */
internal fun PlayerRuntimeController.applyMpvBufferSettings(settings: com.fetch.tv.data.local.PlayerSettings) {
    mpvUserBufferTargetMbSetting =
        if (settings.bufferEngineEnabled && !settings.bufferBudgetManaged) {
            settings.bufferSettings.targetBufferSizeMb.takeIf { it > 0 }
        } else {
            null
        }
    mpvAllowBufferAboveBudgetSetting = settings.allowLargeTargetBuffer
}

/** mpv's demuxer cache for a load starting now: the user's buffer target within this session's memory budget. */
internal fun PlayerRuntimeController.mpvDemuxerCacheLimits(): MpvDemuxerCachePolicy.Limits {
    val budgetMb = runCatching { com.fetch.tv.ui.screens.settings.MemoryBudget.playbackBudgetMb(context) }
        .getOrDefault(MpvDemuxerCachePolicy.MIN_TOTAL_MB)
    return MpvDemuxerCachePolicy.limits(
        userTargetMb = mpvUserBufferTargetMbSetting,
        playbackBudgetMb = budgetMb,
        allowAboveBudget = mpvAllowBufferAboveBudgetSetting
    )
}

internal fun PlayerRuntimeController.initializeMpvPlayer(
    url: String,
    headers: Map<String, String>,
    allowEngineFailover: Boolean = true
) {
    mpvMediaLoadPrepared = true
    prefetchAutoSyncIndex(url, headers) // AutoSync hook
    _exoPlayer?.let { player ->
        player.release()
        lastExoPlayerReleaseElapsedMs = SystemClock.elapsedRealtime()
    }
    _exoPlayer = null
    trackSelector = null
    try {
        currentMediaSession?.release()
    } catch (_: Exception) {
    }
    currentMediaSession = null
    notifyAudioSessionUpdate(false)

    val view = mpvView
    if (view == null) {
        setLoadingStatus(
            phase = "mpv_waiting_surface",
            message = context.getString(com.fetch.tv.R.string.player_loading_building),
            showOverlay = true
        )
        _uiState.update {
            it.copy(
                isBuffering = true,
                isPlaying = false,
                showLoadingOverlay = it.loadingOverlayEnabled,
                error = null
            )
        }
        return
    }

    runCatching {
        setLoadingStatus(
            phase = "mpv_starting",
            message = context.getString(com.fetch.tv.R.string.player_loading_starting),
            showOverlay = true
        )
        performPendingMpvHardRestartIfNeeded(view)
        view.applyHi10pGnextSoftwareFallback(shouldUseMpvHi10pGnextSoftwareFallback())
        view.applyHardwareDecodeMode(mpvHardwareDecodeModeSetting)
        view.applyDemuxerCacheLimits(mpvDemuxerCacheLimits())
        registerMpvEventRelay(view)
        val initialResumePosition = resolvePendingInitialResumePosition()
            .takeIf { it > 0L }
            ?: (pendingSeekPosition?.coerceAtLeast(0L) ?: 0L)
        playbackAnalyticsDiagnostics.setStartupStartPosition(initialResumePosition)
        view.setMedia(url, headers, initialResumePosition)
        playbackAnalyticsDiagnostics.recordRawEventLine(
            "PLAYER_INIT: engine=MPV host=${url.safeMpvTraceHost()} " +
                "playbackSpeed=${_uiState.value.playbackSpeed} resumePositionMs=$initialResumePosition"
        )
        if (initialResumePosition > 0L) {
            clearPendingInitialResumePosition()
            pendingSeekPosition = null
            updatePlaybackTimeline(currentPosition = initialResumePosition)
        }
        view.setPlaybackSpeed(_uiState.value.playbackSpeed)
        view.applyAudioAmplificationDb(_uiState.value.audioAmplificationDb)
        view.applyAudioLanguagePreferences(mpvPreferredAudioLanguages)
        view.applySubtitleLanguagePreferences(
            preferred = _uiState.value.subtitleStyle.preferredLanguage,
            secondary = _uiState.value.subtitleStyle.secondaryPreferredLanguage
        )
        view.applySubtitleStyle(_uiState.value.effectiveSubtitleStyle)
        view.setSubtitleDelayMs(_uiState.value.subtitleDelayMs)
        view.applyBluetoothAudioRoute(currentAudioOutputRoute?.isBluetooth == true)
        view.setAudioDelayMs(_uiState.value.audioDelayMs)
        view.applyAspectMode(_uiState.value.aspectMode)
        // initializePlayer sets userPausedManually for startPaused; honour it, or a retry while
        // paused or in the background starts audible playback (#706).
        view.setPaused(userPausedManually)
        applyPendingMpvSeekIfNeeded(view)

        hasRenderedFirstFrame = false
        endDetectionArmed = false
        mpvEofSeenClear = false
        _uiState.update {
            it.copy(
                isBuffering = true,
                isPlaying = view.isPlayingNow(),
                showLoadingOverlay = it.loadingOverlayEnabled,
                error = null,
                audioTracks = emptyList(),
                subtitleTracks = emptyList(),
                selectedAudioTrackIndex = -1,
                selectedSubtitleTrackIndex = -1
            )
        }
        cancelPauseOverlay()
        startProgressUpdates()
        startWatchProgressSaving()
        startMpvPlatformIntegration()
        updateMpvAvailableTracks()
        scheduleHideControls()
        // Started paused (#706): the scrobble starts when playback does, on resume.
        if (!userPausedManually) emitScrobbleStart()
    }.onFailure { error ->
        Log.e(PlayerRuntimeController.TAG, "libmpv initialize failed: ${error.message}", error)
        val technical = error.message?.trim()?.takeIf { it.isNotEmpty() }
        val explanation = context.getString(com.fetch.tv.R.string.player_error_mpv_playback_failed)
        val detailedError = if (technical == null || technical.equals(explanation, ignoreCase = true)) {
            explanation
        } else {
            "$explanation\n\n$technical"
        }
        if (
            maybeAutoSwitchInternalPlayerOnStartupError(
                detailedError = detailedError,
                allowEngineFailover = allowEngineFailover
            )
        ) {
            return@onFailure
        }
        cancelNextEpisodeAutoPlayOnFatalError()
        _uiState.update {
            it.copy(
                error = detailedError,
                showLoadingOverlay = false,
                isBuffering = false,
                playbackEnded = false,
                postPlayMode = null
            )
        }
    }
}

internal fun PlayerRuntimeController.releaseMpvPlayer() {
    unregisterMpvEventRelay()
    runCatching { mpvView?.releasePlayer() }
}

private fun String.safeMpvTraceHost(): String {
    return runCatching {
        toUri().host ?: substringBefore("://").takeIf { it.isNotBlank() } ?: "unknown"
    }.getOrDefault("unknown")
}

internal fun PlayerRuntimeController.pauseForLifecycle() {
    // Mark we're in background so onPlayerError can defer recovery to onResume.
    isInBackground = true

    // Release the MediaSession so the system doesn't route media commands
    // (play/pause, audio focus) to this player while the app is in the background.
    try {
        currentMediaSession?.release()
        currentMediaSession = null
    } catch (e: Exception) {
        e.printStackTrace()
    }

    resumePlaybackAfterTransientPause = !userPausedManually && hasActivePlayIntent()
    // Mark as user-paused so autoplay logic doesn't resume playback.
    userPausedManually = true
    shouldEnforceAutoplayOnFirstReady = false
    logScrobbleDiagnostic("lifecycle_pause", "userPaused=$userPausedManually")

    if (isUsingMpvEngine()) {
        mpvView?.setPaused(true)
        emitPauseScrobbleForCurrentProgress()
        stopWatchProgressSaving()
        // The app is going to the background and can be killed there: this counts as leaving.
        saveWatchProgress(WatchProgressSaveReason.LEAVING)
        stopProgressUpdates()
        // Not buffering either: a stale flag from a cache stall would read as loading, and the
        // server report skips loading snapshots, so the PAUSE would never be sent.
        _uiState.update { it.copy(isPlaying = false, isBuffering = false) }
        // The tick that reports to media servers stops in the background: report the pause now.
        reportServerPlayback()
        return
    }
    _exoPlayer?.let { player ->
        // Disable automatic audio focus handling so ExoPlayer can't
        // re-acquire focus and set playWhenReady=true behind our back.
        player.setAudioAttributes(player.audioAttributes, false)
        player.playWhenReady = false
        player.pause()
    }
}

/**
 * The app is no longer visible. A paused ExoPlayer still holds its video and audio codecs, and
 * some TVs (Sony BRAVIA) reclaim a held decoder from the background app in a way that leaves
 * video broken in the foreground app. stop() releases the codecs but keeps the media item and
 * position, so [startForLifecycle] can prepare it again, paused, where it was.
 */
internal fun PlayerRuntimeController.stopForLifecycle() {
    // Hidden, not just covered by an overlay: coming back waits for the user to press play.
    resumePlaybackAfterTransientPause = false
    if (isUsingMpvEngine()) {
        stopMpvForLifecycle()
        return
    }
    val player = _exoPlayer ?: return
    if (player.playbackState == androidx.media3.common.Player.STATE_IDLE) return
    exoStoppedForLifecycle = true
    holdExoTracksAcrossStop = true
    player.playWhenReady = false
    player.stop()
}

internal fun PlayerRuntimeController.startForLifecycle() {
    if (mpvStoppedForLifecycle) {
        startMpvForLifecycle()
        return
    }
    if (!exoStoppedForLifecycle) return
    exoStoppedForLifecycle = false
    // resumeForLifecycle rebuilds the player for this case.
    if (pendingBackgroundCrashRecovery) return
    val player = _exoPlayer ?: return
    if (isUsingMpvEngine()) return
    if (player.playbackState == androidx.media3.common.Player.STATE_IDLE) {
        player.prepare()
    }
}

/**
 * mpv's counterpart of the ExoPlayer stop above (#766): the paused file is unloaded, which frees
 * the decoder, the network connection and the demuxer cache, and [startMpvForLifecycle] loads it
 * again, paused, where it was. The core itself stays, so the surface callbacks stay valid.
 */
private fun PlayerRuntimeController.stopMpvForLifecycle() {
    val view = mpvView ?: return
    if (isReleasingPlayer || mpvInitializationInProgress || currentStreamUrl.isBlank()) return
    val state = _uiState.value
    // An ended or failed playback has nothing to come back to; reloading it would replay the end.
    if (state.playbackEnded || state.error != null) return
    val position = if (isLiveForWatchProgress()) {
        0L
    } else {
        view.currentPositionMs().takeIf { it > 0L && view.isPositionFromRequestedMedia() }
            ?: _playbackTimeline.value.currentPosition
    }
    mpvStoppedForLifecycle = true
    mpvLifecycleResumePositionMs = position.coerceAtLeast(0L)
    // Nothing on screen is updated in the background, and an unloaded file must not read as idle.
    stopProgressUpdates()
    cancelStallWatchdog()
    unregisterMpvEventRelay()
    runCatching { view.stopForBackground() }
        .onFailure { Log.w(PlayerRuntimeController.TAG, "mpv background stop failed: ${it.message}") }
    mpvSessionPlayer?.refresh()
}

private fun PlayerRuntimeController.startMpvForLifecycle() {
    mpvStoppedForLifecycle = false
    val position = mpvLifecycleResumePositionMs
    mpvLifecycleResumePositionMs = 0L
    if (!isUsingMpvEngine() || isReleasingPlayer || currentStreamUrl.isBlank()) return
    if (position > 0L) pendingSeekPosition = position
    // Paused, as ExoPlayer comes back from its stop: pauseForLifecycle set userPausedManually.
    userPausedManually = true
    shouldEnforceAutoplayOnFirstReady = false
    initializeMpvPlayer(currentStreamUrl, currentHeaders, allowEngineFailover = false)
}

/** The player the MediaSession drives: its play and pause take the remote's path (#705). */
internal fun PlayerRuntimeController.mediaSessionPlayer(player: androidx.media3.common.Player): SafeMediaSessionPlayer =
    SafeMediaSessionPlayer(
        player = player,
        onPlay = { onEvent(PlayerEvent.OnPlay) },
        onPause = { onEvent(PlayerEvent.OnPause) },
    )

internal fun PlayerRuntimeController.resumeForLifecycle() {
    isInBackground = false
    val resumeTransientPause = resumePlaybackAfterTransientPause
    resumePlaybackAfterTransientPause = false

    // If the codec crashed while in background, the player was released to free
    // resources. Rebuild it now with the saved position so the user comes back
    // to a clean, paused player ready to play.
    if (pendingBackgroundCrashRecovery) {
        pendingBackgroundCrashRecovery = false
        val savedPosition = backgroundCrashSavedPositionMs
        backgroundCrashSavedPositionMs = 0L
        if (savedPosition > 0L) {
            pendingSeekPosition = savedPosition
        }
        if (currentStreamUrl.isNotEmpty()) {
            initializePlayer(currentStreamUrl, currentHeaders, startPaused = true)
        }
        return
    }

    if (isUsingMpvEngine()) {
        // pauseForLifecycle stopped the tick; it would otherwise only restart on the next Play
        // press, leaving the timeline, tracks and server reporting frozen (TheShield2594/fetch#732).
        if (mpvView != null && !isReleasingPlayer) {
            // The MediaSession was released for the background; media keys work again (#766).
            if (currentStreamUrl.isNotBlank()) ensureMpvMediaSession()
            startProgressUpdates()
            if (resumeTransientPause && userPausedManually) {
                // Only an overlay covered the player (it was never hidden): carry on playing.
                onEvent(PlayerEvent.OnPlay)
            } else if (userPausedManually && !_uiState.value.isPlaying) {
                schedulePauseOverlay()
                showControlsTemporarily()
            }
        }
        return
    }

    val player = _exoPlayer
    if (player != null && !isUsingMpvEngine()) {
        // Restore automatic audio focus handling that was disabled in pauseForLifecycle().
        player.setAudioAttributes(player.audioAttributes, true)

        // A pause for the background left a still frame with no controls; show them, and the
        // pause overlay after its delay, as a pause from the remote does (#705).
        if (resumeTransientPause && userPausedManually) {
            // Only an overlay covered the player (it was never hidden): carry on playing.
            onEvent(PlayerEvent.OnPlay)
        } else if (userPausedManually && !_uiState.value.isPlaying) {
            schedulePauseOverlay()
            showControlsTemporarily()
        }

        // Re-create the MediaSession so media controls work in the foreground.
        if (currentMediaSession == null) {
            try {
                currentMediaSession = androidx.media3.session.MediaSession.Builder(context, mediaSessionPlayer(player)).build()
                updateMediaSessionMetadata()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

/** Makes the next progress tick re-read the mpv track list. Safe to call from any thread. */
internal fun PlayerRuntimeController.markMpvTracksStale() {
    mpvTrackRefreshGate.markStale()
}

/**
 * Progress-tick entry point. Reading the track list costs about a dozen synchronous mpv property
 * reads per track on the main thread, so the tick only re-reads it after mpv reports a
 * track-list change. The track decisions still run on every tick, on the tracks already known:
 * some of them wait on things that are not track-list changes, such as the first frame or
 * add-on subtitles arriving one add-on at a time.
 */
internal fun PlayerRuntimeController.updateMpvTracksOnTick() {
    updateMpvHdrVideoState()
    if (mpvTrackRefreshGate.isStale) {
        updateMpvAvailableTracks()
        return
    }
    if (!isUsingMpvEngine() || mpvTrackRefreshInProgress || mpvView == null) return
    val state = _uiState.value
    markMpvTextTracksScannedIfReady(hasInternalSubtitleTracks = state.subtitleTracks.isNotEmpty())
    maybeRestorePendingAudioSelectionAfterSubtitleRefresh(state.audioTracks)
    applyPersistedTrackPreference(
        audioTracks = state.audioTracks,
        subtitleTracks = state.subtitleTracks
    )
    tryAutoSelectPreferredSubtitleFromAvailableTracks()
}

/**
 * mpv never reports HDR video. It renders through `vo=gpu` / `gpu-next` on an OpenGL ES context,
 * which tone-maps PQ and HLG to SDR, so the picture on screen is SDR whatever the source. Flagging
 * it HDR dimmed subtitles for a picture that was not HDR (TheShield2594/fetch#733). Cleared on
 * each tick in case an ExoPlayer session flagged it before an engine switch.
 */
private fun PlayerRuntimeController.updateMpvHdrVideoState() {
    if (!isUsingMpvEngine()) return
    if (_uiState.value.isHdrVideo) {
        _uiState.update { it.copy(isHdrVideo = false) }
    }
}

/** A file without embedded subtitles only counts as text-scanned once the first frame is up. */
private fun PlayerRuntimeController.markMpvTextTracksScannedIfReady(hasInternalSubtitleTracks: Boolean) {
    if (hasInternalSubtitleTracks || hasRenderedFirstFrame) {
        hasScannedTextTracksOnce = true
    }
}

internal fun PlayerRuntimeController.updateMpvAvailableTracks() {
    if (!isUsingMpvEngine()) return
    if (mpvTrackRefreshInProgress) return
    val view = mpvView ?: return
    val streamUrlAtRefresh = currentStreamUrl
    mpvTrackRefreshGate.onRefreshStarted()
    mpvTrackRefreshInProgress = true
    mpvTrackRefreshJob = scope.launch {
        try {
            val snapshot = view.readTrackSnapshot()
            if (!isUsingMpvEngine() || mpvView !== view || currentStreamUrl != streamUrlAtRefresh) return@launch
            applyMpvTrackSnapshot(snapshot)
            tryAutoSelectPreferredSubtitleFromAvailableTracks()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(PlayerRuntimeController.TAG, "Failed to refresh MPV track snapshot: ${error.message}")
        } finally {
            mpvTrackRefreshInProgress = false
        }
    }
}

private fun PlayerRuntimeController.applyMpvTrackSnapshot(snapshot: MpvTrackSnapshot) {
    val switchPending = pendingEngineSwitchTrackPreference
        ?.takeIf { it.streamUrl == currentStreamUrl && it.sourceEngine == InternalPlayerEngine.EXOPLAYER }
    logSwitchTrace(
        stage = "mpv-snapshot-read",
        message = "switchPending=${switchPending != null} audioCount=${snapshot.audioTracks.size} " +
            "subtitleCount=${snapshot.subtitleTracks.size} " +
            "selectedAudioId=${snapshot.audioTracks.firstOrNull { it.isSelected }?.id} " +
            "selectedSubtitleId=${snapshot.subtitleTracks.firstOrNull { it.isSelected }?.id}"
    )

    val audioTracks = snapshot.audioTracks
        .mapIndexed { index, track ->
            val codecSuffix = buildList {
                track.codec?.takeIf { it.isNotBlank() }?.let { add(it) }
                track.channelCount?.takeIf { it > 0 }?.let { add("${it}ch") }
            }.joinToString(" ")
            val displayName = if (codecSuffix.isBlank()) {
                track.name
            } else {
                "${track.name} ($codecSuffix)"
            }
            TrackInfo(
                index = index,
                name = displayName,
                language = track.language,
                trackId = track.id.toString(),
                codec = track.codec,
                channelCount = track.channelCount,
                isSelected = track.isSelected
            )
        }

    val internalSubtitleTracks = snapshot.subtitleTracks
        .filterNot { it.isExternal }
        .mapIndexed { index, track ->
            TrackInfo(
                index = index,
                name = track.name,
                language = track.language,
                trackId = track.id.toString(),
                codec = track.codec,
                isForced = track.isForced,
                isSelected = track.isSelected
            )
        }

    val selectedAudioIndex = audioTracks.indexOfFirst { it.isSelected }
    val selectedSubtitleIndex = internalSubtitleTracks.indexOfFirst { it.isSelected }
    val selectedExternalSubtitleTrack = snapshot.subtitleTracks.firstOrNull { it.isExternal && it.isSelected }
    val selectedExternalSubtitle = selectedExternalSubtitleTrack != null
    logSwitchTrace(
        stage = "mpv-snapshot-mapped",
        message = "selectedAudioIndex=$selectedAudioIndex selectedSubtitleIndex=$selectedSubtitleIndex " +
            "selectedExternalSubtitle=${selectedExternalSubtitleTrack?.id ?: "none"} " +
            "mappedInternalSubtitleCount=${internalSubtitleTracks.size}"
    )

    markMpvTextTracksScannedIfReady(hasInternalSubtitleTracks = internalSubtitleTracks.isNotEmpty())
    maybeRestorePendingAudioSelectionAfterSubtitleRefresh(audioTracks)

    _uiState.update { state ->
        val selectedAddonFromMpvTrack = selectedExternalSubtitleTrack?.let { track ->
            state.addonSubtitles.firstOrNull { subtitle ->
                buildAddonSubtitleTrackId(subtitle).equals(track.name, ignoreCase = true)
            }
        }

        val addonSelection = when {
            selectedAddonFromMpvTrack != null -> selectedAddonFromMpvTrack
            selectedExternalSubtitle -> null
            selectedSubtitleIndex >= 0 -> null
            else -> state.selectedAddonSubtitle
        }
        val normalizedSelectedSubtitleIndex = if (selectedExternalSubtitle) {
            -1
        } else {
            selectedSubtitleIndex
        }

        if (
            state.audioTracks == audioTracks &&
            state.subtitleTracks == internalSubtitleTracks &&
            state.selectedAudioTrackIndex == selectedAudioIndex &&
            state.selectedSubtitleTrackIndex == normalizedSelectedSubtitleIndex &&
            state.selectedAddonSubtitle == addonSelection
        ) {
            state
        } else {
            state.copy(
                audioTracks = audioTracks,
                subtitleTracks = internalSubtitleTracks,
                selectedAudioTrackIndex = selectedAudioIndex,
                selectedSubtitleTrackIndex = normalizedSelectedSubtitleIndex,
                selectedAddonSubtitle = addonSelection
            )
        }
    }
    applyPersistedTrackPreference(
        audioTracks = audioTracks,
        subtitleTracks = internalSubtitleTracks
    )
    logSwitchTrace(
        stage = "mpv-snapshot-after-restore",
        message = "uiAudioIndex=${_uiState.value.selectedAudioTrackIndex} " +
            "uiSubtitleIndex=${_uiState.value.selectedSubtitleTrackIndex} " +
            "uiAddonSelected=${_uiState.value.selectedAddonSubtitle?.let { "${it.lang}/${it.addonName}/${it.id}" } ?: "none"}"
    )
}

private fun PlayerRuntimeController.performPendingMpvHardRestartIfNeeded(view: FetchMpvSurfaceView): Boolean {
    if (!pendingMpvHardRestartOnNextAttach) return false
    pendingMpvHardRestartOnNextAttach = false
    runCatching {
        Log.d(PlayerRuntimeController.TAG, "Applying MPV hard restart for startup engine failover")
        view.releasePlayer()
    }.onFailure {
        Log.w(PlayerRuntimeController.TAG, "MPV hard restart release failed: ${it.message}")
    }
    return true
}

internal fun PlayerRuntimeController.applyPendingMpvSeekIfNeeded(
    view: FetchMpvSurfaceView,
    currentPositionMs: Long = view.currentPositionMs().coerceAtLeast(0L),
    durationMs: Long = view.durationMs().coerceAtLeast(0L)
) {
    if (!isUsingMpvEngine()) return
    if (delayMpvResumeSeekUntilVideoTrack) {
        if (!view.hasVideoTrackSelectedNow()) return
        delayMpvResumeSeekUntilVideoTrack = false
    }

    // The player's own signal too: on the first tick the timeline hasn't caught up yet.
    if (pendingResumeProgress != null && (isLiveForWatchProgress() || view.isLiveStreamNow())) {
        // Live: stay at the live edge rather than resuming into the DVR window. While saved
        // progress is pending, pendingSeekPosition only holds the retry of that same seek.
        pendingResumeProgress = null
        pendingSeekPosition = null
        return
    }
    val state = _uiState.value
    val savedResume = pendingResumeProgress
    val queuedPosition = pendingSeekPosition ?: savedResume?.position
    if (queuedPosition == null) return
    if (queuedPosition <= 0L && savedResume == null) {
        pendingSeekPosition = null
        pendingResumeProgress = null
        return
    }

    // Until the requested file has loaded, position and duration can still be the previous
    // file's: an "already at target" read off them would drop the resume seek.
    if (!view.isPositionFromRequestedMedia()) return

    val target = when {
        savedResume != null && durationMs > 0L -> {
            savedResume.resolveResumePositionWithRewind(durationMs, System.currentTimeMillis()).coerceAtLeast(0L)
        }
        savedResume != null -> {
            val requiresDurationForPercentResume = savedResume.progressPercent != null &&
                savedResume.duration <= 0L
            if (requiresDurationForPercentResume) {
                return
            }
            savedResume.position.coerceAtLeast(0L)
        }
        else -> queuedPosition.coerceAtLeast(0L)
    }

    if (target <= 0L) {
        pendingSeekPosition = null
        pendingResumeProgress = null
        return
    }

    val isAlreadyAtTarget = currentPositionMs >= target ||
        abs(currentPositionMs - target) <= MPV_RESUME_SEEK_TOLERANCE_MS
    if (isAlreadyAtTarget) {
        if (pendingSeekPosition != null || savedResume != null) {
            pendingSeekPosition = null
            pendingResumeProgress = null
        }
        return
    }

    val canSeekNow = durationMs > 0L || currentPositionMs > 0L || hasRenderedFirstFrame
    if (!canSeekNow) return

    view.seekToMs(target)
    view.setSubtitleDelayMs(state.subtitleDelayMs)
    view.setAudioDelayMs(state.audioDelayMs)
    pendingSeekPosition = target
}

internal fun PlayerRuntimeController.isUsingMpvEngine(): Boolean {
    return currentInternalPlayerEngine == InternalPlayerEngine.MVP_PLAYER
}

internal fun PlayerRuntimeController.currentPlaybackPositionMs(): Long? {
    return if (isUsingMpvEngine()) {
        mpvView?.currentPositionMs()
    } else {
        _exoPlayer?.currentPosition
    }
}

internal fun PlayerRuntimeController.currentPlaybackDurationMs(): Long {
    return if (isUsingMpvEngine()) {
        mpvView?.durationMs() ?: 0L
    } else {
        _exoPlayer?.duration ?: 0L
    }
}

internal fun PlayerRuntimeController.isCurrentPlaybackSeekable(): Boolean {
    return if (isUsingMpvEngine()) {
        mpvView?.isSeekableNow() == true
    } else {
        val player = _exoPlayer ?: return false
        // A live window can be seekable while its duration is still unknown.
        player.isCurrentMediaItemSeekable &&
            (player.isCurrentMediaItemLive || player.duration > 0L)
    }
}

/** Seeking is blocked only for a live stream without a seekable window. */
internal fun PlayerRuntimeController.isLiveSeekBlocked(): Boolean =
    LivePlaybackUiPolicy.blocksSeeking(
        isLive = _playbackTimeline.value.isLive,
        playerSeekable = isCurrentPlaybackSeekable()
    )

internal fun PlayerRuntimeController.isPlaybackCurrentlyPlaying(): Boolean {
    return if (isUsingMpvEngine()) {
        mpvView?.isPlayingNow() == true
    } else {
        _exoPlayer?.isPlaying == true
    }
}

/**
 * Play intent survives transient states: ExoPlayer reports isPlaying=false while buffering,
 * but pausing on a route change then would strand playback stopped once buffering ends.
 */
internal fun PlayerRuntimeController.hasActivePlayIntent(): Boolean {
    return if (isUsingMpvEngine()) {
        mpvView?.isPlayingNow() == true
    } else {
        _exoPlayer?.playWhenReady == true
    }
}

internal fun PlayerRuntimeController.seekPlaybackTo(
    positionMs: Long,
    seekParameters: SeekParameters = SeekParameters.CLOSEST_SYNC
) {
    if (isUsingMpvEngine()) {
        mpvView?.let { view ->
            view.seekToMs(positionMs)
            // Keep subtitle/audio delay sticky during FF/RW seeks.
            view.setSubtitleDelayMs(_uiState.value.subtitleDelayMs)
            view.setAudioDelayMs(_uiState.value.audioDelayMs)
        }
    } else {
        _exoPlayer?.let { player ->
            if (FetchExoPlayerPerformanceHelper.enabled) {
                val currentPos = player.currentPosition
                val isForwardSeek = positionMs >= currentPos
                val inBuffer = isForwardSeek && FetchExoPlayerPerformanceHelper.isSeekInBuffer(player, positionMs)
                if (inBuffer) {
                    suppressBufferingUiForSeek = true
                    scheduleSeekSuppressTimeout()
                } else {
                    // Out of buffer or backward seek: show spinner immediately
                    seekBufferingUiDeferred = false
                    suppressBufferingUiForSeek = false
                    seekBufferingUiJob?.cancel()
                    _uiState.update { it.copy(isBuffering = true) }
                }
                FetchExoPlayerPerformanceHelper.buildScrubbingParams()?.let { params ->
                    isScrubbingModeActive = true
                    player.setScrubbingModeParameters(params)
                }
            }
            player.setSeekParameters(seekParameters)
            player.seekTo(positionMs)
        }
    }
}

/**
 * Back to the live edge (#660). ExoPlayer's default position for a live window is the live
 * edge; mpv's is the end of what it has, which is the same place for a live stream.
 */
internal fun PlayerRuntimeController.seekToLiveEdge() {
    if (!_playbackTimeline.value.isLive) return
    if (isUsingMpvEngine()) {
        mpvView?.let { view ->
            val end = currentPlaybackDurationMs()
            if (end > 0L) view.seekToMs(end)
        }
    } else {
        _exoPlayer?.seekToDefaultPosition()
    }
}

internal fun PlayerRuntimeController.setPlaybackSpeedInternal(speed: Float) {
    if (isUsingMpvEngine()) {
        mpvView?.setPlaybackSpeed(speed)
    } else {
        _exoPlayer?.setPlaybackSpeed(speed)
    }
}

internal fun PlayerRuntimeController.setPlaybackPaused(paused: Boolean) {
    if (isUsingMpvEngine()) {
        mpvView?.setPaused(paused)
        _uiState.update { it.copy(isPlaying = !paused) }
    } else {
        _exoPlayer?.let { player ->
            if (paused) player.pause() else player.play()
        }
    }
}

internal fun PlayerRuntimeController.pauseForStillWatchingPrompt() {
    userPausedManually = true
    setPlaybackPaused(true)
    if (isUsingMpvEngine()) {
        // The progress tick keeps running while paused (TheShield2594/fetch#732).
        stopWatchProgressSaving()
        emitPauseScrobbleForCurrentProgress()
    }
}

internal fun PlayerRuntimeController.keepMpvPlayingIfNeeded(wasPlaying: Boolean) {
    if (!wasPlaying || !isUsingMpvEngine()) return
    scope.launch {
        // If track switch forces a pause, nudge playback back only when needed.
        repeat(6) {
            if (!isUsingMpvEngine()) return@launch
            val view = mpvView ?: return@launch
            val pausedByCache = view.isPausedForCacheNow()
            val coreIdle = view.isCoreIdleNow()
            if (view.isPlayingNow() && !pausedByCache && !coreIdle) {
                _uiState.update { state ->
                    if (state.isPlaying) state else state.copy(isPlaying = true, isBuffering = false)
                }
                return@launch
            }
            view.setPaused(false)
            _uiState.update { it.copy(isPlaying = true, isBuffering = false) }
            delay(120L)
        }
    }
}

/**
 * After an in-buffer seek, automatically clear the buffering-UI suppression
 * flag after a short timeout so normal buffering states resume if the seek
 * takes longer than expected.
 */
internal fun PlayerRuntimeController.scheduleSeekSuppressTimeout() {
    scope.launch {
        delay(FetchExoPlayerPerformanceHelper.SEEK_SUPPRESS_TIMEOUT_MS)
        suppressBufferingUiForSeek = false
    }
}
