@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.fetch.tv.ui.screens.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/*
 * mpv has none of what ExoPlayer gets from Media3 (#766): no MediaSession for the Assistant,
 * HDMI-CEC transport keys and Google Home; no audio focus; no pause when headphones or HDMI audio
 * go away; and the decoder, connection and demuxer cache stayed held in the background.
 */

/** What the MediaSession reports for mpv: read from the controller's state, never from mpv's thread. */
internal data class MpvSessionSnapshot(
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val hasMedia: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val isLive: Boolean,
    val speed: Float,
    val metadata: MediaMetadata,
    val mediaId: String
)

/**
 * A Media3 [Player] façade over mpv, so mpv gets the same [MediaSession] as ExoPlayer. It only
 * reports state; play, pause and seek go back to the controller, the same path the remote takes.
 */
internal class MpvSessionPlayer(
    looper: Looper,
    private val snapshot: () -> MpvSessionSnapshot,
    private val onPlay: () -> Unit,
    private val onPause: () -> Unit,
    private val onSeekTo: (Long) -> Unit,
    private val onStop: () -> Unit
) : SimpleBasePlayer(looper) {

    override fun getState(): State {
        val now = snapshot()
        val commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_STOP,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_RELEASE
            )
            .apply {
                if (!now.isLive && now.durationMs > 0L) {
                    add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                    add(Player.COMMAND_SEEK_BACK)
                    add(Player.COMMAND_SEEK_FORWARD)
                }
            }
            .build()
        val builder = State.Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(now.isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackParameters(PlaybackParameters(now.speed.takeIf { it > 0f } ?: 1f))
            .setSeekBackIncrementMs(SESSION_SEEK_INCREMENT_MS)
            .setSeekForwardIncrementMs(SESSION_SEEK_INCREMENT_MS)
        if (!now.hasMedia) {
            // Media3 requires an empty playlist to be idle or ended.
            return builder.setPlaybackState(Player.STATE_IDLE).build()
        }
        val item = MediaItemData.Builder(now.mediaId)
            .setMediaItem(
                MediaItem.Builder()
                    .setMediaId(now.mediaId)
                    .setMediaMetadata(now.metadata)
                    .build()
            )
            .setMediaMetadata(now.metadata)
            .setDurationUs(if (now.durationMs > 0L && !now.isLive) now.durationMs * 1_000L else C.TIME_UNSET)
            .setIsSeekable(!now.isLive && now.durationMs > 0L)
            .setIsDynamic(now.isLive)
            .build()
        return builder
            .setPlaylist(listOf(item))
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(if (now.isBuffering) Player.STATE_BUFFERING else Player.STATE_READY)
            .setIsLoading(now.isBuffering)
            .setContentPositionMs(now.positionMs.coerceAtLeast(0L))
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) onPlay() else onPause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        // Seek back and forward arrive here with the target already worked out from the increments.
        if (positionMs != C.TIME_UNSET) onSeekTo(positionMs.coerceAtLeast(0L))
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        // A session "stop" (Google Home's stop button) is a pause here; leaving is the user's call.
        onStop()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()

    /** Re-reads [snapshot]; call on the player's looper whenever the controller's state changes. */
    fun refresh() = invalidateState()

    private companion object {
        const val SESSION_SEEK_INCREMENT_MS = 10_000L
    }
}

/** What an audio focus change asks mpv to do. Pure, so the rules can be tested. */
internal enum class MpvFocusAction { NONE, PAUSE, PAUSE_FOR_GAIN, RESUME }

/**
 * Mirrors what Media3 does for a movie: a permanent loss pauses for good, a transient one pauses
 * until focus comes back, and a duck keeps playing (a video's audio is not lowered to a murmur).
 */
