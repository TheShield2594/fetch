package com.fetch.desktop.data

import kotlinx.serialization.Serializable

/** When the next episode's card (and with autoplay, the next episode) starts, as on TV. */
enum class NextEpisodeThresholdMode(val label: String) {
    PERCENTAGE("Percentage"),
    MINUTES_BEFORE_END("Minutes before end")
}

/**
 * The TV app's Playback › Auto-play settings the desktop didn't have (`player_settings` there),
 * with its defaults. Synced with the TV under the same keys; see `sync/AutoPlaySync.kt`.
 */
@Serializable
data class AutoPlaySettings(
    /** `next_episode_threshold_mode`: by playback percent or by minutes before the end. */
    val nextEpisodeThresholdMode: NextEpisodeThresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
    /** `next_episode_threshold_percent_v2`: 97 to 100 in half steps, used when no credits time is known. */
    val nextEpisodeThresholdPercent: Float = 99f,
    /** `next_episode_threshold_minutes_before_end_v2`: 0 to 3.5 in half steps. */
    val nextEpisodeThresholdMinutesBeforeEnd: Float = 2f,
    /** `stream_reuse_last_link_enabled`: play the last link that worked for the same movie or episode. */
    val reuseLastLink: Boolean = false,
    /** `stream_reuse_last_link_cache_hours`: how long that link is trusted. */
    val reuseLastLinkCacheHours: Int = 1,
    /** `preload_next_episode_sources`: ask the addons about the next episode before its card shows. */
    val preloadNextEpisode: Boolean = false,
    /** `stream_auto_play_next_episode_fallback_enabled`: pick normally when the binge group isn't found. */
    val nextEpisodeFallback: Boolean = true,
    /** `stream_auto_play_prefer_bingegroup_next_episode`: the next episode tries the same source group first. */
    val preferBingeGroup: Boolean = true,
    /** `stream_auto_play_reuse_binge_group`: remember a show's binge group for later sessions too. */
    val reuseBingeGroup: Boolean = false,
    /**
     * `stream_auto_play_selected_addons`: the addons (by name) auto-play may pick from; empty
     * means every addon. Media servers always count.
     */
    val allowedAddons: Set<String> = emptySet(),
    /** `stream_auto_play_start_early`: Best match starts once the best so far is good enough. */
    val startEarly: Boolean = false,
    /** `best_match_preview_enabled`: ask the addons while a title page is open, so Play names its pick. */
    val bestMatchPreview: Boolean = true,
    /** `post_play_recommendations_enabled`: a recommendation near the end of a movie. */
    val postPlayRecommendations: Boolean = true,
    /** `post_play_movie_threshold_percent`: 80 to 100. */
    val postPlayMovieThresholdPercent: Int = DEFAULT_POST_PLAY_MOVIE_THRESHOLD_PERCENT,
    /** `parental_guide_enabled`: the content warnings overlay when playback starts. */
    val parentalGuide: Boolean = true,
    /**
     * `stream_auto_play_next_source_on_failure`: when the built-in player can't open a source,
     * try the next one in the ranking instead of stopping.
     */
    val nextSourceOnFailure: Boolean = true
) {
    companion object {
        const val DEFAULT_POST_PLAY_MOVIE_THRESHOLD_PERCENT = 96
        const val MIN_POST_PLAY_MOVIE_THRESHOLD_PERCENT = 80
        const val MAX_POST_PLAY_MOVIE_THRESHOLD_PERCENT = 100

        /** The TV's "Unlimited" stream selection timeout. */
        const val TIMEOUT_UNLIMITED = Int.MAX_VALUE

        /** The TV's Stream Selection Timeout choices, in seconds. */
        val TIMEOUT_VALUES: List<Int> = listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 15, 20, 25, 30, TIMEOUT_UNLIMITED)

        val CACHE_HOURS: List<Int> = listOf(1, 2, 3, 6, 12, 24, 48, 72, 168)

        /** The nearest choice the TV offers to a saved timeout (older desktop builds allowed up to 120 s). */
        fun normalizeTimeout(seconds: Int): Int {
            if (seconds in TIMEOUT_VALUES) return seconds
            if (seconds > 30) return 30
            return TIMEOUT_VALUES.filter { it != TIMEOUT_UNLIMITED }.minBy { kotlin.math.abs(it.toLong() - seconds.toLong()) }
        }

        /** A wait with an end: not Instant and not Unlimited. */
        fun isBoundedTimeout(seconds: Int): Boolean = seconds > 0 && seconds != TIMEOUT_UNLIMITED

        /**
         * How long one addon's request may take: at least 20 s so a slow addon still fills the
         * list after auto-play has picked, and the TV's 60 s hard limit when the wait is unlimited.
         */
        fun requestTimeoutMs(waitSeconds: Int): Long = when {
            waitSeconds == TIMEOUT_UNLIMITED -> 60_000L
            else -> maxOf(20_000L, waitSeconds * 1000L)
        }

        fun timeoutLabel(seconds: Int): String = when (seconds) {
            0 -> "Instant"
            TIMEOUT_UNLIMITED -> "Unlimited"
            else -> "$seconds s"
        }

        /** "1 hour", "2 days", "1d 12h", as the TV labels its cache durations. */
        fun cacheLabel(hours: Int): String = when {
            hours < 24 -> if (hours == 1) "1 hour" else "$hours hours"
            hours % 24 == 0 -> (hours / 24).let { if (it == 1) "1 day" else "$it days" }
            else -> "${hours / 24}d ${hours % 24}h"
        }

        /** Clamped to [min]..[max] and rounded to the nearest half, as the TV stores them. */
        fun halfStep(value: Float, min: Float, max: Float): Float =
            (Math.round(value.coerceIn(min, max) * 2f) / 2f).coerceIn(min, max)
    }
}
