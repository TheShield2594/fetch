package com.fetch.tv.ui.screens.player

import com.fetch.tv.data.local.PlayerSettings

/**
 * The player's one pair of seek steps (#407): how far back and forward a single press goes,
 * whether it comes from the D-pad, the remote's rewind / fast-forward keys or the on-screen
 * buttons, whose labels are drawn from the same pair. Settings › Playback sets both.
 */
data class SeekSteps(
    val backMs: Long = PlayerSettings.DEFAULT_SEEK_BACK_SECONDS * 1_000L,
    val forwardMs: Long = PlayerSettings.DEFAULT_SEEK_FORWARD_SECONDS * 1_000L,
) {
    /** The step (always positive) one press moves in that direction. */
    fun stepMs(forward: Boolean): Long = if (forward) forwardMs else backMs

    /** Signed delta of one press: negative going back. */
    fun deltaMs(forward: Boolean): Long = if (forward) forwardMs else -backMs

    val backSeconds: Int get() = (backMs / 1_000L).toInt()
    val forwardSeconds: Int get() = (forwardMs / 1_000L).toInt()

    companion object {
        fun fromSeconds(backSeconds: Int, forwardSeconds: Int) = SeekSteps(
            backMs = backSeconds.coerceAtLeast(1) * 1_000L,
            forwardMs = forwardSeconds.coerceAtLeast(1) * 1_000L,
        )
    }
}

/**
 * Scrub / seek step sizes for remote D-pad and media keys.
 *
 * A single press moves by the configured [SeekSteps]. Android TV remotes fire repeated
 * ACTION_DOWN events while a direction key is held (`KeyEvent.repeatCount` increases); a long
 * hold ramps up to larger steps so it scrubs faster, but never below the configured step.
 */
object PlayerScrubRates {
    const val STEP_SHORT_MS = 10_000L
    const val STEP_MEDIUM_MS = 20_000L
    const val STEP_LONG_MS = 30_000L
    const val STEP_VERY_LONG_MS = 60_000L

    private const val MEDIUM_REPEAT_THRESHOLD = 3
    private const val LONG_REPEAT_THRESHOLD = 8
    private const val VERY_LONG_REPEAT_THRESHOLD = 15

    /**
     * The hold ramp alone: the step (always positive) a key repeated [repeatCount] times
     * reaches, whatever the configured steps.
     */
    fun stepMsForKeyRepeat(repeatCount: Int): Long {
        val count = repeatCount.coerceAtLeast(0)
        return when {
            count >= VERY_LONG_REPEAT_THRESHOLD -> STEP_VERY_LONG_MS
            count >= LONG_REPEAT_THRESHOLD -> STEP_LONG_MS
            count >= MEDIUM_REPEAT_THRESHOLD -> STEP_MEDIUM_MS
            else -> STEP_SHORT_MS
        }
    }

    /**
     * Signed delta for a left/rewind (negative) or right/forward (positive) press: the
     * configured step on a tap, the hold ramp once it overtakes that step.
     */
    fun deltaMsForKeyRepeat(repeatCount: Int, forward: Boolean, steps: SeekSteps = SeekSteps()): Long {
        val configured = steps.stepMs(forward)
        // Taps and the first repeats move exactly the configured step, even one below the
        // ramp's shortest; only a sustained hold speeds up.
        val step = if (repeatCount < MEDIUM_REPEAT_THRESHOLD) {
            configured
        } else {
            maxOf(configured, stepMsForKeyRepeat(repeatCount))
        }
        return if (forward) step else -step
    }
}

/**
 * What the seek overlay shows during a seek: where playback will land and how far that is
 * from where the seek started (#407).
 */
data class SeekFeedback(val originMs: Long, val targetMs: Long) {
    val deltaMs: Long get() = targetMs - originMs
}

/** Formats [deltaMs] as a signed duration: "+0:30", "−1:05", "+1:02:03". */
internal fun formatSeekDelta(deltaMs: Long): String {
    val sign = if (deltaMs < 0L) "−" else "+"
    val totalSeconds = kotlin.math.abs(deltaMs) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(java.util.Locale.ROOT, "%s%d:%02d:%02d", sign, hours, minutes, seconds)
    } else {
        String.format(java.util.Locale.ROOT, "%s%d:%02d", sign, minutes, seconds)
    }
}
