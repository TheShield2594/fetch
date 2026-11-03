package com.fetch.desktop.youtube

import com.fetch.desktop.data.DesktopSettings
import com.fetch.desktop.data.JsonFile
import com.fetch.desktop.data.PlayerSettings
import com.fetch.desktop.data.SubtitleMode
import com.fetch.desktop.skip.SegmentType
import com.fetch.desktop.skip.SkipPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Locale

/** How desktop plays YouTube in mpv, and the TV's rules for resume, searches, the Home row and settings. */
class YouTubePlaybackTest {

    @Test
    fun `the quality setting caps yt-dlp's format`() {
        assertEquals("bestvideo[height<=?1080]+bestaudio/best[height<=?1080]/best", YouTubePlayback.ytdlFormat(1080))
        val options = YouTubePlayback.mpvOptions(PlayerSettings(), YouTubeSettings(maxVideoHeight = 2160), Locale.US)
        assertTrue(options.contains("--ytdl=yes"))
        assertTrue(options.contains("--ytdl-format=bestvideo[height<=?2160]+bestaudio/best[height<=?2160]/best"))
        // A height the TV doesn't offer falls back to 1080p.
        assertEquals(1080, YouTubeSettings(maxVideoHeight = 999).videoHeight)
    }

    @Test
    fun `captions in the subtitle languages are loaded but start off, as on TV`() {
        val player = PlayerSettings(subtitleLanguage = "de", secondarySubtitleLanguage = "en")
        val options = YouTubePlayback.mpvOptions(player, YouTubeSettings(), Locale.US)
        val raw = options.single { it.startsWith("--ytdl-raw-options=") }
        assertEquals("--ytdl-raw-options=write-subs=,write-auto-subs=,sub-langs=^(de|en)(-.*)?$", raw)
        // mpv splits its raw-options list on commas: the languages are one pattern, without any.
        assertFalse(raw.substringAfter("sub-langs=").contains(','))
        assertEquals("--sid=no", options.last())
        // The player settings still apply (hardware decoding and the rest).
        assertTrue(options.any { it.startsWith("--hwdec=") })
    }

    @Test
    fun `no captions when subtitles are off, and the system language without a preference`() {
        val off = YouTubePlayback.mpvOptions(PlayerSettings(subtitleMode = SubtitleMode.OFF), YouTubeSettings(), Locale.US)
        assertTrue(off.none { it.startsWith("--ytdl-raw-options") })
        assertEquals("^(fr)(-.*)?$", YouTubePlayback.captionLanguages(PlayerSettings(), Locale.FRANCE))
    }

    @Test
    fun `SponsorBlock modes feed the skip system`() {
        val segments = listOf(
            YouTubeSkipSegment(5_000, 12_000, "sponsor"),
            YouTubeSkipSegment(20_000, 25_000, "intro"),
            YouTubeSkipSegment(30_000, 40_000, "selfpromo"),
            YouTubeSkipSegment(50_000, 55_000, "filler"),
            YouTubeSkipSegment(60_000, 65_000, "unknown_new_category")
        )
        val settings = YouTubeSettings()
            .withMode(SponsorBlockCategory.INTRO, SponsorBlockMode.ASK)
            .withMode(SponsorBlockCategory.SELF_PROMO, SponsorBlockMode.SHOW)
        val skips = YouTubePlayback.skipSegments(segments, settings.activeModes)
        assertEquals(listOf("sponsor", "intro"), skips.map { it.label })
        assertEquals(listOf(true, false), skips.map { it.autoSkip })
        assertEquals(5.0, skips[0].startSec, 0.0)

        // Skip jumps by itself, once; Ask is left for the built-in player's Skip button.
        val planner = SkipPlanner(skips, emptySet())
        assertEquals("sponsor", planner.at(6.0)?.displayLabel)
        assertNull(planner.at(6.5))
        assertNull(planner.at(21.0))
        assertFalse(skips[1].skipsAutomatically(setOf(SegmentType.INTRO)))
    }

    @Test
    fun `only categories that aren't off are asked for, and none with SponsorBlock off`() {
        val defaults = YouTubeSettings()
        assertEquals(listOf("sponsor", "selfpromo", "interaction"), YouTubePlayback.categoriesToFetch(defaults.activeModes))
        assertTrue(YouTubePlayback.categoriesToFetch(defaults.copy(sponsorBlockEnabled = false).activeModes).isEmpty())
        // A mode set back to its default isn't stored.
        assertTrue(defaults.withMode(SponsorBlockCategory.SPONSOR, SponsorBlockMode.ASK).withMode(SponsorBlockCategory.SPONSOR, SponsorBlockMode.SKIP).sponsorBlockModes.isEmpty())
    }

    @Test
    fun `IntroDB segments still skip by type`() {
        val planner = SkipPlanner(listOf(com.fetch.desktop.skip.Segment(SegmentType.INTRO, 10.0, 40.0)), setOf(SegmentType.INTRO))
        assertEquals("intro", planner.at(11.0)?.displayLabel)
    }

    @Test
    fun `a video resumes from Fetch's own place, else YouTube's percentage`() {
        val saved = YouTubeResumePoint("v", positionMs = 754_300, durationMs = 1_200_000, updatedAtMs = 0)
        assertEquals("754.3", youTubeResumeStart(saved, watchedPercent = 10))
        assertEquals("42%", youTubeResumeStart(null, watchedPercent = 42))
        assertNull("barely started", youTubeResumeStart(null, watchedPercent = 2))
        assertNull("nearly finished", youTubeResumeStart(null, watchedPercent = 97))
        assertNull(youTubeResumeStart(saved.copy(positionMs = 5_000), watchedPercent = null))
    }

