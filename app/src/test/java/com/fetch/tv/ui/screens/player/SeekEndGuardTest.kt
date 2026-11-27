package com.fetch.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SeekEndGuardTest {

    private val duration = 60 * 60_000L

    @Test
    fun forwardSeekNearTheEnd_stopsShortOfIt() {
        val base = duration - 20_000L
        assertEquals(duration - SEEK_END_GUARD_MS, seekTargetWithEndGuard(base, base + 30_000L, duration))
    }

    @Test
    fun forwardSeekInsideTheGuard_staysPut() {
        val base = duration - 2_000L
        assertEquals(base, seekTargetWithEndGuard(base, base + 30_000L, duration))
    }

    @Test
    fun backwardSeek_isNotClamped() {
        val base = duration - 2_000L
        assertEquals(base - 10_000L, seekTargetWithEndGuard(base, base - 10_000L, duration))
    }

    @Test
    fun seekBeforeTheStart_landsAtZero() {
        assertEquals(0L, seekTargetWithEndGuard(5_000L, -10_000L, duration))
    }

    @Test
    fun unknownDuration_onlyClampsAtZero() {
        assertEquals(90_000L, seekTargetWithEndGuard(60_000L, 90_000L, Long.MAX_VALUE))
        assertEquals(90_000L, seekTargetWithEndGuard(60_000L, 90_000L, 0L))
    }
}
