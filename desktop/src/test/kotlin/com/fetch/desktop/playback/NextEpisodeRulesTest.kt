package com.fetch.desktop.playback

import com.fetch.desktop.data.AutoPlaySettings
import com.fetch.desktop.data.NextEpisodeThresholdMode
import com.fetch.desktop.skip.Segment
import com.fetch.desktop.skip.SegmentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NextEpisodeRulesTest {
    private val duration = 2_400.0 // 40 minutes
    private val percent = AutoPlaySettings()
    private val minutes = AutoPlaySettings(nextEpisodeThresholdMode = NextEpisodeThresholdMode.MINUTES_BEFORE_END, nextEpisodeThresholdMinutesBeforeEnd = 2f)

    @Test
    fun `without credits the threshold applies`() {
        assertFalse(NextEpisodeRules.thresholdReached(2_370.0, duration, emptyList(), percent)) // 98.75 %
        assertTrue(NextEpisodeRules.thresholdReached(2_376.0, duration, emptyList(), percent)) // 99 %
        assertFalse(NextEpisodeRules.thresholdReached(2_279.0, duration, emptyList(), minutes))
        assertTrue(NextEpisodeRules.thresholdReached(2_280.0, duration, emptyList(), minutes))
    }

    @Test
    fun `credits near the end start the card at the credits`() {
        val credits = listOf(Segment(SegmentType.OUTRO, 2_300.0, 2_395.0))
        assertFalse(NextEpisodeRules.thresholdReached(2_299.0, duration, credits, percent))
        assertTrue(NextEpisodeRules.thresholdReached(2_300.0, duration, credits, percent))
    }

    @Test
    fun `credits well before the end leave room for a scene, so the threshold applies`() {
        val credits = listOf(Segment(SegmentType.OUTRO, 2_000.0, 2_100.0))
        assertFalse(NextEpisodeRules.thresholdReached(2_100.0, duration, credits, minutes))
        assertTrue(NextEpisodeRules.thresholdReached(2_281.0, duration, credits, minutes))
    }

    @Test
    fun `a short guessed scene after the credits shows no card, leaving it to autoplay`() {
        // 45 minutes, credits end 60 s before the end; 97 % is 81 s from the end.
        val long = 2_700.0
        val credits = listOf(Segment(SegmentType.OUTRO, 2_550.0, 2_640.0))
        val at97 = AutoPlaySettings(nextEpisodeThresholdPercent = 97f)
        assertFalse(NextEpisodeRules.thresholdReached(2_550.0, long, credits, at97))
        assertFalse(NextEpisodeRules.thresholdReached(2_690.0, long, credits, at97))
    }

    @Test
    fun `a long guessed scene after the credits waits for the threshold`() {
        val long = 2_700.0
        val credits = listOf(Segment(SegmentType.OUTRO, 2_400.0, 2_580.0)) // 2-minute scene after
        val at97 = AutoPlaySettings(nextEpisodeThresholdPercent = 97f)
        assertFalse(NextEpisodeRules.thresholdReached(2_400.0, long, credits, at97))
        assertFalse(NextEpisodeRules.thresholdReached(2_618.0, long, credits, at97))
        assertTrue(NextEpisodeRules.thresholdReached(2_619.0, long, credits, at97))
    }

    @Test
    fun `an explicit post-credits scene holds the card until it ends`() {
        val segments = listOf(
            Segment(SegmentType.OUTRO, 2_200.0, 2_300.0),
            Segment(SegmentType.POST_CREDITS, 2_300.0, 2_390.0)
        )
        // 99 % is 2 376 s; the scene ends later, at 2 390 s.
        assertFalse(NextEpisodeRules.thresholdReached(2_380.0, duration, segments, percent))
        assertTrue(NextEpisodeRules.thresholdReached(2_390.0, duration, segments, percent))
    }

    @Test
    fun `values are clamped to the TV's ranges and a bad duration never fires`() {
        assertEquals(72.0, NextEpisodeRules.thresholdSecondsFromEnd(duration, NextEpisodeThresholdMode.PERCENTAGE, 50f, 2f), 0.001)
        assertEquals(210.0, NextEpisodeRules.thresholdSecondsFromEnd(duration, NextEpisodeThresholdMode.MINUTES_BEFORE_END, 99f, 9f), 0.001)
        assertFalse(NextEpisodeRules.thresholdReached(10.0, 0.0, emptyList(), percent))
        assertFalse(NextEpisodeRules.thresholdReached(3_000.0, duration, emptyList(), percent))
    }

    @Test
    fun `preloading starts ahead of the card`() {
        // 10 s timeout: preload 10 s before the 99 % point (2 376 s).
        assertFalse(NextEpisodeRules.shouldPreload(2_360.0, duration, emptyList(), percent, timeoutSeconds = 10))
        assertTrue(NextEpisodeRules.shouldPreload(2_366.0, duration, emptyList(), percent, timeoutSeconds = 10))
        // An unlimited wait looks 30 s ahead.
        assertTrue(NextEpisodeRules.shouldPreload(2_346.0, duration, emptyList(), percent, AutoPlaySettings.TIMEOUT_UNLIMITED))
    }

    @Test
    fun `only a position away from the end arms the card`() {
        assertTrue(NextEpisodeRules.isAwayFromEnd(100.0, duration, emptyList(), percent))
        assertFalse(NextEpisodeRules.isAwayFromEnd(2_390.0, duration, emptyList(), percent))
    }
}