internal fun mpvFocusAction(
    focusChange: Int,
    isPlaying: Boolean,
    pausedForTransientLoss: Boolean,
    inBackground: Boolean
): MpvFocusAction = when (focusChange) {
    AudioManager.AUDIOFOCUS_LOSS -> if (isPlaying) MpvFocusAction.PAUSE else MpvFocusAction.NONE
    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (isPlaying) MpvFocusAction.PAUSE_FOR_GAIN else MpvFocusAction.NONE
    AudioManager.AUDIOFOCUS_GAIN ->
        if (pausedForTransientLoss && !isPlaying && !inBackground) MpvFocusAction.RESUME else MpvFocusAction.NONE
    else -> MpvFocusAction.NONE
}

/**
 * Audio focus, headphone-disconnect ("becoming noisy") and HDMI-audio-unplug handling for mpv.
 * Focus is requested when playback starts and held across pauses, as ExoPlayer does, so a
 * transient loss can resume; it is abandoned on [release].
 */
internal class MpvAudioFocusAndRouting(
    private val context: Context,
    private val isPlaying: () -> Boolean,
    private val inBackground: () -> Boolean,
    private val pause: () -> Unit,
    private val resume: () -> Unit
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var holdsFocus = false
    private var pausedForTransientLoss = false
    private var receiverRegistered = false
    private var focusRequest: AudioFocusRequest? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (mpvFocusAction(change, isPlaying(), pausedForTransientLoss, inBackground())) {
            MpvFocusAction.PAUSE -> {
                pausedForTransientLoss = false
                pause()
            }
            MpvFocusAction.PAUSE_FOR_GAIN -> {
                pausedForTransientLoss = true
                pause()
            }
            MpvFocusAction.RESUME -> {
                pausedForTransientLoss = false
                resume()
            }
            MpvFocusAction.NONE -> if (change == AudioManager.AUDIOFOCUS_GAIN) pausedForTransientLoss = false
        }
        if (change == AudioManager.AUDIOFOCUS_LOSS) holdsFocus = false
    }

    private val routeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Registering for the sticky HDMI broadcast delivers its current state at once.
            if (isInitialStickyBroadcast) return
            val unplugged = when (intent.action) {
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> true
                AudioManager.ACTION_HDMI_AUDIO_PLUG ->
                    intent.getIntExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, 1) == 0
                else -> false
            }
            if (unplugged && isPlaying()) {
                pausedForTransientLoss = false
                pause()
            }
        }
    }

    /** Playback started or resumed: take focus and watch the audio route. */
    fun onPlaying() {
        pausedForTransientLoss = false
        requestFocus()
        registerReceiver()
    }

    /** Playback paused: keep focus (a transient loss may resume) but stop watching the route. */
    fun onPaused() {
        unregisterReceiver()
    }

    fun release() {
        unregisterReceiver()
        abandonFocus()
        pausedForTransientLoss = false
    }

    private fun requestFocus() {
        if (holdsFocus) return
        val manager = audioManager ?: return
        val granted = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build()
                    )
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener(focusListener)
                    .build()
                    .also { focusRequest = it }
                manager.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                manager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            }
        }.onFailure { Log.w(TAG, "mpv audio focus request failed: ${it.message}") }
            .getOrNull()
        holdsFocus = granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        if (!holdsFocus) return
        holdsFocus = false
        val manager = audioManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let(manager::abandonAudioFocusRequest)
            } else {
                @Suppress("DEPRECATION")
                manager.abandonAudioFocus(focusListener)
            }
        }
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            addAction(AudioManager.ACTION_HDMI_AUDIO_PLUG)
        }
        receiverRegistered = runCatching {
            ContextCompat.registerReceiver(context, routeReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }.isSuccess
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        receiverRegistered = false
        runCatching { context.unregisterReceiver(routeReceiver) }
    }

    private companion object {
        const val TAG = "MpvAudioFocus"
    }
}

