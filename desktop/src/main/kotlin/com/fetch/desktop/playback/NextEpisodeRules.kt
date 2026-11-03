package com.fetch.desktop.playback

import com.fetch.desktop.data.AutoPlaySettings
import com.fetch.desktop.data.NextEpisodeThresholdMode
import com.fetch.desktop.skip.Segment
import com.fetch.desktop.skip.SegmentType

/**
 * When the next episode's card shows, and with autoplay on the next episode starts: the TV's
 * `PlayerNextEpisodeRules.shouldShowNextEpisodeCard`, in seconds. With credits from IntroDB the
 * card shows when they start, unless the credits end well before the file does (a scene may
 * follow), when the threshold applies instead. Without credits the threshold applies.
 */
object NextEpisodeRules {
    /** A duration shorter than the position by more than this isn't a real end. */
    private const val END_EPSILON_SEC = 1.0

    fun thresholdReached(
        positionSec: Double,
        durationSec: Double,
        segments: List<Segment>,
        mode: NextEpisodeThresholdMode,
        percent: Float,
        minutesBeforeEnd: Float
    ): Boolean {
        if (durationSec <= 0.0) return false
        if (positionSec > durationSec + END_EPSILON_SEC) return false
        val credits = segments.filter { it.type == SegmentType.OUTRO && it.endSec > it.startSec }
        val byThreshold = when (mode) {
            NextEpisodeThresholdMode.PERCENTAGE -> positionSec / durationSec >= clampPercent(percent) / 100.0
            NextEpisodeThresholdMode.MINUTES_BEFORE_END -> durationSec - positionSec <= clampMinutes(minutesBeforeEnd) * 60.0
        }
        if (credits.isEmpty()) return byThreshold
        val gapAfterCredits = durationSec - credits.maxOf { it.endSec }
        return if (gapAfterCredits > thresholdSecondsFromEnd(durationSec, mode, percent, minutesBeforeEnd)) {
            byThreshold
        } else {
            positionSec >= credits.minOf { it.startSec }
        }
    }

    /** Seconds before the end the threshold stands at. */
    fun thresholdSecondsFromEnd(durationSec: Double, mode: NextEpisodeThresholdMode, percent: Float, minutesBeforeEnd: Float): Double =
        when (mode) {
            NextEpisodeThresholdMode.PERCENTAGE -> (1.0 - clampPercent(percent) / 100.0) * durationSec
            NextEpisodeThresholdMode.MINUTES_BEFORE_END -> clampMinutes(minutesBeforeEnd) * 60.0
        }

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

    private fun clampPercent(percent: Float) = percent.coerceIn(97f, 100f)
    private fun clampMinutes(minutes: Float) = minutes.coerceIn(0f, 3.5f)
}
