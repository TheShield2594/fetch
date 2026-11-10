package com.fetch.desktop.playback

import com.fetch.desktop.data.AutoPlaySettings
import com.fetch.desktop.data.NextEpisodeThresholdMode
import com.fetch.desktop.skip.Segment
import com.fetch.desktop.skip.SkipButtonRules
import com.fetch.desktop.skip.SegmentType
import com.fetch.tv.core.player.NextEpisodeCardRules

/**
 * When the next episode's card shows, and with autoplay on the next episode starts: the shared
 * [NextEpisodeCardRules] (the TV uses them too), fed this app's skip segments and settings. The
 * post-credits scene after the credits is the one the Skip button finds.
 */
object NextEpisodeRules {
    fun thresholdReached(
        positionSec: Double,
        durationSec: Double,
        segments: List<Segment>,
        mode: NextEpisodeThresholdMode,
        percent: Float,
        minutesBeforeEnd: Float
    ): Boolean {
        val credits = segments.filter { it.type == SegmentType.OUTRO && it.endSec > it.startSec }
        val scene = credits.maxByOrNull { it.endSec }?.let { SkipButtonRules.followingPostCredits(it, segments, durationSec) }
        return NextEpisodeCardRules.shouldShow(
            positionSec = positionSec,
            durationSec = durationSec,
            credits = credits.map { NextEpisodeCardRules.Credits(it.startSec, it.endSec) },
            postCreditsScene = scene?.let { NextEpisodeCardRules.PostCreditsScene(it.startSec, it.endSec, guessed = it.provider == HEURISTIC) },
            threshold = threshold(mode, percent, minutesBeforeEnd)
        )
    }

    /** Seconds before the end the threshold stands at. */
    fun thresholdSecondsFromEnd(durationSec: Double, mode: NextEpisodeThresholdMode, percent: Float, minutesBeforeEnd: Float): Double =
        NextEpisodeCardRules.secondsFromEnd(durationSec, threshold(mode, percent, minutesBeforeEnd))

    private fun threshold(mode: NextEpisodeThresholdMode, percent: Float, minutesBeforeEnd: Float): NextEpisodeCardRules.Threshold =
        when (mode) {
            NextEpisodeThresholdMode.PERCENTAGE -> NextEpisodeCardRules.Threshold.Percent(percent)
            NextEpisodeThresholdMode.MINUTES_BEFORE_END -> NextEpisodeCardRules.Threshold.MinutesBeforeEnd(minutesBeforeEnd)
        }

    /** The provider [SkipButtonRules.followingPostCredits] gives a scene it guessed from the gap. */
    private const val HEURISTIC = "heuristic"

    /** A position clearly before the end and outside the card's window (arms end detection, as on TV). */
    fun isAwayFromEnd(positionSec: Double, durationSec: Double, segments: List<Segment>, settings: AutoPlaySettings): Boolean =
        durationSec > 0 && positionSec < durationSec - 0.5 && !thresholdReached(positionSec, durationSec, segments, settings)

    fun thresholdReached(positionSec: Double, durationSec: Double, segments: List<Segment>, settings: AutoPlaySettings): Boolean =
        thresholdReached(
            positionSec, durationSec, segments,
            settings.nextEpisodeThresholdMode, settings.nextEpisodeThresholdPercent, settings.nextEpisodeThresholdMinutesBeforeEnd
        )

    /**
     * Whether to look up the next episode's sources now ("Preload Next Episode Sources"): the
     * TV starts max(timeout, 10 s), at most 30 s, before the card would show or the file ends.
     */
    fun shouldPreload(positionSec: Double, durationSec: Double, segments: List<Segment>, settings: AutoPlaySettings, timeoutSeconds: Int): Boolean {
        if (durationSec <= 0) return false
        val lead = (if (timeoutSeconds == AutoPlaySettings.TIMEOUT_UNLIMITED) 30.0 else maxOf(timeoutSeconds.toDouble(), 10.0)).coerceAtMost(30.0)
        return thresholdReached(positionSec + lead, durationSec, segments, settings) || durationSec - positionSec <= lead
    }
}
