package com.fetch.tv.ui.screens.player

import androidx.media3.common.Player
import com.fetch.tv.core.tracking.TrackingScrobbleAction

internal fun trackingActionForNonPlayingState(playbackState: Int): TrackingScrobbleAction? = when (playbackState) {
    Player.STATE_BUFFERING -> null
    Player.STATE_ENDED, Player.STATE_IDLE -> TrackingScrobbleAction.STOP
    else -> TrackingScrobbleAction.PAUSE
}

internal fun shouldSendPauseScrobble(
    hasActiveScrobble: Boolean,
    progressPercent: Float
): Boolean = hasActiveScrobble && progressPercent in 0f..100f

internal fun shouldSendStopScrobble(
    hasActiveScrobble: Boolean,
    progressPercent: Float
): Boolean = hasActiveScrobble || progressPercent >= 80f

/** The share of a title at which Trakt, AniList and MyAnimeList record a stop scrobble as a play. */
internal const val TRACKER_WATCHED_PERCENT = 80f

/** What leaving the player sends to the trackers. */
internal sealed interface ExitScrobble {
    data object None : ExitScrobble
    /** Still in progress below the trackers' watched line: a stop that records no play. */
    data class StopInProgress(val percent: Float) : ExitScrobble
    /** Past the trackers' watched line but not finished on this TV: a pause keeps a resume point. */
    data class PauseInProgress(val percent: Float) : ExitScrobble
    /** Finished on this TV: a stop the trackers record as a play. */
    data class StopWatched(val percent: Float) : ExitScrobble
}

/**
 * The exit scrobble for stopping at [progressPercent]. "Watched" follows this TV's own rule
 * ([finishedLocally]: the later of 90% and about three minutes left, or the credits), so a title
 * left at 85% isn't a play on Trakt while it is still in Continue Watching here (#620).
 */
internal fun exitScrobbleFor(
    hasActiveScrobble: Boolean,
    progressPercent: Float,
    finishedLocally: Boolean
): ExitScrobble {
    if (finishedLocally) {
        // Finished by the credits marker can be under the trackers' line; lift it so they count the play.
        val percent = progressPercent.coerceAtLeast(TRACKER_WATCHED_PERCENT)
        return if (shouldSendStopScrobble(hasActiveScrobble, percent)) ExitScrobble.StopWatched(percent) else ExitScrobble.None
    }
    if (!shouldSendStopScrobble(hasActiveScrobble, progressPercent)) return ExitScrobble.None
    if (progressPercent < TRACKER_WATCHED_PERCENT) return ExitScrobble.StopInProgress(progressPercent)
    return if (shouldSendPauseScrobble(hasActiveScrobble, progressPercent)) {
        ExitScrobble.PauseInProgress(progressPercent)
    } else {
        ExitScrobble.None
    }
}
