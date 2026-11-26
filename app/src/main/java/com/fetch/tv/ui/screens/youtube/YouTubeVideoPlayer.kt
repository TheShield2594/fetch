package com.fetch.tv.ui.screens.youtube

import android.graphics.Color as AndroidColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.session.MediaSession
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.fetch.tv.data.trailer.YoutubeChunkedDataSourceFactory
import com.fetch.tv.ui.screens.player.SafeMediaSessionPlayer
import com.fetch.tv.ui.screens.settings.MemoryBudget
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The YouTube player's own ExoPlayer (#743), rather than the muted hero-trailer pool's. It is
 * built for watching: it renders to a SurfaceView, takes audio focus (a music app pauses under
 * it) and pauses when headphones are unplugged, has a MediaSession, starts after a second of
 * media instead of five, opens a link at its start position, and reports the position every
 * 100 ms for captions and seeking. It lives as long as the composable and is released with it.
 *
 * Seeks arrive as [seekRequestToken] bumps with a [seekDeltaMs] from where the last one landed.
 * A [seekScrub] request (held D-pad, rewind and fast-forward) waits [SEEK_COMMIT_DELAY_MS] for
 * the next press before seeking, so a run of presses seeks once, as in the main player; the
 * position reported meanwhile is the target.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun YouTubeVideoPlayer(
    videoUrl: String,
    audioUrl: String?,
    isPaused: Boolean,
    playbackSpeed: Float,
    seekRequestToken: Int,
    seekDeltaMs: Long,
    seekScrub: Boolean,
    /** Where a new [videoUrl] starts (0 = its default start: a live stream's edge). Read on each new link. */
    startPositionMs: Long,
    /** [seeking]: a scrub is waiting to commit, and [positionMs] is its target, not playback's. */
    onProgressChanged: (positionMs: Long, durationMs: Long, seeking: Boolean) -> Unit,
    onEnded: () -> Unit,
    onError: (PlaybackException) -> Unit,
    /** Play or pause asked from outside the remote's keys (the MediaSession: a voice assistant, a phone). */
    onPlayPauseRequest: (play: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // The activity's lifecycle: the player sits in a Dialog, whose own owner doesn't stop with the app.
    val activityLifecycleOwner = remember(context) { context as? LifecycleOwner ?: lifecycleOwner }
    val currentIsPaused by rememberUpdatedState(isPaused)
    val currentStartPositionMs by rememberUpdatedState(startPositionMs)
    val currentSeekDeltaMs by rememberUpdatedState(seekDeltaMs)
    val currentSeekScrub by rememberUpdatedState(seekScrub)
    val currentOnProgressChanged by rememberUpdatedState(onProgressChanged)
    val currentOnEnded by rememberUpdatedState(onEnded)
    val currentOnError by rememberUpdatedState(onError)
    val currentOnPlayPauseRequest by rememberUpdatedState(onPlayPauseRequest)

    val player = remember(context) { buildYouTubeExoPlayer(context) }
    val seek = remember(player) { SeekState() }

    DisposableEffect(player) {
        YouTubePlayerPresence.enter()
        val session = runCatching {
            MediaSession.Builder(
                context,
                SafeMediaSessionPlayer(
                    player = player,
                    onPlay = { currentOnPlayPauseRequest(true) },
                    onPause = { currentOnPlayPauseRequest(false) }
                )
            )
                // The main player's session has the default id; two sessions may not share one.
                .setId(MEDIA_SESSION_ID)
                .build()
        }.getOrNull()
        onDispose {
            YouTubePlayerPresence.leave()
            runCatching { session?.release() }
            runCatching { player.release() }
        }
    }

    // A new link (quality change, Retry, the next video) opens at its start position.
    LaunchedEffect(player, videoUrl, audioUrl) {
        seek.targetMs = null
        val startMs = currentStartPositionMs.coerceAtLeast(0L)
        if (!audioUrl.isNullOrBlank()) {
            val factory = DefaultMediaSourceFactory(YoutubeChunkedDataSourceFactory())
            val merged = MergingMediaSource(
                factory.createMediaSource(MediaItem.fromUri(videoUrl)),
                factory.createMediaSource(MediaItem.fromUri(audioUrl))
            )
            if (startMs > 0L) player.setMediaSource(merged, startMs) else player.setMediaSource(merged)
        } else {
            val item = MediaItem.fromUri(videoUrl)
            if (startMs > 0L) player.setMediaItem(item, startMs) else player.setMediaItem(item)
        }
        player.prepare()
        player.playWhenReady = !currentIsPaused
    }

    LaunchedEffect(player, isPaused) { player.playWhenReady = !isPaused }

    LaunchedEffect(player, playbackSpeed) { player.setPlaybackSpeed(playbackSpeed.coerceIn(0.25f, 4f)) }

    LaunchedEffect(player, seekRequestToken) {
        if (seekRequestToken <= 0) {
            // The caller reset its tokens (a new attempt): its next request is a new one.
            seek.appliedToken = 0
            seek.targetMs = null
            return@LaunchedEffect
        }
        if (seekRequestToken == seek.appliedToken) return@LaunchedEffect
        seek.appliedToken = seekRequestToken
        // From where the last request landed, even if it is still waiting to be committed.
        val from = seek.targetMs ?: player.currentPosition
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
        val target = (from + currentSeekDeltaMs).coerceIn(0L, duration ?: Long.MAX_VALUE)
        seek.targetMs = target
        // A newer request restarts this effect and cancels the wait, so a run of presses seeks once.
        if (currentSeekScrub) delay(SEEK_COMMIT_DELAY_MS)
        player.seekTo(target)
        seek.targetMs = null
    }

    LaunchedEffect(player) {
        while (true) {
            val pending = seek.targetMs
            val position = pending ?: player.currentPosition.coerceAtLeast(0L)
            val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
            currentOnProgressChanged(position, duration, pending != null)
            delay(PROGRESS_INTERVAL_MS)
        }
    }

    val playerViewHolder = remember { arrayOfNulls<PlayerView>(1) }

    DisposableEffect(player, activityLifecycleOwner) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                // A scrub still waiting to commit (a rewind pressed just before the end) wins:
                // ending now would close the player and drop it.
                if (playbackState == Player.STATE_ENDED && player.playWhenReady && seek.targetMs == null) {
                    currentOnEnded()
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // Headphones unplugged or another app took the audio: the player paused itself,
                // so the overlay must show paused, or the next OK would "pause" a paused video.
                if (!playWhenReady && (
                        reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY ||
                            reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS
                        )
                ) {
                    currentOnPlayPauseRequest(false)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Hold the screen only while video plays, so a paused video lets the TV's
                // screensaver come on.
                playerViewHolder[0]?.keepScreenOn = isPlaying
            }

            override fun onPlayerError(error: PlaybackException) {
                currentOnError(error)
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> player.playWhenReady = false
                // Frees the decoders and the connection; the item and the position stay, so
                // returning carries on from the same place.
                Lifecycle.Event.ON_STOP -> runCatching { player.stop() }
                Lifecycle.Event.ON_START -> {
                    if (player.playbackState == Player.STATE_IDLE && player.currentMediaItem != null) {
                        runCatching { player.prepare() }
                    }
                }
                Lifecycle.Event.ON_RESUME -> player.playWhenReady = !currentIsPaused
                else -> Unit
            }
        }
        player.addListener(listener)
        activityLifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            runCatching { activityLifecycleOwner.lifecycle.removeObserver(observer) }
            runCatching { player.removeListener(listener) }
        }
    }

    AndroidView(
        factory = { ctx ->
            // Built in code, a PlayerView renders to a SurfaceView: the display's own video
            // path, which a TextureView isn't.
            PlayerView(ctx).apply {
                useController = false
                isFocusable = false
                isFocusableInTouchMode = false
                setShutterBackgroundColor(AndroidColor.BLACK)
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                this.player = player
                keepScreenOn = player.isPlaying
                playerViewHolder[0] = this
            }
        },
        update = { view -> if (view.player !== player) view.player = player },
        onRelease = { view ->
            playerViewHolder[0] = null
            view.player = null
            view.keepScreenOn = false
        },
        modifier = modifier
    )
}

