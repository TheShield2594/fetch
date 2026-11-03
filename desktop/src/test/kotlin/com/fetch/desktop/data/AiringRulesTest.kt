package com.fetch.desktop.data

import com.fetch.tv.domain.model.ContinueWatchingSortMode
import com.fetch.tv.domain.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AiringRulesTest {

    private val today = LocalDate.of(2026, 11, 3)

    private fun ep(season: Int, episode: Int, released: String? = "2026-01-01") =
        Video(id = "tt1:$season:$episode", title = "E$episode", released = released, thumbnail = null, season = season, episode = episode, overview = null)

    private fun show(episodes: List<Video>, video: Video? = null) = PlaybackTarget("tt1", "series", "Show", null, null, null, video, episodes)

    private fun finish(file: ProgressFile, episodes: List<Video>, video: Video, fromFurthest: Boolean = true, now: Long = 1) =
        WatchHistory.record(file, show(episodes, video), 2_900_000, 3_000_000, now, fromFurthest)

    @Test
    fun `finishing the last listed episode makes the show caught up, and a new episode brings it back`() {
        val season1 = listOf(ep(1, 1), ep(1, 2))
        var file = finish(ProgressFile(), season1, ep(1, 1))
        file = finish(file, season1, ep(1, 2), now = 2)
        assertTrue(WatchHistory.continueWatching(file).isEmpty())
        assertEquals("tt1:1:2", file.caughtUp.single().lastVideoId)

        // The addon now lists season 2.
        file = WatchHistory.refreshShow(file, show(season1 + ep(2, 1, "2026-11-01")), now = 99)
        val back = WatchHistory.continueWatching(file).single()
        assertTrue(back.upNext)
        assertEquals("tt1:2:1", back.videoId)
        assertEquals("2026-11-01", back.airDate)
        assertEquals("keeps its place by when it was finished", 2L, back.lastWatched)
        assertTrue(file.caughtUp.isEmpty())
    }

    @Test
    fun `an unaired next episode waits as upcoming`() {
        val episodes = listOf(ep(1, 1), ep(1, 2, "2026-11-14T00:00:00.000Z"))
        val file = finish(ProgressFile(), episodes, ep(1, 1))
        val entry = WatchHistory.continueWatching(file).single()
        assertTrue(WatchHistory.isUpcoming(entry, today))
        assertFalse("aired once the day comes", WatchHistory.isUpcoming(entry, LocalDate.of(2026, 11, 14)))
    }

    @Test
    fun `rows follow the sort order and the unaired setting`() {
        val aired = ProgressEntry("tt2", "movie", "Movie", videoId = "tt2", positionMs = 10, durationMs = 100, lastWatched = 1)
        val soon = ProgressEntry("tt3", "series", "Soon", videoId = "tt3:1:1", positionMs = 0, durationMs = 0, lastWatched = 5, upNext = true, airDate = "2026-11-10")
        val later = ProgressEntry("tt4", "series", "Later", videoId = "tt4:1:1", positionMs = 0, durationMs = 0, lastWatched = 9, upNext = true, airDate = "2026-12-01")
        val file = ProgressFile(entries = listOf(aired, soon, later))
        fun rows(show: Boolean, sort: ContinueWatchingSortMode) =
            WatchHistory.continueWatchingRows(file, today, show, sort).let { (a, b) -> a.map { it.name } to b.map { it.name } }
        assertEquals(listOf("Later", "Soon", "Movie") to emptyList<String>(), rows(true, ContinueWatchingSortMode.DEFAULT))
        assertEquals(listOf("Movie", "Soon", "Later") to emptyList<String>(), rows(true, ContinueWatchingSortMode.STREAMING_STYLE))
        assertEquals(listOf("Movie") to listOf("Soon", "Later"), rows(true, ContinueWatchingSortMode.SPLIT_UPCOMING))
        assertEquals(listOf("Movie") to emptyList<String>(), rows(false, ContinueWatchingSortMode.SPLIT_UPCOMING))
    }

    @Test
    fun `Next Up follows the furthest episode, or the latest for rewatches`() {
        val episodes = listOf(ep(1, 1), ep(1, 2), ep(1, 3), ep(1, 4))
        val watched = ProgressFile(watched = listOf("tt1:1:1", "tt1:1:2", "tt1:1:3"))
        // Rewatching episode 1 from the start.
        assertEquals("tt1:1:4", WatchHistory.continueWatching(finish(watched, episodes, ep(1, 1))).single().videoId)
        assertEquals("tt1:1:2", WatchHistory.continueWatching(finish(watched.copy(watched = listOf("tt1:1:1")), episodes, ep(1, 1), fromFurthest = false)).single().videoId)
    }

    @Test
    fun `a show part-way through an episode isn't touched, and unknown shows are ignored`() {
        val episodes = listOf(ep(1, 1), ep(1, 2))
        val inProgress = WatchHistory.record(ProgressFile(), show(episodes, ep(1, 1)), 600_000, 3_000_000, 1)
        assertEquals(inProgress, WatchHistory.refreshShow(inProgress, show(episodes + ep(1, 3)), 5))
        assertEquals(ProgressFile(), WatchHistory.refreshShow(ProgressFile(), show(episodes), 5))
    }

    @Test
    fun `marking the last episode watched by hand also makes the show caught up`() {
        val episodes = listOf(ep(1, 1), ep(1, 2))
        var file = finish(ProgressFile(), episodes, ep(1, 1))
        file = WatchHistory.setWatched(file, show(episodes), listOf("tt1:1:2"), watched = true, now = 3)
        assertTrue(WatchHistory.continueWatching(file).isEmpty())
        assertEquals("tt1", file.caughtUp.single().contentId)
        assertNull(file.entries.firstOrNull { it.contentId == "tt1" })
    }
}
