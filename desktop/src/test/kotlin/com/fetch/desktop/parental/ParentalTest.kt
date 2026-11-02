package com.fetch.desktop.parental

import com.fetch.desktop.data.ProfileLimits
import com.fetch.tv.core.parental.CertificationLevel
import com.fetch.tv.core.parental.ProfilePinHasher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ParentalTest {

    private val teens = ProfileLimits(maxRating = CertificationLevel.TEENS.name)

    @Test
    fun `a rating limit allows up to its level and follows the unrated choice`() {
        assertTrue(Parental.allows(teens, "PG-13"))
        assertTrue(Parental.allows(teens, "TV-PG"))
        assertFalse(Parental.allows(teens, "R"))
        assertFalse(Parental.allows(teens, "TV-MA"))
        assertTrue("unrated allowed by default", Parental.allows(teens, null))
        assertFalse(Parental.allows(teens.copy(allowUnrated = false), null))
        assertTrue("no limit allows anything", Parental.allows(ProfileLimits(), "NC-17"))
    }

    @Test
    fun `bedtime runs from its start until seven`() {
        val limits = ProfileLimits(bedtimeStartMinute = 21 * 60)
        assertFalse(Parental.isBedtime(limits, LocalDateTime.of(2026, 11, 2, 20, 59)))
        assertTrue(Parental.isBedtime(limits, LocalDateTime.of(2026, 11, 2, 21, 0)))
        assertTrue(Parental.isBedtime(limits, LocalDateTime.of(2026, 11, 3, 6, 59)))
        assertFalse(Parental.isBedtime(limits, LocalDateTime.of(2026, 11, 3, 7, 0)))
    }

    @Test
    fun `daily screen time blocks once used up, and extra time or no limit lifts it`() {
        val limits = ProfileLimits(dailyMinutes = 60)
        val noon = LocalDateTime.of(2026, 11, 2, 12, 0)
        val day = ScreenTimeDay("2026-11-02", watchedMs = 59 * 60_000L)
        assertNull(Parental.block(limits, day, noon))
        assertEquals(1, Parental.minutesLeft(limits, day))
        val used = day.copy(watchedMs = 60 * 60_000L)
        assertEquals(ScreenTimeBlock.DAILY, Parental.block(limits, used, noon))
        assertNull(Parental.block(limits, used.copy(extraMinutes = 15), noon))
        assertNull(Parental.block(limits, used.copy(unlimited = true), noon))
    }

    @Test
    fun `yesterday's time resets at midnight`() {
        val yesterday = ScreenTimeDay("2026-11-01", watchedMs = 999_999, extraMinutes = 30)
        assertEquals(ScreenTimeDay("2026-11-02"), Parental.today(yesterday, LocalDate.of(2026, 11, 2)))
    }

    @Test
    fun `PIN checks lock out after repeated wrong tries`() {
        var now = 0L
        val gate = PinGate { now }
        val hash = ProfilePinHasher.hash("2468")
        repeat(4) { assertTrue(gate.check(2, hash, "0000") is PinGate.Result.Wrong) }
        val locked = gate.check(2, hash, "0000")
        assertTrue(locked is PinGate.Result.Locked)
        assertTrue("even the right PIN waits", gate.check(2, hash, "2468") is PinGate.Result.Locked)
        now += 31_000
        assertEquals(PinGate.Result.Ok, gate.check(2, hash, "2468"))
    }
}
