package com.fetch.tv.ui.screens.calendar

import com.fetch.tv.data.website.WebsiteCalendarEntry
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarPresentationTest {
    private val today = LocalDate.of(2026, 11, 4)

    private fun entry(name: String, airDate: String, season: Int = 1, episode: Int = 1, id: String = "tt-$name") =
        WebsiteCalendarEntry(seriesImdbId = id, seriesName = name, season = season, episode = episode, airDate = airDate)

    @Test
    fun `entries are grouped by day, earliest first, each day in name order`() {
        val days = groupCalendarByDay(
            listOf(
                entry("Zebra", "2026-11-10"),
                entry("beta", "2026-11-04"),
                entry("Alpha", "2026-11-04"),
                entry("Gamma", "2026-11-05")
            ),
            today
        )

        assertEquals(listOf(LocalDate.of(2026, 11, 4), LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 10)), days.map { it.date })
        assertEquals(listOf(CalendarDayKind.TODAY, CalendarDayKind.TOMORROW, CalendarDayKind.LATER), days.map { it.kind })
        assertEquals(listOf("Alpha", "beta"), days[0].entries.map { it.seriesName })
    }

    @Test
    fun `an unreadable air date is kept under a day of its own at the end`() {
        val days = groupCalendarByDay(listOf(entry("Later", "soon"), entry("Show", "2026-11-06")), today)

        assertEquals(2, days.size)
        assertNull(days.last().date)
        assertEquals(listOf("Later"), days.last().entries.map { it.seriesName })
    }

    @Test
    fun `the same episode listed twice appears once`() {
        val days = groupCalendarByDay(listOf(entry("Show", "2026-11-06"), entry("Show", "2026-11-06")), today)

        assertEquals(1, days.single().entries.size)
    }

    @Test
    fun `air dates that don't exist aren't rolled over to another day`() {
        assertNull(parseAirDate("2026-02-30"))
        assertNull(parseAirDate("2026"))
        assertEquals(LocalDate.of(2026, 2, 28), parseAirDate("2026-02-28"))
    }

    @Test
    fun `episodes are labelled as on the website`() {
        assertEquals("S2:E5", episodeCode(2, 5))
        assertNull(episodeCode(null, null))
    }

    @Test
    fun `an air time places the episode on the viewer's local day, first in time order`() {
        val newYork = java.time.ZoneId.of("America/New_York")
        val late = entry("Alpha", "2026-11-10").copy(airsAt = "2026-11-10T02:00:00Z")
        val early = entry("Zulu", "2026-11-09").copy(airsAt = "2026-11-09T18:00:00Z")
        val untimed = entry("Beta", "2026-11-09")

        val days = groupCalendarByDay(listOf(untimed, late, early), today, newYork)

        // 02:00 UTC on the 10th is 21:00 on the 9th in New York.
        assertEquals(listOf(LocalDate.parse("2026-11-09")), days.map { it.date })
        assertEquals(listOf("Zulu", "Alpha", "Beta"), days.single().entries.map { it.seriesName })
        assertEquals(21, calendarAirTime(late, newYork)!!.hour)
    }

    @Test
    fun `a long Trakt calendar window is split into requests Trakt allows`() {
        val start = LocalDate.of(2026, 11, 1)
        assertEquals(
            listOf(start to 33, start.plusDays(33) to 33, start.plusDays(66) to 32),
            traktCalendarChunks(start, 98)
        )
        assertEquals(listOf(start to 22), traktCalendarChunks(start, 22))
    }

    @Test
    fun `Trakt air times fill in episodes by IMDb or TMDB id, not films or entries that have one`() {
        val dto = com.fetch.tv.data.remote.dto.trakt.TraktCalendarShowDto(
            firstAired = "2026-11-10T02:00:00.000Z",
            episode = com.fetch.tv.data.remote.dto.trakt.TraktEpisodeDto(season = 1, number = 2),
            show = com.fetch.tv.data.remote.dto.trakt.TraktShowDto(
                ids = com.fetch.tv.data.remote.dto.trakt.TraktIdsDto(imdb = "tt1", tmdb = 42)
            )
        )
        val times = traktAirTimes(listOf(dto))
        val episode = WebsiteCalendarEntry(seriesImdbId = "tt1", seriesName = "Show", season = 1, episode = 2, airDate = "2026-11-09")

        assertEquals("2026-11-10T02:00:00.000Z", withAirTime(episode, times).airsAt)
        assertEquals("2026-11-10T02:00:00.000Z", withAirTime(episode.copy(seriesImdbId = "tmdb:42"), times).airsAt)
        assertNull(withAirTime(episode.copy(episode = 3), times).airsAt)
        assertNull(withAirTime(episode.copy(season = null, episode = null, kind = "movie"), times).airsAt)
        // An anime episode Simkl numbers its own way is matched by show and air date.
        val byDate = mapOf("date:tt9:2026-11-09" to "2026-11-08T15:00:00Z")
        assertEquals("2026-11-08T15:00:00Z", withAirTime(episode.copy(seriesImdbId = "tt9", episode = 40), byDate).airsAt)
    }

    @Test
    fun `an air time can keep the week's oldest day, and an untimed entry before the week stays out`() {
        // Kiritimati is UTC+14: 12:30Z on the 20th is the 21st there, inside a week ending the 28th.
        val kiritimati = java.time.ZoneId.of("Pacific/Kiritimati")
        val lastDay = LocalDate.of(2026, 11, 28)
        val timed = entry("Timed", "2026-11-20")
        val untimed = entry("Untimed", "2026-11-20", id = "tt-untimed")
        val times = mapOf("${timed.seriesImdbId}:1:1" to "2026-11-20T12:30:00Z")

        val kept = placeOnLocalDays(listOf(timed, untimed), times, lastDay, pastDays = 7, zone = kiritimati)

        assertEquals(listOf("Timed"), kept.map { it.seriesName })
        assertEquals("2026-11-20T12:30:00Z", kept.single().airsAt)
    }

    @Test
    fun `days before today are past`() {
        val days = groupCalendarByDay(
            listOf(entry("Old", "2026-11-01").copy(watched = true), entry("New", "2026-11-04")),
            today
        )

        assertEquals(listOf(CalendarDayKind.PAST, CalendarDayKind.TODAY), days.map { it.kind })
        assertTrue(days.first().entries.single().watched)
    }

    @Test
    fun `an unreadable air time falls back to the air date`() {
        val odd = entry("Show", "2026-11-09").copy(airsAt = "tonight")
        assertNull(calendarAirTime(odd, java.time.ZoneOffset.UTC))
        assertEquals(LocalDate.parse("2026-11-09"), calendarDay(odd, java.time.ZoneOffset.UTC))
    }

    @Test
    fun `a row's wide picture is the still, else the backdrop, else none`() {
        val base = entry("Show", "2026-11-09")
        assertEquals("still", calendarWideArtwork(base.copy(still = "still", backdrop = "backdrop")))
        assertEquals("backdrop", calendarWideArtwork(base.copy(still = " ", backdrop = "backdrop")))
        assertNull(calendarWideArtwork(base))
    }
}
