package com.fetch.tv.domain.model

import androidx.compose.runtime.Immutable

/**
 * Represents the watch progress for a content item (movie or episode).
 */
@Immutable
data class WatchProgress(
    val contentId: String,           // IMDB ID of the movie/series
    val contentType: String,         // "movie" or "series"
    val name: String,                // Movie or series name
    val poster: String?,             // Poster URL
    val backdrop: String?,           // Backdrop URL
    val logo: String?,               // Logo URL
    val videoId: String,             // Specific video/episode ID being watched
    val season: Int?,                // Season number (null for movies)
    val episode: Int?,               // Episode number (null for movies)
    val episodeTitle: String?,       // Episode title (null for movies)
    val position: Long,              // Current playback position in ms
    val duration: Long,              // Total duration in ms
    val lastWatched: Long,           // Timestamp when last watched
    val addonBaseUrl: String? = null, // Addon that was used to play
    val progressPercent: Float? = null, // 0..100 from remote sources like Trakt playback
    val source: String = SOURCE_LOCAL,
    val traktPlaybackId: Long? = null,
    val traktMovieId: Int? = null,
    val traktShowId: Int? = null,
    val traktEpisodeId: Int? = null,
    val simklPlaybackId: Long? = null,
    val trackingProviderId: String? = null,
    val trackingProviderItemId: String? = null,
    val trackingSourceUrl: String? = null,
    val completionThresholdOverride: Float? = null,
    val excludedNextUpSeasons: Set<Int> = emptySet()
) {
    companion object {
        const val SOURCE_LOCAL = "local"
        const val SOURCE_TRAKT_PLAYBACK = "trakt_playback"
        const val SOURCE_TRAKT_HISTORY = "trakt_history"
        const val SOURCE_TRAKT_SHOW_PROGRESS = "trakt_show_progress"
        const val SOURCE_SIMKL_PLAYBACK = "simkl_playback"
        const val SOURCE_REMOTE_PLAYBACK = "remote_playback"
        const val SOURCE_REMOTE_HISTORY = "remote_history"
        const val STARTED_THRESHOLD = 0.02f
        /** The flat share of a title that counts as watched when no better rule applies. */
        const val COMPLETED_THRESHOLD = 0.90f
        const val SIMKL_COMPLETED_THRESHOLD = 0.80f

        /**
         * Google's Watch Next rule: a title is finished once under about three minutes are left.
         * Used with [COMPLETED_THRESHOLD] so a long film keeps its resume point well past 90%.
         */
        const val COMPLETED_REMAINING_MS = 3L * 60_000L

        /**
         * Share of a title of [durationMs] that counts as watched: whichever comes later of 90% and
         * "about three minutes left". A 180-minute film finishes at 177 minutes, not 162; anything
         * under 30 minutes keeps the flat 90%. An unknown length falls back to 90%.
         */
        fun completedFraction(durationMs: Long): Float {
            if (durationMs <= 0L) return COMPLETED_THRESHOLD
            val remainingRule = 1f - COMPLETED_REMAINING_MS.toFloat() / durationMs.toFloat()
            return maxOf(COMPLETED_THRESHOLD, remainingRule)
        }

        /**
         * Whether playback stopped at [positionMs] of [durationMs] counts as finished. When the
         * credits' start is known ([creditsStartMs]) reaching it is enough; a credits marker in
         * the first half of the title is ignored as a bad marker.
         */
        fun isFinishedAt(positionMs: Long, durationMs: Long, creditsStartMs: Long? = null): Boolean {
            if (durationMs <= 0L || positionMs <= 0L) return false
            if (creditsStartMs != null && creditsStartMs >= durationMs / 2 && positionMs >= creditsStartMs) {
                return true
            }
            // The later of the two thresholds means both must hold; whole milliseconds avoid float edges.
            return positionMs * 10L >= durationMs * 9L && durationMs - positionMs <= COMPLETED_REMAINING_MS
        }

        /**
         * How far before the saved position to resume after [gapMs] away: nothing after a short
         * break, a few seconds after a longer one, a little more after a day or more, so the
         * viewer picks up the thread instead of landing mid-sentence.
         */
        fun resumeRewindMs(gapMs: Long): Long = when {
            gapMs < 10L * 60_000L -> 0L
            gapMs < 24L * 60L * 60_000L -> 5_000L
            else -> 10_000L
        }
    }

    /**
     * Progress percentage (0.0 to 1.0)
     */
    val progressPercentage: Float
        get() {
            progressPercent?.let { explicitPercent ->
                return (explicitPercent / 100f).coerceIn(0f, 1f)
            }
            return if (duration > 0) (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
        }

    /**
     * Returns true if the content has been watched past the threshold: the later of 90% and
     * "about three minutes left" when the length is known (see [completedFraction]).
     */
    fun isCompleted(threshold: Float = completionThreshold()): Boolean = progressPercentage >= threshold

    /**
     * Returns true if the content has been started but not completed
     */
    fun isInProgress(startThreshold: Float = STARTED_THRESHOLD, endThreshold: Float = completionThreshold()): Boolean =
        progressPercentage >= startThreshold && progressPercentage < endThreshold

    private fun completionThreshold(): Float =
        completionThresholdOverride?.takeIf { it.isFinite() && it > 0f && it <= 1f }
            ?: when {
                source == SOURCE_SIMKL_PLAYBACK -> SIMKL_COMPLETED_THRESHOLD
                // A remote percentage has no reliable length behind it, so it keeps the flat 90%.
                progressPercent != null -> COMPLETED_THRESHOLD
                else -> completedFraction(duration)
            }

    /**
     * Returns the remaining time in milliseconds
     */
    val remainingTime: Long
        get() = (duration - position).coerceAtLeast(0)

    /**
     * [resolveResumePosition] stepped back by [resumeRewindMs] for the time since [lastWatched].
     * A position of zero (start from the beginning) stays zero.
     */
    fun resolveResumePositionWithRewind(actualDuration: Long, nowMs: Long): Long {
        val resolved = resolveResumePosition(actualDuration)
        if (resolved <= 0L || lastWatched <= 0L) return resolved
        return (resolved - resumeRewindMs(nowMs - lastWatched)).coerceAtLeast(0L)
    }

    fun resolveResumePosition(actualDuration: Long): Long {
        if (actualDuration <= 0) return position.coerceAtLeast(0L)
        // Position is the most precise resume indicator. Prefer it over
        // progressPercent even when saved duration is 0 (e.g. progress was
        // saved while paused and getEffectiveDuration returned 0).
        if (position > 0) {
            return position.coerceIn(0L, actualDuration)
        }
        progressPercent?.let { explicitPercent ->
            val fraction = (explicitPercent / 100f).coerceIn(0f, 1f)
            return (actualDuration * fraction).toLong()
        }
        return position.coerceAtLeast(0L)
    }
}

/**
 * Represents the next item to watch for a series or a movie to resume.
 */
@Immutable
data class NextToWatch(
    val watchProgress: WatchProgress?,  // Null if nothing has been watched yet
    val isResume: Boolean,              // True if resuming current item, false if next episode
    val nextVideoId: String?,           // Video ID to play next
    val nextSeason: Int?,               // Next season number
    val nextEpisode: Int?,              // Next episode number
    val displayText: String             // Text to show on button (e.g., "Resume S1 E2", "Play S1 E3")
)
