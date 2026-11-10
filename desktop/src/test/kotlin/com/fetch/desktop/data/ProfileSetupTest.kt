package com.fetch.desktop.data

import com.fetch.desktop.mdblist.MdbListSettings
import com.fetch.desktop.sync.SyncMerge
import com.fetch.desktop.sync.RemoteProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSetupTest {
    @Test
    fun `copied settings keep the target's Home layout and keys, and take missing keys when asked`() {
        val source = DesktopSettings(
            theme = "OCEAN",
            pinnedCatalogs = listOf("a"),
            tmdbApiKey = "src-tmdb",
            mdblist = MdbListSettings(enabled = true, apiKey = "src-mdb"),
            recentSearches = listOf("dune")
        )
        val target = DesktopSettings(pinnedCatalogs = listOf("b"), mdblist = MdbListSettings(apiKey = "mine"))
        val plain = copiedSettings(source, target, copyMissingKeys = false)
        assertEquals("OCEAN", plain.theme)
        assertEquals(listOf("b"), plain.pinnedCatalogs)
        assertEquals(emptyList<String>(), plain.recentSearches)
        assertNull(plain.tmdbApiKey)
        assertEquals("mine", plain.mdblist.apiKey)
        assertTrue(plain.mdblist.enabled)
        val withKeys = copiedSettings(source, target, copyMissingKeys = true)
        assertEquals("src-tmdb", withKeys.tmdbApiKey)
        assertEquals("mine", withKeys.mdblist.apiKey)
    }

    @Test
    fun `preferences round-trip through the player settings`() {
        val settings = DesktopSettings()
        val prefs = ProfilePreferences.from(settings).copy(audioLanguage = "ja", subtitlesOff = true, autoplayNextEpisode = false)
        val applied = prefs.applyTo(settings)
        assertEquals("ja", applied.player.audioLanguage)
        assertEquals(SubtitleMode.OFF, applied.player.subtitleMode)
        assertEquals(false, applied.player.autoplayNextEpisode)
        assertEquals(prefs, ProfilePreferences.from(applied))
        // Turning subtitles back on picks "when available".
        assertEquals(SubtitleMode.AUTO, prefs.copy(subtitlesOff = false).applyTo(applied).player.subtitleMode)
    }

    @Test
    fun `viewing activity lists finished and partly watched titles newest first, once each`() {
        val file = ProgressFile(
            entries = listOf(
                ProgressEntry("tt1", "series", "Show", videoId = "tt1:1:2", season = 1, episode = 2, episodeTitle = "Two", positionMs = 30, durationMs = 100, lastWatched = 300),
                ProgressEntry("tt2", "series", "Next", videoId = "tt2:1:1", season = 1, episode = 1, positionMs = 0, durationMs = 0, lastWatched = 500, upNext = true),
                ProgressEntry("tt3", "movie", "Film", videoId = "tt3", positionMs = 50, durationMs = 100, lastWatched = 100)
            ),
            finished = listOf(FinishedTitle("tt3", "movie", "Film", at = 200), FinishedTitle("tt4", "movie", "Old", at = 0))
        )
        val activity = viewingActivity(file)
        assertEquals(listOf("tt1", "tt3"), activity.map { it.contentId })
        assertEquals(30, activity[0].percentWatched)
        assertEquals("S1 E2 · Two", activity[0].episodeLine)
        assertNull(activity[1].percentWatched)
    }

    @Test
    fun `bundled avatars match the TV's ids`() {
        assertEquals(40, BundledAvatars.catalog.size)
        assertEquals("/avatars/space_invader.webp", BundledAvatars.resource("fetch:space_invader"))
        assertNull(BundledAvatars.resource("fox"))
        assertTrue(BundledAvatars.catalog.all { BundledAvatars::class.java.getResource(BundledAvatars.resource(it.id)!!) != null })
    }

    @Test
    fun `an avatar picked here goes up, otherwise the account's stays`() {
        val remote = listOf(RemoteProfile(1, "Me", "#1E88E5", usesPrimaryAddons = false, avatarId = "server:1", avatarUrl = "https://x/pic.png"))
        val kept = SyncMerge.profilesToPush(listOf(DesktopProfile(1, "Me", "#1E88E5", avatarId = "server:1")), remote).single()
        assertEquals("server:1", kept.avatarId)
        assertEquals("https://x/pic.png", kept.avatarUrl)
        val changed = SyncMerge.profilesToPush(listOf(DesktopProfile(1, "Me", "#1E88E5", avatarId = "fetch:fox")), remote).single()
        assertEquals("fetch:fox", changed.avatarId)
        assertNull(changed.avatarUrl)
        val pulled = SyncMerge.profiles(emptyList(), remote).local.single()
        assertEquals("server:1", pulled.avatarId)
    }
}
