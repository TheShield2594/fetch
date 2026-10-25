package com.fetch.tv.data.youtube

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeWatchNextTest {

    private fun tile(videoId: String, channel: String = "Channel") = """
        {"tileRenderer": {
          "contentType": "TILE_CONTENT_TYPE_VIDEO",
          "onSelectCommand": {"watchEndpoint": {"videoId": "$videoId"}},
          "metadata": {"tileMetadataRenderer": {
            "title": {"simpleText": "Video $videoId"},
            "lines": [{"lineRenderer": {"items": [{"lineItemRenderer": {"text": {"simpleText": "$channel"}}}]}}]
          }}
        }}
    """

    private fun response(autoplay: String) = Json.parseToJsonElement(
        """
        {"contents": {"singleColumnWatchNextResults": {
          "pivot": {"sectionListRenderer": {"contents": [{"shelfRenderer": {
            "content": {"horizontalListRenderer": {"items": [${tile("current")}, ${tile("rel1")}, ${tile("rel2")}]}}
          }}]}},
          "autoplay": {"autoplay": {"sets": [{$autoplay}]}}
        }}}
        """
    )

    @Test
    fun `the autoplay pick plays next, with the details from its related tile`() {
        val next = YouTubeResponseParser.parseWatchNext(
            response(
                """"autoplayVideoRenderer": {"autoplayEndpointRenderer": {
                    "item": {"previewButtonRenderer": {"title": {"simpleText": "Pick"}}},
                    "endpoint": {"watchEndpoint": {"videoId": "rel2"}}
                }}"""
            ),
            currentVideoId = "current"
        )

        assertEquals("rel2", next.next?.videoId)
        assertEquals("Video rel2", next.next?.title)
        assertEquals(listOf("rel1", "rel2"), next.related.map { it.videoId })
    }

    @Test
    fun `an autoplay pick outside the related videos is used as given`() {
        val next = YouTubeResponseParser.parseWatchNext(
            response(
                """"nextVideoRenderer": {"maybeHistoryEndpointRenderer": {
                    "item": {"previewButtonRenderer": {"title": {"simpleText": "Elsewhere"}, "byline": {"runs": [{"text": "Someone"}]}}},
                    "endpoint": {"watchEndpoint": {"videoId": "other"}}
                }}"""
            ),
            currentVideoId = "current"
        )

        assertEquals("other", next.next?.videoId)
        assertEquals("Elsewhere", next.next?.title)
        assertEquals("Someone", next.next?.channelName)
    }

    @Test
    fun `without an autoplay pick the first related video plays next`() {
        val next = YouTubeResponseParser.parseWatchNext(response(""), currentVideoId = "current")

        assertEquals("rel1", next.next?.videoId)
    }

    @Test
    fun `an empty response has nothing next`() {
        val next = YouTubeResponseParser.parseWatchNext(Json.parseToJsonElement("{}"), currentVideoId = "x")

        assertNull(next.next)
        assertEquals(emptyList<YouTubeVideo>(), next.related)
    }
}
