package com.fetch.tv.core.parental

import com.fetch.tv.TestPreferencesStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTimeSharingTest {

    @Test
    fun `playback and added time add up across TVs on the same day`() {
        val local = ScreenTimeDay(date = "2026-11-02", watchedMs = 10 * 60_000L, extraMinutes = 5)
        val others = listOf(
            ScreenTimeDay(date = "2026-11-02", watchedMs = 20 * 60_000L, extraMinutes = 10, bedtimeLiftedUntilMs = 99L),
            ScreenTimeDay(date = "2026-11-01", watchedMs = 600 * 60_000L, unlockedForDay = true)
        )

        val merged = mergeScreenTimeDays(local, others)

        assertEquals(30 * 60_000L, merged.watchedMs)
        assertEquals(15, merged.extraMinutes)
        assertEquals(99L, merged.bedtimeLiftedUntilMs)
        assertEquals(false, merged.unlockedForDay)
    }

    @Test
    fun `a day unlocked on another TV is unlocked here`() {
        val merged = mergeScreenTimeDays(
            ScreenTimeDay(date = "2026-11-02"),
            listOf(ScreenTimeDay(date = "2026-11-02", unlockedForDay = true))
        )
        assertTrue(merged.unlockedForDay)
    }

    @Test
    fun `two TVs at once share one allowance`() {
        val rules = com.fetch.tv.data.local.KidsRules(dailyMinutes = 60)
        val here = ScreenTimeDay(date = "2026-11-02", watchedMs = 35 * 60_000L)
        val there = ScreenTimeDay(date = "2026-11-02", watchedMs = 30 * 60_000L)
        val status = screenTimeStatus(
            rules, mergeScreenTimeDays(here, listOf(there)),
            java.time.LocalDateTime.of(2026, 11, 2, 15, 0), 0L
        )
        assertEquals(ScreenTimeStatus.DailyLimitReached, status)
    }

    @Test
    fun `other TVs' records are stored per profile`() = runTest {
        val store = ScreenTimeStore(TestPreferencesStore())
        val records = mapOf("tv-b" to ScreenTimeDay(date = "2026-11-02", watchedMs = 5L))
        store.setOtherDevices(2, records)
        assertEquals(records, store.otherDevices(2))
        store.setOtherDevices(2, emptyMap())
        assertTrue(store.otherDevices(2).isEmpty())
    }

    @Test
    fun `a deleted profile's screen time goes with it`() = runTest {
        val store = ScreenTimeStore(TestPreferencesStore())
        store.update(2, "2026-11-02") { it.copy(watchedMs = 10L, unlockedForDay = true) }
        store.update(3, "2026-11-02") { it.copy(watchedMs = 20L) }
        store.setOtherDevices(2, mapOf("tv-b" to ScreenTimeDay(date = "2026-11-02", watchedMs = 5L)))

        store.deleteProfile(2)

        assertEquals(setOf(3), store.days().keys)
        assertTrue(store.otherDevices(2).isEmpty())
    }
}
