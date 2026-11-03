package com.fetch.desktop.data

import com.fetch.tv.domain.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchHistoryTest {

    private fun ep(season: Int, episode: Int) =
        Video(id = "tt1:$season:$episode", title = "Ep $episode", released = null, thumbnail = null, season = season, episode = episode, overview = null)

    private val episodes = listOf(ep(0, 1), ep(1, 1), ep(1, 2), ep(2, 1))

    private fun show(video: Video) = PlaybackTarget("tt1", "series", "Show", null, null, null, video, episodes)
    private val movie = PlaybackTarget("tt9", "movie", "Movie", null, null, null, video = null)

    @Test
    fun `a title opened and closed straight away is not started`() {
        val file = WatchHistory.record(ProgressFile(), movie, positionMs = 10_000, durationMs = 6_000_000, now = 1)
        assertTrue(file.entries.isEmpty())
    }

    @Test
    fun `a movie in progress is in Continue Watching and resumes`() {
        val file = WatchHistory.record(ProgressFile(), movie, positionMs = 1_800_000, durationMs = 6_000_000, now = 1)
        assertEquals(listOf("tt9"), WatchHistory.continueWatching(file).map { it.contentId })
        assertEquals(1_800L, WatchHistory.resumeSeconds(file, "tt9"))
    }

    @Test
    fun `a finished movie leaves Continue Watching and counts as watched`() {
        var file = WatchHistory.record(ProgressFile(), movie, 1_800_000, 6_000_000, now = 1)
        file = WatchHistory.record(file, movie, 5_500_000, 6_000_000, now = 2)
        assertTrue(WatchHistory.continueWatching(file).isEmpty())
        assertTrue("tt9" in file.watched)
    }

    @Test
    fun `a finished episode becomes Next Up for the following one, across seasons`() {
        var file = WatchHistory.record(ProgressFile(), show(ep(1, 2)), 2_800_000, 3_000_000, now = 1)
        val next = WatchHistory.continueWatching(file).single()
        assertTrue(next.upNext)
        assertEquals("tt1:2:1", next.videoId)
        assertNull(WatchHistory.resumeSeconds(file, "tt1:2:1"))

        file = WatchHistory.record(file, show(ep(2, 1)), 2_900_000, 3_000_000, now = 2)
        assertTrue("the last episode leaves nothing next", WatchHistory.continueWatching(file).isEmpty())
    }

    @Test
    fun `one entry per show, the latest episode wins`() {
        var file = WatchHistory.record(ProgressFile(), show(ep(1, 1)), 600_000, 3_000_000, now = 1)
        file = WatchHistory.record(file, show(ep(1, 2)), 900_000, 3_000_000, now = 2)
        assertEquals(listOf("tt1:1:2"), file.entries.map { it.videoId })
    }

    @Test
    fun `removing hides the title until it is watched again`() {
        var file = WatchHistory.record(ProgressFile(), movie, 1_800_000, 6_000_000, now = 1)
        file = WatchHistory.hide(file, "tt9")
        assertTrue(WatchHistory.continueWatching(file).isEmpty())
        file = WatchHistory.record(file, movie, 1_900_000, 6_000_000, now = 2)
        assertEquals(1, WatchHistory.continueWatching(file).size)
    }

    @Test
    fun `specials never count as the next episode`() {
        assertNull(WatchHistory.nextEpisode(episodes, ep(0, 1)))
    }

    @Test
    fun `time left reads like the TV cards`() {
        val entry = WatchHistory.record(ProgressFile(), movie, 1_200_000, 5_100_000, now = 1).entries.single()
        assertEquals("1h 5m left", remainingLabel(entry))
    }

    @Test
    fun `marking the Continue Watching episode watched moves it on to Next Up`() {
        var file = WatchHistory.record(ProgressFile(), show(ep(1, 1)), 600_000, 3_000_000, now = 1)
        file = WatchHistory.setWatched(file, show(ep(1, 1)).copy(video = null), listOf("tt1:1:1"), watched = true, now = 2)
        val entry = WatchHistory.continueWatching(file).single()
        assertTrue(entry.upNext)
        assertEquals("tt1:1:2", entry.videoId)
        assertTrue("tt1:1:1" in file.watched)
    }

    @Test
    fun `marking a movie watched removes it from Continue Watching, unwatching keeps the rest`() {
        var file = WatchHistory.record(ProgressFile(), movie, 1_800_000, 6_000_000, now = 1)
        file = WatchHistory.setWatched(file, movie, listOf("tt9"), watched = true, now = 2)
        assertTrue(WatchHistory.continueWatching(file).isEmpty())
        file = WatchHistory.setWatched(file.copy(watched = file.watched + "other"), movie, listOf("tt9"), watched = false, now = 3)
        assertEquals(listOf("other"), file.watched)
    }

    @Test
    fun `marking an episode that isn't in Continue Watching leaves the entry alone`() {
        var file = WatchHistory.record(ProgressFile(), show(ep(1, 2)), 600_000, 3_000_000, now = 1)
        file = WatchHistory.setWatched(file, show(ep(1, 1)).copy(video = null), listOf("tt1:1:1"), watched = true, now = 2)
        assertEquals("tt1:1:2", WatchHistory.continueWatching(file).single().videoId)
    }

    @Test
    fun `episodes before skip specials and keep order`() {
        assertEquals(listOf("tt1:1:1", "tt1:1:2"), WatchHistory.episodesBefore(episodes, ep(2, 1)).map { it.id })
    }
}