    @Test
    fun `resume points keep the newest first and drop finished videos`() {
        var points = updateResumePoints(emptyList(), "a", 60_000, 600_000, nowMs = 1)
        points = updateResumePoints(points, "b", 120_000, 600_000, nowMs = 2)
        assertEquals(listOf("b", "a"), points.map { it.videoId })
        points = updateResumePoints(points, "a", 590_000, 600_000, nowMs = 3)
        assertEquals("finished starts over", listOf("b"), points.map { it.videoId })
        assertEquals(2, updateResumePoints(points + YouTubeResumePoint("c", 60_000, 600_000, 0), "d", 60_000, 600_000, 4, max = 2).size)
    }

    @Test
    fun `recent searches are newest first, without repeats`() {
        val recent = addRecentSearch(addRecentSearch(listOf("cats"), "Dogs"), "dogs ")
        assertEquals(listOf("dogs", "cats"), recent)
        assertEquals(10, (1..15).fold(emptyList<String>()) { acc, i -> addRecentSearch(acc, "q$i") }.size)
        assertEquals(recent, addRecentSearch(recent, "   "))
    }

    @Test
    fun `the Home row needs the setting, a sign-in and a profile without limits`() {
        assertTrue(youTubeHomeRowAllowed(rowEnabled = true, signedIn = true, restrictedProfile = false))
        assertFalse(youTubeHomeRowAllowed(rowEnabled = false, signedIn = true, restrictedProfile = false))
        assertFalse(youTubeHomeRowAllowed(rowEnabled = true, signedIn = false, restrictedProfile = false))
        assertFalse(youTubeHomeRowAllowed(rowEnabled = true, signedIn = true, restrictedProfile = true))
    }

    @Test
    fun `the Home row takes each video once, through the feed filter`() {
        val page = YouTubePage(
            listOf(
                YouTubeShelf(null, listOf(video("a"), video("b", short = true), video("a"))),
                YouTubeShelf("More", (1..30).map { video("v$it") })
            ),
            continuation = null
        )
        val videos = youTubeHomeRowVideos(page, YouTubeFeedFilter(showShorts = false))
        assertEquals("a", videos.first().videoId)
        assertTrue(videos.none { it.isShort })
        assertEquals(YOUTUBE_HOME_ROW_MAX_VIDEOS, videos.size)
    }

    @Test
    fun `untitled pages continue the untitled row, titled ones are new rows`() {
        val first = listOf(YouTubeShelf(null, listOf(video("a"))))
        val more = appendPage(first, YouTubePage(listOf(YouTubeShelf(null, listOf(video("a"), video("b"))), YouTubeShelf("Mix", listOf(video("c")))), null))
        assertEquals(listOf("a", "b"), more[0].videos.map { it.videoId })
        assertEquals("Mix", more[1].title)
    }

    @Test
    fun `settings without a youtube block read as the TV's defaults`() {
        val dir = Files.createTempDirectory("yt-settings").toFile()
        File(dir, "settings.json").writeText("""{"theme":"FETCH","showContinueWatching":true}""")
        val loaded = com.fetch.desktop.data.settingsFile(dir).load()
        assertEquals(YouTubeSettings(), loaded.youtube)
        assertTrue(loaded.youtube.sponsorBlockEnabled && loaded.youtube.watchHistoryEnabled && loaded.youtube.autoplayEnabled)
        assertFalse(loaded.youtube.deArrowEnabled || loaded.youtube.returnDislikeEnabled || loaded.youtube.homeRowEnabled)

        val changed = DesktopSettings(youtube = YouTubeSettings(maxVideoHeight = 720).withMode(SponsorBlockCategory.FILLER, SponsorBlockMode.SKIP))
        com.fetch.desktop.data.settingsFile(dir).save(changed)
        assertEquals(changed.youtube, com.fetch.desktop.data.settingsFile(dir).load().youtube)
        assertEquals(SponsorBlockMode.SKIP, com.fetch.desktop.data.settingsFile(dir).load().youtube.mode(SponsorBlockCategory.FILLER))
    }

    @Test
    fun `the sign-in file is owner-only and survives a restart`() {
        val dir = Files.createTempDirectory("yt-auth").toFile()
        youTubeAuthFile(dir).save(YouTubeAuth(refreshToken = "RT", accountName = "Ada"))
        val file = File(dir, "youtube-auth.json")
        val perms = runCatching { Files.getPosixFilePermissions(file.toPath()).map { it.name }.toSet() }.getOrNull()
        if (perms != null) assertEquals(setOf("OWNER_READ", "OWNER_WRITE"), perms)
        val reloaded = youTubeAuthFile(dir).load()
        assertTrue(reloaded.isSignedIn)
        assertEquals("Ada", reloaded.accountName)
        // Searches and resume points live beside it.
        val data: JsonFile<YouTubeLocalData> = youTubeDataFile(dir)
        data.save(YouTubeLocalData(recentSearches = listOf("nasa")))
        assertEquals(listOf("nasa"), youTubeDataFile(dir).load().recentSearches)
    }

    private fun video(id: String, short: Boolean = false) = YouTubeVideo(videoId = id, title = id, thumbnailUrl = "https://i.ytimg.com/vi/$id/hq.jpg", isShort = short)
}