/**
 * Whether the YouTube player is on screen, paused or not. Ambient mode stays away meanwhile: it
 * would close the player, and a paused video is still being watched.
 */
object YouTubePlayerPresence {
    private val players = AtomicInteger(0)
    private val _isShowing = MutableStateFlow(false)
    val isShowing: StateFlow<Boolean> = _isShowing.asStateFlow()

    internal fun enter() {
        _isShowing.value = players.incrementAndGet() > 0
    }

    internal fun leave() {
        _isShowing.value = players.updateAndGet { (it - 1).coerceAtLeast(0) } > 0
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun buildYouTubeExoPlayer(context: android.content.Context): ExoPlayer {
    val targetBufferMb = if (MemoryBudget.isLowRamTier) LOW_RAM_BUFFER_MB else BUFFER_MB
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs = */ 15_000,
            /* maxBufferMs = */ 50_000,
            /* bufferForPlaybackMs = */ 1_000,
            /* bufferForPlaybackAfterRebufferMs = */ 2_500
        )
        .setTargetBufferBytes(targetBufferMb * 1024 * 1024)
        // The byte cap holds even before minBufferMs, so a 4K stream can't grow the buffer unbounded.
        .setPrioritizeTimeOverSizeThresholds(false)
        .build()
    return ExoPlayer.Builder(context)
        .setLoadControl(loadControl)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ true
        )
        .setHandleAudioBecomingNoisy(true)
        .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS)
        .build()
        .apply { repeatMode = Player.REPEAT_MODE_OFF }
}

/** Plain holders, written and read from effects only, so they needn't be observable. */
private class SeekState {
    var appliedToken: Int = 0
    var targetMs: Long? = null
}

private const val MEDIA_SESSION_ID = "fetch_youtube"
private const val PROGRESS_INTERVAL_MS = 100L
internal const val SEEK_COMMIT_DELAY_MS = 400L
private const val LOW_RAM_BUFFER_MB = 64
private const val BUFFER_MB = 160
