package com.fetch.tv.ui.screens.youtube

import com.fetch.tv.data.youtube.YouTubePage
import com.fetch.tv.data.youtube.YouTubeShelf
import com.fetch.tv.data.youtube.YouTubeSkipSegment
import com.fetch.tv.data.youtube.YouTubeVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubePlaybackRulesTest {

    private fun video(id: String) = YouTubeVideo(videoId = id, title = id, thumbnailUrl = "")

    private val sponsor = YouTubeSkipSegment(startMs = 10_000, endMs = 40_000, category = "sponsor")

    @Test
    fun `a segment is skipped once, and not in its last half second`() {
        assertEquals(sponsor, segmentToSkip(listOf(sponsor), 10_000, emptySet()))
        assertEquals(sponsor, segmentToSkip(listOf(sponsor), 25_000, emptySet()))
        assertNull(segmentToSkip(listOf(sponsor), 25_000, setOf(sponsor)))
        assertNull(segmentToSkip(listOf(sponsor), 39_700, emptySet()))
        assertNull(segmentToSkip(listOf(sponsor), 9_999, emptySet()))
    }

    @Test
    fun `resume skips videos barely started or nearly done`() {
        assertEquals(300_000L, resumePositionMs(50, 600_000))
        assertNull(resumePositionMs(2, 600_000))
        assertNull(resumePositionMs(97, 600_000))
        assertNull(resumePositionMs(null, 600_000))
        assertNull(resumePositionMs(50, 0))
    }

    @Test
    fun `holding a seek button speeds it up`() {
        assertEquals(10_000L, seekStepMs(0))
        assertEquals(30_000L, seekStepMs(8))
        assertEquals(60_000L, seekStepMs(20))
    }

    @Test
    fun `a next page continues the untitled row and adds titled rows`() {
        val shelves = listOf(YouTubeShelf(null, listOf(video("a"), video("b"))))
        val page = YouTubePage(
            shelves = listOf(
                YouTubeShelf(null, listOf(video("b"), video("c"))),
                YouTubeShelf("New row", listOf(video("d")))
            ),
            continuation = "next"
        )

        val result = appendPage(shelves, page)

        assertEquals(listOf("a", "b", "c"), result[0].videos.map { it.videoId })
        assertEquals("New row", result[1].title)
    }

    @Test
    fun `untitled videos extend a feed whose only row has a title`() {
        val shelves = listOf(YouTubeShelf("Results", listOf(video("a"))))
        val result = appendPage(shelves, YouTubePage(listOf(YouTubeShelf(null, listOf(video("b")))), null))

        assertEquals(1, result.size)
        assertEquals(listOf("a", "b"), result[0].videos.map { it.videoId })
    }
}
