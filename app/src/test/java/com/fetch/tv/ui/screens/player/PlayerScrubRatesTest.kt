package com.fetch.tv.ui.screens.player

import com.fetch.tv.data.local.PlayerSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerScrubRatesTest {

    @Test
    fun stepMsForKeyRepeat_rampsWithHold() {
        assertEquals(PlayerScrubRates.STEP_SHORT_MS, PlayerScrubRates.stepMsForKeyRepeat(0))
        assertEquals(PlayerScrubRates.STEP_SHORT_MS, PlayerScrubRates.stepMsForKeyRepeat(2))
        assertEquals(PlayerScrubRates.STEP_MEDIUM_MS, PlayerScrubRates.stepMsForKeyRepeat(3))
        assertEquals(PlayerScrubRates.STEP_MEDIUM_MS, PlayerScrubRates.stepMsForKeyRepeat(7))
        assertEquals(PlayerScrubRates.STEP_LONG_MS, PlayerScrubRates.stepMsForKeyRepeat(8))
        assertEquals(PlayerScrubRates.STEP_LONG_MS, PlayerScrubRates.stepMsForKeyRepeat(14))
        assertEquals(PlayerScrubRates.STEP_VERY_LONG_MS, PlayerScrubRates.stepMsForKeyRepeat(15))
        assertEquals(PlayerScrubRates.STEP_VERY_LONG_MS, PlayerScrubRates.stepMsForKeyRepeat(100))
    }

    @Test
    fun deltaMsForKeyRepeat_appliesDirection() {
        assertEquals(-PlayerScrubRates.STEP_SHORT_MS, PlayerScrubRates.deltaMsForKeyRepeat(0, forward = false))
        assertEquals(PlayerScrubRates.STEP_LONG_MS, PlayerScrubRates.deltaMsForKeyRepeat(8, forward = true))
    }

    @Test
    fun defaultSteps_areTenBackAndThirtyForward() {
        val steps = SeekSteps()
        assertEquals(-10_000L, steps.deltaMs(forward = false))
        assertEquals(30_000L, steps.deltaMs(forward = true))
    }

    @Test
    fun aTap_movesByTheConfiguredStepInEitherDirection() {
        val steps = SeekSteps.fromSeconds(backSeconds = 15, forwardSeconds = 45)
        assertEquals(-15_000L, PlayerScrubRates.deltaMsForKeyRepeat(0, forward = false, steps = steps))
        assertEquals(45_000L, PlayerScrubRates.deltaMsForKeyRepeat(0, forward = true, steps = steps))
    }

    @Test
    fun keysAndButtons_useTheSameStep() {
        // The on-screen buttons seek by steps.deltaMs; a single key press must match it.
        val steps = SeekSteps.fromSeconds(backSeconds = 5, forwardSeconds = 20)
        for (forward in listOf(false, true)) {
            assertEquals(steps.deltaMs(forward), PlayerScrubRates.deltaMsForKeyRepeat(0, forward, steps))
        }
    }

    @Test
    fun aLongHold_rampsPastTheConfiguredStep_butNeverBelowIt() {
        val steps = SeekSteps.fromSeconds(backSeconds = 10, forwardSeconds = 30)
        assertEquals(30_000L, PlayerScrubRates.deltaMsForKeyRepeat(3, forward = true, steps = steps))
        assertEquals(-20_000L, PlayerScrubRates.deltaMsForKeyRepeat(3, forward = false, steps = steps))
        assertEquals(60_000L, PlayerScrubRates.deltaMsForKeyRepeat(15, forward = true, steps = steps))
        val bigSteps = SeekSteps.fromSeconds(backSeconds = 90, forwardSeconds = 90)
        assertEquals(90_000L, PlayerScrubRates.deltaMsForKeyRepeat(15, forward = true, steps = bigSteps))
    }

    @Test
    fun seekFeedback_formatsASignedDelta() {
        assertEquals("+0:30", formatSeekDelta(SeekFeedback(originMs = 60_000L, targetMs = 90_000L).deltaMs))
        assertEquals("\u22121:05", formatSeekDelta(-65_000L))
        assertEquals("+1:02:03", formatSeekDelta(3_723_000L))
        assertEquals("+0:00", formatSeekDelta(0L))
    }

    @Test
    fun stepMsForKeyRepeat_clampsNegativeRepeatCount() {
        assertEquals(PlayerScrubRates.STEP_SHORT_MS, PlayerScrubRates.stepMsForKeyRepeat(-1))
    }

    @Test
    fun seekStepSetting_snapsToItsListAndDefaults() {
        val back = PlayerSettings.SEEK_BACK_SECONDS_VALUES
        assertEquals(10, PlayerSettings.normalizeSeekSeconds(null, back, 10))
        assertEquals(15, PlayerSettings.normalizeSeekSeconds(15, back, 10))
        assertEquals(60, PlayerSettings.normalizeSeekSeconds(600, back, 10))
        assertEquals(5, PlayerSettings.normalizeSeekSeconds(-3, back, 10))
        val defaults = PlayerSettings()
        assertEquals(SeekSteps(), SeekSteps.fromSeconds(defaults.seekBackSeconds, defaults.seekForwardSeconds))
    }
}
