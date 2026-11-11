package com.fetch.tv.data.youtube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubePlaylistAndChaptersTest {

    private fun load(name: String): JsonElement = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/youtube/$name.json")!!.bufferedReader().use { it.readText() }
    )

    @Test
    fun `a playlist page's videos carry the playlist they play from`() {
        val videos = YouTubeResponseParser.parsePage(load("playlist")).shelves.flatMap { it.videos }
        assertTrue(videos.isNotEmpty())
        assertTrue(videos.all { it.playlistId?.startsWith("PL") == true })
    }

    @Test
    fun `played from a playlist, Up Next is the playlist's next video`() {
        // Saved by scripts/youtube_fixtures.py: NASA's "Artemis III" playlist, first video playing.
        val next = YouTubeResponseParser.parseWatchNext(load("next_playlist"), currentVideoId = "yMoGiIeDH9s")
        val playlist = next.playlist!!
        assertEquals("PL2aBZuCeDwlRaF6AIey7ulmW1X0SHI1mQ", playlist.playlistId)
        assertEquals("Artemis III", playlist.title)
        assertEquals(0, playlist.currentIndex)
        assertEquals(9, playlist.totalVideos)
        val pick = next.next!!
        assertEquals("SGVxZ4idYvc", pick.videoId)
        // The pick keeps its place in the playlist, so the video after it continues the list too.
        assertEquals("PL2aBZuCeDwlRaF6AIey7ulmW1X0SHI1mQ", pick.playlistId)
        assertEquals(1, pick.playlistIndex)
        assertTrue("title from the related tile", pick.title.isNotBlank())
    }

    @Test
    fun `a watch page asked without a playlist has none`() {
        assertNull(YouTubeResponseParser.parseWatchNext(load("next"), currentVideoId = "none").playlist)
    }

    @Test
    fun `chapters come from the uploader's markers, in order`() {
        // The shape of the web watch page's player bar, trimmed from a real response.
        val root = Json.parseToJsonElement(
            """
            {"playerOverlays": {"playerOverlayRenderer": {"decoratedPlayerBarRenderer": {"decoratedPlayerBarRenderer": {
              "playerBar": {"multiMarkersPlayerBarRenderer": {"markersMap": [
                {"key": "DESCRIPTION_CHAPTERS", "value": {"chapters": [
                  {"chapterRenderer": {"title": {"simpleText": "Introduction"}, "timeRangeStartMillis": 0}},
                  {"chapterRenderer": {"title": {"simpleText": "RPS - Calling Functions"}, "timeRangeStartMillis": 547000}},
                  {"chapterRenderer": {"title": {"simpleText": "RPS - Variables and Functions"}, "timeRangeStartMillis": 191000}}
                ]}},
                {"key": "QUIZ_MARKERS", "value": {"markers": [{"markerRenderer": {"timeRangeStartMillis": 3666422}}]}}
              ]}}
            }}}}}
            """
        )
        assertEquals(
            listOf(
                YouTubeChapter(0, "Introduction"),
                YouTubeChapter(191_000, "RPS - Variables and Functions"),
                YouTubeChapter(547_000, "RPS - Calling Functions")
            ),
            YouTubeResponseParser.parseChapters(root)
        )
    }

    @Test
    fun `the uploader's chapters win over automatic ones, and one chapter is none`() {
        fun markers(key: String, vararg starts: Long) = """{"key": "$key", "value": {"chapters": [${
            starts.joinToString(",") { """{"chapterRenderer": {"title": {"simpleText": "$key $it"}, "timeRangeStartMillis": $it}}""" }
        }]}}"""
        val both = Json.parseToJsonElement("""{"markersMap": [${markers("AUTO_CHAPTERS", 0, 5)}, ${markers("DESCRIPTION_CHAPTERS", 0, 9)}]}""")
        assertEquals(listOf("DESCRIPTION_CHAPTERS 0", "DESCRIPTION_CHAPTERS 9"), YouTubeResponseParser.parseChapters(both).map { it.title })
        val auto = Json.parseToJsonElement("""{"markersMap": [${markers("AUTO_CHAPTERS", 0, 5)}]}""")
        assertEquals(2, YouTubeResponseParser.parseChapters(auto).size)
        // A later entry for the uploader's chapters without a list doesn't hide the first one.
        val withEmpty = Json.parseToJsonElement(
            """{"markersMap": [${markers("DESCRIPTION_CHAPTERS", 0, 9)}, ${markers("AUTO_CHAPTERS", 0, 5)}, {"key": "DESCRIPTION_CHAPTERS", "value": {}}]}"""
        )
        assertEquals(listOf(0L, 9L), YouTubeResponseParser.parseChapters(withEmpty).map { it.startMs })
        val single = Json.parseToJsonElement("""{"markersMap": [${markers("DESCRIPTION_CHAPTERS", 0)}]}""")
        assertTrue(YouTubeResponseParser.parseChapters(single).isEmpty())
    }

    @Test
    fun `the storyboard spec is read from the player response`() {
        val root = Json.parseToJsonElement("""{"storyboards": {"playerStoryboardSpecRenderer": {"spec": "https://i.ytimg.com/sb/x|a"}}}""")
        assertEquals("https://i.ytimg.com/sb/x|a", YouTubeResponseParser.parseStoryboardSpec(root))
        val live = Json.parseToJsonElement("""{"storyboards": {"playerLiveStoryboardSpecRenderer": {"spec": "x"}}}""")
        assertNull(YouTubeResponseParser.parseStoryboardSpec(live))
    }
}
