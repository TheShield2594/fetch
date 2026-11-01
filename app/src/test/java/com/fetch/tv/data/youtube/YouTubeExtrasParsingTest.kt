package com.fetch.tv.data.youtube

import com.fetch.tv.data.local.YouTubeAuthDataStore
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Search suggestions, DeArrow branding and Return YouTube Dislike counts. */
class YouTubeExtrasParsingTest {

    private val service = YouTubeService(mockk<YouTubeAuthDataStore>(relaxed = true))

    @Test
    fun `suggestions are read from the suggest service's script`() {
        // As the service answers, trimmed.
        val body = """window.google.ac.h(["nasa art",[["nasa artemis",0,[512,433]],["nasa artemis lego",0,[512]],["nasa artemis",0,[512]]],{"k":1,"q":"x"}])"""
        assertEquals(listOf("nasa artemis", "nasa artemis lego"), service.parseSuggestions(body))
    }

    @Test
    fun `an unreadable suggest answer gives none`() {
        assertTrue(service.parseSuggestions("").isEmpty())
        assertTrue(service.parseSuggestions("<html>").isEmpty())
        assertTrue(service.parseSuggestions("""window.google.ac.h(["q"])""").isEmpty())
    }

    private val deArrow = DeArrowService()

    @Test
    fun `DeArrow's top trusted title and thumbnail are used`() {
        val root = Json.parseToJsonElement(
            """
            {"vid12345678": {
              "titles": [
                {"title": "Sunk title", "original": false, "votes": -1, "locked": false},
                {"title": ">How the rocket works", "original": false, "votes": 3, "locked": false},
                {"title": "Original", "original": true, "votes": 9, "locked": false}
              ],
              "thumbnails": [{"timestamp": 12.5, "original": false, "votes": 0, "locked": false}]
            },
             "other": {"titles": [{"title": "Not this one", "original": false, "votes": 5}]}}
            """
        )
        val branding = deArrow.parseBranding(root, "vid12345678")!!
        assertEquals("How the rocket works", branding.title)
        assertEquals("https://dearrow-thumb.ajay.app/api/v1/getThumbnail?videoID=vid12345678&time=12.5", branding.thumbnailUrl)
    }

    @Test
    fun `a locked title counts even when voted down, and originals never do`() {
        val root = Json.parseToJsonElement(
            """{"v": {"titles": [{"title": "Locked", "original": false, "votes": -2, "locked": true}],
                      "thumbnails": [{"timestamp": 1.0, "original": true, "votes": 4}]}}"""
        )
        val branding = deArrow.parseBranding(root, "v")!!
        assertEquals("Locked", branding.title)
        assertNull(branding.thumbnailUrl)
    }

    @Test
    fun `no branding for a video DeArrow has nothing on`() {
        val root = Json.parseToJsonElement("""{"v": {"titles": [], "thumbnails": []}}""")
        assertNull(deArrow.parseBranding(root, "v"))
        assertNull(deArrow.parseBranding(root, "missing"))
    }

    @Test
    fun `branding replaces only what it has`() {
        val video = YouTubeVideo(videoId = "v", title = "YouTube title", thumbnailUrl = "yt.jpg")
        assertEquals("New", video.withBranding(YouTubeBranding("New", null)).title)
        assertEquals("yt.jpg", video.withBranding(YouTubeBranding("New", null)).thumbnailUrl)
        assertEquals(video, video.withBranding(null))
    }

    @Test
    fun `vote counts are read from Return YouTube Dislike`() {
        val root = Json.parseToJsonElement(
            """{"id":"jNQXAC9IVRw","likes":20124579,"rawDislikes":3601,"dislikes":452247,"rating":4.9,"viewCount":447434663,"deleted":false}"""
        )
        assertEquals(YouTubeVoteCounts(likes = 20_124_579, dislikes = 452_247), ReturnYouTubeDislikeService().parseVotes(root))
        assertNull(ReturnYouTubeDislikeService().parseVotes(Json.parseToJsonElement("""{"likes": 1}""")))
    }

    @Test
    fun `DeArrow and SponsorBlock are asked by the same hash prefix`() {
        assertEquals(SponsorBlockService().hashPrefix("jNQXAC9IVRw"), SponsorBlockService.hashPrefixOf("jNQXAC9IVRw"))
        assertEquals(4, SponsorBlockService.hashPrefixOf("jNQXAC9IVRw").length)
    }

    @Test
    fun `a session resumed past three minutes reports from there`() {
        val info = YouTubePlayerInfo(trackingLinks = "https://s.youtube.com/a" to "https://s.youtube.com/b", captions = null)
        val session = service.newWatchSession("v", info, startPositionSec = 0f)!!
        session.startAt(600f)
        assertEquals(600f, session.lastPositionSec)
        session.startAt(60f)
        assertEquals(0f, session.lastPositionSec)
        session.recorded = true
        session.startAt(900f)
        assertEquals("ignored once reported", 0f, session.lastPositionSec)
    }
}
