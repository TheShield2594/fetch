package com.fetch.tv.ui.screens.calendar

import com.fetch.tv.data.remote.api.TmdbNextEpisode
import com.fetch.tv.domain.model.WatchProgress
import com.fetch.tv.domain.model.WatchedItem
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WatchHistoryCalendarTest {
    private val today = LocalDate.of(2026, 11, 4)

    private fun progress(id: String, type: String, lastWatched: Long, name: String = "Show", season: Int? = 1) = WatchProgress(
        contentId = id, contentType = type, name = name, poster = null, backdrop = null, logo = null,
        videoId = id, season = season, episode = 1, episodeTitle = null, position = 0, duration = 0, lastWatched = lastWatched
    )

    private fun watched(id: String, at: Long, title: String = "Show", poster: String? = null) =
        WatchedItem(contentId = id, contentType = "series", title = title, season = 1, episode = 2, watchedAt = at, poster = poster)

    @Test
    fun `series come once each, most recently watched first, movies left out`() {
        val series = startedSeries(
            progress = listOf(
                progress("tt1:1:3", "series", lastWatched = 100, name = "One"),
                progress("tt9", "movie", lastWatched = 500, season = null)
            ),
            watched = listOf(watched("tt2", at = 300, title = "Two"), watched("tt1", at = 50, title = "One", poster = "p1"))
        )

        assertEquals(listOf("tt2", "tt1"), series.map { it.id })
        assertEquals("p1", series.last().poster)
        assertEquals(100L, series.last().lastWatchedMs)
    }

    @Test
    fun `only the most recent series are looked up`() {
        val series = startedSeries(emptyList(), (1..40).map { watched("tt$it", at = it.toLong()) }, limit = 30)

        assertEquals(30, series.size)
        assertEquals("tt40", series.first().id)
    }

    @Test
    fun `episode ids reduce to their series, prefixed ids keep their prefix`() {
        assertEquals("tt123", seriesBaseId("tt123:2:5"))
        assertEquals("tmdb:42", seriesBaseId("tmdb:42:1:2"))
        assertEquals("tmdb:42", seriesBaseId("tmdb:42"))
    }

    private val show = StartedSeries("tt1", "Show", poster = null, lastWatchedMs = 0)

    private fun next(airDate: String?) = TmdbNextEpisode(seasonNumber = 2, episodeNumber = 3, name = "Next", airDate = airDate)

    @Test
    fun `a next episode inside the range becomes an entry with the TMDB poster as fallback`() {
        val entry = upcomingEntry(show, next("2026-11-10"), "Returning Series", "tmdb-poster", today, days = 30)

        assertNotNull(entry)
        assertEquals("2026-11-10", entry!!.airDate)
        assertEquals(2, entry.season)
        assertEquals("tmdb-poster", entry.poster)
    }

    @Test
    fun `an entry carries the still and backdrop it is given`() {
        val entry = upcomingEntry(
            show, next("2026-11-10"), null, null, today, days = 30,
            backdrop = "backdrop", still = "still"
        )

        assertEquals("still", entry!!.still)
        assertEquals("backdrop", entry.backdrop)
    }

    @Test
    fun `a watchlist film released in the window becomes a movie entry, watched from the history`() {
        val film = com.fetch.tv.domain.model.LibraryEntry(
            id = "tt7", type = "movie", name = "Film", poster = null, background = null, logo = null,
            description = null, releaseInfo = null, imdbRating = null, genres = emptyList(), addonBaseUrl = null
        )
        val watchedIds = watchedMovieIds(
            progress = emptyList(),
            watched = listOf(WatchedItem(contentId = "tt7", contentType = "movie", title = "Film", watchedAt = 1))
        )

        val past = movieEntry(film, "2026-11-01", null, null, null, today, pastDays = 7, days = 30, watchedIds = watchedIds)

        assertNotNull(past)
        assertEquals(true, past!!.isMovie)
        assertEquals(true, past.watched)
        assertEquals(false, movieEntry(film, "2026-11-20", null, null, null, today, 7, 30, emptySet())!!.watched)
        assertNull(movieEntry(film, "2026-10-01", null, null, null, today, 7, 30, watchedIds))
        assertNull(movieEntry(film, null, null, null, null, today, 7, 30, watchedIds))
    }

    @Test
    fun `the latest aired episode of the past week is listed, marked watched from the history`() {
        val keys = watchedEpisodeKeys(progress = emptyList(), watched = listOf(watched("tt1:1:2", at = 1)))
        val one = show.copy(id = "tt1")
        val last = TmdbNextEpisode(seasonNumber = 1, episodeNumber = 2, name = "Two", airDate = "2026-11-01")

        val entry = recentEntry(one, last, null, today, pastDays = 7, watchedKeys = keys)

        assertNotNull(entry)
        assertEquals(true, entry!!.watched)
        assertEquals(false, recentEntry(one, last.copy(episodeNumber = 3), null, today, 7, keys)!!.watched)
        assertNull(recentEntry(one, last.copy(airDate = "2026-10-20"), null, today, 7, keys))
        assertNull(recentEntry(one, last.copy(airDate = "2026-11-05"), null, today, 7, keys))
    }

    @Test
    fun `episodes outside the range, undated, or of ended shows are left out`() {
        assertNull(upcomingEntry(show, next("2026-12-20"), null, null, today, days = 14))
        assertNull(upcomingEntry(show, next("2026-11-01"), null, null, today, days = 30))
        assertNull(upcomingEntry(show, next(null), null, null, today, days = 30))
        assertNull(upcomingEntry(show, next("2026-11-10"), "Ended", null, today, days = 30))
        assertNull(upcomingEntry(show, null, null, null, today, days = 30))
    }

    @Test
    fun `today and the last day of the range both count`() {
        assertNotNull(upcomingEntry(show, next("2026-11-04"), null, null, today, days = 14))
        assertNotNull(upcomingEntry(show, next("2026-11-18"), null, null, today, days = 14))
    }
}
