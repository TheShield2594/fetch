package com.fetch.tv.data.website

import com.fetch.tv.TestPreferencesStore
import com.fetch.tv.data.local.KidsRules
import com.fetch.tv.data.local.ParentalTitle
import com.fetch.tv.data.local.ProfileRestrictions
import com.fetch.tv.data.local.ProfileRestrictionsDataStore
import com.fetch.tv.data.local.ProfileTitleLists
import com.fetch.tv.data.website.FetchWebsiteMappings.KidsRulesSync
import com.fetch.tv.data.website.FetchWebsiteMappings.LocalKidsRules
import com.fetch.tv.domain.model.UserProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kids rules carried with a profile by a paired Fetch website. */
class FetchWebsiteKidsRulesTest {
    private val kid = UserProfile(id = 2, name = "Kid", avatarColorHex = "#43A047")
    private val film = ParentalTitle(ids = listOf("tmdb:603", "tt0133093"), type = "movie", name = "A film", poster = "https://img.example/p.jpg")
    private val anime = ParentalTitle(ids = listOf("kitsu:1"), type = "series", name = "A show")
    private val rules = KidsRules(
        autoplayEpisodeCap = 2,
        approvedTitlesOnly = true,
        dailyMinutes = 90,
        bedtimeStartMinute = 20 * 60,
        bedtimeEndMinute = 7 * 60,
        hardStop = true,
        approvedListIds = listOf("local-list-1")
    )
    private val changedAtMs = 1_762_945_200_000L // 2025-11-12T11:00:00Z
    private val json = Json { ignoreUnknownKeys = true }

    private fun kidsRulesOf(body: JsonObject): JsonObject =
        (body["parental"] as JsonObject)["kidsRules"] as JsonObject

    @Test
    fun `the TV sends its rules, approved titles and when they changed, but not its own list choices`() {
        val body = FetchWebsiteMappings.toWebsiteBody(
            kid,
            ProfileRestrictions.KidsPreset,
            LocalKidsRules(rules, listOf(film, anime), changedAtMs)
        )

        val sent = kidsRulesOf(body)
        assertEquals(JsonPrimitive(2), sent["autoplayEpisodeCap"])
        assertEquals(JsonPrimitive(true), sent["approvedTitlesOnly"])
        assertEquals(JsonPrimitive(90), sent["dailyMinutes"])
        assertEquals(JsonPrimitive(1200), sent["bedtimeStartMinute"])
        assertEquals(JsonPrimitive(420), sent["bedtimeEndMinute"])
        assertEquals(JsonPrimitive(true), sent["hardStop"])
        assertEquals(JsonPrimitive("2025-11-12T11:00:00Z"), sent["updatedAt"])
        assertFalse(sent.containsKey("approvedListIds"))
        val titles = sent["approvedTitles"]!!.jsonArray
        assertEquals(2, titles.size)
        assertEquals(JsonArray(listOf(JsonPrimitive("tmdb:603"), JsonPrimitive("tt0133093"))), titles[0].jsonObject["ids"])
        assertEquals(JsonArray(listOf(JsonPrimitive("kitsu:1"))), titles[1].jsonObject["ids"])
        assertEquals(JsonNull, titles[1].jsonObject["poster"])
    }

    @Test
    fun `no cap and no limit go as nulls`() {
        val body = FetchWebsiteMappings.toWebsiteBody(
            kid,
            ProfileRestrictions.KidsPreset,
            LocalKidsRules(KidsRules(autoplayEpisodeCap = null), emptyList(), changedAtMs)
        )

        val sent = kidsRulesOf(body)
        assertEquals(JsonNull, sent["autoplayEpisodeCap"])
        assertEquals(JsonNull, sent["dailyMinutes"])
        assertEquals(JsonNull, sent["bedtimeStartMinute"])
        assertEquals(JsonArray(emptyList()), sent["approvedTitles"])
    }

