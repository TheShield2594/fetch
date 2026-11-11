package com.fetch.tv.ui.screens.library

import com.fetch.tv.domain.model.WatchProgress
import com.fetch.tv.domain.model.WatchedItem
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryActivityTest {

    private fun at(year: Int, month: Int, day: Int): Long =
        LocalDateTime.of(year, month, day, 20, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun watched(id: String, at: Long, season: Int? = null, episode: Int? = null, title: String = id, poster: String? = null) =
        WatchedItem(
            contentId = id,
            contentType = if (season != null) "series" else "movie",
            title = title,
            season = season,
            episode = episode,
            watchedAt = at,
            poster = poster
        )

    @Test
    fun `a series is one entry at the episode watched last`() {
        val items = libraryHistoryItems(
            listOf(
                watched("tt1", at(2026, 10, 1), season = 1, episode = 1, poster = "p1"),
                watched("tt1", at(2026, 10, 3), season = 1, episode = 2),
                watched("tt1", at(2026, 10, 2), season = 1, episode = 3)
            )
        )

        assertEquals(1, items.size)
        assertEquals(2, items.single().episode)
        assertEquals(at(2026, 10, 3), items.single().at)
        assertEquals("a poster from another episode fills a gap", "p1", items.single().poster)
    }

    @Test
    fun `history is newest first and split by month`() {
        val items = libraryHistoryItems(
            listOf(
                watched("tt-old", at(2026, 9, 28)),
                watched("tt-new", at(2026, 11, 2)),
                watched("tt-mid", at(2026, 10, 15)),
                watched("tt-mid2", at(2026, 10, 1))
            )
        )

        val months = groupHistoryByMonth(items, ZoneOffset.UTC)

        assertEquals(listOf(YearMonth.of(2026, 11), YearMonth.of(2026, 10), YearMonth.of(2026, 9)), months.map { it.month })
        assertEquals(listOf("tt-mid", "tt-mid2"), months[1].items.map { it.id })
    }

    @Test
    fun `entries without a time or id are left out and a blank title falls back`() {
        val items = libraryHistoryItems(
            listOf(
                watched("tt1", 0L),
                watched("", at(2026, 10, 1)),
                watched("tt2", at(2026, 10, 1), title = "")
            )
        )

        assertEquals(listOf("tt2"), items.map { it.id })
        assertEquals("tt2", items.single().title)
    }

    @Test
    fun `in progress keeps the latest play per title with how far in`() {
        fun progress(id: String, lastWatched: Long, position: Long, episode: Int? = null) = WatchProgress(
            contentId = id,
            contentType = if (episode != null) "series" else "movie",
            name = id,
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "$id:$episode",
            season = episode?.let { 1 },
            episode = episode,
            episodeTitle = null,
            position = position,
            duration = 1_000L,
            lastWatched = lastWatched
        )

        val items = libraryInProgressItems(
            listOf(
                progress("tt-show", 10L, position = 100L, episode = 1),
                progress("tt-show", 20L, position = 450L, episode = 2),
                progress("tt-movie", 15L, position = 0L)
            )
        )

        assertEquals(listOf("tt-show", "tt-movie"), items.map { it.id })
        assertEquals(2, items[0].episode)
        assertEquals(45, items[0].progressPercent)
        assertEquals(0, items[1].progressPercent)
    }

    @Test
    fun `no history gives no months`() {
        assertEquals(emptyList<LibraryHistoryMonth>(), groupHistoryByMonth(emptyList()))
        assertNull(libraryHistoryItems(emptyList()).firstOrNull())
    }
}
