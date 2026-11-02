package com.fetch.tv.core.parental

import com.fetch.tv.TestPreferencesStore
import com.fetch.tv.core.tracking.LocalList
import com.fetch.tv.data.local.KidsRules
import com.fetch.tv.data.local.ParentalTitle
import com.fetch.tv.data.local.ProfileRestrictions
import com.fetch.tv.data.local.ProfileRestrictionsDataStore
import com.fetch.tv.data.local.ProfileTitleLists
import com.fetch.tv.data.local.coveringBedtime
import com.fetch.tv.domain.model.PosterShape
import com.fetch.tv.domain.model.SavedLibraryItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KidsRulesSharingTest {

    private fun bed(start: Int, end: Int) = KidsRules(bedtimeStartMinute = start, bedtimeEndMinute = end)

    @Test
    fun `tightening keeps the stricter of each rule`() {
        val here = KidsRules(autoplayEpisodeCap = 3, dailyMinutes = 120, approvedListIds = listOf("a"))
        val there = KidsRules(autoplayEpisodeCap = null, dailyMinutes = 60, approvedTitlesOnly = true, hardStop = true)

        val tightened = here.tightenedWith(there)

        assertEquals(3, tightened.autoplayEpisodeCap)
        assertEquals(60, tightened.dailyMinutes)
        assertTrue(tightened.approvedTitlesOnly)
        assertTrue(tightened.hardStop)
        assertEquals(listOf("a"), tightened.approvedListIds)
    }

    @Test
    fun `tightening never loosens`() {
        val strict = KidsRules(autoplayEpisodeCap = 1, dailyMinutes = 30, approvedTitlesOnly = true, hardStop = true)
            .copy(bedtimeStartMinute = 19 * 60, bedtimeEndMinute = 7 * 60)
        assertEquals(strict, strict.tightenedWith(KidsRules(autoplayEpisodeCap = null)))
        assertEquals(strict, strict.tightenedWith(KidsRules.Default))
    }

    @Test
    fun `a longer bedtime from another TV applies`() {
        assertEquals(19 * 60 to 7 * 60, coveringBedtime(bed(21 * 60, 7 * 60), bed(19 * 60, 7 * 60)))
        assertEquals(20 * 60 to 7 * 60, coveringBedtime(KidsRules.Default, bed(20 * 60, 7 * 60)))
        assertEquals(null to null, coveringBedtime(KidsRules.Default, KidsRules.Default))
    }

    @Test
    fun `overlapping bedtimes merge and separate ones close the shorter gap`() {
        // 20:00-06:00 and 22:00-08:00 overlap: 20:00-08:00.
        assertEquals(20 * 60 to 8 * 60, coveringBedtime(bed(20 * 60, 6 * 60), bed(22 * 60, 8 * 60)))
        // 13:00-14:00 and 20:00-07:00 don't touch: one window has to cover both, filling a gap.
        val (start, end) = coveringBedtime(bed(13 * 60, 14 * 60), bed(20 * 60, 7 * 60))
        val merged = KidsRules(bedtimeStartMinute = start, bedtimeEndMinute = end)
        listOf(13 * 60, 13 * 60 + 59, 20 * 60, 0, 6 * 60 + 59).forEach { assertTrue(merged.isBedtime(it)) }
    }

    @Test
    fun `account kids rules tighten this TV's rules and are recorded`() = runTest {
        val store = ProfileRestrictionsDataStore(TestPreferencesStore())
        store.setKidsRules(2, KidsRules(dailyMinutes = 120, approvedListIds = listOf("mine")))

        val changed = store.tightenFromAccount(2, ProfileRestrictions.KidsPreset, KidsRules(dailyMinutes = 60))

        assertTrue(changed)
        val local = store.kidsRules.first()[2]!!
        assertEquals(60, local.dailyMinutes)
        assertEquals(listOf("mine"), local.approvedListIds)
        assertEquals(KidsRules(dailyMinutes = 60), store.accountKidsRules.first()[2])
    }

    @Test
    fun `looser account kids rules leave this TV's alone`() = runTest {
        val store = ProfileRestrictionsDataStore(TestPreferencesStore())
        store.setKidsRules(2, KidsRules(dailyMinutes = 30))

        assertFalse(store.tightenFromAccount(2, ProfileRestrictions.None, KidsRules(dailyMinutes = 180)))
        assertEquals(30, store.kidsRules.first()[2]?.dailyMinutes)
    }

    @Test
    fun `a parent's change waits for its push and then clears`() = runTest {
        val store = ProfileRestrictionsDataStore(TestPreferencesStore())
        val rules = KidsRules(dailyMinutes = 90, approvedListIds = listOf("mine"))
        store.setKidsRulesByParent(2, rules)

        assertEquals(setOf(2), store.pendingAccountPushes.first())
        assertEquals(rules.forAccount(), store.accountKidsRules.first()[2])
        // A pull meanwhile leaves the profile alone.
        assertFalse(store.tightenFromAccount(2, ProfileRestrictions.KidsPreset, KidsRules(dailyMinutes = 10)))

        store.setPendingAccountRestrictions(2, ProfileRestrictions.KidsPreset)
        store.accountPushSucceeded(2, ProfileRestrictions.KidsPreset, rules.forAccount())
        assertTrue(store.pendingAccountPushes.first().isEmpty())
    }

    private fun item(id: String, type: String = "series", addedAt: Long = 0L) = SavedLibraryItem(
        id = id, type = type, name = id, poster = null, posterShape = PosterShape.POSTER,
        background = null, description = null, releaseInfo = null, imdbRating = null,
        genres = emptyList(), addonBaseUrl = null, addedAt = addedAt
    )

    @Test
    fun `titles on the parent's chosen lists are approved`() {
        val lists = listOf(
            LocalList(id = "kids", name = "Kids", items = listOf(item("tt2", addedAt = 2), item("tt1", addedAt = 1))),
            LocalList(id = "mine", name = "Mine", items = listOf(item("tt9")))
        )
        val rules = KidsRules(approvedTitlesOnly = true, approvedListIds = listOf("kids"))

        val titles = approvedTitlesFromLists(rules, lists)

        assertEquals(listOf("tt1", "tt2"), titles.map { it.ids.single() })
        val effective = ProfileTitleLists.Empty.withApprovedFromLists(titles)
        assertTrue(effective.isAllowed("tt1:1:2", null, "series"))
        assertFalse(effective.isAllowed("tt9", null, "series"))
    }

    @Test
    fun `chosen lists count only in approved titles only mode`() {
        val lists = listOf(LocalList(id = "kids", name = "Kids", items = listOf(item("tt1"))))
        assertTrue(approvedTitlesFromLists(KidsRules(approvedListIds = listOf("kids")), lists).isEmpty())
        assertTrue(approvedTitlesFromLists(null, lists).isEmpty())
    }

    @Test
    fun `a hidden title stays hidden even on a chosen list`() {
        val hidden = ParentalTitle(ids = listOf("tt1"), type = "series", name = "x")
        val lists = ProfileTitleLists(hidden = listOf(hidden))
        val effective = lists.withApprovedFromLists(
            listOf(ParentalTitle(ids = listOf("tt1"), type = "series", name = "x"))
        )
        assertEquals(lists, effective)
    }
}