    @Test
    fun `what the website would refuse is left out rather than failing the whole profile`() {
        val odd = ParentalTitle(ids = listOf(" ", "x".repeat(201)), type = "movie", name = "No usable id")
        val badArt = ParentalTitle(ids = listOf("tt1"), type = "movie", name = "Art", poster = "file:///sdcard/p.jpg")
        val body = FetchWebsiteMappings.toWebsiteBody(
            kid,
            ProfileRestrictions.KidsPreset,
            LocalKidsRules(KidsRules(bedtimeStartMinute = 20 * 60, bedtimeEndMinute = null), listOf(odd, badArt), changedAtMs)
        )

        val sent = kidsRulesOf(body)
        assertEquals(JsonNull, sent["bedtimeStartMinute"])
        val titles = sent["approvedTitles"]!!.jsonArray
        assertEquals(1, titles.size)
        assertEquals(JsonNull, titles[0].jsonObject["poster"])
    }

    @Test
    fun `a TV with no copy of its own sends no rules, and the primary profile never does`() {
        assertFalse(kidsRulesOfOrNull(FetchWebsiteMappings.toWebsiteBody(kid, ProfileRestrictions.KidsPreset)))
        val primary = FetchWebsiteMappings.toWebsiteBody(
            UserProfile(id = 1, name = "Me", avatarColorHex = "#1E88E5"),
            ProfileRestrictions.None,
            LocalKidsRules(rules, emptyList(), changedAtMs)
        )
        assertFalse(kidsRulesOfOrNull(primary))
    }

    private fun kidsRulesOfOrNull(body: JsonObject): Boolean = (body["parental"] as JsonObject).containsKey("kidsRules")

    @Test
    fun `the website's answer reads back into the TV's rules and approved titles`() {
        val profile = json.decodeFromString(
            WebsiteSlottedProfile.serializer(),
            """
            {"slot":2,"id":"p","name":"Kid","parental":{"isKidsProfile":true,"maxRating":"ALL_AGES","allowUnrated":false,
             "kidsRules":{"autoplayEpisodeCap":null,"approvedTitlesOnly":true,"dailyMinutes":45,
               "bedtimeStartMinute":1260,"bedtimeEndMinute":390,"hardStop":false,
               "approvedTitles":[{"ids":["tmdb:603"],"type":"movie","name":"A film","poster":null,"background":null}],
               "updatedAt":"2025-11-12T11:00:00.000Z"}}}
            """.trimIndent()
        )

        val remote = profile.parental.kidsRules!!
        assertEquals(
            KidsRules(
                autoplayEpisodeCap = null,
                approvedTitlesOnly = true,
                dailyMinutes = 45,
                bedtimeStartMinute = 1260,
                bedtimeEndMinute = 390,
                hardStop = false
            ),
            FetchWebsiteMappings.fromWebsiteKidsRules(remote)
        )
        assertEquals(
            listOf(ParentalTitle(ids = listOf("tmdb:603"), type = "movie", name = "A film")),
            FetchWebsiteMappings.fromWebsiteApprovedTitles(remote)
        )
        assertEquals(changedAtMs, FetchWebsiteMappings.parseInstantMs(remote.updatedAt!!))
    }

    @Test
    fun `a website that doesn't carry kids rules reads as none`() {
        val profile = json.decodeFromString(
            WebsiteSlottedProfile.serializer(),
            """{"slot":2,"id":"p","name":"Kid","parental":{"isKidsProfile":true,"maxRating":"ALL_AGES","allowUnrated":false}}"""
        )
        assertNull(profile.parental.kidsRules)
    }

    private fun remote(updatedAt: String?) = WebsiteKidsRules(dailyMinutes = 30, updatedAt = updatedAt)

