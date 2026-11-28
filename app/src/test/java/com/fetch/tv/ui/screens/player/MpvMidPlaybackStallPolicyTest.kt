package com.fetch.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class MpvMidPlaybackStallPolicyTest {

    private fun input(
        enabled: Boolean = true,
        userPaused: Boolean = false,
        pausedForCache: Boolean = false,
        eofReached: Boolean = false,
        cacheProgressing: Boolean = false,
        positionMs: Long = 10 * 60_000L,
        durationMs: Long = 100 * 60_000L,
        earlyEofTicks: Int = 0,
        cacheStallTicks: Int = 0,
    ) = MpvMidPlaybackStallPolicy.Input(
        enabled, userPaused, pausedForCache, eofReached, cacheProgressing,
        positionMs, durationMs, earlyEofTicks, cacheStallTicks
    )

    private fun run(start: MpvMidPlaybackStallPolicy.Input, ticks: Int): MpvMidPlaybackStallPolicy.Action {
        var current = start
        repeat(ticks) {
            val step = MpvMidPlaybackStallPolicy.step(current)
            if (step.action != MpvMidPlaybackStallPolicy.Action.Continue) return step.action
            current = current.copy(earlyEofTicks = step.earlyEofTicks, cacheStallTicks = step.cacheStallTicks)
        }
        return MpvMidPlaybackStallPolicy.Action.Continue
    }

    @Test
    fun `early end of file recovers after the timeout`() {
        assertEquals(
            MpvMidPlaybackStallPolicy.Action.EarlyEof,
            run(input(eofReached = true), MpvMidPlaybackStallPolicy.earlyEofTicksLimit)
        )
    }

    @Test
    fun `end of file past the natural end is left alone`() {
        assertEquals(
            MpvMidPlaybackStallPolicy.Action.Continue,
            run(input(eofReached = true, positionMs = 95 * 60_000L), 100)
        )
    }

    @Test
    fun `a cache pause with no growth recovers after the timeout`() {
        assertEquals(
            MpvMidPlaybackStallPolicy.Action.Continue,
            run(input(pausedForCache = true), MpvMidPlaybackStallPolicy.cacheStallTicksLimit - 1)
        )
        assertEquals(
            MpvMidPlaybackStallPolicy.Action.CacheStall,
            run(input(pausedForCache = true), MpvMidPlaybackStallPolicy.cacheStallTicksLimit)
        )
    }

    @Test
    fun `a growing cache, a user pause or a disabled watchdog never fires`() {
        val ticks = MpvMidPlaybackStallPolicy.cacheStallTicksLimit * 2
        assertEquals(
            MpvMidPlaybackStallPolicy.Action.Continue,
            run(input(pausedForCache = true, cacheProgressing = true), ticks)
        )
        assertEquals(
            MpvMidPlaybackStallPolicy.Action.Continue,
            run(input(pausedForCache = true, userPaused = true), ticks)
        )
        assertEquals(
            MpvMidPlaybackStallPolicy.Action.Continue,
            run(input(enabled = false, pausedForCache = true, eofReached = true), ticks)
        )
    }
}
