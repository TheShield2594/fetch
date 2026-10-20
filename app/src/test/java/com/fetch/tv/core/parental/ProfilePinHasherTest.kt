package com.fetch.tv.core.parental

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilePinHasherTest {
    @Test
    fun `verifies the right PIN and rejects others`() {
        val hash = ProfilePinHasher.hash("1234", iterations = 1_000)
        assertTrue(ProfilePinHasher.verify("1234", hash))
        assertFalse(ProfilePinHasher.verify("1235", hash))
        assertFalse(hash.contains("1234"))
    }

    @Test
    fun `salts every hash`() {
        assertNotEquals(ProfilePinHasher.hash("1234", 1_000), ProfilePinHasher.hash("1234", 1_000))
    }

    @Test
    fun `rejects malformed hashes`() {
        assertFalse(ProfilePinHasher.verify("1234", null))
        assertFalse(ProfilePinHasher.verify("1234", "v1:abc"))
        assertFalse(ProfilePinHasher.verify("1234", "v2:1000:00:00"))
        assertFalse(ProfilePinHasher.verify("1234", "v1:1000:zz:00"))
    }

    @Test
    fun `accepts only short numeric PINs`() {
        assertTrue(ProfilePinHasher.isValidPin("0000"))
        assertTrue(ProfilePinHasher.isValidPin("12345678"))
        assertFalse(ProfilePinHasher.isValidPin("123"))
        assertFalse(ProfilePinHasher.isValidPin("12a4"))
    }

    @Test
    fun `lockout starts after the free attempts and doubles up to the cap`() {
        assertEquals(0L, PinAttemptPolicy.lockoutAfterFailures(PinAttemptPolicy.FREE_ATTEMPTS - 1))
        assertEquals(30_000L, PinAttemptPolicy.lockoutAfterFailures(PinAttemptPolicy.FREE_ATTEMPTS))
        assertEquals(60_000L, PinAttemptPolicy.lockoutAfterFailures(PinAttemptPolicy.FREE_ATTEMPTS + 1))
        assertEquals(PinAttemptPolicy.MAX_LOCKOUT_MS, PinAttemptPolicy.lockoutAfterFailures(100))
        assertEquals(2, PinAttemptPolicy.retryAfterSeconds(lockedUntilMs = 1_500, nowMs = 0))
        assertEquals(0, PinAttemptPolicy.retryAfterSeconds(lockedUntilMs = 1_000, nowMs = 1_000))
    }
}
