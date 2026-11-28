@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.fetch.tv.ui.screens.player

import android.content.Intent
import android.media.audiofx.AudioEffect
import android.os.SystemClock
import kotlinx.coroutines.flow.update

internal fun PlayerRuntimeController.releasePlayer() {
    releasePlayer(flushPlaybackState = true)
}

internal fun PlayerRuntimeController.releasePlayer(
    flushPlaybackState: Boolean
) {
    logScrobbleDiagnostic("release_player", "flushPlaybackState=$flushPlaybackState")
    isReleasingPlayer = true
    // Only clears the flag if this controller set it: a stale controller released after the next
    // player started must not let recommendation work run during that playback.
    com.fetch.tv.core.recommendations.TvRecommendationManager.setPlaybackActive(this, false)
    if (flushPlaybackState) {
        flushPlaybackSnapshotForSwitchOrExit()
    }

    notifyAudioSessionUpdate(false)
    unregisterAudioDelayRouteCallback()
    audioRouteChangeJob?.cancel()
    audioRouteChangeJob = null

    stopMpvPlatformIntegration()
    mpvStoppedForLifecycle = false
    mpvLifecycleResumePositionMs = 0L
    try {
        currentMediaSession?.release()
        currentMediaSession = null
    } catch (e: Exception) {
        e.printStackTrace()
    }
    progressJob?.cancel()
    // A subtitle fetch for the stream being released must not land on the next one (#703); the
    // next initialization starts its own.
    if (addonSubtitleFetchJob?.isActive == true) {
        addonSubtitleFetchJob?.cancel()
        _uiState.update { it.copy(isLoadingAddonSubtitles = false) }
    }
    addonSubtitleFetchJob = null
    // Same for an mpv addon-subtitle pick still downloading (#726); the files handed to mpv
    // are only needed while this core is alive.
    cancelMpvAddonSubtitleSelection()
    deleteMpvAddonSubtitleCacheFiles()
    mpvTrackRefreshJob?.cancel()
    mpvTrackRefreshJob = null
    mpvTrackRefreshInProgress = false
    hideControlsJob?.cancel()
    cancelFirstFrameWatchdog()
    cancelStallWatchdog()
    seekBufferingUiJob?.cancel()
    seekBufferingUiJob = null
    // The cancelled job was the one that would have cleared this after its grace window.
    seekBufferingUiDeferred = false
    // A pending pause-overlay timer would otherwise show the overlay over the next stream.
    pauseOverlayJob?.cancel()
    pauseOverlayJob = null
    _uiState.update { it.copy(showPauseOverlay = false) }
    watchProgressSaveJob?.cancel()
    seekProgressSyncJob?.cancel()
    seekSourceLogJob?.cancel()
    seekSourceLogJob = null
    cancelAfrPreflight()
    hideStreamSourceIndicatorJob?.cancel()
    hideStreamSourceIndicatorJob = null
    _uiState.update { it.copy(showStreamSourceIndicator = false) }
    hidePlayerEngineSwitchInfoJob?.cancel()
    hideSubtitleDelayOverlayJob?.cancel()
    subtitleAutoSyncLoadJob?.cancel()
    cancelAutomaticSubtitleSync() // AutoSync hook
    stopSidecarAddonSubtitle(clearView = true)
    subtitleTimingRefreshJob?.cancel()
    subtitleTimingRefreshJob = null
    playbackPreparationJob?.cancel()
    playbackPreparationJob = null
    traktMappingJob?.cancel()
    traktMappingJob = null
    mpvMediaLoadPrepared = false
    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null
    cancelNextEpisodePreload()
    streamResolveJob?.cancel()
    streamResolveJob = null
    stillWatchingPromptJob?.cancel()
    stillWatchingPromptJob = null
    errorRetryJob?.cancel()
    errorRetryJob = null
    stableProgressResetJob?.cancel()
    stableProgressResetJob = null
    mpvStableProgressResetJob?.cancel()
    mpvStableProgressResetJob = null
    releaseMpvPlayer()
    _exoPlayer?.let { player ->
        runCatching { player.playWhenReady = false }
        runCatching { player.pause() }
        runCatching { player.stop() }
        runCatching { player.clearMediaItems() }
        runCatching { player.clearVideoSurface() }
        runCatching { player.release() }
        lastExoPlayerReleaseElapsedMs = SystemClock.elapsedRealtime()
    }
    _exoPlayer = null
    holdExoTracksAcrossStop = false
    exoStoppedForLifecycle = false
    _loadControl = null
    currentBitrateAwareLoadControl = null
    currentParallelChunkOverheadMb = 0
    ffmpegAudioRenderer = null
    updateAudioControlAvailability()
    playbackSpeedAwareAudioSink = null
    resetPlaybackTimeline()
    isReleasingPlayer = false
}

internal fun PlayerRuntimeController.notifyAudioSessionUpdate(active: Boolean) {
    _exoPlayer?.let { player ->
        try {
            val intent = Intent(
                if (active) AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION
                else AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION
            )
            intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.audioSessionId)
            intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
            if (active) {
                intent.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MOVIE)
            }
            context.sendBroadcast(intent)
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }
}
