package com.fetch.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Dv7HevcFallbackCandidateTest {

    @Test
    fun `profile 7 track is a candidate`() {
        assertTrue(isDv7HevcFallbackCandidate("dvhe.07.06", sourceProfile = null))
        assertTrue(isDv7HevcFallbackCandidate("dvh1.07.06", sourceProfile = 7))
    }

    @Test
    fun `profile 7 source converted to 8_1 is a candidate`() {
        assertTrue(isDv7HevcFallbackCandidate("dvhe.08.06", sourceProfile = 7))
    }

    @Test
    fun `profile 5 track is not a candidate`() {
        assertFalse(isDv7HevcFallbackCandidate("dvhe.05.06", sourceProfile = null))
    }

    @Test
    fun `profile 5 source converted to 8_1 is not a candidate`() {
        assertFalse(isDv7HevcFallbackCandidate("dvhe.08.06", sourceProfile = 5))
    }

    @Test
    fun `non Dolby Vision or missing codecs are not candidates`() {
        assertFalse(isDv7HevcFallbackCandidate("hvc1.2.4.L153.B0", sourceProfile = null))
        assertFalse(isDv7HevcFallbackCandidate(null, sourceProfile = 7))
    }
}
