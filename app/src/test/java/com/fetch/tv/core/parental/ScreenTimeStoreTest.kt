package com.fetch.tv.core.parental

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.fetch.tv.TestPreferencesStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenTimeStoreTest {

    @Test
    fun `a deleted profile's screen time goes with it`() = runTest {
        val store = ScreenTimeStore(TestPreferencesStore())
        store.update(2, "2026-11-02") { it.copy(watchedMs = 10L, unlockedForDay = true) }
        store.update(3, "2026-11-02") { it.copy(watchedMs = 20L) }

        store.deleteProfile(2)

        assertEquals(setOf(3), store.days().keys)
    }

    @Test
    fun `days saved next to other TVs' old records still load, and the old records are dropped on the next write`() = runTest {
        val legacyKey = stringPreferencesKey("other_devices")
        val prefs = TestPreferencesStore(
            preferencesOf(
                stringPreferencesKey("days") to """{"2":{"date":"2026-11-02","watchedMs":600000,"extraMinutes":5}}""",
                legacyKey to """{"2":{"tv-b":{"date":"2026-11-02","watchedMs":5}}}"""
            )
        )
        val store = ScreenTimeStore(prefs)

        assertEquals(
            mapOf(2 to ScreenTimeDay(date = "2026-11-02", watchedMs = 600_000L, extraMinutes = 5)),
            store.days()
        )

        store.update(2, "2026-11-02") { it.copy(watchedMs = it.watchedMs + 1L) }

        assertEquals(600_001L, store.days().getValue(2).watchedMs)
        assertNull(prefs.value[legacyKey])
    }

    @Test
    fun `playback on this TV alone counts against the allowance`() {
        val rules = com.fetch.tv.data.local.KidsRules(dailyMinutes = 60)
        val now = java.time.LocalDateTime.of(2026, 11, 2, 15, 0)
        assertEquals(
            ScreenTimeStatus.Allowed(25 * 60_000L),
            screenTimeStatus(rules, ScreenTimeDay(date = "2026-11-02", watchedMs = 35 * 60_000L), now, 0L)
        )
        assertEquals(
            ScreenTimeStatus.DailyLimitReached,
            screenTimeStatus(rules, ScreenTimeDay(date = "2026-11-02", watchedMs = 60 * 60_000L), now, 0L)
        )
    }
}
