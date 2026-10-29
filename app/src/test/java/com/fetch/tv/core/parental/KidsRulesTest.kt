package com.fetch.tv.core.parental

import com.fetch.tv.data.local.KidsRules
import com.fetch.tv.data.local.ParentalTitle
import com.fetch.tv.data.local.ProfileRestrictions
import com.fetch.tv.data.local.ProfileTitleLists
import com.fetch.tv.ui.screens.home.approvedTitleRows
import com.fetch.tv.ui.screens.player.effectiveStillWatching
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class KidsRulesTest {

    private val bedtime = KidsRules(bedtimeStartMinute = 20 * 60, bedtimeEndMinute = 7 * 60)

    @Test
    fun `bedtime runs past midnight`() {
        assertFalse(bedtime.isBedtime(19 * 60 + 59))
        assertTrue(bedtime.isBedtime(20 * 60))
        assertTrue(bedtime.isBedtime(2 * 60))
        assertFalse(bedtime.isBedtime(7 * 60))
    }

    @Test
    fun `no bedtime without both ends`() {
        assertFalse(KidsRules(bedtimeStartMinute = 20 * 60).isBedtime(21 * 60))
    }

    private val noon = LocalDateTime.of(2026, 10, 29, 12, 0)
    private val evening = LocalDateTime.of(2026, 10, 29, 21, 0)

    @Test
    fun `daily allowance counts down and runs out`() {
        val rules = KidsRules(dailyMinutes = 60)
        val day = ScreenTimeDay("2026-10-29", watchedMs = 45 * 60_000L)

        assertEquals(ScreenTimeStatus.Allowed(15 * 60_000L), screenTimeStatus(rules, day, noon, 0L))
        assertEquals(
            ScreenTimeStatus.DailyLimitReached,
            screenTimeStatus(rules, day.copy(watchedMs = 60 * 60_000L), noon, 0L)
        )
    }

    @Test
    fun `a parent's extra minutes extend the allowance`() {
        val day = ScreenTimeDay("2026-10-29", watchedMs = 60 * 60_000L, extraMinutes = 15)

        assertEquals(ScreenTimeStatus.Allowed(15 * 60_000L), screenTimeStatus(KidsRules(dailyMinutes = 60), day, noon, 0L))
    }

    @Test
    fun `bedtime blocks until a parent lifts it`() {
        val day = ScreenTimeDay("2026-10-29")
        val nowMs = 1_000_000L

        assertEquals(ScreenTimeStatus.Bedtime, screenTimeStatus(bedtime, day, evening, nowMs))
        assertEquals(
            ScreenTimeStatus.Allowed(null),
            screenTimeStatus(bedtime, day.copy(bedtimeLiftedUntilMs = nowMs + 1), evening, nowMs)
        )
    }

    @Test
    fun `unlocking for the day lifts every limit`() {
        val rules = bedtime.copy(dailyMinutes = 30)
        val day = ScreenTimeDay("2026-10-29", watchedMs = 120 * 60_000L, unlockedForDay = true)

        assertEquals(ScreenTimeStatus.Allowed(null), screenTimeStatus(rules, day, evening, 0L))
    }

    @Test
    fun `yesterday's record doesn't carry over`() {
        val yesterday = ScreenTimeDay("2026-10-28", watchedMs = 99L, unlockedForDay = true)

        assertEquals(ScreenTimeDay("2026-10-29"), yesterday.forDate("2026-10-29"))
    }

    @Test
    fun `a kids autoplay cap turns the still-watching prompt on`() {
        assertEquals(false to 3, effectiveStillWatching(false, 3, kidsAutoplayCap = null))
        assertEquals(true to 2, effectiveStillWatching(false, 5, kidsAutoplayCap = 2))
        assertEquals(true to 2, effectiveStillWatching(true, 4, kidsAutoplayCap = 2))
        assertEquals(true to 1, effectiveStillWatching(true, 1, kidsAutoplayCap = 3))
    }

    @Test
    fun `the lock asks for the parent PIN only for open profiles while one has limits`() {
        val restrictions = mapOf(3 to ProfileRestrictions.KidsPreset)
        val pins = mapOf(1 to true, 4 to true)
        val ids = listOf(1, 2, 3, 4)
        fun needs(profileId: Int, lockOn: Boolean = true, limits: Map<Int, ProfileRestrictions> = restrictions) =
            needsParentPinToOpen(profileId, ids, lockOn, limits, pins)

        assertTrue(needs(2))
        assertFalse(needs(2, lockOn = false))
        assertFalse(needs(1))
        assertFalse(needs(3))
        assertFalse(needs(4))
        assertFalse(needs(2, limits = emptyMap()))
        assertFalse(needsParentPinToOpen(2, ids, true, restrictions, mapOf(1 to false)))
    }

    @Test
    fun `approved rows split movies and shows, newest first`() {
        val lists = ProfileTitleLists(
            allowed = listOf(
                ParentalTitle(listOf("tt1"), "movie", "First movie", poster = "p1"),
                ParentalTitle(listOf("tt2"), "series", "A show"),
                ParentalTitle(listOf("tt3"), "movie", "Second movie")
            )
        )

        val rows = approvedTitleRows(approvedOnly = true, lists = lists, moviesTitle = "Movies", showsTitle = "Shows")

        assertEquals(listOf("Movies", "Shows"), rows.map { it.catalogName })
        assertEquals(listOf("tt3", "tt1"), rows[0].items.map { it.id })
        assertEquals("p1", rows[0].items[1].poster)
        assertEquals(listOf("tt2"), rows[1].items.map { it.id })
        assertTrue(approvedTitleRows(approvedOnly = false, lists = lists, moviesTitle = "M", showsTitle = "S").isEmpty())
    }
}
