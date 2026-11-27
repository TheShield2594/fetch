package com.fetch.tv.core.player

/**
 * When the next episode's card shows (and, with autoplay on, when the next episode starts), for
 * both the TV app and the desktop app. Times are in seconds.
 *
 * With the credits known (IntroDB, AniSkip and the like):
 * - a post-credits scene after them holds the card back so the scene is seen: an explicit scene
 *   until it ends or the threshold, whichever is later; a guessed one until the threshold, or,
 *   when it is shorter than the threshold's window, not at all (autoplay takes over at the end);
 * - otherwise the card shows when the credits start, unless they end well before the file does,
 *   when the threshold applies instead.
 * Without credits the threshold applies to the card, and autoplay's countdown waits for the last
 * seconds of the file ([countdownMayStart], #693).
 *
 * Each app finds the credits and the scene after them with its own skip-segment rules.
 */
object NextEpisodeCardRules {
    /** The user's setting: a share of the file played, or minutes left. */
    sealed interface Threshold {
        /** 97 to 100. */
        data class Percent(val percent: Float) : Threshold

        /** 0 to 3.5. */
        data class MinutesBeforeEnd(val minutes: Float) : Threshold
    }

    /** A credits segment. */
    data class Credits(val startSec: Double, val endSec: Double)

    /** A scene after the credits: from the segment provider, or [guessed] from the time left after them. */
    data class PostCreditsScene(val startSec: Double, val endSec: Double, val guessed: Boolean)

    /** A duration shorter than the position by more than this isn't a real end. */
    const val END_EPSILON_SEC = 1.0

    const val MIN_PERCENT = 97f
    const val MAX_PERCENT = 100f
    const val MAX_MINUTES_BEFORE_END = 3.5f

    /**
     * Whether the card shows at [positionSec]. [postCreditsScene] is the scene after the latest
     * of [credits], when there is one.
     */
    fun shouldShow(
        positionSec: Double,
        durationSec: Double,
        credits: List<Credits>,
        postCreditsScene: PostCreditsScene?,
        threshold: Threshold
    ): Boolean {
        if (durationSec <= 0.0) return false
        if (positionSec > durationSec + END_EPSILON_SEC) return false
        val byThreshold = when (threshold) {
            is Threshold.Percent -> positionSec / durationSec >= clampPercent(threshold.percent) / 100.0
            is Threshold.MinutesBeforeEnd -> durationSec - positionSec <= clampMinutes(threshold.minutes) * 60.0
        }
        if (!timedByCredits(durationSec, credits, postCreditsScene, threshold)) return byThreshold
        if (postCreditsScene != null) {
            val fromEnd = secondsFromEnd(durationSec, threshold)
            val trigger = durationSec - fromEnd
            val sceneEnd = postCreditsScene.endSec.coerceAtMost(durationSec)
            return when {
                postCreditsScene.guessed && sceneEnd - postCreditsScene.startSec < fromEnd -> false
                postCreditsScene.guessed -> positionSec >= trigger
                else -> positionSec >= maxOf(sceneEnd, trigger)
            }
        }
        return positionSec >= credits.filter { it.endSec > it.startSec }.minOf { it.startSec }
    }

    /**
     * Whether autoplay's countdown may run at [positionSec], once the card is up. When the card
     * was timed by the credits (it shows as they start, or after the scene that follows them), the
     * countdown runs at once. Otherwise the card shows at the threshold, which can be minutes
     * before the end, so the countdown waits for the file's last [countdownSec] seconds and the
     * next episode starts at the end rather than cutting the final scene (#693).
     */
    fun countdownMayStart(
        positionSec: Double,
        durationSec: Double,
        credits: List<Credits>,
        postCreditsScene: PostCreditsScene?,
        threshold: Threshold,
        countdownSec: Int
    ): Boolean {
        if (durationSec <= 0.0) return true
        if (timedByCredits(durationSec, credits, postCreditsScene, threshold)) return true
        return durationSec - positionSec <= countdownSec.coerceAtLeast(0)
    }

    /** Whether [shouldShow] times the card from the credits rather than the threshold alone. */
    fun timedByCredits(
        durationSec: Double,
        credits: List<Credits>,
        postCreditsScene: PostCreditsScene?,
        threshold: Threshold
    ): Boolean {
        val validCredits = credits.filter { it.endSec > it.startSec }
        if (validCredits.isEmpty()) return false
        if (postCreditsScene != null) return true
        return durationSec - validCredits.maxOf { it.endSec } <= secondsFromEnd(durationSec, threshold)
    }

    /** Seconds before the end the threshold stands at. */
    fun secondsFromEnd(durationSec: Double, threshold: Threshold): Double = when (threshold) {
        is Threshold.Percent -> (1.0 - clampPercent(threshold.percent) / 100.0) * durationSec
        is Threshold.MinutesBeforeEnd -> clampMinutes(threshold.minutes) * 60.0
    }

    fun clampPercent(percent: Float): Float = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)

    fun clampMinutes(minutes: Float): Float = minutes.coerceIn(0f, MAX_MINUTES_BEFORE_END)
}
