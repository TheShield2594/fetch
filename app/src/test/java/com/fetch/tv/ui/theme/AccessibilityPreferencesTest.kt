package com.fetch.tv.ui.theme

import com.fetch.tv.ui.components.cardProgressPercent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityPreferencesTest {

    @Test
    fun `reduced motion follows the member's preference, not the device tier`() {
        assertTrue(fullMotionAllowed(reduceMotionSetting = false, animatorScale = 1f))
        assertFalse("Fetch's own setting", fullMotionAllowed(reduceMotionSetting = true, animatorScale = 1f))
        assertFalse("system animations off", fullMotionAllowed(reduceMotionSetting = false, animatorScale = 0f))
        // A slowed or sped-up animator scale is not a request to stop motion.
        assertTrue(fullMotionAllowed(reduceMotionSetting = false, animatorScale = 0.5f))
    }

    @Test
    fun `controls wait longer while a screen reader is on`() {
        assertEquals(4_000L, controlsAutoHideDelayMs(4_000L, screenReaderOn = false))
        assertEquals(SCREEN_READER_MIN_AUTO_HIDE_MS, controlsAutoHideDelayMs(4_000L, screenReaderOn = true))
        assertEquals(30_000L, controlsAutoHideDelayMs(10_000L, screenReaderOn = true))
    }

    @Test
    fun `card progress is announced only while the bar is drawn`() {
        assertNull(cardProgressPercent(null))
        assertNull(cardProgressPercent(0f))
        assertNull(cardProgressPercent(1f))
        assertEquals(45, cardProgressPercent(0.45f))
        // Never "0%" for a started title or "100%" for an unfinished one.
        assertEquals(1, cardProgressPercent(0.001f))
        assertEquals(99, cardProgressPercent(0.999f))
    }
}
