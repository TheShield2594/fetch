package com.fetch.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import com.fetch.tv.R
import com.fetch.tv.core.util.findCause
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val MAX_AUTO_RETRIES = 2
private const val RETRY_DELAY_MS = 1_500L
private const val STABLE_PROGRESS_RESET_DELAY_MS = 5_000L

internal fun PlayerRuntimeController.showRecoveryOverlay() {
    _uiState.update { state ->
        state.copy(
            error = null,
            isBuffering = true,
            showLoadingOverlay = true,
            loadingMessage = context.getString(R.string.player_loading_buffering),
            showPauseOverlay = false
        )
    }
}

/**
 * Determines whether the given [PlaybackException] is transient and worth retrying.
 *
 * Retryable errors include source/IO errors, parsing glitches, and unexpected runtime
 * exceptions that commonly occur after pause/resume or seek on flaky streams.
 * Decoder-init and DRM errors are considered fatal.
 */
internal fun isRetryablePlaybackError(error: PlaybackException): Boolean {
    return when (error.errorCode) {
        // --- Source / IO errors (the 2xxx range) ---
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, -> true

        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
            val httpCause = error.findCause<HttpDataSource.InvalidResponseCodeException>()
            if (httpCause != null) {
                val code = httpCause.responseCode
                !(code == 400 || code == 401 || code == 403 || code == 404 || code == 410)
            } else {
                true
            }
        }
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,

        // --- Decoder errors (often transient after pause/resume on some hardware) ---
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> true

        // --- Behind-the-scenes / unexpected errors (often IllegalStateException / NPE) ---
        PlaybackException.ERROR_CODE_UNSPECIFIED -> {
            val cause = error.cause
            cause is IllegalStateException || cause is NullPointerException
        }

        else -> false
    }
}

/**
 * Audio-track failures that the safe-audio → audio-disabled fallback ladder can recover from.
 *
 * - [PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED] (5001): the AudioTrack could not be
 *   created (e.g. the requested passthrough/offload encoding is not actually accepted by the sink).
 * - [PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED] (5002): a write to the AudioTrack
 *   failed, most commonly with `AudioTrack.ERROR_DEAD_OBJECT` (-6) when an HDMI/audio-route
 *   renegotiation invalidates an E-AC-3/AC-3 passthrough or offload track mid-playback.
 *
 * Both are remedied by re-selecting audio with tunneling/passthrough off and the channel count
 * constrained to the device's capabilities (safe-audio mode), or by dropping audio entirely — so
 * a write failure must take the same recovery path as an init failure rather than landing on the
 * fatal error screen.
 *
 * [combinedMessage] is the concatenated exception/cause messages; the string checks are a safety
 * net for devices that surface the same failure under a generic error code.
 */
internal fun isAudioTrackFailure(errorCode: Int, combinedMessage: String): Boolean {
    if (errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED) return true
    if (errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED) return true
    return combinedMessage.contains("audiotrack init failed", ignoreCase = true) ||
        combinedMessage.contains("audiotrack write failed", ignoreCase = true)
}

internal fun httpStatusExplanation(context: android.content.Context, code: Int): String {
    return when (code) {
        401, 410 -> context.getString(com.fetch.tv.R.string.player_error_stream_expired)
        404 -> context.getString(com.fetch.tv.R.string.player_error_stream_removed)
        429 -> context.getString(com.fetch.tv.R.string.player_error_stream_rate_limited)
        in 500..599 -> context.getString(com.fetch.tv.R.string.player_error_stream_unavailable)
        in 400..499 -> context.getString(com.fetch.tv.R.string.player_error_stream_blocked)
        else -> ""
    }
}

/** A failure reading the source (network, HTTP status, file): the 2xxx range. */
internal fun PlaybackException.isSourceError(): Boolean = errorCode in 2000..2999

