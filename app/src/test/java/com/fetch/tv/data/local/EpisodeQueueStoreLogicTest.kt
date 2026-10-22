package com.fetch.tv.data.local

import com.fetch.tv.ui.screens.player.upcomingQueueItems
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = android.app.Application::class)
class EpisodeQueueStoreLogicTest {

    private fun item(videoId: String, contentId: String = "tt1", origin: QueueItemOrigin = QueueItemOrigin.MANUAL) =
        QueueItem(contentId = contentId, contentType = "series", videoId = videoId, name = "Show", origin = origin)

    @Test
    fun `adding appends and ignores a video already queued`() {
        val queue = queueWithAdded(listOf(item("a")), item("b"))

        assertEquals(listOf("a", "b"), queueWithAdded(queue, item("a")).map { it.videoId })
    }

    @Test
    fun `adding an autoplay pick by hand keeps its place and makes it manual`() {
        val queue = listOf(item("tt1:1:2", origin = QueueItemOrigin.AUTOPLAY), item("tt2", contentId = "tt2"))

        val result = queueWithAdded(queue, item("tt1:1:2"))

        assertEquals(listOf("tt1:1:2", "tt2"), result.map { it.videoId })
        assertEquals(QueueItemOrigin.MANUAL, result.first().origin)
    }

    @Test
    fun `autoplay seed goes first and replaces the older seed of the same series`() {
        val queue = listOf(
            item("tt1:1:2", origin = QueueItemOrigin.AUTOPLAY),
            item("tt2", contentId = "tt2")
        )

        val result = queueWithAutoplaySeed(queue, item("tt1:1:3", origin = QueueItemOrigin.AUTOPLAY))

        assertEquals(listOf("tt1:1:3", "tt2"), result.map { it.videoId })
    }

    @Test
    fun `autoplay seed leaves an episode the person queued alone`() {
        val queue = listOf(item("tt1:1:2"))

        val result = queueWithAutoplaySeed(queue, item("tt1:1:3", origin = QueueItemOrigin.AUTOPLAY))

        assertEquals(listOf("tt1:1:3", "tt1:1:2"), result.map { it.videoId })
    }

    @Test
    fun `moving swaps with the neighbour and stops at the ends`() {
        val queue = listOf(item("a"), item("b"), item("c"))

        assertEquals(listOf("b", "a", "c"), queueWithMoved(queue, "b", up = true).map { it.videoId })
        assertEquals(queue, queueWithMoved(queue, "a", up = true))
        assertEquals(queue, queueWithMoved(queue, "c", up = false))
    }

    @Test
    fun `queue survives an encode and decode`() {
        val queue = listOf(
            item("tt1:2:6").copy(season = 2, episode = 6, episodeTitle = "Six", poster = "p", addedAtMs = 5L),
            QueueItem(contentId = "tt9", contentType = "movie", videoId = "tt9", name = "Film", origin = QueueItemOrigin.AUTOPLAY)
        )

        assertEquals(queue, decodeQueueItems(encodeQueueItems(queue)))
    }

    @Test
    fun `broken data reads as an empty queue`() {
        assertEquals(emptyList<QueueItem>(), decodeQueueItems("{"))
    }

    @Test
    fun `the playing video is not its own up next`() {
        val queue = listOf(item("a"), item("b"))

        assertEquals(listOf("b"), upcomingQueueItems(queue, currentVideoId = "a").map { it.videoId })
    }
}
