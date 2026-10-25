package com.fetch.tv.data.youtube

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SponsorBlockServiceTest {

    private val service = SponsorBlockService()

    @Test
    fun `the lookup sends only the start of the video id's hash`() {
        assertEquals("5f6b", service.hashPrefix("dQw4w9WgXcQ"))
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
            service.parseSegments(root, "mine")
        )
    }
}
