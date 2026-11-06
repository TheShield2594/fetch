package com.fetch.tv.ui.screens.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilePinEntryTest {

    @Test
    fun `a PIN of 4 to 8 digits can be submitted`() {
        assertFalse(ProfilePinEntry.canSubmit(0))
        assertFalse(ProfilePinEntry.canSubmit(3))
        assertTrue(ProfilePinEntry.canSubmit(4))
        assertTrue(ProfilePinEntry.canSubmit(6))
        assertTrue(ProfilePinEntry.canSubmit(8))
        assertFalse(ProfilePinEntry.canSubmit(9))
    }

    @Test
    fun `entry goes ahead by itself at 8 digits, or at the first entry's length when confirming`() {
        assertEquals(8, ProfilePinEntry.autoSubmitLength(confirmingLength = null))
        assertEquals(4, ProfilePinEntry.autoSubmitLength(confirmingLength = 4))
        assertEquals(6, ProfilePinEntry.autoSubmitLength(confirmingLength = 6))
    }

    @Test
    fun `boxes start at 4 and add one for the next digit up to 8`() {
        assertEquals(4, ProfilePinEntry.boxCount(0))
        assertEquals(4, ProfilePinEntry.boxCount(3))
        assertEquals(5, ProfilePinEntry.boxCount(4))
        assertEquals(8, ProfilePinEntry.boxCount(7))
        assertEquals(8, ProfilePinEntry.boxCount(8))
        assertEquals(6, ProfilePinEntry.boxCount(2, confirmingLength = 6))
    }
}
