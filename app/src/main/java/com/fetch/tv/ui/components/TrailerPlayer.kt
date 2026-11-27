package com.fetch.tv.ui.components

import com.fetch.tv.ui.theme.FetchMotion
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import com.fetch.tv.core.player.LetterboxDetector
import com.fetch.tv.core.player.LetterboxSampler
import com.fetch.tv.core.player.LetterboxTracker
import com.fetch.tv.core.player.LocalTrailerPlayerPool
import com.fetch.tv.data.trailer.YoutubeChunkedDataSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import android.view.LayoutInflater
import android.view.TextureView
import com.fetch.tv.R
import kotlinx.coroutines.delay

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun TrailerPlayer(
    trailerUrl: String?,
    modifier: Modifier = Modifier,
    trailerAudioUrl: String? = null,
    isPlaying: Boolean,
    isPaused: Boolean = false,
    /** 1 is normal speed. The pool's player is shared, so every caller sets it. */
    playbackSpeed: Float = 1f,
    onEnded: () -> Unit,
    onFirstFrameRendered: () -> Unit = {},
    /** Playback stopped on an error (an expired or refused link, the network gone). */
    onError: (androidx.media3.common.PlaybackException) -> Unit = {},
    muted: Boolean = false,
    seekRequestToken: Int = 0,
    seekDeltaMs: Long = 0L,
    /**
     * Where a new [trailerUrl] starts, read when the link changes. A stream swapped mid-video
     * (another quality) starts at the place it was, rather than at 0:00 and then jumping.
     */
    startPositionMs: Long = 0L,
    onProgressChanged: (positionMs: Long, durationMs: Long) -> Unit = { _, _ -> },
    cropToFill: Boolean = false,
    overscanZoom: Float = 1f,
    autoCropLetterbox: Boolean = false,
    /**
     * Fade the video in over [FetchMotion] `medium` once its first frame renders. Callers that
     * keep an opaque cover image above the player pass false, so the video is fully opaque
     * underneath before their cover fades away.
     */
    firstFrameFadeIn: Boolean = true
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activityLifecycleOwner = remember(context) { context as? androidx.lifecycle.LifecycleOwner ?: lifecycleOwner }
    val currentIsPlaying by rememberUpdatedState(isPlaying)
    val currentIsPaused by rememberUpdatedState(isPaused)
    val currentStartPositionMs by rememberUpdatedState(startPositionMs)
    val currentTrailerUrl by rememberUpdatedState(trailerUrl)
    val currentTrailerAudioUrl by rememberUpdatedState(trailerAudioUrl)
    val currentOnEnded by rememberUpdatedState(onEnded)
    val currentOnFirstFrameRendered by rememberUpdatedState(onFirstFrameRendered)
    val currentOnError by rememberUpdatedState(onError)
    val currentOnProgressChanged by rememberUpdatedState(onProgressChanged)
    val zoomScale = if (cropToFill) overscanZoom.coerceAtLeast(1f) else 1f
    var hasRenderedFirstFrame by remember(trailerUrl) { mutableStateOf(false) }
    val playerAlphaState = animateFloatAsState(
        targetValue = if (isPlaying && hasRenderedFirstFrame) 1f else 0f,
        animationSpec = if (firstFrameFadeIn) {
            tween(durationMillis = FetchMotion.tokens.durations.medium)
        } else {
            snap()
        },
        label = "trailerFirstFrameAlpha"
    )
    val playerViewRef = remember { mutableStateOf<PlayerView?>(null) }
    var letterboxZoom by remember(trailerUrl) { mutableFloatStateOf(1f) }
    val letterboxZoomState = animateFloatAsState(
        targetValue = if (autoCropLetterbox) letterboxZoom else 1f,
        animationSpec = tween(durationMillis = FetchMotion.tokens.durations.slow),
        label = "trailerLetterboxZoom"
    )

    val resolvedPool = LocalTrailerPlayerPool.current

    // Use the shared pool instance instead of creating a new ExoPlayer per focus.
    // The pool keeps one ExoPlayer alive across poster focus changes, eliminating
    // the expensive create/teardown cycle that was the app-launch bottleneck.
    // Bumped on resume when the held player was released meanwhile (a memory trim once the app
    // was hidden, or the full-screen player), so a fresh one is acquired instead of a dead one
    // that prepares without an error and shows black (#698).
    // Not keyed on the link: the lifecycle observer outlives a link change when the pool hands
    // back the same instance, and must bump the state this composition reads.
    var playerEpoch by remember(resolvedPool) { mutableIntStateOf(0) }
    val trailerPlayer = remember(trailerUrl, resolvedPool, playerEpoch) {
        if (trailerUrl != null) {
            resolvedPool?.acquire()
        } else {
            null
        }
    }

    // Where playback was when the app went to the background. The stream is stopped and cleared
    // then (decoders freed); on return it reopens here, not at 0:00 (#698). One holder for the
    // composable's life, for the same reason as the epoch; it records which link it belongs to.
    val backgroundedAt = remember { BackgroundedPosition() }

    // The audio link last loaded: a new audio stream for the same video link must still reload.
    val loadedAudio = remember { LoadedAudio() }

    // The seek request last applied. A re-acquired player re-runs the seek effect with the same
    // token; replaying it there (duration still unknown, so clamped to 0) restarted at 0:00.
    val appliedSeek = remember { AppliedSeek() }

    // Loads this link into [player] from [startMs] (0 = its default start: a live stream's edge).
    fun loadInto(player: ExoPlayer, url: String, audioUrl: String?, startMs: Long) {
        loadedAudio.url = audioUrl
        if (!audioUrl.isNullOrBlank()) {
            val mediaSourceFactory = DefaultMediaSourceFactory(YoutubeChunkedDataSourceFactory())
            val videoSource = mediaSourceFactory.createMediaSource(MediaItem.fromUri(url))
            val audioSource = mediaSourceFactory.createMediaSource(MediaItem.fromUri(audioUrl))
            val merged = MergingMediaSource(videoSource, audioSource)
            if (startMs > 0L) player.setMediaSource(merged, startMs) else player.setMediaSource(merged)
        } else {
            val item = MediaItem.fromUri(url)
            if (startMs > 0L) player.setMediaItem(item, startMs) else player.setMediaItem(item)
        }
        player.prepare()
    }

    // The start for a (re)load: the place playback was backgrounded at, once, else the caller's.
    fun takeStartPosition(url: String): Long {
        val resumeAt = backgroundedAt.positionMs.takeIf { backgroundedAt.url == url } ?: 0L
        backgroundedAt.url = null
        backgroundedAt.positionMs = 0L
        return if (resumeAt > 0L) resumeAt else currentStartPositionMs
    }

    // Configure player settings when acquired
    LaunchedEffect(trailerPlayer, muted, cropToFill) {
        val player = trailerPlayer ?: return@LaunchedEffect
        player.volume = if (muted) 0f else 1f
        player.videoScalingMode = if (cropToFill) {
            C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
        } else {
            C.VIDEO_SCALING_MODE_SCALE_TO_FIT
        }
    }

    LaunchedEffect(isPlaying, trailerUrl, trailerAudioUrl, muted, trailerPlayer) {
        val player = trailerPlayer ?: return@LaunchedEffect
        player.volume = if (muted) 0f else 1f
        if (isPlaying && trailerUrl != null) {
            // The lifecycle observer below may already have loaded this link on resume (from
            // where it was backgrounded); loading it again here would restart it at the start.
            val alreadyLoaded = player.playbackState != Player.STATE_IDLE &&
                player.currentMediaItem?.localConfiguration?.uri?.toString() == trailerUrl &&
                loadedAudio.url == trailerAudioUrl
            if (!alreadyLoaded) {
                hasRenderedFirstFrame = false
                // Only a real position: 0 would pin a live stream to its start, not its live edge.
                loadInto(player, trailerUrl, trailerAudioUrl, takeStartPosition(trailerUrl))
            }
            player.playWhenReady = !currentIsPaused
        } else {
            hasRenderedFirstFrame = false
            player.playWhenReady = false
            // Defer heavy stop and clear until focus settling/collapse has finished
            delay(150)
            if (!isPlaying) {
                player.stop()
                player.clearMediaItems()
            }
        }
    }

    LaunchedEffect(autoCropLetterbox, hasRenderedFirstFrame, trailerPlayer) {
        letterboxZoom = 1f
        if (!autoCropLetterbox || !hasRenderedFirstFrame) return@LaunchedEffect
        val player = trailerPlayer ?: return@LaunchedEffect
        val tracker = LetterboxTracker()
        val sampler = LetterboxSampler()
        try {
            while (true) {
                delay(LetterboxDetector.SAMPLE_INTERVAL_MS)
                if (LetterboxDetector.isSampleWindowOver(player.currentPosition, player.duration)) break
                if (!player.isPlaying) continue
                val textureView = playerViewRef.value?.videoSurfaceView as? TextureView ?: continue
                val bar = sampler.sample(textureView) ?: continue
                letterboxZoom = tracker.onSample(bar) ?: continue
                break
            }
        } finally {
            sampler.release()
        }
    }

    LaunchedEffect(isPaused, trailerPlayer) {
        val player = trailerPlayer ?: return@LaunchedEffect
        if (!isPlaying) return@LaunchedEffect
        player.playWhenReady = !isPaused
    }

    LaunchedEffect(playbackSpeed, trailerPlayer) {
        val player = trailerPlayer ?: return@LaunchedEffect
        player.setPlaybackSpeed(playbackSpeed.coerceIn(0.25f, 4f))
    }

    LaunchedEffect(seekRequestToken, seekDeltaMs, trailerPlayer) {
        val player = trailerPlayer ?: return@LaunchedEffect
        if (seekRequestToken <= 0) {
            // The caller reset its tokens (a new attempt): its next request is a new one.
            appliedSeek.token = 0
            return@LaunchedEffect
        }
        if (seekRequestToken == appliedSeek.token) return@LaunchedEffect
        appliedSeek.token = seekRequestToken
        val duration = player.duration.takeIf { it > 0 } ?: 0L
        val current = player.currentPosition
        val target = (current + seekDeltaMs).coerceIn(0L, duration.coerceAtLeast(0L))
        player.seekTo(target)
    }

    LaunchedEffect(trailerPlayer, isPlaying) {
        val player = trailerPlayer ?: return@LaunchedEffect
        while (isPlaying) {
            val position = player.currentPosition.coerceAtLeast(0L)
            val duration = player.duration.takeIf { it > 0 } ?: 0L
            currentOnProgressChanged(position, duration)
            delay(250)
        }
        currentOnProgressChanged(0L, 0L)
    }

    DisposableEffect(activityLifecycleOwner, trailerPlayer) {
        val player = trailerPlayer ?: return@DisposableEffect onDispose {}
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED &&
                    currentIsPlaying &&
                    player.playWhenReady
                ) {
                    currentOnEnded()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Hold the screen only while video actually plays, so an idle or stalled trailer
                // view never keeps the system screensaver away.
                playerViewRef.value?.keepScreenOn = isPlaying
            }

            override fun onRenderedFirstFrame() {
                hasRenderedFirstFrame = true
                currentOnFirstFrameRendered()
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                currentOnError(error)
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (resolvedPool != null && !resolvedPool.isLive(player)) {
                        // Released while hidden: acquire a fresh player. Its effects load the
                        // link from the backgrounded position.
                        playerEpoch += 1
                        return@LifecycleEventObserver
                    }
                    val url = currentTrailerUrl
                    if (currentIsPlaying && !url.isNullOrBlank()) {
                        if (player.currentMediaItem == null) {
                            hasRenderedFirstFrame = false
                            loadInto(player, url, currentTrailerAudioUrl, takeStartPosition(url))
                        }
                        player.playWhenReady = !currentIsPaused
                    }
                }
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP -> {
                    // Keep the place before the stream is cleared; a live stream rejoins its edge.
                    runCatching {
                        if (player.currentMediaItem != null && !player.isCurrentMediaItemLive) {
                            val position = player.currentPosition
                            if (position > 0L) {
                                backgroundedAt.url = currentTrailerUrl
                                backgroundedAt.positionMs = position
                            }
                        }
                        player.playWhenReady = false
                        player.pause()
                        player.stop()
                        player.clearMediaItems()
                    }
                }
                // Do NOT release on destroy — the pool owns the lifecycle.
                else -> Unit
            }
        }
        player.addListener(listener)
        activityLifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            runCatching { activityLifecycleOwner.lifecycle.removeObserver(observer) }
            runCatching { player.removeListener(listener) }
            // Only stop — never release. The pool manages the ExoPlayer lifecycle. And only this
            // instance: after a re-acquire the pool's live player belongs to the new effect.
            resolvedPool?.stop(player)
        }
    }

    if (trailerPlayer != null) {
        AnimatedVisibility(
            visible = isPlaying,
            enter = fadeIn(animationSpec = FetchMotion.enterTween()),
            exit = fadeOut(animationSpec = FetchMotion.exitTween())
        ) {
            AndroidView(
                factory = { ctx ->
                    (LayoutInflater.from(ctx).inflate(R.layout.trailer_player_view, null) as PlayerView).apply {
                        playerViewRef.value = this
                        player = trailerPlayer
                        isFocusable = false
                        isFocusableInTouchMode = false
                        keepScreenOn = trailerPlayer.isPlaying
                        resizeMode = if (cropToFill) {
                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        } else {
                            AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    }
                },
                update = { view ->
                    // Re-attach player in case it was reclaimed after yield
                    if (view.player !== trailerPlayer) {
                        view.player = trailerPlayer
                    }
                    view.resizeMode = if (cropToFill) {
                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    } else {
                        AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                onRelease = { view ->
                    playerViewRef.value = null
                    view.player = null
                    view.keepScreenOn = false
                },
                modifier = modifier
                    .clipToBounds()
                    .graphicsLayer {
                        alpha = playerAlphaState.value
                        scaleX = zoomScale * letterboxZoomState.value
                        scaleY = zoomScale * letterboxZoomState.value
                    }
            )
        }
    }
}

private class LoadedAudio {
    var url: String? = null
}

private class AppliedSeek {
    var token: Int = 0
}

/** A plain holder: written from lifecycle callbacks and read once, so it needn't be observable. */
private class BackgroundedPosition {
    var url: String? = null
    var positionMs: Long = 0L
}
