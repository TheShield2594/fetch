package com.fetch.desktop.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQueueTest {
    private fun item(video: String, content: String = "tt1", origin: QueueItemOrigin = QueueItemOrigin.MANUAL) =
        QueueItem(contentId = content, contentType = "series", videoId = video, name = "Show", season = 1, episode = video.last().digitToInt(), origin = origin)

    @Test
    fun `adding keeps one entry per video, and adding by hand keeps an autoplay pick but stops it being replaced`() {
        var q = PlaybackQueue.added(emptyList(), item("tt1:1:1"))
        q = PlaybackQueue.added(q, item("tt1:1:1"))
        assertEquals(1, q.size)
        val seeded = PlaybackQueue.seeded(emptyList(), item("tt1:1:2"))
        assertEquals(QueueItemOrigin.AUTOPLAY, seeded.single().origin)
        assertEquals(QueueItemOrigin.MANUAL, PlaybackQueue.added(seeded, item("tt1:1:2")).single().origin)
    }

    @Test
    fun `autoplay's seed goes first and replaces its older pick for the show`() {
        val q = listOf(item("tt9:1:1", content = "tt9"), item("tt1:1:2", origin = QueueItemOrigin.AUTOPLAY))
        val next = PlaybackQueue.seeded(q, item("tt1:1:3"))
        assertEquals(listOf("tt1:1:3", "tt9:1:1"), next.map { it.videoId })
    }

    @Test
    fun `moving, removing and undo`() {
        val q = listOf(item("a1"), item("b2"), item("c3"))
        assertEquals(listOf("b2", "a1", "c3"), PlaybackQueue.moved(q, "b2", up = true).map { it.videoId })
        assertEquals(q, PlaybackQueue.moved(q, "a1", up = true))
        val removed = PlaybackQueue.removed(q, "b2")
        assertEquals(q, PlaybackQueue.restored(removed, q[1], 1))
        assertEquals(listOf("a1", "c3"), PlaybackQueue.upcoming(q, "b2").map { it.videoId })
    }

    @Test
    fun `reads and writes the TV's JSON`() {
        val tv = """[{"contentId":"tt1","contentType":"series","videoId":"tt1:1:2","name":"Show","season":1,"episode":2,"poster":"p","origin":"AUTOPLAY","addedAtMs":5},
            {"contentId":"","videoId":"x"},{"contentId":"tt2","contentType":"movie","videoId":"tt2","name":"Film","origin":"WHAT"},
            {"contentId":"tt2","contentType":"movie","videoId":"tt2","name":"Again"}]"""
        val items = PlaybackQueue.decode(tv)
        assertEquals(listOf("tt1:1:2", "tt2"), items.map { it.videoId })
        assertEquals(QueueItemOrigin.AUTOPLAY, items[0].origin)
        assertEquals(QueueItemOrigin.MANUAL, items[1].origin)
        assertEquals(items, PlaybackQueue.decode(PlaybackQueue.encode(items)))
        val encoded = PlaybackQueue.encode(items)
        assertTrue("absent fields aren't written as null", !encoded.contains("null"))
        assertTrue(PlaybackQueue.decode("not json").isEmpty())
        assertEquals("S1 E2", items[0].subtitle)
    }
}
