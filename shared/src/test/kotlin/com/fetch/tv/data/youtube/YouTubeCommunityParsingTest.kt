package com.fetch.tv.data.youtube

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** SponsorBlock segments, DeArrow branding and Return YouTube Dislike counts. */
class YouTubeCommunityParsingTest {

    @Test
    fun `the lookup sends only the start of the video id's hash`() {
        assertEquals("5f6b", SponsorBlockClient.hashPrefixOf("dQw4w9WgXcQ"))
        assertEquals(4, SponsorBlockClient.hashPrefixOf("jNQXAC9IVRw").length)
    }

    @Test
    fun `segments of the matching video are read, others ignored`() {
        val root = Json.parseToJsonElement(
            """
            [
              {"videoID": "other", "segments": [{"segment": [1.0, 2.0], "category": "sponsor", "actionType": "skip"}]},
              {"videoID": "mine", "segments": [
                {"segment": [30.5, 60.0], "category": "selfpromo", "actionType": "skip"},
                {"segment": [5.0, 12.25], "category": "sponsor", "actionType": "skip"},
                {"segment": [70.0, 80.0], "category": "sponsor", "actionType": "mute"},
                {"segment": [90.0, 90.0], "category": "sponsor", "actionType": "skip"}
              ]}
            ]
            """
        )

        assertEquals(
            listOf(
                YouTubeSkipSegment(5_000, 12_250, "sponsor"),
                YouTubeSkipSegment(30_500, 60_000, "selfpromo")
            ),
            SponsorBlockClient.parseSegments(root, "mine")
        )
    }

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
        val branding = DeArrowClient.parseBranding(root, "vid12345678")!!
        assertEquals("How the rocket works", branding.title)
        assertEquals("https://dearrow-thumb.ajay.app/api/v1/getThumbnail?videoID=vid12345678&time=12.5", branding.thumbnailUrl)
    }

    @Test
    fun `a locked title counts even when voted down, and originals never do`() {
        val root = Json.parseToJsonElement(
            """{"v": {"titles": [{"title": "Locked", "original": false, "votes": -2, "locked": true}],
                      "thumbnails": [{"timestamp": 1.0, "original": true, "votes": 4}]}}"""
        )
        val branding = DeArrowClient.parseBranding(root, "v")!!
        assertEquals("Locked", branding.title)
        assertNull(branding.thumbnailUrl)
    }

    @Test
    fun `no branding for a video DeArrow has nothing on`() {
        val root = Json.parseToJsonElement("""{"v": {"titles": [], "thumbnails": []}}""")
        assertNull(DeArrowClient.parseBranding(root, "v"))
        assertNull(DeArrowClient.parseBranding(root, "missing"))
    }

    @Test
    fun `vote counts are read from Return YouTube Dislike`() {
        val root = Json.parseToJsonElement(
            """{"id":"jNQXAC9IVRw","likes":20124579,"rawDislikes":3601,"dislikes":452247,"rating":4.9,"viewCount":447434663,"deleted":false}"""
        )
        assertEquals(YouTubeVoteCounts(likes = 20_124_579, dislikes = 452_247), ReturnYouTubeDislikeClient.parseVotes(root))
        assertNull(ReturnYouTubeDislikeClient.parseVotes(Json.parseToJsonElement("""{"likes": 1}""")))
    }
}
