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
