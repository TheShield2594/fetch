package com.fetch.desktop.youtube

import com.fetch.desktop.data.PlayerSettings
import com.fetch.desktop.data.SubtitleMode
import com.fetch.desktop.player.MpvProgress
import com.fetch.tv.data.youtube.SponsorBlockCategory
import com.fetch.tv.data.youtube.SponsorBlockMode
import com.fetch.tv.data.youtube.YouTubePlaylistContext
import com.fetch.tv.data.youtube.YouTubeSkipSegment
import com.fetch.tv.data.youtube.YouTubeStoryboard
import com.fetch.tv.data.youtube.YouTubeVideo
import com.fetch.tv.data.youtube.YouTubeWatchNext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** The built-in player's YouTube parts: storyboards, captions, chapters, marks, the end card, signed-in playback. */
class YouTubePlayerExtrasTest {

    // --- Storyboards (the TV's YouTubeStoryboard) ---

    private val spec = "https://i.ytimg.com/sb/vid/storyboard3_L\$L/\$N.jpg?sqp=abc|" +
        "48#27#100#10#10#0#default#rs\$A|" +
        "80#45#100#10#10#2000#M\$M#sigh1|" +
        "160#90#100#5#5#2000#M\$M#sigh2"

    @Test
    fun `the storyboard is the level with the widest frames`() {
        val board = YouTubeStoryboard.parse(spec)!!
        assertEquals(160, board.frameWidth)
        assertEquals(5, board.columns)
        // Frame 0 is the first sheet's top-left; the URL names the level and the sheet.
        val first = board.frameAt(0, 200_000)!!
        assertEquals("https://i.ytimg.com/sb/vid/storyboard3_L2/M0.jpg?sqp=abc&sigh=sigh2", first.sheetUrl)
        assertEquals(0 to 0, first.x to first.y)
        // 2 s a frame, 25 frames a sheet: 62 s is frame 31, the second sheet's seventh (row 1, column 1).
        val later = board.frameAt(62_000, 200_000)!!
        assertTrue(later.sheetUrl.contains("/M1.jpg"))
        assertEquals(160 to 90, later.x to later.y)
        // Past the end stays on the last frame.
        assertTrue(board.frameAt(10_000_000, 200_000)!!.sheetUrl.contains("/M3.jpg"))
    }

    @Test
    fun `an unreadable or missing storyboard shows nothing`() {
        assertNull(YouTubeStoryboard.parse(null))
        assertNull(YouTubeStoryboard.parse("not a spec"))
        assertNull(YouTubeStoryboard.parse("https://x/\$L/\$N.jpg|48#27#100#10#10#0#default#x"))
    }

    @Test
    fun `the player info carries captions and the storyboard, from the web player when the TV leaves it out`() = runBlocking {
        val auth = InMemoryYouTubeAuthStore(YouTubeAuth(accessToken = "AT", refreshToken = "RT", accessTokenExpiresAt = Long.MAX_VALUE / 2))
        val http = YouTubeFakeHttp { req ->
            when {
                req.path == "/tv" -> 404 to ""
                req.path == "/youtubei/v1/player" && req.request.header("Authorization") != null ->
                    200 to """{"captions":$CAPTIONS,"playbackTracking":{}}"""
                req.path == "/youtubei/v1/player" -> 200 to """{"storyboards":{"playerStoryboardSpecRenderer":{"spec":"$spec"}}}"""
                else -> 404 to ""
            }
        }
        val info = YouTubeClient(http.client, auth).loadPlayerInfo("vid")!!
        assertEquals(spec, info.storyboardSpec)
        assertEquals(2, YouTubeCaptions.tracks(listOf(info.captions), emptyList()).size)
        // The fallback is asked signed out, as the web client.
        val web = http.innertube("player").last()
        assertNull(web.request.header("Authorization"))
        assertEquals("WEB", Json.parseToJsonElement(web.body).jsonObject["context"]!!.jsonObject["client"]!!.jsonObject["clientName"]!!.jsonPrimitive.content)
    }

    // --- Captions (the TV's YouTubeCaptions) ---

