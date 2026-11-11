package com.fetch.desktop.youtube

import com.fetch.tv.data.youtube.YouTubeSkipSegment
import com.fetch.tv.data.youtube.YouTubeVoteCounts
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SponsorBlock, DeArrow and Return YouTube Dislike, against recorded answers. */
class YouTubeCommunityTest {

    private val segmentsAnswer = """
        [
          {"videoID": "other", "segments": [{"segment": [1.0, 2.0], "category": "sponsor", "actionType": "skip"}]},
          {"videoID": "dQw4w9WgXcQ", "segments": [
            {"segment": [30.5, 60.0], "category": "selfpromo", "actionType": "skip"},
            {"segment": [5.0, 12.25], "category": "sponsor", "actionType": "skip"},
            {"segment": [70.0, 80.0], "category": "sponsor", "actionType": "mute"},
            {"segment": [90.0, 90.0], "category": "sponsor", "actionType": "skip"}
          ]}
        ]
    """

    @Test
    fun `SponsorBlock is asked by the hash prefix and the chosen categories`() = runBlocking {
        val http = YouTubeFakeHttp { 200 to segmentsAnswer }
        val segments = SponsorBlockClient(http.client).segments("dQw4w9WgXcQ", listOf("sponsor", "selfpromo"))
        assertEquals(
            listOf(YouTubeSkipSegment(5_000, 12_250, "sponsor"), YouTubeSkipSegment(30_500, 60_000, "selfpromo")),
            segments
        )
        val url = http.sent.single().request.url
        assertEquals("/api/skipSegments/5f6b", url.encodedPath)
        assertEquals("""["sponsor","selfpromo"]""", url.queryParameter("categories"))
        assertEquals("""["skip"]""", url.queryParameter("actionTypes"))
    }

    @Test
    fun `no segments when SponsorBlock has none, fails, or nothing is wanted`() = runBlocking {
        assertTrue(SponsorBlockClient(YouTubeFakeHttp { 404 to "Not Found" }.client).segments("v", listOf("sponsor")).isEmpty())
        assertTrue(SponsorBlockClient(YouTubeFakeHttp { 500 to "" }.client).segments("v", listOf("sponsor")).isEmpty())
        val idle = YouTubeFakeHttp { 200 to segmentsAnswer }
        assertTrue(SponsorBlockClient(idle.client).segments("v", emptyList()).isEmpty())
        assertTrue("nothing asked", idle.sent.isEmpty())
    }

    @Test
    fun `DeArrow's top trusted title and thumbnail are used, and cached`() = runBlocking {
        val answer = """
            {"vid12345678": {
              "titles": [
                {"title": "Sunk title", "original": false, "votes": -1, "locked": false},
                {"title": ">How the rocket works", "original": false, "votes": 3, "locked": false},
                {"title": "Original", "original": true, "votes": 9, "locked": false}
              ],
              "thumbnails": [{"timestamp": 12.5, "original": false, "votes": 0, "locked": false}]
            }}
        """
        val http = YouTubeFakeHttp { req -> if (req.path.endsWith("/" + SponsorBlockClient.hashPrefixOf("vid12345678"))) 200 to answer else 404 to "" }
        val deArrow = DeArrowClient(http.client)
        val branding = deArrow.branding(listOf("vid12345678", "nothing0000"))
        assertEquals(setOf("vid12345678"), branding.keys)
        assertEquals("How the rocket works", branding["vid12345678"]?.title)
        assertEquals("https://dearrow-thumb.ajay.app/api/v1/getThumbnail?videoID=vid12345678&time=12.5", branding["vid12345678"]?.thumbnailUrl)
        val asked = http.sent.size
        deArrow.branding(listOf("vid12345678", "nothing0000"))
        assertEquals("answers, empty ones too, are cached", asked, http.sent.size)
    }

    @Test
    fun `a locked DeArrow title counts even when voted down, and originals never do`() {
        val root = Json.parseToJsonElement(
            """{"v": {"titles": [{"title": "Locked", "original": false, "votes": -2, "locked": true}],
                      "thumbnails": [{"timestamp": 1.0, "original": true, "votes": 4}]}}"""
        )
        val branding = DeArrowClient(YouTubeFakeHttp { 404 to "" }.client).parseBranding(root, "v")!!
        assertEquals("Locked", branding.title)
        assertNull(branding.thumbnailUrl)
    }

    @Test
    fun `vote counts come from Return YouTube Dislike`() = runBlocking {
        val http = YouTubeFakeHttp {
            200 to """{"id":"jNQXAC9IVRw","likes":20124579,"rawDislikes":3601,"dislikes":452247,"rating":4.9,"viewCount":447434663,"deleted":false}"""
        }
        assertEquals(YouTubeVoteCounts(20_124_579, 452_247), ReturnYouTubeDislikeClient(http.client).votes("jNQXAC9IVRw"))
        assertEquals("jNQXAC9IVRw", http.sent.single().request.url.queryParameter("videoId"))
        assertNull(ReturnYouTubeDislikeClient(YouTubeFakeHttp { 500 to "" }.client).votes("x"))
        assertEquals("20.1M likes · 452K dislikes",YouTubePlayback.votesLine(YouTubeVoteCounts(20_124_579, 452_247)))
    }
}