    @Test
    fun `the newer copy wins`() {
        val website = remote("2025-11-12T11:00:00Z")

        val newerThere = FetchWebsiteMappings.decideKidsRules(website, localChangedAtMs = changedAtMs - 1, localHasRules = true)
        assertTrue(newerThere is KidsRulesSync.TakeWebsite)
        assertEquals(30, (newerThere as KidsRulesSync.TakeWebsite).rules.dailyMinutes)
        assertEquals(changedAtMs, newerThere.changedAtMs)

        assertEquals(
            KidsRulesSync.SendLocal,
            FetchWebsiteMappings.decideKidsRules(website, localChangedAtMs = changedAtMs + 1, localHasRules = true)
        )
        assertEquals(
            KidsRulesSync.Keep,
            FetchWebsiteMappings.decideKidsRules(website, localChangedAtMs = changedAtMs, localHasRules = true)
        )
    }

    @Test
    fun `a TV that never had a copy of its own takes the website's`() {
        val decision = FetchWebsiteMappings.decideKidsRules(remote("2025-11-12T11:00:00Z"), localChangedAtMs = null, localHasRules = true)
        assertTrue(decision is KidsRulesSync.TakeWebsite)
    }

    @Test
    fun `rules set on a TV before the website had any are sent to it`() {
        assertEquals(KidsRulesSync.SendLocal, FetchWebsiteMappings.decideKidsRules(null, localChangedAtMs = null, localHasRules = true))
        assertEquals(KidsRulesSync.SendLocal, FetchWebsiteMappings.decideKidsRules(remote(null), localChangedAtMs = changedAtMs, localHasRules = false))
        assertEquals(KidsRulesSync.Keep, FetchWebsiteMappings.decideKidsRules(null, localChangedAtMs = null, localHasRules = false))
    }

    @Test
    fun `taking the website's copy keeps the TV's list choices and hidden titles`() = runTest {
        val store = ProfileRestrictionsDataStore(TestPreferencesStore())
        val hidden = ParentalTitle(ids = listOf("tt0000001"), type = "movie", name = "Hidden")
        store.setKidsRules(2, KidsRules(dailyMinutes = 60, approvedListIds = listOf("local-list-1")))
        store.updateTitleLists(2) { ProfileTitleLists(hidden = listOf(hidden, anime), allowed = listOf(film)) }
        store.markKidsRulesChanged(2, changedAtMs - 1000)

        val applied = store.applyWebsiteKidsRules(
            profileId = 2,
            rules = KidsRules(dailyMinutes = 30),
            allowed = listOf(anime),
            changedAtMs = changedAtMs,
            expectedLocalChangedAtMs = changedAtMs - 1000
        )

        assertTrue(applied)
        assertEquals(KidsRules(dailyMinutes = 30, approvedListIds = listOf("local-list-1")), store.kidsRules.first()[2])
        // A title is on one list at most: the one the website allows comes off the hidden list.
        assertEquals(ProfileTitleLists(hidden = listOf(hidden), allowed = listOf(anime)), store.titleLists.first()[2])
        assertEquals(changedAtMs, store.kidsRulesChangedAt.first()[2])
    }

    @Test
    fun `a change made here while the website's copy was on its way is kept`() = runTest {
        val store = ProfileRestrictionsDataStore(TestPreferencesStore())
        store.setKidsRules(2, KidsRules(dailyMinutes = 60))
        store.markKidsRulesChanged(2, changedAtMs + 5000)

        val applied = store.applyWebsiteKidsRules(
            profileId = 2,
            rules = KidsRules(dailyMinutes = 30),
            allowed = emptyList(),
            changedAtMs = changedAtMs,
            expectedLocalChangedAtMs = changedAtMs - 1000
        )

        assertFalse(applied)
        assertEquals(60, store.kidsRules.first()[2]?.dailyMinutes)
        assertEquals(changedAtMs + 5000, store.kidsRulesChangedAt.first()[2])
    }

    @Test
    fun `deleting a profile forgets when its rules changed`() = runTest {
        val store = ProfileRestrictionsDataStore(TestPreferencesStore())
        store.markKidsRulesChanged(2, changedAtMs)

        store.deleteProfile(2)

        assertTrue(store.kidsRulesChangedAt.first().isEmpty())
    }
}
