package com.fetch.tv.data.local

import com.fetch.tv.ui.screens.player.PlayerNextEpisodeRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The binge defaults a fresh profile gets (#408). */
class BingeDefaultsTest {

    private val defaults = PlayerSettings()

    @Test
    fun `a fresh profile auto-plays the next episode with the still-watching prompt on`() {
        assertTrue(defaults.streamAutoPlayNextEpisodeEnabled)
        assertTrue(defaults.stillWatchingEnabled)
        assertEquals(3, defaults.stillWatchingEpisodeThreshold)
    }

    @Test
    fun `the countdown runs about eight seconds and stays within its range`() {
        assertEquals(8, defaults.autoPlayCountdownSeconds)
        assertTrue(
            defaults.autoPlayCountdownSeconds in
                PlayerSettings.MIN_AUTO_PLAY_COUNTDOWN_SECONDS..PlayerSettings.MAX_AUTO_PLAY_COUNTDOWN_SECONDS
        )
    }

    @Test
    fun `without markers the up-next card shows a fixed time before the end`() {
        assertEquals(NextEpisodeThresholdMode.MINUTES_BEFORE_END, defaults.nextEpisodeThresholdMode)
        val durationMs = 45 * 60_000L
        fun shows(positionMs: Long) = PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
            positionMs = positionMs,
            durationMs = durationMs,
            skipIntervals = emptyList(),
            thresholdMode = defaults.nextEpisodeThresholdMode,
            thresholdPercent = defaults.nextEpisodeThresholdPercent,
            thresholdMinutesBeforeEnd = defaults.nextEpisodeThresholdMinutesBeforeEnd,
        )
        // Two minutes before the end, well before the old 99% (27 s before the end).
        assertTrue(shows(durationMs - 2 * 60_000L))
        assertFalse(shows(durationMs - 3 * 60_000L))
    }

    @Test
    fun `existing profiles are pinned to the values they had before`() {
        assertFalse(PlayerSettings.LEGACY_AUTO_PLAY_NEXT_EPISODE_ENABLED)
        assertFalse(PlayerSettings.LEGACY_STILL_WATCHING_ENABLED)
        assertEquals(NextEpisodeThresholdMode.PERCENTAGE, PlayerSettings.LEGACY_NEXT_EPISODE_THRESHOLD_MODE)
    }
}