private fun PlayerRuntimeController.mpvSessionSnapshot(): MpvSessionSnapshot {
    val state = _uiState.value
    val timeline = _playbackTimeline.value
    return MpvSessionSnapshot(
        isPlaying = state.isPlaying,
        isBuffering = state.isBuffering && !state.isPlaying,
        hasMedia = currentStreamUrl.isNotBlank() && !mpvStoppedForLifecycle,
        positionMs = timeline.currentPosition,
        durationMs = timeline.duration,
        isLive = timeline.isLive,
        speed = state.playbackSpeed,
        metadata = buildMediaSessionMetadata(),
        mediaId = currentFilename?.takeIf { it.isNotBlank() } ?: currentStreamUrl.hashCode().toString()
    )
}

/**
 * Starts mpv's MediaSession, audio focus and route handling once mpv has media. Safe to call
 * again; what is already running is kept.
 */
internal fun PlayerRuntimeController.startMpvPlatformIntegration() {
    if (!isUsingMpvEngine()) return
    if (mpvAudioFocus == null) {
        mpvAudioFocus = MpvAudioFocusAndRouting(
            context = context,
            isPlaying = { _uiState.value.isPlaying },
            inBackground = { isInBackground },
            pause = {
                if (isUsingMpvEngine() && hasActivePlayIntent()) {
                    onEvent(PlayerEvent.OnPause)
                    if (!isInBackground) showControlsTemporarily()
                }
            },
            resume = { if (isUsingMpvEngine()) onEvent(PlayerEvent.OnPlay) }
        )
    }
    ensureMpvMediaSession()
    if (mpvPlatformJob?.isActive == true) return
    mpvPlatformJob = scope.launch {
        combine(_uiState, _playbackTimeline) { state, timeline ->
            // Position only to the second: enough for controllers, without a refresh per tick.
            listOf(
                state.isPlaying, state.isBuffering, state.playbackSpeed, state.title, state.contentName,
                state.currentSeason, state.currentEpisode, state.currentEpisodeTitle,
                timeline.duration, timeline.isLive, timeline.currentPosition / 1_000L
            )
        }
            .distinctUntilChanged()
            .collect {
                val playing = _uiState.value.isPlaying
                if (playing) mpvAudioFocus?.onPlaying() else mpvAudioFocus?.onPaused()
                mpvSessionPlayer?.refresh()
            }
    }
}

/** Builds mpv's MediaSession if there is none (it is released in the background). */
internal fun PlayerRuntimeController.ensureMpvMediaSession() {
    if (!isUsingMpvEngine() || isInBackground) return
    if (currentMediaSession != null && mpvSessionPlayer != null) return
    runCatching {
        currentMediaSession?.release()
        val player = mpvSessionPlayer ?: MpvSessionPlayer(
            looper = Looper.getMainLooper(),
            snapshot = { mpvSessionSnapshot() },
            onPlay = { onEvent(PlayerEvent.OnPlay) },
            onPause = { onEvent(PlayerEvent.OnPause) },
            onSeekTo = { onEvent(PlayerEvent.OnSeekTo(it)) },
            onStop = { onEvent(PlayerEvent.OnPause) }
        ).also { mpvSessionPlayer = it }
        currentMediaSession = MediaSession.Builder(context, player).build()
    }.onFailure {
        Log.w(PlayerRuntimeController.TAG, "mpv MediaSession failed: ${it.message}")
        currentMediaSession = null
    }
}

/** Tears down what [startMpvPlatformIntegration] started: on release and on a switch to ExoPlayer. */
internal fun PlayerRuntimeController.stopMpvPlatformIntegration() {
    mpvPlatformJob?.cancel()
    mpvPlatformJob = null
    mpvAudioFocus?.release()
    mpvAudioFocus = null
    if (mpvSessionPlayer != null) {
        runCatching { currentMediaSession?.release() }
        currentMediaSession = null
        runCatching { mpvSessionPlayer?.release() }
        mpvSessionPlayer = null
    }
}
