package com.fetch.tv.core.player

import com.fetch.tv.core.player.FrameRateUtils.DisplayModeSpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayCapabilitiesFilmRateTest {

    private fun mode(id: Int, width: Int, height: Int, refreshRate: Float) =
        DisplayModeSpec(modeId = id, width = width, height = height, refreshRate = refreshRate)

    @Test
    fun `display with a 23_976 mode at the current resolution is capable`() {
        val modes = listOf(
            mode(1, 3840, 2160, 59.94f),
            mode(2, 3840, 2160, 23.976f),
            mode(3, 1920, 1080, 60f),
        )
        assertTrue(DisplayCapabilities.supportsFilmRateMatching(modes, currentModeId = 1))
    }

    @Test
    fun `film rate only at another resolution is not capable`() {
        val modes = listOf(
            mode(1, 3840, 2160, 59.94f),
            mode(2, 3840, 2160, 50f),
            mode(3, 1920, 1080, 24.000002f),
        )
        assertFalse(DisplayCapabilities.supportsFilmRateMatching(modes, currentModeId = 1))
    }

    @Test
    fun `multiples of the film rate count`() {
        val modes = listOf(
            mode(1, 1920, 1080, 60.000004f),
            mode(2, 1920, 1080, 119.88013f),
        )
        assertTrue(DisplayCapabilities.supportsFilmRateMatching(modes, currentModeId = 1))
        val modes48 = listOf(
            mode(1, 1920, 1080, 60f),
            mode(2, 1920, 1080, 48f),
        )
        assertTrue(DisplayCapabilities.supportsFilmRateMatching(modes48, currentModeId = 1))
    }

    @Test
    fun `switchable display without a film rate is not capable`() {
        val modes = listOf(
            mode(1, 1920, 1080, 60f),
            mode(2, 1920, 1080, 59.94f),
            mode(3, 1920, 1080, 50f),
            mode(4, 1920, 1080, 30f),
            mode(5, 1920, 1080, 25f),
        )
        assertFalse(DisplayCapabilities.supportsFilmRateMatching(modes, currentModeId = 1))
    }

    @Test
    fun `single mode display is not capable even at a film multiple`() {
        val modes = listOf(mode(1, 1920, 1080, 120f))
        assertFalse(DisplayCapabilities.supportsFilmRateMatching(modes, currentModeId = 1))
        assertFalse(DisplayCapabilities.supportsFilmRateMatching(emptyList(), currentModeId = 1))
    }

    @Test
    fun `float noise duplicates of one rate do not count as switchable`() {
        val modes = listOf(
            mode(1, 1920, 1080, 24f),
            mode(2, 1920, 1080, 24.000002f),
        )
        assertFalse(DisplayCapabilities.supportsFilmRateMatching(modes, currentModeId = 1))
    }
}
