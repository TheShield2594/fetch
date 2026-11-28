package com.fetch.tv.ui.screens.player

/**
 * After the first frame, mpv has no error for a connection that drops mid-film: with
 * `keep-open=yes` it reaches end of file and pauses there, or sits in `paused-for-cache` for good,
 * and the spinner shows forever (#697). This counts those ticks so the player can run its usual
 * mid-playback recovery (a fresh link at the same position, then the next sources).
 */
internal object MpvMidPlaybackStallPolicy {
    const val POLL_INTERVAL_MS = MpvStartupWatchdogPolicy.POLL_INTERVAL_MS
    /** An end of file short of the natural end is a dropped or truncated connection, not a finish. */
    const val EARLY_EOF_TIMEOUT_MS = 3_000L
    /** How long the cache may sit paused with no growth before the stream counts as stalled. */
    const val CACHE_STALL_TIMEOUT_MS = 20_000L
    /** Below this fraction of the duration, an end of file is early (as in natural completion). */
    const val EARLY_EOF_MAX_FRACTION = 0.8

    val earlyEofTicksLimit: Int = (EARLY_EOF_TIMEOUT_MS / POLL_INTERVAL_MS).toInt()
    val cacheStallTicksLimit: Int = (CACHE_STALL_TIMEOUT_MS / POLL_INTERVAL_MS).toInt()

    enum class Action { Continue, EarlyEof, CacheStall }

    data class Input(
        /** First frame shown, not live, no error, not releasing or already recovering, in front. */
        val enabled: Boolean,
        val userPaused: Boolean,
        val pausedForCache: Boolean,
        val eofReached: Boolean,
        val cacheProgressing: Boolean,
        val positionMs: Long,
        val durationMs: Long,
        val earlyEofTicks: Int,
        val cacheStallTicks: Int,
    )

    data class Step(val action: Action, val earlyEofTicks: Int, val cacheStallTicks: Int)

    fun isEarlyEof(eofReached: Boolean, positionMs: Long, durationMs: Long): Boolean =
        eofReached && durationMs > 0L && positionMs < durationMs * EARLY_EOF_MAX_FRACTION

    fun step(input: Input): Step {
        if (!input.enabled) return Step(Action.Continue, 0, 0)

        val earlyEof = isEarlyEof(input.eofReached, input.positionMs, input.durationMs)
        val eofTicks = if (earlyEof) input.earlyEofTicks + 1 else 0
        if (eofTicks >= earlyEofTicksLimit) return Step(Action.EarlyEof, 0, 0)

        // A pause the user asked for is not a stall, and a growing cache is still on its way.
        val cacheStalled = input.pausedForCache && !input.userPaused && !input.cacheProgressing
        val stallTicks = if (cacheStalled) input.cacheStallTicks + 1 else 0
        if (stallTicks >= cacheStallTicksLimit) return Step(Action.CacheStall, 0, 0)

        return Step(Action.Continue, eofTicks, stallTicks)
    }
}
