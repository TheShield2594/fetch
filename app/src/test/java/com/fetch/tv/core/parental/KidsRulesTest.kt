package com.fetch.tv.core.parental

import com.fetch.tv.data.local.KidsRules
import com.fetch.tv.data.local.ParentalTitle
import com.fetch.tv.data.local.ProfileRestrictions
import com.fetch.tv.data.local.ProfileTitleLists
import com.fetch.tv.ui.screens.home.approvedTitleRows
import com.fetch.tv.ui.screens.player.effectiveStillWatching
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `the stored lock answers like the live check, for the remembered profile too`() {
        val lock = ProfileOpenLock(
            lockOn = true,
            restrictions = mapOf(3 to ProfileRestrictions.KidsPreset),
            pinEnabled = mapOf(1 to true, 4 to true)
        )
        val ids = listOf(1, 2, 3, 4)

        assertTrue(lock.needsParentPinToOpen(2, ids))
        assertFalse(lock.needsParentPinToOpen(1, ids))
        assertFalse(lock.needsParentPinToOpen(3, ids))
        assertFalse(lock.needsParentPinToOpen(4, ids))
        assertFalse(lock.copy(lockOn = false).needsParentPinToOpen(2, ids))
        // A deleted profile's leftover limits don't lock the others.
        assertFalse(lock.needsParentPinToOpen(2, listOf(1, 2, 4)))
    }

    @Test
    fun `deleting a profile needs the parent PIN while any profile has limits`() {
        val restrictions = mapOf(3 to ProfileRestrictions.KidsPreset)
        val ids = listOf(1, 2, 3)

        assertTrue(needsParentPinToDelete(ids, restrictions, parentHasPin = true))
        assertFalse(needsParentPinToDelete(ids, restrictions, parentHasPin = false))
        assertFalse(needsParentPinToDelete(ids, emptyMap(), parentHasPin = true))
        assertFalse(needsParentPinToDelete(listOf(1, 2), restrictions, parentHasPin = true))
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

    @Test
    fun `startup makes no lock decision until every input is loaded`() {
        val lock = ProfileOpenLock(
            lockOn = true,
            restrictions = mapOf(3 to ProfileRestrictions.KidsPreset),
            pinEnabled = mapOf(1 to true)
        )
        // Lock settings arrive first; the profile list and active id are still unread.
        assertNull(startupNeedsParentPin(lock, activeProfileId = null, profileIds = null))
        assertNull(startupNeedsParentPin(lock, activeProfileId = 2, profileIds = null))
        assertNull(startupNeedsParentPin(lock, activeProfileId = null, profileIds = listOf(1, 2, 3)))
        assertNull(startupNeedsParentPin(null, activeProfileId = 2, profileIds = listOf(1, 2, 3)))
        // Once the real list (with the restricted sibling) is in, the remembered profile is locked.
        assertTrue(startupNeedsParentPin(lock, activeProfileId = 2, profileIds = listOf(1, 2, 3)) == true)
    }

    @Test
    fun `startup picker follows loaded values and always gates a parent-PIN profile`() {
        // Placeholder phase: nothing loaded yet, so no picker decision is based on one profile.
        assertFalse(shouldShowStartupPicker(false, loadedProfileCount = null, activeProfileHasPin = false, needsParentPin = null))
        // Several profiles loaded: picker.
        assertTrue(shouldShowStartupPicker(false, loadedProfileCount = 3, activeProfileHasPin = false, needsParentPin = false))
        // A single profile that still needs the parent PIN goes through the picker.
        assertTrue(shouldShowStartupPicker(false, loadedProfileCount = 1, activeProfileHasPin = false, needsParentPin = true))
        // Already chosen this session: no picker.
        assertFalse(shouldShowStartupPicker(true, loadedProfileCount = 3, activeProfileHasPin = true, needsParentPin = true))
    }
    @Test
    fun `picker refuses a selection until PIN and lock states have loaded`() {
        val lock = ProfileOpenLock(
            lockOn = true,
            restrictions = mapOf(3 to ProfileRestrictions.KidsPreset),
            pinEnabled = mapOf(1 to true, 4 to true)
        )
        // Nothing read yet, or only part of it: the press is ignored, never an unchecked open.
        assertNull(pickerOpenGate(null, profileId = 4, profileIds = null))
        assertNull(pickerOpenGate(null, profileId = 4, profileIds = listOf(1, 2, 3, 4)))
        assertNull(pickerOpenGate(lock, profileId = 4, profileIds = null))
        // Once loaded: own PIN, parent PIN for the unrestricted sibling, open for the kid.
        assertEquals(PickerOpenGate.OWN_PIN, pickerOpenGate(lock, profileId = 4, profileIds = listOf(1, 2, 3, 4)))
        assertEquals(PickerOpenGate.PARENT_PIN, pickerOpenGate(lock, profileId = 2, profileIds = listOf(1, 2, 3, 4)))
        assertEquals(PickerOpenGate.OPEN, pickerOpenGate(lock, profileId = 3, profileIds = listOf(1, 2, 3, 4)))
        assertEquals(PickerOpenGate.OWN_PIN, pickerOpenGate(lock, profileId = 1, profileIds = listOf(1, 2, 3, 4)))
    }
}