    @Test
    fun `caption tracks are labelled and ordered as on TV, with translations into the viewer's languages`() {
        val tracks = YouTubeCaptions.tracks(listOf(Json.parseToJsonElement(CAPTIONS)), translateInto = listOf("de", "en"))
        assertEquals(listOf("English", "English (auto-generated)", "Deutsch (auto-translated)"), YouTubeCaptions.menuOrder(tracks).map { it.label })
        val german = tracks.single { it.isTranslated }
        // Translated from the automatic track, which is in the spoken language.
        assertTrue(german.urls.single().contains("kind=asr") && german.urls.single().endsWith("&tlang=de"))
        // mpv reads them as WebVTT.
        assertTrue(german.webVttUrl!!.contains("fmt=vtt") && german.webVttUrl!!.contains("tlang=de"))
        // People's captions first, then automatic ones, then a translation.
        assertEquals("English", YouTubeCaptions.pickDefault(tracks, listOf("en"))!!.label)
        assertEquals("Deutsch (auto-translated)", YouTubeCaptions.pickDefault(tracks, listOf("de", "en"))!!.label)
        assertNull(YouTubeCaptions.pickDefault(tracks, listOf("fr")))
        assertTrue(languageMatches("en-GB", "en"))
        assertFalse(languageMatches("en", "en-GB"))
    }

    @Test
    fun `captions left on start again in their language, in mpv's window through yt-dlp`() {
        val on = YouTubePlayback.mpvOptions(PlayerSettings(subtitleLanguage = "en"), YouTubeSettings(), Locale.US, YouTubeCaptionPreference(true, "de"))
        assertTrue(on.contains("--slang=de,en"))
        assertFalse(on.contains("--sid=no"))
        assertTrue(on.single { it.startsWith("--ytdl-raw-options=") }.contains("sub-langs=^(de|en)(-.*)?$"))
        // Turned off last time: loaded, but off.
        val off = YouTubePlayback.mpvOptions(PlayerSettings(subtitleLanguage = "en"), YouTubeSettings(), Locale.US, YouTubeCaptionPreference(false, "de"))
        assertEquals("--sid=no", off.last())
        // Subtitles off in the player settings wins.
        val none = YouTubePlayback.mpvOptions(PlayerSettings(subtitleMode = SubtitleMode.OFF), YouTubeSettings(), Locale.US, YouTubeCaptionPreference(true, "de"))
        assertTrue(none.none { it.startsWith("--slang=de") || it.startsWith("--ytdl-raw-options") })
    }

    // --- Signed-in playback ---

    @Test
    fun `playing signed in borrows a browser's sign-in through yt-dlp, in the same raw options`() {
        val options = YouTubePlayback.mpvOptions(PlayerSettings(subtitleLanguage = "en"), YouTubeSettings(playbackCookiesBrowser = "firefox"), Locale.US)
        // One --ytdl-raw-options: a second would replace the first in mpv.
        val raw = options.filter { it.startsWith("--ytdl-raw-options") }
        assertEquals(listOf("--ytdl-raw-options=write-subs=,write-auto-subs=,sub-langs=^(en)(-.*)?$,cookies-from-browser=firefox"), raw)
        val noSubs = YouTubePlayback.mpvOptions(PlayerSettings(subtitleMode = SubtitleMode.OFF), YouTubeSettings(playbackCookiesBrowser = "chrome"), Locale.US)
        assertTrue(noSubs.contains("--ytdl-raw-options=cookies-from-browser=chrome"))
        // Anything yt-dlp doesn't know, and the default, stay signed out.
        assertNull(YouTubePlayback.cookiesBrowser(YouTubeSettings(playbackCookiesBrowser = "netscape,--exec=x")))
        assertTrue(YouTubePlayback.mpvOptions(PlayerSettings(), YouTubeSettings(), Locale.US).none { it.contains("cookies") })
    }

    // --- SponsorBlock marks ---

    @Test
    fun `the seek bar marks every category that isn't off, Show included`() {
        val segments = listOf(
            YouTubeSkipSegment(30_000, 40_000, "selfpromo"),
            YouTubeSkipSegment(5_000, 12_000, "sponsor"),
            YouTubeSkipSegment(20_000, 25_000, "intro"),
            YouTubeSkipSegment(50_000, 50_000, "sponsor"),
            YouTubeSkipSegment(60_000, 65_000, "unknown")
        )
        val modes = YouTubeSettings().withMode(SponsorBlockCategory.SELF_PROMO, SponsorBlockMode.SHOW).activeModes
        // Intro is off by default; the empty and unknown ones are left out.
        assertEquals(listOf("sponsor", "selfpromo"), YouTubePlayback.seekBarMarks(segments, modes).map { it.category })
        // Show plays through: it isn't a skip.
        assertTrue(YouTubePlayback.skipSegments(segments, modes).none { it.label == "self-promotion" })
    }

    // --- Chapters ---

