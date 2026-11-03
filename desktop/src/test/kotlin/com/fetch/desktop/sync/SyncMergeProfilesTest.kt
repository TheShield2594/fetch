package com.fetch.desktop.sync

import com.fetch.desktop.data.DesktopProfile
import com.fetch.desktop.data.DesktopSettings
import com.fetch.desktop.data.PlayerSettings
import com.fetch.desktop.data.ProfileLimits
import com.fetch.desktop.skip.SegmentType
import com.fetch.tv.core.streams.ranking.StreamRankingRules
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMergeProfilesTest {

    // --- Addons ---

    @Test
    fun `addon urls are canonicalized like the TV app`() {
        assertEquals("https://a.example/x", SyncMerge.canonicalAddonUrl(" https://a.example/x/manifest.json/ "))
        assertEquals("https://a.example/x?c=1", SyncMerge.canonicalAddonUrl("https://a.example/x/manifest.json?c=1"))
        assertEquals(SyncMerge.addonKey("https://A.example/X/"), SyncMerge.addonKey("https://a.example/x/MANIFEST.JSON"))
    }

    @Test
    fun `the account's addon list replaces the local one, keeping local spelling`() {
        val merge = SyncMerge.addons(
            local = listOf("https://Cinemeta", "https://local-only"),
            remote = listOf(
                RemoteAddon("https://b/manifest.json", 1),
                RemoteAddon("https://cinemeta/manifest.json", 0),
                RemoteAddon("https://off", 2, enabled = false),
            ),
        )
        assertEquals(listOf("https://Cinemeta", "https://b"), merge.local)
        assertNull(merge.push)
    }

    @Test
    fun `an empty account list keeps the local addons and pushes nothing`() {
        val merge = SyncMerge.addons(listOf("https://a/manifest.json"), emptyList())
        assertEquals(listOf("https://a"), merge.local)
        assertNull(merge.push)
    }

    @Test
    fun `prefer local pushes the local list with TV names and disabled addons in place`() {
        val remote = listOf(
            RemoteAddon("https://a", 0, name = "Mine"),
            RemoteAddon("https://off", 1, enabled = false),
            RemoteAddon("https://b", 2),
        )
        val merge = SyncMerge.addons(listOf("https://b", "https://a/manifest.json", "https://new"), remote, preferLocal = true)
        assertEquals(listOf("https://b", "https://a", "https://new"), merge.local)
        assertEquals(
            listOf(
                RemoteAddon("https://b", 0),
                RemoteAddon("https://a", 1, name = "Mine"),
                RemoteAddon("https://off", 2, enabled = false),
                RemoteAddon("https://new", 3),
            ),
            merge.push,
        )
    }

    @Test
    fun `a leading disabled addon stays first`() {
        val push = SyncMerge.addonsToPush(listOf("https://a"), listOf(RemoteAddon("https://off", 0, enabled = false), RemoteAddon("https://a", 1)))
        assertEquals(listOf("https://off", "https://a"), push.map { it.url })
        assertEquals(listOf(0, 1), push.map { it.order })
    }

    // --- Profiles ---

    private val me = DesktopProfile(1, "Me", "#1E88E5", pinHash = "v1:20000:aa:bb")
    private val kids = DesktopProfile(2, "Kids", "#E53935", usesPrimaryAddons = false, pinHash = "v1:20000:cc:dd", limits = ProfileLimits(maxRating = "TEENS", dailyMinutes = 60))

    @Test
    fun `the account's profiles replace the local ones, keeping local pins and limits`() {
        val remote = listOf(
            RemoteProfile(2, "Children", "#8E24AA", usesPrimaryAddons = true),
            RemoteProfile(1, "Parent", "#43A047", usesPrimaryAddons = false),
            RemoteProfile(4, "Guest", "#FB8C00", usesPrimaryAddons = true),
        )
        val third = DesktopProfile(3, "Old", "#000000")
        val merge = SyncMerge.profiles(listOf(me, kids, third), remote)
        assertEquals(listOf(1, 2, 4), merge.local.map { it.id })
        assertEquals(listOf("Parent", "Children", "Guest"), merge.local.map { it.name })
        assertEquals("#8E24AA", merge.local[1].colorHex)
        assertTrue(merge.local[1].usesPrimaryAddons)
        assertTrue("primary keeps its own flag", merge.local[0].usesPrimaryAddons)
        assertEquals(kids.pinHash, merge.local[1].pinHash)
        assertEquals(kids.limits, merge.local[1].limits)
        assertNull(merge.local[2].pinHash)
        assertEquals(listOf(third), merge.removedLocal)
        assertNull(merge.push)
    }

    @Test
    fun `the primary profile is kept when the account lacks it`() {
        val merge = SyncMerge.profiles(listOf(me, kids), listOf(RemoteProfile(2, "Kids", "#E53935", false)))
        assertEquals(listOf(1, 2), merge.local.map { it.id })
        assertEquals(me, merge.local[0])
    }

    @Test
    fun `an account without profiles gets the local ones, keeping avatars`() {
        val merge = SyncMerge.profiles(listOf(me, kids), emptyList())
        assertEquals(listOf(me, kids), merge.local)
        assertEquals(
            listOf(RemoteProfile(1, "Me", "#1E88E5", false), RemoteProfile(2, "Kids", "#E53935", false)),
            merge.push,
        )
        val withAvatar = SyncMerge.profilesToPush(
            listOf(kids.copy(usesPrimaryAddons = true)),
            listOf(RemoteProfile(2, "x", "#000000", false, avatarId = "fox", avatarUrl = "u", profileBackgroundId = "bg", profileBackgroundUrl = "bu")),
        ).single()
        assertEquals(RemoteProfile(2, "Kids", "#E53935", true, "fox", "u", "bg", "bu"), withAvatar)
    }

    @Test
    fun `prefer local pushes even when the account has profiles`() {
        val merge = SyncMerge.profiles(listOf(me), listOf(RemoteProfile(1, "Other", "#000000", false)), preferLocal = true)
        assertEquals(listOf(me), merge.local)
        assertEquals("Me", merge.push!!.single().name)
    }

    @Test
    fun `profiles locked only on the account need a server check`() {
        val guest = DesktopProfile(3, "Guest", "#000000")
        val locks = listOf(RemoteProfileLock(1, true), RemoteProfileLock(3, true), RemoteProfileLock(2, false))
        assertEquals(listOf(3), SyncMerge.lockedOnlyRemotely(listOf(me, kids, guest), locks))
    }

    // --- Parental limits ---

    @Test
    fun `account limits only ever tighten the local ones`() {
        val local = ProfileLimits(maxRating = "TEENS", allowUnrated = true, dailyMinutes = 90)
        val tighter = SyncMerge.parentalLimits(local, RemoteParentalLimits(maxRating = "PARENTAL_GUIDANCE", allowUnrated = false))
        assertEquals(ProfileLimits(maxRating = "PARENTAL_GUIDANCE", allowUnrated = false, dailyMinutes = 90), tighter)

        val looser = SyncMerge.parentalLimits(local, RemoteParentalLimits(maxRating = null))
        assertEquals(local, looser)
        assertEquals(local, SyncMerge.parentalLimits(local, null))

        val fromNone = SyncMerge.parentalLimits(ProfileLimits(), RemoteParentalLimits(maxRating = "MATURE", allowUnrated = false))
        assertEquals("MATURE", fromNone.maxRating)
        assertFalse(fromNone.allowUnrated)
    }

    @Test
    fun `limits to push carry this profile's kids flag`() {
        val push = SyncMerge.parentalLimitsToPush(ProfileLimits(maxRating = "TEENS", allowUnrated = false, kidsProfile = true), RemoteParentalLimits(isKidsProfile = false))
        assertEquals(RemoteParentalLimits(true, "TEENS", false), push)
        // The account's flag arrives with the pull.
        assertEquals(true, SyncMerge.parentalLimits(ProfileLimits(), RemoteParentalLimits(isKidsProfile = true)).kidsProfile)
        assertEquals(RemoteParentalLimits(false, null, true), SyncMerge.parentalLimitsToPush(ProfileLimits(allowUnrated = false), null))
    }

    // --- Settings blob ---

    @Test
    fun `shared settings are read from the TV's typed entries`() {
        val blob = Json.parseToJsonElement(
            """{"version":1,"features":{
              "theme_settings":{"selected_theme":{"type":"string","value":"JADE"}},
              "layout_settings":{"discover_location":{"type":"string","value":"IN_SIDEBAR"},"continue_watching_enabled":{"type":"boolean","value":false},
                "catalog_type_suffix_enabled":{"type":"boolean","value":"true"},"poster_labels_enabled":{"type":"int","value":1}},
              "player_settings":{"subtitle_preferred_language":{"type":"string","value":"en"},
                "stream_ranking_rules":{"type":"string","value":"{\"preferOwnLibrary\":false}"},
                "auto_skip_segment_types":{"type":"string_set","value":["intro","movie-credits"]}}
            }}"""
        ).jsonObject
        val settings = SyncSettingsBlob.sharedSettings(blob)
        assertEquals("JADE", settings.theme)
        assertEquals("IN_SIDEBAR", settings.discoverLocation)
        assertEquals(false, settings.showContinueWatching)
        assertEquals(true, settings.showCatalogType)
        assertNull("wrong type is ignored", settings.showPosterLabels)
        assertNull(settings.showAddonName)
        assertEquals("en", settings.subtitleLanguage)
        assertEquals(StreamRankingRules(preferOwnLibrary = false), settings.rankingRules)
        assertEquals(setOf("intro", "movie-credits"), settings.autoSkipSegments)
        assertEquals(RemoteSharedSettings(), SyncSettingsBlob.sharedSettings(null))
    }

    @Test
    fun `writing shared settings uses the TV's types and keeps the rest`() {
        val blob = SyncSettingsBlob.withSharedSettings(
            null,
            RemoteSharedSettings(theme = "GOLD", showAddonName = false, autoSkipSegments = setOf("recap", "intro"), rankingRules = StreamRankingRules()),
        )
        assertEquals(
            """{"version":1,"features":{"theme_settings":{"selected_theme":{"type":"string","value":"GOLD"}},""" +
                """"layout_settings":{"catalog_addon_name_enabled":{"type":"boolean","value":false}},""" +
                """"player_settings":{"stream_ranking_rules":{"type":"string","value":"{}"},"auto_skip_segment_types":{"type":"string_set","value":["intro","recap"]}}}}""",
            blob.toString(),
        )
        val again = SyncSettingsBlob.withSharedSettings(blob, RemoteSharedSettings(showAddonName = true))
        assertEquals("GOLD", SyncSettingsBlob.sharedSettings(again).theme)
        assertEquals(true, SyncSettingsBlob.sharedSettings(again).showAddonName)
    }

    @Test
    fun `parental limits decode like the TV app`() {
        fun decode(json: String) = SyncSettingsBlob.parentalLimits(Json.parseToJsonElement(json).jsonObject)
        assertNull(decode("""{"features":{}}"""))
        assertEquals(RemoteParentalLimits(false, "ALL_AGES", false), decode("""{"parental":{"maxRating":"SOMETHING_NEW","allowUnrated":false}}"""))
        assertEquals(RemoteParentalLimits(true, null, true), decode("""{"parental":{"isKidsProfile":true,"maxRating":null,"allowUnrated":false}}"""))
        val written = SyncSettingsBlob.withParentalLimits(null, RemoteParentalLimits(false, "TEENS", true))
        assertEquals("""{"version":1,"parental":{"isKidsProfile":false,"maxRating":"TEENS","allowUnrated":true}}""", written.toString())
    }

    @Test
    fun `writing limits keeps the TV's kids rules and screen time`() {
        val blob = Json.parseToJsonElement(
            """{"version":1,"parental":{"isKidsProfile":true,"maxRating":"PARENTAL_GUIDANCE","allowUnrated":false,"kidsRules":{"dailyMinutes":60}},"screenTime":{"tv":{"date":"2026-11-02"}}}"""
        ).jsonObject
        val written = SyncSettingsBlob.withParentalLimits(blob, RemoteParentalLimits(true, "ALL_AGES", false))
        assertEquals(
            """{"version":1,"parental":{"isKidsProfile":true,"maxRating":"ALL_AGES","allowUnrated":false,"kidsRules":{"dailyMinutes":60}},"screenTime":{"tv":{"date":"2026-11-02"}}}""",
            written.toString()
        )
    }

    @Test
    fun `desktop settings map to and from shared settings`() {
        val desktop = DesktopSettings(
            theme = "OCEAN",
            showPosterLabels = false,
            discoverInSidebar = true,
            player = PlayerSettings(audioLanguage = "de", autoplayNextEpisode = false, autoSkip = setOf(SegmentType.OUTRO)),
        )
        val remote = RemoteSharedSettings(
            rankingRules = StreamRankingRules(dropUndecodable = true),
            autoSkipSegments = setOf("intro", "movie-credits"),
            theme = "CUSTOM",
        )
        val shared = desktop.toRemoteSharedSettings(remote)
        assertEquals("OCEAN", shared.theme)
        assertEquals("IN_SIDEBAR", shared.discoverLocation)
        assertEquals("de", shared.audioLanguage)
        assertNull(shared.subtitleLanguage)
        assertEquals(true, shared.rankingRules!!.dropUndecodable)
        assertEquals("movie credits are a desktop type too, so off here means off", setOf("outro"), shared.autoSkipSegments)
        assertEquals("FETCH", DesktopSettings().toRemoteSharedSettings(remote).theme)

        val applied = RemoteSharedSettings(
            theme = "CUSTOM",
            showContinueWatching = false,
            discoverLocation = "OFF",
            audioLanguage = "device",
            subtitleLanguage = "fr",
            rankingRules = StreamRankingRules(dropUndecodable = true, preferOwnLibrary = false),
            autoSkipSegments = setOf("intro", "movie-credits"),
        ).applyTo(desktop)
        assertEquals("a TV custom theme shows here too", "CUSTOM", applied.theme)
        assertFalse(applied.showContinueWatching)
        assertTrue(applied.discoverInSidebar)
        assertEquals("de", applied.player.audioLanguage)
        assertEquals("fr", applied.player.subtitleLanguage)
        assertFalse(applied.rankingRules.dropUndecodable)
        assertFalse(applied.rankingRules.preferOwnLibrary)
        assertEquals(setOf(SegmentType.INTRO, SegmentType.MOVIE_CREDITS), applied.player.autoSkip)
        assertNull(RemoteSharedSettings(audioLanguage = "default").applyTo(desktop).player.audioLanguage)
    }

    // --- Avatars and backgrounds ---

    @Test
    fun `an avatar removed here clears the account's, while one never set here keeps it`() {
        val remote = listOf(RemoteProfile(2, "Kid", "#000000", false, avatarId = "fetch:fox", avatarUrl = "https://x/own.png"))
        val kept = SyncMerge.profilesToPush(listOf(DesktopProfile(2, "Kid", "#000000")), remote).single()
        assertEquals("fetch:fox", kept.avatarId)
        assertEquals("https://x/own.png", kept.avatarUrl)

        val cleared = SyncMerge.profilesToPush(listOf(DesktopProfile(2, "Kid", "#000000", avatarCleared = true)), remote).single()
        assertNull(cleared.avatarId)
        assertNull(cleared.avatarUrl)
    }

    @Test
    fun `a cleared avatar stays cleared until another device sets one`() {
        val local = listOf(DesktopProfile(1, "Me", "#111111"), DesktopProfile(2, "Kid", "#000000", avatarCleared = true))
        val stillNone = SyncMerge.profiles(local, listOf(RemoteProfile(1, "Me", "#111111", false), RemoteProfile(2, "Kid", "#000000", false)))
        assertTrue(stillNone.local.first { it.id == 2 }.avatarCleared)

        val setOnTv = SyncMerge.profiles(local, listOf(RemoteProfile(1, "Me", "#111111", false), RemoteProfile(2, "Kid", "#000000", false, avatarId = "fetch:cat")))
        val kid = setOnTv.local.first { it.id == 2 }
        assertFalse(kid.avatarCleared)
        assertEquals("fetch:cat", kid.avatarId)
    }

    @Test
    fun `backgrounds come from the account and are only replaced once picked here`() {
        val remote = listOf(RemoteProfile(1, "Me", "#111111", false, profileBackgroundId = "space"))
        val merged = SyncMerge.profiles(listOf(DesktopProfile(1, "Me", "#111111")), remote).local.single()
        assertEquals("space", merged.profileBackgroundId)

        val untouched = SyncMerge.profilesToPush(listOf(DesktopProfile(1, "Me", "#111111")), remote).single()
        assertEquals("space", untouched.profileBackgroundId)

        val picked = DesktopProfile(1, "Me", "#111111", profileBackgroundId = null, backgroundEdited = true)
        assertNull(SyncMerge.profilesToPush(listOf(picked), remote).single().profileBackgroundId)
        val other = picked.copy(profileBackgroundId = "forest")
        assertEquals("forest", SyncMerge.profilesToPush(listOf(other), remote).single().profileBackgroundId)
    }
}
