package com.fetch.tv.core.stats

import com.fetch.tv.data.remote.dto.trakt.TraktIdsDto
import com.fetch.tv.data.remote.dto.trakt.TraktMovieDto
import com.fetch.tv.data.remote.dto.trakt.TraktShowDto
import com.fetch.tv.data.remote.dto.trakt.TraktWatchedMovieItemDto
import com.fetch.tv.data.remote.dto.trakt.TraktWatchedShowEpisodeDto
import com.fetch.tv.data.remote.dto.trakt.TraktWatchedShowItemDto
import com.fetch.tv.data.remote.dto.trakt.TraktWatchedShowSeasonDto
import com.fetch.tv.domain.model.WatchedItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWatchStatsTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 4)

    private fun at(date: String): Long = LocalDate.parse(date).atTime(20, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun movie(id: String, date: String) = LocalWatch(id, WatchKind.MOVIE, at(date))
    private fun episode(id: String, date: String) = LocalWatch(id, WatchKind.EPISODE, at(date))

    @Test
    fun `streaks count consecutive days, and the current one only when it reaches today`() {
        val days = listOf("2026-10-01", "2026-10-02", "2026-10-03", "2026-11-03", "2026-11-04").map(LocalDate::parse)

        assertEquals(3 to 2, streaks(days, today))
        assertEquals(3 to 0, streaks(days.dropLast(1), today))
        assertEquals(0 to 0, streaks(emptyList(), today))
    }

    @Test
    fun `totals count each movie and episode once`() {
        val history = LocalWatchHistory(
            watches = listOf(movie("tt1", "2026-11-01"), episode("tt2", "2026-11-02"), episode("tt2", "2026-01-01")),
            titles = emptyMap()
        )

        val totals = localTotals(history, Instant.parse("2026-11-04T12:00:00Z"))

        assertEquals(1, totals.totalMovies)
        assertEquals(2, totals.totalEpisodes)
        assertEquals(2, totals.playsThisWeek)
    }

    @Test
    fun `twelve months end with this one, genres count titles, top rated is by community score`() {
        val history = LocalWatchHistory(
            watches = listOf(
                movie("tt1", "2026-11-01"),
                episode("tt2", "2026-11-02"),
                episode("tt2", "2026-10-02"),
                movie("tt3", "2025-01-01")
            ),
            titles = mapOf(
                "tt1" to LocalTitle("tt1", "movie", "One", genres = listOf("Drama", "Crime"), rating = 7.0),
                "tt2" to LocalTitle("tt2", "series", "Two", genres = listOf("drama"), rating = 8.5),
                "tt3" to LocalTitle("tt3", "movie", "Three", genres = listOf("Comedy"))
            )
        )

        val detailed = localDetailedStats(history, today, zone)

        assertEquals(12, detailed.monthly.size)
        assertEquals("2026-11", detailed.monthly.last().month)
        assertEquals(1, detailed.monthly.last().movies)
        assertEquals(1, detailed.monthly.last().episodes)
        assertEquals(1, detailed.monthly[10].episodes)
        assertEquals("Drama", detailed.genreDistribution.first().genre)
        assertEquals(2, detailed.genreDistribution.first().count)
        assertEquals(listOf("tt2", "tt1"), detailed.topRated.map { it.imdbId })
    }

    @Test
    fun `a year in review counts that year's watches, runtime, genres and busiest month`() {
        val history = LocalWatchHistory(
            watches = listOf(episode("tt2", "2026-03-01"), episode("tt2", "2026-03-08"), movie("tt1", "2026-05-01"), movie("tt1", "2025-05-01")),
            titles = mapOf(
                "tt1" to LocalTitle("tt1", "movie", "One", genres = listOf("Drama"), runtimeMinutes = 120),
                "tt2" to LocalTitle("tt2", "series", "Two", genres = listOf("Comedy"), runtimeMinutes = 30)
            )
        )

        val review = localYearInReview(history, 2026, zone)

        assertEquals(1, review.totalMovies)
        assertEquals(2, review.totalEpisodes)
        assertEquals(180, review.totalRuntimeMinutes)
        assertEquals(2, review.busiestMonth)
        assertEquals("Comedy", review.topGenres.first().genre)
        assertTrue(review.topRated.isEmpty())
    }

    @Test
    fun `an empty year has no busiest month`() {
        assertNull(localYearInReview(LocalWatchHistory(emptyList(), emptyMap()), 2020, zone).busiestMonth)
    }

    @Test
    fun `the TV's watched list keeps each episode once at its latest date and skips bare series`() {
        val history = watchedItemsHistory(
            listOf(
                WatchedItem("tt2", "series", "Two", season = 1, episode = 1, watchedAt = 100),
                WatchedItem("tt2", "series", "Two", season = 1, episode = 1, watchedAt = 300),
                WatchedItem("tt2:1:2", "series", "Two", season = 1, episode = 2, watchedAt = 200),
                WatchedItem("tt3", "series", "Three", watchedAt = 50),
                WatchedItem("tt1", "movie", "One", watchedAt = 400, poster = "p")
            )
        )

        assertEquals(3, history.watches.size)
        assertEquals(300L, history.watches.single { it.kind == WatchKind.EPISODE && it.watchedAtMs > 250 }.watchedAtMs)
        assertEquals(setOf("tt1", "tt2", "tt3"), history.titles.keys)
        assertEquals("p", history.titles.getValue("tt1").poster)
        assertEquals("series", history.titles.getValue("tt2").type)
    }

    @Test
    fun `Trakt's watched movies and shows become one history with their details`() {
        val history = traktHistory(
            movies = listOf(
                TraktWatchedMovieItemDto(
                    plays = 2,
                    lastWatchedAt = "2026-11-01T20:00:00.000Z",
                    movie = TraktMovieDto(title = "One", ids = TraktIdsDto(imdb = "tt1"), runtime = 110, rating = 7.9, genres = listOf("drama"))
                )
            ),
            shows = listOf(
                TraktWatchedShowItemDto(
                    show = TraktShowDto(title = "Two", ids = TraktIdsDto(tmdb = 42), runtime = 45, genres = listOf("comedy")),
                    seasons = listOf(
                        TraktWatchedShowSeasonDto(
                            number = 1,
                            episodes = listOf(
                                TraktWatchedShowEpisodeDto(number = 1, plays = 1, lastWatchedAt = "2026-10-01T20:00:00.000Z"),
                                TraktWatchedShowEpisodeDto(number = 2, plays = 1, lastWatchedAt = "2026-10-02T20:00:00.000Z")
                            )
                        )
                    )
                )
            )
        )

        assertEquals(1, history.watches.count { it.kind == WatchKind.MOVIE })
        assertEquals(2, history.watches.count { it.kind == WatchKind.EPISODE && it.titleId == "tmdb:42" })
        assertEquals(110, history.titles.getValue("tt1").runtimeMinutes)
        assertEquals(listOf("comedy"), history.titles.getValue("tmdb:42").genres)
    }

    @Test
    fun `episode and prefixed ids reduce to their title`() {
        assertEquals("tt1", titleIdOf("tt1:2:3"))
        assertEquals("tmdb:42", titleIdOf("tmdb:42:1:1"))
    }
}
