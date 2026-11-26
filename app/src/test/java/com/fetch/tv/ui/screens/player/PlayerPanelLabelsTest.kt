package com.fetch.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Labels and checks in the player's audio, stream info and speed panels (#785). */
class PlayerPanelLabelsTest {

    @Test
    fun sampleRate_wholeKhzHasNoDecimal() {
        assertEquals("48", formatSampleRateKhz(48_000, Locale.US))
        assertEquals("96", formatSampleRateKhz(96_000, Locale.US))
        assertEquals("192", formatSampleRateKhz(192_000, Locale.US))
    }

    @Test
    fun sampleRate_fractionalKhzKeepsOneDecimal() {
        assertEquals("44.1", formatSampleRateKhz(44_100, Locale.US))
        assertEquals("88.2", formatSampleRateKhz(88_200, Locale.US))
        assertEquals("22.1", formatSampleRateKhz(22_050, Locale.US))
    }

    @Test
    fun sampleRate_roundsToTheNearestTenth() {
        // 11.025 kHz rounds to 11.0, shown as a whole number.
        assertEquals("11", formatSampleRateKhz(11_025, Locale.US))
        assertEquals("48", formatSampleRateKhz(47_999, Locale.US))
    }

    @Test
    fun sampleRate_usesTheLocaleDecimalSeparator() {
        assertEquals("44,1", formatSampleRateKhz(44_100, Locale.GERMANY))
    }

    @Test
    fun resolution_sdTierTellsTheShortSideApart() {
        assertEquals("1024 × 576 (576p)", formatResolution(1024, 576))
        assertEquals("960 × 540 (540p)", formatResolution(960, 540))
        assertEquals("854 × 480 (480p)", formatResolution(854, 480))
        assertEquals("854 × 357 (480p)", formatResolution(854, 357))
        assertEquals("1024 × 768 (768p)", formatResolution(1024, 768))
    }

    @Test
    fun resolution_otherTiersUnchanged() {
        assertEquals("720 × 576 (576p)", formatResolution(720, 576))
        assertEquals("640 × 360 (360p)", formatResolution(640, 360))
        assertEquals("1280 × 720 (720p)", formatResolution(1280, 720))
        assertEquals("1920 × 1080 (1080p)", formatResolution(1920, 1080))
        assertEquals("576 × 1024 (576p)", formatResolution(576, 1024))
    }

    @Test
    fun playbackSpeed_matchesWithinTolerance() {
        assertTrue(isPlaybackSpeedSelected(1.25f, 1.2499999f))
        assertTrue(isPlaybackSpeedSelected(1f, 1.0000001f))
        assertTrue(isPlaybackSpeedSelected(0.75f, 0.75f))
    }

    @Test
    fun playbackSpeed_neighbourDoesNotMatch() {
        assertFalse(isPlaybackSpeedSelected(1.25f, 1.5f))
        assertFalse(isPlaybackSpeedSelected(1f, 1.1f))
    }
}