internal fun PlaybackException.findInvalidResponseCodeException(): HttpDataSource.InvalidResponseCodeException? {
    var current: Throwable? = cause
    while (current != null) {
        if (current is HttpDataSource.InvalidResponseCodeException) return current
        current = current.cause
    }
    return null
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlaybackException.toDisplayMessage(context: android.content.Context): String {
    val responseException = findInvalidResponseCodeException()
    if (responseException != null) {
        val code = responseException.responseCode
        val statusText = responseException.responseMessage?.takeIf { it.isNotBlank() }
        val providerHint = httpStatusExplanation(context, code)
        return buildString {
            append("HTTP $code")
            statusText?.let { append(" $it") }
            append(" [$errorCodeName]")
            append(providerHint)
        }
    }

    // Check for unrecognized format (provider returned non-video content)
    val isUnrecognizedFormat = findCause<androidx.media3.exoplayer.source.UnrecognizedInputFormatException>() != null
    if (isUnrecognizedFormat) {
        return context.getString(com.fetch.tv.R.string.player_error_source_invalid_content, errorCodeName)
    }

    val decoderInit = findCause<MediaCodecRenderer.DecoderInitializationException>()
    if (decoderInit != null) {
        val decoderMessage = decoderInit.message?.trim()?.takeIf { it.isNotBlank() }
            ?: decoderInit.diagnosticInfo?.trim()?.takeIf { it.isNotBlank() }
        return if (decoderMessage != null) {
            "$decoderMessage [$errorCodeName]"
        } else {
            errorCodeName
        }
    }

    val meaningfulMessage = findMostRelevantCauseMessage() ?: cause?.message ?: message
    return if (meaningfulMessage != null) {
        "$meaningfulMessage [$errorCodeName]"
    } else {
        errorCodeName
    }
}

internal fun Throwable.toDisplayMessage(context: android.content.Context, fallback: String? = null): String {
    val meaningfulMessage = findMostRelevantCauseMessage()
    return meaningfulMessage
        ?: message?.takeIf { it.isNotBlank() }
        ?: fallback
        ?: context.getString(com.fetch.tv.R.string.player_error_playback_fallback)
}

private fun Throwable.findMostRelevantCauseMessage(): String? {
    val candidates = buildList {
        var current: Throwable? = this@findMostRelevantCauseMessage
        while (current != null) {
            current.message
                ?.trim()
                ?.takeIf {
                    it.isNotBlank() &&
                        !it.equals("Playback error", ignoreCase = true) &&
                        !it.equals("Source error", ignoreCase = true) &&
                        !it.equals("Unexpected runtime error", ignoreCase = true)
                }
                ?.let(::add)
            current = current.cause
        }
    }
    return candidates.firstOrNull()
}

/**
 * Attempts an automatic retry of the current stream, preserving the playback position.
 *
 * The first retry re-prepares the current player, and the second retry fully rebuilds it,
 * so recovery stays on the loading overlay until playback succeeds or finally fails.
 *
 * Returns `true` if a retry was scheduled, `false` if the error should be shown to the user.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.attemptAutoRetry(
    error: PlaybackException,
    detailedError: String
): Boolean {
    if (currentVideoTrackIsLikelyVc1 ||
        Vc1VideoFormatHeuristics.isLikelyVc1(streamName = _uiState.value.currentStreamName ?: streamName)
    ) {
        return false
    }
    if (!isRetryablePlaybackError(error)) return false
    if (errorRetryCount >= MAX_AUTO_RETRIES) return false

    val paused = userPausedManually
    val attempt = errorRetryCount
    errorRetryCount++

    Log.w(
        PlayerRuntimeController.TAG,
        "Auto-retry ${attempt + 1}/$MAX_AUTO_RETRIES after ${RETRY_DELAY_MS}ms for: $detailedError"
    )

    // Capture the current position so we can resume after re-init.
    val savedPosition = _exoPlayer?.currentPosition?.takeIf { it > 0L } ?: 0L
    val isFirstAttempt = attempt == 0

    errorRetryJob?.cancel()
    errorRetryJob = scope.launch {
        _uiState.update {
            it.copy(
                error = null,
                showLoadingOverlay = if (isFirstAttempt) false else it.loadingOverlayEnabled,
                showPauseOverlay = false
            )
        }

        delay(RETRY_DELAY_MS)

        if (isFirstAttempt) {
            // Lightweight recovery: re-prepare the same source without destroying the player.
            val player = _exoPlayer
            if (player != null) {
                if (savedPosition > 0L) {
                    player.seekTo((savedPosition - 1).coerceAtLeast(0L))
                }
                player.prepare()
                // Only resume playback if the user hadn't paused.
                player.playWhenReady = !paused
            } else {
                releasePlayer(flushPlaybackState = false)
                if (savedPosition > 0L) {
                    _uiState.update { it.copy(pendingSeekPosition = savedPosition) }
                }
                initializePlayer(currentStreamUrl, currentHeaders, startPaused = paused)
            }
        } else {
            // Full teardown — clears any corrupt decoder/internal state.
            releasePlayer(flushPlaybackState = false)
            if (savedPosition > 0L) {
                _uiState.update { it.copy(pendingSeekPosition = savedPosition) }
            }
            initializePlayer(currentStreamUrl, currentHeaders, startPaused = paused)
        }
    }
    return true
}

/**
 * Resets the retry counter. Call this whenever playback enters a healthy state
 * (first frame rendered, or user-initiated retry).
 */
internal fun PlayerRuntimeController.resetErrorRetryState() {
    errorRetryCount = 0
    parsingErrorProbeAttempted = false
    pendingAudioPcmFallbackRebuild = false
    errorRetryJob?.cancel()
    errorRetryJob = null
}

internal fun PlayerRuntimeController.scheduleStableProgressReset() {
    stableProgressResetJob?.cancel()
    stableProgressResetJob = scope.launch {
        delay(STABLE_PROGRESS_RESET_DELAY_MS)
        val player = _exoPlayer ?: return@launch
        if (player.playbackState == Player.STATE_READY && player.isPlaying) {
            resetErrorRetryState()
            clearMidPlaybackRecovery()
        }
    }
}

internal fun PlayerRuntimeController.cancelStableProgressReset() {
    stableProgressResetJob?.cancel()
    stableProgressResetJob = null
}

internal fun PlayerRuntimeController.refreshStableProgressResetGate() {
    if (!hasRenderedFirstFrame) return
    val player = _exoPlayer ?: return
    val healthy = player.playbackState == Player.STATE_READY && player.isPlaying
    if (healthy) {
        if (stableProgressResetJob?.isActive != true) {
            scheduleStableProgressReset()
        }
    } else {
        cancelStableProgressReset()
    }
}

/**
 * Silent PCM audio fallback for ERROR_CODE_AUDIO_TRACK_INIT_FAILED (5001).
 *
 * When the decoder is set to EXTENSION_RENDERER_MODE_ON (decoderPriority == 1,
 * the default) and tunneling is NOT active, audio passthrough may fail on certain devices/formats.
 * Instead of tearing down and re-building the entire player, we apply an
 * imperceptible speed change (1.00001×) which forces ExoPlayer to decode audio
 * through the software PCM pipeline — identical to what happens when the user
 * manually changes playback speed.
 *
 * This is a one-shot attempt per stream; if it fails again the normal retry
 * logic takes over.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.tryAudioTrackPcmFallback(
    error: PlaybackException
): Boolean {
    if (error.errorCode != PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED) return false
    if (hasTriedAudioPcmFallback) return false
    if (cachedDecoderPriority != 1) return false // Only for EXTENSION_RENDERER_MODE_ON
    if (_uiState.value.tunnelingEnabled) return false

    hasTriedAudioPcmFallback = true
    pendingAudioPcmFallbackRebuild = true

    val player = _exoPlayer ?: return false
    val savedPosition = player.currentPosition.takeIf { it > 0L } ?: 0L
    val paused = userPausedManually

    Log.d(PlayerRuntimeController.TAG, "Audio track init failed (5001) — rebuilding player with PCM forcing, position=${savedPosition}ms")
    showRecoveryOverlay()

    errorRetryJob?.cancel()
    errorRetryJob = scope.launch {
        releasePlayer(flushPlaybackState = false)
        if (savedPosition > 0L) {
            _uiState.update { it.copy(pendingSeekPosition = savedPosition) }
        }
        initializePlayer(currentStreamUrl, currentHeaders, startPaused = paused)
    }

    return true
}

/**
 * Takes the next step of [PlayerAudioFallbackPolicy] for an audio failure on the current stream.
 * Returns false once every step is in force, so the caller falls through to the rest of the chain.
 */
internal fun PlayerRuntimeController.tryNextAudioFallbackStep(positionMs: Long): Boolean {
    val step = PlayerAudioFallbackPolicy.nextStep(
        safeAudioActive = isSafeAudioModeActiveForCurrentPlayback,
        pcmForced = hasTriedAudioPcmFallback,
        audioDisabled = isAudioDisabledForCurrentPlayback
    ) ?: return false
    when (step) {
        PlayerAudioFallbackPolicy.Step.SAFE_AUDIO -> {
            safeAudioForcedStreamUrls.add(currentStreamUrl)
            retryCurrentStreamWithSafeAudioFallback(positionMs)
        }
        PlayerAudioFallbackPolicy.Step.FORCE_PCM -> {
            hasTriedAudioPcmFallback = true
            pendingAudioPcmFallbackRebuild = true
            retryCurrentStreamWithSafeAudioFallback(positionMs)
        }
        PlayerAudioFallbackPolicy.Step.DISABLE_AUDIO -> {
            pendingAudioPcmFallbackRebuild = true
            audioDisabledForcedStreamUrls.add(currentStreamUrl)
            retryCurrentStreamWithAudioDisabled(positionMs)
        }
    }
    return true
}

/**
 * DV7-to-HEVC decoder fallback for ERROR_CODE_DECODER_INIT_FAILED (4003).
 *
 * On devices without a Dolby Vision decoder, a Dolby Vision (profile 7) video track can fail
 * to initialise its decoder outright. Mapping DV7 to HEVC lets the plain HEVC decoder play the
 * base layer instead. The mapping is baked into the renderers at build time, so this marks the
 * stream in [PlayerRuntimeController.dv7ToHevcForcedStreamUrls] (the same per-stream override
 * the DV decoding-failure path uses) and rebuilds the player at the current position.
 *
 * Runs at most once per stream: once the mapping is active for the current playback, a further
 * decoder-init failure falls through to the generic recovery chain.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.tryDv7HevcFallback(
    error: PlaybackException,
    fromPositionMs: Long
): Boolean {
    if (error.errorCode != PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) return false
    if (isMapDv7ToHevcActiveForCurrentPlayback) return false
    if (dv7ToHevcForcedStreamUrls.contains(currentStreamUrl)) return false
    val format = (error as? androidx.media3.exoplayer.ExoPlaybackException)?.rendererFormat ?: return false
    val sourceProfile = com.fetch.tv.core.player.DolbyVisionConversionStats.getLastSourceProfile()
    if (!isDv7HevcFallbackCandidate(format.codecs, sourceProfile)) return false

    Log.i(
        PlayerRuntimeController.TAG,
        "DV_DECODER_INIT_FALLBACK: decoder init failed for ${format.sampleMimeType} " +
            "codecs=${format.codecs}; retrying with DV7-to-HEVC mapping, position=${fromPositionMs}ms"
    )
    dv7ToHevcForcedStreamUrls.add(currentStreamUrl)
    retryCurrentStreamWithDolbyVisionFallback(fromPositionMs)
    return true
}

/**
 * Whether a decoder-init failure on a track with [codecs] should take the DV7-to-HEVC fallback.
 *
 * Accepts Dolby Vision profile 7, or profile 8 (what a DV7 source looks like once converted to
 * 8.1). Profile 5 is excluded, including a profile-5 source whose codec string the extractor
 * rewrote to 8.1 ([sourceProfile] is the pre-conversion profile): it has no HEVC-compatible base
 * layer, so the mapping cannot help and would only disarm its conversion for this stream.
 */
internal fun isDv7HevcFallbackCandidate(codecs: String?, sourceProfile: Int?): Boolean {
    if (sourceProfile == 5) return false
    val codecString = codecs?.lowercase() ?: return false
    return codecString.split(',').map { it.trim() }.any { codec ->
        (codec.startsWith("dvhe.") || codec.startsWith("dvh1.")) &&
            codec.substringAfter('.').substringBefore('.') in DV_HEVC_BASE_LAYER_PROFILES
    }
}

private val DV_HEVC_BASE_LAYER_PROFILES = setOf("07", "08")

internal fun PlayerRuntimeController.tryParsingErrorProbeFallback(
    error: PlaybackException,
    detailedError: String,
    allowEngineFailover: Boolean,
    savedPosition: Long = 0L,
    paused: Boolean = userPausedManually
): Boolean {
    if (currentVideoTrackIsLikelyVc1 ||
        Vc1VideoFormatHeuristics.isLikelyVc1(streamName = _uiState.value.currentStreamName ?: streamName)
    ) {
        return false
    }
    val isSourceOrParsingError = error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
        error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
        error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ||
        error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
        error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ||
        error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
        error.findCause<androidx.media3.exoplayer.source.UnrecognizedInputFormatException>() != null ||
        error.cause?.toString()?.contains("UnrecognizedInputFormatException") == true

    if (!isSourceOrParsingError) return false
    if (parsingErrorProbeAttempted) return false
    parsingErrorProbeAttempted = true

    val previousMimeType = currentStreamMimeType
    Log.w(
        PlayerRuntimeController.TAG,
        "Source/parsing error [${error.errorCode}] detected (previous mimeType=$previousMimeType). " +
            "Probing stream format..."
    )

    errorRetryJob?.cancel()
    errorRetryJob = scope.launch {
        showRecoveryOverlay()
        val probedMime = PlayerMediaSourceFactory.probeNetworkMimeType(
            url = currentStreamUrl,
            headers = currentHeaders
        )

        if (probedMime != null && probedMime != previousMimeType) {
            Log.i(
                PlayerRuntimeController.TAG,
                "Stream probe resolved mimeType=$probedMime (was $previousMimeType). Retrying playback..."
            )
            currentStreamMimeType = probedMime
            currentStreamResponseHeaders = emptyMap()
            releasePlayer(flushPlaybackState = false)
            if (savedPosition > 0L) {
                _uiState.update { it.copy(pendingSeekPosition = savedPosition) }
            }
            initializePlayer(currentStreamUrl, currentHeaders, startPaused = paused)
        } else if (previousMimeType == androidx.media3.common.MimeTypes.APPLICATION_M3U8) {
            currentStreamMimeType = null
            currentStreamResponseHeaders = emptyMap()
            releasePlayer(flushPlaybackState = false)
            if (savedPosition > 0L) {
                _uiState.update { it.copy(pendingSeekPosition = savedPosition) }
            }
            initializePlayer(currentStreamUrl, currentHeaders, startPaused = paused)
        } else {
            if (maybeAutoSwitchInternalPlayerOnStartupError(detailedError = detailedError, allowEngineFailover = allowEngineFailover)) {
                return@launch
            }
            if (attemptAutoRetry(error, detailedError)) {
                return@launch
            }
            if (tryServerFallback()) {
                return@launch
            }
            val userFacingError = error.toDisplayMessage(context)
            val showFatalError = {
                _uiState.update {
                    it.copy(
                        error = userFacingError,
                        isBuffering = false,
                        showLoadingOverlay = false,
                        showPauseOverlay = false
                    )
                }
            }
            if (!tryNextSourceAfterFailure(
                    showFatalError,
                    httpStatus = error.findInvalidResponseCodeException()?.responseCode,
                    isSourceError = error.isSourceError()
                )
            ) {
                showFatalError()
            }
        }
    }
    return true
}
