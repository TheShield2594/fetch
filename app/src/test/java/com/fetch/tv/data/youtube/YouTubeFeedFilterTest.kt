package com.fetch.tv.data.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeFeedFilterTest {

    private fun video(id: String, short: Boolean = false, live: Boolean = false, watched: Int? = null) =
        YouTubeVideo(id, id, thumbnailUrl = "", isShort = short, isLive = live, watchedPercent = watched)

    private val videos = listOf(
        video("plain"),
        video("short", short = true),
        video("live", live = true),
        video("half", watched = 50),
        video("done", watched = 95)
    )

    private fun shown(filter: YouTubeFeedFilter, feed: YouTubeFeed = YouTubeFeed.HOME) =
        filter.apply(YouTubePage(listOf(YouTubeShelf(null, videos)), "NEXT"), feed)
            .shelves.flatMap { it.videos }.map { it.videoId }

    @Test
    fun `by default everything shows`() {
        assertEquals(videos.map { it.videoId }, shown(YouTubeFeedFilter()))
    }

    @Test
    fun `each choice leaves out its own videos`() {
        assertEquals(listOf("plain", "live", "half", "done"), shown(YouTubeFeedFilter(showShorts = false)))
        assertEquals(listOf("plain", "short", "half", "done"), shown(YouTubeFeedFilter(showLive = false)))
        assertEquals(listOf("plain", "short", "live", "half"), shown(YouTubeFeedFilter(hideWatched = true)))
    }

    @Test
    fun `history keeps watched videos`() {
        assertEquals(videos.map { it.videoId }, shown(YouTubeFeedFilter(hideWatched = true), YouTubeFeed.HISTORY))
    }

    @Test
    fun `hiding shorts drops shorts rows and rows it empties, and keeps the page token`() {
        val page = YouTubePage(
            listOf(
                YouTubeShelf("Shorts", listOf(video("a")), continuation = "ROW", isShorts = true),
                YouTubeShelf("Only shorts", listOf(video("b", short = true))),
                YouTubeShelf("Videos", listOf(video("c"), video("d", short = true)))
            ),
            "NEXT"
        )

        val result = YouTubeFeedFilter(showShorts = false).apply(page, YouTubeFeed.HOME)

        assertEquals(listOf("Videos"), result.shelves.map { it.title })
        assertEquals(listOf("c"), result.shelves.single().videos.map { it.videoId })
        assertEquals("NEXT", result.continuation)
    }

    @Test
    fun `up next skips a hidden pick for the first related video shown`() {
        val watchNext = YouTubeWatchNext(next = video("short", short = true), related = listOf(video("short", short = true), video("plain")))

        val result = YouTubeFeedFilter(showShorts = false).apply(watchNext)

        assertEquals("plain", result.next?.videoId)
        assertEquals(listOf("plain"), result.related.map { it.videoId })
        assertNull(YouTubeFeedFilter(showShorts = false).apply(YouTubeWatchNext(video("s", short = true), emptyList())).next)
    }
}