    @Test
    fun `mpv's chapter list becomes the chapter menu`() {
        val chapters = YouTubeChapters.fromMpv("""[{"title":"Intro","time":0.0},{"title":"Main","time":61.5},{"title":"End","time":300}]""")
        assertEquals(listOf(0L, 61_500L, 300_000L), chapters.map { it.startMs })
        assertEquals("Main", YouTubeChapters.at(chapters, 100_000)?.title)
        assertEquals("Intro", YouTubeChapters.at(chapters, 0)?.title)
        // One chapter, or none, isn't a list.
        assertTrue(YouTubeChapters.fromMpv("""[{"title":"Only","time":0}]""").isEmpty())
        assertTrue(YouTubeChapters.fromMpv(null).isEmpty())
        assertTrue(YouTubeChapters.fromMpv("garbage").isEmpty())
    }

    // --- The end card ---

    private fun video(id: String, playlist: String? = null) = YouTubeVideo(id, "Video $id", thumbnailUrl = "", playlistId = playlist)

    @Test
    fun `the end card counts ten seconds to YouTube's pick, as on TV`() {
        val next = YouTubeWatchNext(video("n"), listOf(video("n"), video("r1"), video("r2")))
        var prompt = YouTubeUpNext.prompt(next, autoplay = true, builtIn = true)!!
        assertEquals(10, prompt.secondsLeft)
        assertEquals(listOf("r1", "r2"), prompt.related.map { it.videoId })
        assertEquals("Playing in 10 seconds", prompt.status)
        repeat(9) { prompt = prompt.tick() }
        assertFalse(prompt.due)
        assertEquals("Playing in 1 second", prompt.status)
        prompt = prompt.tick()
        assertTrue(prompt.due)
        // Cancel stops it: it waits for a pick.
        val stopped = YouTubeUpNext.prompt(next, autoplay = true, builtIn = false)!!.copy(counting = false)
        assertEquals(stopped, stopped.tick())
        assertEquals("Select a video to play", stopped.status)
    }

    @Test
    fun `without autoplay the card waits, and with nothing to offer there's no card`() {
        val prompt = YouTubeUpNext.prompt(YouTubeWatchNext(video("n"), emptyList()), autoplay = false, builtIn = false)!!
        assertFalse(prompt.counting)
        repeat(20) { assertFalse(prompt.tick().due) }
        assertNull(YouTubeUpNext.prompt(YouTubeWatchNext(null, emptyList()), autoplay = true, builtIn = true))
        assertNull(YouTubeUpNext.prompt(null, autoplay = true, builtIn = true))
        // From a playlist, its next video says so.
        val inList = YouTubeWatchNext(video("n", "PL1"), emptyList(), playlist = YouTubePlaylistContext("PL1", "Mix", 2, 10))
        assertEquals("Next in Mix", YouTubeUpNext.prompt(inList, autoplay = true, builtIn = true)!!.heading)
    }

    // --- mpv's window ---

    @Test
    fun `vote counts reach mpv's window whether they come before or after it opens`() {
        val shown = mutableListOf<String>()
        val early = MpvOsdNote()
        early.show("1.2K likes · 34 dislikes")
        assertTrue(shown.isEmpty())
        early.attach { shown += it }
        assertEquals(listOf("1.2K likes · 34 dislikes"), shown)
        val late = MpvOsdNote()
        late.attach { shown += it }
        late.show("5 likes · 1 dislikes")
        assertEquals(2, shown.size)
        // The IPC line is valid JSON, quotes and all.
        val line = MpvProgress.showTextCommand("Say \"hi\" \\ there", 6000)
        val command = Json.parseToJsonElement(line).jsonObject["command"]!!.jsonArray
        assertEquals("Say \"hi\" \\ there", command[1].jsonPrimitive.content)
        assertTrue(line.endsWith("\n"))
    }

    private companion object {
        val CAPTIONS = """{"playerCaptionsTracklistRenderer":{
            "captionTracks":[
              {"baseUrl":"https://www.youtube.com/api/timedtext?v=vid&lang=en","name":{"simpleText":"English"},"languageCode":"en","isTranslatable":true},
              {"baseUrl":"/api/timedtext?v=vid&lang=en&kind=asr","name":{"simpleText":"English (auto-generated)"},"languageCode":"en","kind":"asr","isTranslatable":true}
            ],
            "translationLanguages":[{"languageCode":"de","languageName":{"simpleText":"Deutsch"}},{"languageCode":"fr","languageName":{"simpleText":"Français"}}]
        }}"""
    }
}
