package com.fetch.tv.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class FrameRateMatchingDefaultTest {

    @Test
    fun `untouched profile gets the device default`() {
        assertEquals(
            FrameRateMatchingMode.START,
            resolveFrameRateMatchingMode(null, null) { FrameRateMatchingMode.START }
        )
        assertEquals(
            FrameRateMatchingMode.OFF,
            resolveFrameRateMatchingMode(null, null) { FrameRateMatchingMode.OFF }
        )
    }

    @Test
    fun `explicit choice is kept on a capable device`() {
        val capable = { FrameRateMatchingMode.START }
        assertEquals(FrameRateMatchingMode.OFF, resolveFrameRateMatchingMode("OFF", false, capable))
        assertEquals(FrameRateMatchingMode.START_STOP, resolveFrameRateMatchingMode("START_STOP", true, capable))
    }

    @Test
    fun `legacy on-off flag is an explicit choice`() {
        val noDefault = { fail("device default must not be read"); FrameRateMatchingMode.START }
        assertEquals(FrameRateMatchingMode.OFF, resolveFrameRateMatchingMode(null, false, noDefault))
        assertEquals(FrameRateMatchingMode.START_STOP, resolveFrameRateMatchingMode(null, true, noDefault))
    }

    @Test
    fun `unknown stored mode falls back to the legacy flag then the device default`() {
        assertEquals(FrameRateMatchingMode.OFF, resolveFrameRateMatchingMode("BOGUS", false) { FrameRateMatchingMode.START })
        assertEquals(FrameRateMatchingMode.START, resolveFrameRateMatchingMode("BOGUS", null) { FrameRateMatchingMode.START })
    }
}
