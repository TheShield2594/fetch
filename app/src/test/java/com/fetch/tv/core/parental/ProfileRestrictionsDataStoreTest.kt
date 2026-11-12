package com.fetch.tv.core.parental

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.fetch.tv.TestPreferencesStore
import com.fetch.tv.data.local.KidsRules
import com.fetch.tv.data.local.ParentalTitle
import com.fetch.tv.data.local.ProfileRestrictions
import com.fetch.tv.data.local.ProfileRestrictionsDataStore
import com.fetch.tv.data.local.ProfileTitleLists
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileRestrictionsDataStoreTest {
    private val store = ProfileRestrictionsDataStore(TestPreferencesStore())

    @Test
    fun `round trips restrictions per profile`() = runTest {
        val teen = ProfileRestrictions(maxLevel = CertificationLevel.TEENS, allowUnrated = true)
        store.setRestrictions(2, ProfileRestrictions.KidsPreset)
        store.setRestrictions(3, teen)

        val all = store.restrictions.first()
        assertEquals(ProfileRestrictions.KidsPreset, all[2])
        assertEquals(teen, all[3])
    }

    private fun corruptedStore() = ProfileRestrictionsDataStore(
        TestPreferencesStore(preferencesOf(booleanPreferencesKey("corruption_reset") to true))
    )

    @Test
    fun `after the file was lost every profile but the main one reads as a kids profile`() = runTest {
        val corrupted = corruptedStore()

        val all = corrupted.restrictions.first()
        assertEquals(ProfileRestrictions.KidsPreset, all[2])
        assertEquals(ProfileRestrictions.KidsPreset, all[7])
        assertEquals(null, all[1])
        assertTrue(corrupted.parentalControlsConfigured.first())
    }

    @Test
    fun `after the file was lost a parent can still lift a profile's limits`() = runTest {
        val corrupted = corruptedStore()

        corrupted.setRestrictions(2, ProfileRestrictions.None)

        val all = corrupted.restrictions.first()
        assertEquals(ProfileRestrictions.None, all[2])
        assertEquals(ProfileRestrictions.KidsPreset, all[3])
    }

    @Test
    fun `parental controls count as configured only when something is set`() = runTest {
        assertFalse(store.parentalControlsConfigured.first())
        store.setRestrictions(2, ProfileRestrictions.KidsPreset)
        assertTrue(store.parentalControlsConfigured.first())
    }

    @Test
    fun `clearing to none removes the entry`() = runTest {
        store.setRestrictions(2, ProfileRestrictions.KidsPreset)
        store.removeProfile(2)

        assertTrue(store.restrictions.first().isEmpty())
    }

    @Test
    fun `kids preset is restricted and hides unrated titles`() {
        val preset = ProfileRestrictions.KidsPreset
        assertTrue(preset.isRestricted)
        assertFalse(preset.contentRestriction.allows(null))
        assertTrue(preset.contentRestriction.allows(CertificationLevel.PARENTAL_GUIDANCE))
        assertFalse(preset.contentRestriction.allows(CertificationLevel.TEENS))
        assertFalse(ProfileRestrictions.None.isRestricted)
    }

    @Test
    fun `limits and kids rules saved next to the old account copies still load, and the copies are dropped on the next write`() = runTest {
        val accountKeys = listOf("account_restrictions_map", "account_push_pending", "account_kids_rules")
        val prefs = TestPreferencesStore(
            preferencesOf(
                stringPreferencesKey("restrictions_map") to "2:true:PARENTAL_GUIDANCE:false,3:false:TEENS:true",
                stringPreferencesKey("kids_rules") to """{"2":{"dailyMinutes":60,"approvedListIds":["mine"]}}""",
                stringPreferencesKey("account_restrictions_map") to "2:true:PARENTAL_GUIDANCE:false",
                stringSetPreferencesKey("account_push_pending") to setOf("2"),
                stringPreferencesKey("account_kids_rules") to """{"2":{"dailyMinutes":30}}"""
            )
        )
        val legacy = ProfileRestrictionsDataStore(prefs)

        val restrictions = legacy.restrictions.first()
        assertEquals(ProfileRestrictions.KidsPreset, restrictions[2])
        assertEquals(ProfileRestrictions(maxLevel = CertificationLevel.TEENS, allowUnrated = true), restrictions[3])
        assertEquals(KidsRules(dailyMinutes = 60, approvedListIds = listOf("mine")), legacy.kidsRules.first()[2])

        legacy.setRestrictions(4, ProfileRestrictions.KidsPreset)

        assertTrue(prefs.value.asMap().keys.none { it.name in accountKeys })
        assertEquals(ProfileRestrictions.KidsPreset, legacy.restrictions.first()[2])
        assertEquals(60, legacy.kidsRules.first()[2]?.dailyMinutes)
    }

    private val movie = ParentalTitle(ids = listOf("tt0111161", "tmdb:278"), type = "movie", name = "A Film")
    private val show = ParentalTitle(ids = listOf("tt0903747"), type = "series", name = "A Show")

    @Test
    fun `title lists round trip per profile`() = runTest {
        store.updateTitleLists(2) { it.withHidden(movie) }
        store.updateTitleLists(3) { it.withAllowed(show) }

        val all = store.titleLists.first()
        assertEquals(listOf(movie), all[2]?.hidden)
        assertEquals(listOf(show), all[3]?.allowed)
    }

    @Test
    fun `limits from a website pull leave the title lists alone`() = runTest {
        store.updateTitleLists(2) { it.withHidden(movie) }
        store.setRestrictions(2, ProfileRestrictions.KidsPreset)
        store.removeProfile(2)

        assertEquals(listOf(movie), store.titleLists.first()[2]?.hidden)
    }

    @Test
    fun `deleting a profile drops its title lists`() = runTest {
        store.updateTitleLists(2) { it.withHidden(movie) }
        store.deleteProfile(2)

        assertTrue(store.titleLists.first().isEmpty())
    }

    @Test
    fun `a title is on one list at most`() {
        val lists = ProfileTitleLists().withHidden(movie).withAllowed(movie)

        assertTrue(lists.hidden.isEmpty())
        assertEquals(listOf(movie), lists.allowed)
        assertTrue(lists.without(movie).isEmpty)
    }

    @Test
    fun `a listed title matches any of its ids and its episodes`() {
        val lists = ProfileTitleLists().withHidden(movie).withHidden(show)

        assertTrue(lists.isHidden("tmdb:278", imdbId = null, type = "movie"))
        assertTrue(lists.isHidden("tmdb:999", imdbId = "tt0111161", type = null))
        assertTrue(lists.isHidden("tt0903747:1:2", imdbId = null, type = "series"))
        assertFalse(lists.isHidden("tt09037470", imdbId = null, type = null))
        assertFalse(lists.isAllowed("tt0111161", imdbId = null, type = "movie"))
    }

    @Test
    fun `a movie and a series sharing an id stay apart`() {
        val series = ParentalTitle(ids = listOf("tmdb:278"), type = "tv", name = "Another Show")
        val lists = ProfileTitleLists().withAllowed(movie).withHidden(series)

        assertTrue(lists.isAllowed("tmdb:278", imdbId = null, type = "movie"))
        assertFalse(lists.isAllowed("tmdb:278", imdbId = null, type = "series"))
        assertTrue(lists.isHidden("tmdb:278", imdbId = null, type = "series"))
        assertFalse(lists.isHidden("tmdb:278", imdbId = null, type = "movie"))
        assertEquals(listOf(series), lists.without(movie).hidden)
    }
}
