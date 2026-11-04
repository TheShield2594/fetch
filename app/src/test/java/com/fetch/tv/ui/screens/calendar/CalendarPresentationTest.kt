package com.fetch.tv.ui.screens.calendar

import com.fetch.tv.data.website.WebsiteCalendarEntry
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    }
}
