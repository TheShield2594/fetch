package com.fetch.tv.core.player

import com.fetch.tv.ui.screens.player.SleepTimerLabel
import com.fetch.tv.ui.screens.player.sleepTimerPromptSecondsLeft
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
    fun `the list starts with Off, then every duration, then end of title`() {
        assertNull(SleepTimer.LIST_CHOICES.first())
        assertEquals(
            listOf(15, 30, 45, 60, 90),
            SleepTimer.LIST_CHOICES.filterIsInstance<SleepTimerChoice.Minutes>().map { it.minutes }
        )
        assertEquals(SleepTimerChoice.EndOfTitle, SleepTimer.LIST_CHOICES.last())
    }

    @Test
    fun `the current choice follows the setting and Off cancels it`() {
        assertNull(timer.currentChoice())
        timer.set(SleepTimerChoice.Minutes(45))
        assertEquals(SleepTimerChoice.Minutes(45), timer.currentChoice())
        timer.set(SleepTimerChoice.EndOfTitle)
        assertEquals(SleepTimerChoice.EndOfTitle, timer.currentChoice())
        timer.set(SleepTimer.LIST_CHOICES.first())
        assertNull(timer.setting.value)
        assertNull(timer.currentChoice())
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
    fun `choosing again restarts the clock from the press`() {
        timer.set(SleepTimerChoice.Minutes(15))
        now += 10 * SleepTimer.MINUTE_MS
        val next = timer.set(SleepTimerChoice.Minutes(30)) as SleepTimerSetting.Minutes
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
    fun `the prompt counts down from its deadline to null`() {
        val endsAt = 100_000L
        assertEquals(60, sleepTimerPromptSecondsLeft(endsAt, endsAt - 60_000L))
        assertEquals(1, sleepTimerPromptSecondsLeft(endsAt, endsAt - 1L))
        assertNull(sleepTimerPromptSecondsLeft(endsAt, endsAt))
        // A TV that slept through the prompt closes the player on the next tick.
        assertNull(sleepTimerPromptSecondsLeft(endsAt, endsAt + 600_000L))
    }
}
