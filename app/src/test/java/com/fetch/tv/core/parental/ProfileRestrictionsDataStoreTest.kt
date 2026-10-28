package com.fetch.tv.core.parental

import com.fetch.tv.TestPreferencesStore
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
    fun `tightening keeps the stricter of each setting`() {
        val teen = ProfileRestrictions(maxLevel = CertificationLevel.TEENS, allowUnrated = true)
        val tightened = teen.tightenedWith(ProfileRestrictions.KidsPreset)

        assertEquals(ProfileRestrictions.KidsPreset, tightened)
        assertEquals(tightened, ProfileRestrictions.KidsPreset.tightenedWith(teen))
    }

    @Test
    fun `tightening with no limits changes nothing`() {
        val teen = ProfileRestrictions(maxLevel = CertificationLevel.TEENS, allowUnrated = false)

        assertEquals(teen, teen.tightenedWith(ProfileRestrictions.None))
        assertEquals(teen, ProfileRestrictions.None.tightenedWith(teen))
        assertEquals(ProfileRestrictions.None, ProfileRestrictions.None.tightenedWith(ProfileRestrictions.None))
    }

    @Test
    fun `account limits tighten this TV's but never loosen them`() = runTest {
        store.setRestrictions(2, ProfileRestrictions.KidsPreset)

        assertFalse(store.tightenFromAccount(2, ProfileRestrictions.None))
        assertEquals(ProfileRestrictions.KidsPreset, store.restrictions.first()[2])

        val teen = ProfileRestrictions(maxLevel = CertificationLevel.TEENS)
        assertFalse(store.tightenFromAccount(2, teen))
        assertEquals(ProfileRestrictions.KidsPreset, store.restrictions.first()[2])
        assertEquals(teen, store.accountRestrictions.first()[2])
    }

    @Test
    fun `account limits make an unrestricted profile a kids profile`() = runTest {
        assertTrue(store.tightenFromAccount(3, ProfileRestrictions.KidsPreset))

        assertEquals(ProfileRestrictions.KidsPreset, store.restrictions.first()[3])
        assertEquals(ProfileRestrictions.KidsPreset, store.accountRestrictions.first()[3])
    }

    @Test
    fun `the account copy keeps an explicit none and goes with the profile`() = runTest {
        store.setAccountRestrictions(2, ProfileRestrictions.None)
        assertEquals(ProfileRestrictions.None, store.accountRestrictions.first()[2])

        store.deleteProfile(2)
        assertFalse(2 in store.accountRestrictions.first())
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
