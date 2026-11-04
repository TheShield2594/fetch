package com.fetch.tv.core.player

import com.fetch.tv.ui.screens.player.SleepTimerLabel
import com.fetch.tv.ui.screens.player.nextSleepTimerPromptSec
import com.fetch.tv.ui.screens.player.sleepTimerLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerTest {
    private var now = 1_000_000L
    private val timer = SleepTimer { now }

    @Test
    fun `cycling walks every duration, then end of title, then off`() {
        val seen = (0..SleepTimer.CHOICES.size).map { timer.cycle() }
        assertEquals(
            listOf(15, 30, 45, 60, 90),
            seen.filterIsInstance<SleepTimerSetting.Minutes>().map { it.minutes }
        )
        assertEquals(SleepTimerSetting.EndOfTitle, seen[5])
        assertNull(seen[6])
        assertNull(timer.setting.value)
    }

    @Test
    fun `a minutes timer counts down on the clock and expires`() {
        timer.set(SleepTimerChoice.Minutes(15))
        assertEquals(15 * SleepTimer.MINUTE_MS, timer.remainingMs())
        assertFalse(timer.hasExpired())

        now += 15 * SleepTimer.MINUTE_MS - 1
        assertFalse(timer.hasExpired())
        now += 1
        assertTrue(timer.hasExpired())
        assertEquals(0L, timer.remainingMs())
    }

    @Test
    fun `cycling restarts the clock from the press`() {
        timer.cycle()
        now += 10 * SleepTimer.MINUTE_MS
        val next = timer.cycle() as SleepTimerSetting.Minutes
        assertEquals(30, next.minutes)
        assertEquals(30 * SleepTimer.MINUTE_MS, timer.remainingMs())
    }

    @Test
    fun `an expired timer is dropped when a player opens, a running one is kept`() {
        timer.set(SleepTimerChoice.Minutes(15))
        timer.clearIfExpired()
        assertTrue(timer.setting.value is SleepTimerSetting.Minutes)

        now += 20 * SleepTimer.MINUTE_MS
        timer.clearIfExpired()
        assertNull(timer.setting.value)
    }

    @Test
    fun `end of title never expires on the clock`() {
        timer.set(SleepTimerChoice.EndOfTitle)
        now += 24 * 60 * SleepTimer.MINUTE_MS
        assertFalse(timer.hasExpired())
        assertNull(timer.remainingMs())
        assertTrue(timer.stopsAtEndOfTitle)
    }

    @Test
    fun `minutes left round up so a running timer never reads zero`() {
        assertEquals(1, SleepTimer.minutesLeft(1L))
        assertEquals(1, SleepTimer.minutesLeft(SleepTimer.MINUTE_MS))
        assertEquals(2, SleepTimer.minutesLeft(SleepTimer.MINUTE_MS + 1))
        assertEquals(0, SleepTimer.minutesLeft(0L))
    }

    @Test
    fun `the button label follows the setting`() {
        assertNull(sleepTimerLabel(null, null))
        assertEquals(SleepTimerLabel.EndOfTitle, sleepTimerLabel(SleepTimerSetting.EndOfTitle, null))
        assertEquals(
            SleepTimerLabel.MinutesLeft(14),
            sleepTimerLabel(SleepTimerSetting.Minutes(15, endsAtMs = 0L), remainingMs = 13 * SleepTimer.MINUTE_MS + 5)
        )
    }

    @Test
    fun `the prompt counts down to null`() {
        assertEquals(59, nextSleepTimerPromptSec(60))
        assertEquals(1, nextSleepTimerPromptSec(2))
        assertNull(nextSleepTimerPromptSec(1))
    }
}
