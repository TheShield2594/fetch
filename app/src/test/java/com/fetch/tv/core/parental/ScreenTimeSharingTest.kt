package com.fetch.tv.core.parental

import com.fetch.tv.TestPreferencesStore
import com.fetch.tv.core.sync.ScreenTimeBlob
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
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
    fun `blob round trips and push keeps recent days only`() {
        val records = ScreenTimeBlob.recordsForPush(
            others = mapOf(
                "tv-b" to ScreenTimeDay(date = "2026-11-02", watchedMs = 5L),
                "tv-old" to ScreenTimeDay(date = "2026-10-20", watchedMs = 7L),
                "tv-a" to ScreenTimeDay(date = "2026-11-02", watchedMs = 1L)
            ),
            ownClientId = "tv-a",
            own = ScreenTimeDay(date = "2026-11-02", watchedMs = 42L),
            oldestDate = "2026-11-01"
        )
        assertEquals(setOf("tv-a", "tv-b"), records.keys)
        assertEquals(42L, records.getValue("tv-a").watchedMs)

        val blob = JsonObject(mapOf(ScreenTimeBlob.KEY to ScreenTimeBlob.encode(records)))
        assertEquals(records, ScreenTimeBlob.decode(blob))
        assertTrue(ScreenTimeBlob.decode(JsonObject(emptyMap())).isEmpty())
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
}
