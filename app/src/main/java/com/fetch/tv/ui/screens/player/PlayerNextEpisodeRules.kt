package com.fetch.tv.ui.screens.player

import com.fetch.tv.core.player.NextEpisodeCardRules
import com.fetch.tv.data.local.NextEpisodeThresholdMode
import com.fetch.tv.data.repository.SkipInterval
import com.fetch.tv.core.util.isEpisodeReleaseAired
import com.fetch.tv.core.util.parseEpisodeReleaseLocalDate
import com.fetch.tv.domain.model.Video
import java.time.Clock
import java.time.LocalDate

object PlayerNextEpisodeRules {
    fun resolveNextEpisode(
        videos: List<Video>,
        currentSeason: Int?,
        currentEpisode: Int
    ): Video? {
        // Absolute-numbered content (e.g. some anime via Kitsu) carries no season; order by episode
        // number alone and advance to the next one.
        if (currentSeason == null) {
            val sorted = videos
                .filter { it.episode != null }
                .sortedWith(compareBy<Video>({ it.season ?: 0 }, { it.episode ?: 0 }))
            val index = sorted.indexOfFirst { it.episode == currentEpisode }
            return if (index < 0) null else sorted.getOrNull(index + 1)
        }

        val sortedEpisodes = videos
            .filter { it.season != null && it.episode != null }
            .sortedWith(compareBy<Video> { it.season ?: Int.MAX_VALUE }.thenBy { it.episode ?: Int.MAX_VALUE })

        val currentIndex = sortedEpisodes.indexOfFirst {
            it.season == currentSeason && it.episode == currentEpisode
        }
        if (currentIndex < 0) return null

        return sortedEpisodes.getOrNull(currentIndex + 1)
    }

    /** The shared [NextEpisodeCardRules], fed this app's skip segments and settings. */
    fun shouldShowNextEpisodeCard(
        positionMs: Long,
        durationMs: Long,
        skipIntervals: List<SkipInterval>,
        thresholdMode: NextEpisodeThresholdMode,
        thresholdPercent: Float,
        thresholdMinutesBeforeEnd: Float
    ): Boolean {
        val outros = skipIntervals.filter { it.type in OUTRO_SEGMENT_TYPES && it.endTime > it.startTime }
        // The same post-credits detection as the Skip button, so the card never covers the scene.
        val scene = if (durationMs > 0L) {
            outros.maxByOrNull { it.endTime }?.followingPostCreditsScene(skipIntervals, durationMs)
        } else {
            null
        }
        return NextEpisodeCardRules.shouldShow(
            positionSec = positionMs / 1_000.0,
            durationSec = durationMs / 1_000.0,
            credits = outros.map { NextEpisodeCardRules.Credits(it.startTime, it.endTime) },
            postCreditsScene = scene?.let {
                NextEpisodeCardRules.PostCreditsScene(it.startTime, it.endTime, guessed = it.provider == "heuristic")
            },
            threshold = threshold(thresholdMode, thresholdPercent, thresholdMinutesBeforeEnd)
        )
    }

    fun threshold(
        mode: NextEpisodeThresholdMode,
        percent: Float,
        minutesBeforeEnd: Float
    ): NextEpisodeCardRules.Threshold = when (mode) {
        NextEpisodeThresholdMode.PERCENTAGE -> NextEpisodeCardRules.Threshold.Percent(percent)
        NextEpisodeThresholdMode.MINUTES_BEFORE_END -> NextEpisodeCardRules.Threshold.MinutesBeforeEnd(minutesBeforeEnd)
    }

    /** True when a reading is clearly before the end and outside the next-episode window. */
    fun isAwayFromEnd(
        positionMs: Long,
        durationMs: Long,
        skipIntervals: List<SkipInterval>,
        thresholdMode: NextEpisodeThresholdMode,
        thresholdPercent: Float,
        thresholdMinutesBeforeEnd: Float
    ): Boolean =
        durationMs > 0L &&
            positionMs < durationMs - NEAR_END_MS &&
            !shouldShowNextEpisodeCard(
                positionMs = positionMs,
                durationMs = durationMs,
                skipIntervals = skipIntervals,
                thresholdMode = thresholdMode,
                thresholdPercent = thresholdPercent,
                thresholdMinutesBeforeEnd = thresholdMinutesBeforeEnd
            )

    fun parseEpisodeReleaseDate(raw: String?): LocalDate? {
        return parseEpisodeReleaseLocalDate(raw)
    }

    fun hasEpisodeAired(raw: String?, clock: Clock = Clock.systemDefaultZone()): Boolean {
        return isEpisodeReleaseAired(raw, clock) ?: true
    }

    val OUTRO_SEGMENT_TYPES = setOf("outro", "ed", "mixed-ed")

    const val POST_OUTRO_AUTOPLAY_GAP_MS = 5_000L

    const val END_OF_VIDEO_EPSILON_MS = 1_000L

    /** How close to the duration MPV treats as the end of the file. */
    const val NEAR_END_MS = 500L
}
