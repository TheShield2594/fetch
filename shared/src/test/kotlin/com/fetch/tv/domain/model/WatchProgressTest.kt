package com.fetch.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchProgressTest {

    @Test
    fun `resolveResumePosition returns position when duration and position are both valid`() {
        val wp = watchProgress(
            position = 60000,
            duration = 180000
        )
        val result = wp.resolveResumePosition(actualDuration = 200000)
        assertEquals(60000, result)
    }

    @Test
    fun `resolveResumePosition clamps position to actualDuration`() {
        val wp = watchProgress(
            position = 300000,
            duration = 180000
        )
        val result = wp.resolveResumePosition(actualDuration = 200000)
        assertEquals(200000, result)
    }

    @Test
    fun `resolveResumePosition returns position when duration is zero`() {
        // Regression test for #2580: resume-after-pause bug
        // When saved duration is 0 but position is valid, position must be honored.
        val wp = watchProgress(
            position = 60000,
            duration = 0
        )
        val result = wp.resolveResumePosition(actualDuration = 200000)
        assertEquals(60000, result)
    }

    @Test
    fun `resolveResumePosition returns position when duration is zero and progressPercent is set`() {
        // Regression test for #2580: exact bug scenario
        // When exiting while paused, getEffectiveDuration returns 0,
        // saveWatchProgressInternal sets fallbackPercent = 5f.
        // Position must take priority over progressPercent.
        val wp = watchProgress(
            position = 60000,
            duration = 0,
            progressPercent = 5f
        )
        val result = wp.resolveResumePosition(actualDuration = 200000)
        // Should use position (60000), not 5% of 200000 (= 10000)
        assertEquals(60000, result)
    }

    @Test
    fun `resolveResumePosition uses progressPercent when position is zero`() {
        val wp = watchProgress(
            position = 0,
            duration = 0,
            progressPercent = 50f
        )
        val result = wp.resolveResumePosition(actualDuration = 200000)
        assertEquals(100000, result) // 50% of 200000
    }

    @Test
    fun `resolveResumePosition returns position when actualDuration is zero`() {
        val wp = watchProgress(
            position = 60000,
            duration = 0
        )
        val result = wp.resolveResumePosition(actualDuration = 0)
        assertEquals(60000, result)
    }

    @Test
    fun `resolveResumePosition returns zero when everything is zero`() {
        val wp = watchProgress(
            position = 0,
            duration = 0
        )
        val result = wp.resolveResumePosition(actualDuration = 0)
        assertEquals(0, result)
    }

    @Test
    fun `a 180-minute film stopped at 165 minutes still resumes`() {
        val wp = watchProgress(position = 165 * MINUTE, duration = 180 * MINUTE)
        assertFalse(wp.isCompleted())
        assertTrue(wp.isInProgress())
    }

    @Test
    fun `a 180-minute film is finished with under three minutes left`() {
        assertTrue(watchProgress(position = 177 * MINUTE + 30_000L, duration = 180 * MINUTE).isCompleted())
        assertFalse(watchProgress(position = 176 * MINUTE, duration = 180 * MINUTE).isCompleted())
    }

    @Test
    fun `short titles keep the flat 90 percent`() {
        // 22-minute episode: three minutes left is 86%, so 90% comes later and wins.
        assertFalse(watchProgress(position = 19 * MINUTE, duration = 22 * MINUTE).isCompleted())
        assertTrue(watchProgress(position = 1_190_000L, duration = 22 * MINUTE).isCompleted())
        assertEquals(WatchProgress.COMPLETED_THRESHOLD, WatchProgress.completedFraction(22 * MINUTE), 0f)
        assertEquals(WatchProgress.COMPLETED_THRESHOLD, WatchProgress.completedFraction(30 * MINUTE), 0.0001f)
    }

    @Test
    fun `unknown length and remote percentages keep the flat 90 percent`() {
        assertEquals(WatchProgress.COMPLETED_THRESHOLD, WatchProgress.completedFraction(0L), 0f)
        assertTrue(watchProgress(position = 0, duration = 0, progressPercent = 91f).isCompleted())
        assertTrue(watchProgress(position = 0, duration = 180 * MINUTE, progressPercent = 92f).isCompleted())
    }

    @Test
    fun `simkl and explicit overrides are unchanged`() {
        val simkl = watchProgress(position = 150 * MINUTE, duration = 180 * MINUTE)
            .copy(source = WatchProgress.SOURCE_SIMKL_PLAYBACK)
        assertTrue(simkl.isCompleted())
        val overridden = watchProgress(position = 160 * MINUTE, duration = 180 * MINUTE)
            .copy(completionThresholdOverride = 0.85f)
        assertTrue(overridden.isCompleted())
    }

    @Test
    fun `reaching the credits marker finishes the title`() {
        val duration = 120 * MINUTE
        assertTrue(WatchProgress.isFinishedAt(110 * MINUTE, duration, creditsStartMs = 108 * MINUTE))
        assertFalse(WatchProgress.isFinishedAt(107 * MINUTE, duration, creditsStartMs = 108 * MINUTE))
        // Without credits the film runs to three minutes left.
        assertFalse(WatchProgress.isFinishedAt(110 * MINUTE, duration))
        assertTrue(WatchProgress.isFinishedAt(117 * MINUTE, duration))
    }

    @Test
    fun `a credits marker in the first half is ignored`() {
        val duration = 100 * MINUTE
        assertFalse(WatchProgress.isFinishedAt(40 * MINUTE, duration, creditsStartMs = 30 * MINUTE))
    }

    @Test
    fun `nothing is finished without a length or a position`() {
        assertFalse(WatchProgress.isFinishedAt(0L, 100 * MINUTE))
        assertFalse(WatchProgress.isFinishedAt(10 * MINUTE, 0L))
    }

    @Test
    fun `resume rewinds a little after a long gap`() {
        val wp = watchProgress(position = 600_000L, duration = 3_600_000L)
        val lastWatched = wp.lastWatched
        assertEquals(600_000L, wp.resolveResumePositionWithRewind(3_600_000L, lastWatched + 5 * MINUTE))
        assertEquals(595_000L, wp.resolveResumePositionWithRewind(3_600_000L, lastWatched + 2 * 60 * MINUTE))
        assertEquals(590_000L, wp.resolveResumePositionWithRewind(3_600_000L, lastWatched + 3 * 24 * 60 * MINUTE))
    }

    @Test
    fun `resume rewind never goes below zero or moves a fresh start`() {
        val day = 24 * 60 * MINUTE
        assertEquals(0L, watchProgress(position = 4_000L, duration = 3_600_000L).resolveResumePositionWithRewind(3_600_000L, day * 2))
        assertEquals(0L, watchProgress(position = 0L, duration = 0L).resolveResumePositionWithRewind(3_600_000L, day * 2))
    }

    private fun watchProgress(
        position: Long,
        duration: Long,
        progressPercent: Float? = null
    ): WatchProgress {
        return WatchProgress(
            contentId = "tt1234567",
            contentType = "movie",
            name = "Test",
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "tt1234567",
            season = null,
            episode = null,
            episodeTitle = null,
            position = position,
            duration = duration,
            lastWatched = 1000L,
            progressPercent = progressPercent
        )
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
