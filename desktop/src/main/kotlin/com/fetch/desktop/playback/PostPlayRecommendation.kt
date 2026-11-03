package com.fetch.desktop.playback

import com.fetch.desktop.data.AutoPlaySettings
import com.fetch.desktop.isUnreleased
import com.fetch.desktop.skip.Segment
import com.fetch.desktop.skip.SegmentType
import com.fetch.tv.domain.model.MetaPreview
import kotlin.math.ceil

/** The title the end of a movie recommends, with what its card shows (the TV's `PostPlayRecommendation`). */
data class PostPlayRecommendation(
    val preview: MetaPreview,
    val title: String,
    val backdrop: String?,
    val logo: String?,
    val description: String?,
    /** "2019 · 2h 12m · Drama, Thriller" */
    val facts: List<String>,
    val rating: Float?,
    /** A YouTube id, when TMDB lists a trailer. */
    val trailerYtId: String?
)

/**
 * When the recommendation card shows near the end of a movie, and its trailer countdown: the
 * TV's `PostPlayRecommendationTiming` and `PostPlayRecommendationState`, in seconds.
 */
object PostPlayTiming {
    /** The card is looked up this much earlier than it shows (as a share of the movie). */
    private const val PREFETCH_LEAD = 0.05
    /** …or ten minutes before the end, whichever is first. */
    private const val PREFETCH_REMAINING_SEC = 10 * 60.0
    /** The trailer countdown in the last seconds, and after the end. */
    const val TRAILER_COUNTDOWN_SECONDS = 5
    /** How many recommendations the card pages through. */
    const val MAX_RECOMMENDATIONS = 4

    /**
     * Where the card shows: the movie threshold, unless IntroDB knows the credits. Then at the
     * credits, or, when the credits end well before the file does (a scene may follow), at the
     * next-episode threshold from the end.
     */
    fun triggerSec(durationSec: Double, segments: List<Segment>, settings: AutoPlaySettings): Double {
        val threshold = settings.postPlayMovieThresholdPercent.coerceIn(
            AutoPlaySettings.MIN_POST_PLAY_MOVIE_THRESHOLD_PERCENT,
            AutoPlaySettings.MAX_POST_PLAY_MOVIE_THRESHOLD_PERCENT
        )
        val fallback = ceil(durationSec * threshold / 100.0)
        val credits = segments.filter {
            it.type == SegmentType.OUTRO && it.startSec >= 0 && it.endSec > it.startSec &&
                it.startSec < durationSec && it.endSec <= durationSec + 1.0
        }
        if (credits.isEmpty()) return fallback
        val creditsEnd = credits.maxOf { it.endSec }
        val userFromEnd = NextEpisodeRules.thresholdSecondsFromEnd(
            durationSec, settings.nextEpisodeThresholdMode, settings.nextEpisodeThresholdPercent, settings.nextEpisodeThresholdMinutesBeforeEnd
        )
        return if (durationSec - creditsEnd > userFromEnd) (durationSec - userFromEnd).coerceAtLeast(creditsEnd)
        else credits.minOf { it.startSec }
    }

    fun shouldShow(positionSec: Double, durationSec: Double, segments: List<Segment>, settings: AutoPlaySettings): Boolean =
        durationSec > 0 && positionSec.coerceIn(0.0, durationSec) >= triggerSec(durationSec, segments, settings)

    fun shouldPrefetch(positionSec: Double, durationSec: Double, segments: List<Segment>, settings: AutoPlaySettings): Boolean {
        if (durationSec <= 0) return false
        val position = positionSec.coerceIn(0.0, durationSec)
        val progress = (triggerSec(durationSec, segments, settings) / durationSec - PREFETCH_LEAD).coerceAtLeast(0.0)
        return position / durationSec >= progress || durationSec - position <= PREFETCH_REMAINING_SEC
    }

    /** The trailer countdown in the movie's last five seconds; null before. */
    fun countdownSeconds(positionSec: Double, durationSec: Double): Int? {
        if (durationSec <= 0) return null
        val remaining = (durationSec - positionSec).coerceAtLeast(0.0)
        if (remaining > TRAILER_COUNTDOWN_SECONDS) return null
        return ceil(remaining).toInt().coerceIn(1, TRAILER_COUNTDOWN_SECONDS)
    }

    /**
     * The TV's candidate rules: not the movie that played, nothing watched, nothing unreleased
     * when those are hidden, nothing a limit hides; the first with a backdrop leads.
     */
    fun candidates(
        recommendations: List<MetaPreview>,
        currentIds: Set<String>,
        watched: (MetaPreview) -> Boolean,
        allowed: (MetaPreview) -> Boolean,
        hideUnreleased: Boolean
    ): List<MetaPreview> {
        val current = currentIds.map { it.lowercase() }.toSet()
        val filtered = recommendations.asSequence()
            .filterNot { it.id.lowercase() in current || it.imdbId?.lowercase() in current }
            .filterNot(watched)
            .filter(allowed)
            .filterNot { hideUnreleased && isUnreleased(it) }
            .distinctBy { it.id.lowercase() }
            .toList()
        val first = filtered.firstOrNull { !it.background.isNullOrBlank() } ?: filtered.firstOrNull() ?: return emptyList()
        return listOf(first) + filtered.filterNot { it === first }.take(MAX_RECOMMENDATIONS - 1)
    }
}
