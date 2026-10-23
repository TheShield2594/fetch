package com.fetch.tv.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.core.streams.SpecLine
import com.fetch.tv.data.local.FrameRateMatchingMode
import com.fetch.tv.ui.components.SpecStrip
import com.fetch.tv.ui.theme.FetchMotion
import com.fetch.tv.ui.theme.FetchTheme
import kotlinx.coroutines.delay
import java.util.Locale

internal enum class PlaybackAudioPath {
    PASSTHROUGH,
    DECODED
}

/** The parts of the playback details bar that change while it is open, read from the live engine. */
internal data class PlaybackDetailsSnapshot(
    val audioPath: PlaybackAudioPath? = null,
    val bitrateBps: Long? = null
)

/**
 * Reads the engine on the calling thread, which must be the main one: ExoPlayer is only safe to
 * read there. The track bitrate is preferred because an adaptive stream switches between them;
 * a file that names none (Matroska never does) falls back to its size over its duration.
 */
internal fun PlayerRuntimeController.playbackDetailsSnapshot(): PlaybackDetailsSnapshot {
    if (isUsingMpvEngine()) {
        val mpv = mpvView ?: return PlaybackDetailsSnapshot()
        return PlaybackDetailsSnapshot(
            audioPath = mpv.isAudioPassthroughNow()?.let { if (it) PlaybackAudioPath.PASSTHROUGH else PlaybackAudioPath.DECODED },
            bitrateBps = mpv.currentBitrateBps() ?: fileBitrate()
        )
    }
    val player = _exoPlayer ?: return PlaybackDetailsSnapshot()
    val trackBps = (runCatching { player.videoFormat?.bitrate }.getOrNull() ?: 0).coerceAtLeast(0) +
        (runCatching { player.audioFormat?.bitrate }.getOrNull() ?: 0).coerceAtLeast(0)
    val audioPath = when {
        player.audioFormat == null -> null
        playbackSpeedAwareAudioSink?.isEncodedOutputActive() == true -> PlaybackAudioPath.PASSTHROUGH
        else -> PlaybackAudioPath.DECODED
    }
    return PlaybackDetailsSnapshot(
        audioPath = audioPath,
        bitrateBps = trackBps.toLong().takeIf { it > 0L } ?: fileBitrate()
    )
}

private fun PlayerRuntimeController.fileBitrate(): Long? =
    PlayerBitrateEstimator.fileBitrateBps(currentVideoSize, playbackTimeline.value.duration)?.toLong()

/** How long the bar stays up after the last Up press. */
internal const val PLAYBACK_DETAILS_VISIBLE_MS = 6_000L

/**
 * The slim bar Up opens during playback: the playing file's spec line, the refresh rate the
 * display runs at (and whether frame-rate matching picked it), the audio path and the bitrate.
 * A second Up opens the Sources panel, which the trailing hint says.
 */
@Composable
internal fun PlaybackDetailsBar(
    visible: Boolean,
    specLine: SpecLine?,
    displayModeInfo: DisplayModeInfo?,
    frameRateMatchingMode: FrameRateMatchingMode,
    snapshot: () -> PlaybackDetailsSnapshot,
    modifier: Modifier = Modifier
) {
    val fullMotion = FetchTheme.fullMotion
    AnimatedVisibility(
        visible = visible,
        enter = if (fullMotion) {
            fadeIn(tween(FetchMotion.tokens.durations.fast)) +
                slideInVertically(tween(FetchMotion.tokens.durations.fast)) { it / 2 }
        } else {
            EnterTransition.None
        },
        exit = if (fullMotion) fadeOut(tween(FetchMotion.tokens.durations.quick)) else ExitTransition.None,
        modifier = modifier
    ) {
        var live by remember { mutableStateOf(PlaybackDetailsSnapshot()) }
        LaunchedEffect(Unit) {
            while (true) {
                live = snapshot()
                delay(1_000L)
            }
        }
        // Matched only when frame-rate matching reached the video's rate; otherwise the display's
        // own mode is the honest answer.
        val view = LocalView.current
        val matchedHz = displayModeInfo?.takeIf { it.refreshMatched }?.refreshRate
            ?.takeIf { frameRateMatchingMode != FrameRateMatchingMode.OFF && it > 0f }
        val displayHz = matchedHz ?: remember(view) { view.display?.mode?.refreshRate }?.takeIf { it > 0f }

        val details = buildList {
            displayHz?.let {
                add(
                    if (matchedHz != null) {
                        stringResource(R.string.playback_details_refresh_matched, formatHz(it))
                    } else {
                        stringResource(R.string.playback_details_refresh_display, formatHz(it))
                    }
                )
            }
            when (live.audioPath) {
                PlaybackAudioPath.PASSTHROUGH -> add(stringResource(R.string.playback_details_audio_passthrough))
                PlaybackAudioPath.DECODED -> add(stringResource(R.string.playback_details_audio_decoded))
                null -> Unit
            }
            live.bitrateBps?.let {
                add(stringResource(R.string.playback_details_bitrate, String.format(Locale.getDefault(), "%.1f", it / 1_000_000.0)))
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(FetchTheme.colors.media.videoControlsScrim)
                .padding(horizontal = FetchTheme.spacing.xxxl, vertical = FetchTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xl)
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xl)
            ) {
                if (specLine != null) {
                    SpecStrip(
                        line = specLine,
                        color = FetchTheme.colors.text.onOverlay,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
                if (details.isNotEmpty()) {
                    // Shares the row with the spec line, so long translations ellipsize instead of
                    // pushing it out.
                    Text(
                        text = details.joinToString(SpecLine.SEPARATOR),
                        style = FetchTheme.textStyles.spec,
                        color = FetchTheme.colors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }
            Text(
                text = stringResource(R.string.playback_details_sources_hint),
                style = FetchTheme.textStyles.metadata,
                color = FetchTheme.colors.TextSecondary,
                maxLines = 1
            )
        }
    }
}
