package com.fetch.tv.ui.screens.calendar

import com.fetch.tv.data.website.WebsiteCalendarEntry
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/** The ranges the agenda offers, as on the website. */
internal val CalendarRanges = listOf(14, 30, 60, 90)
internal const val DefaultCalendarRange = 30

/** How a day heading reads: relative for the next two days, the date after that. */
enum class CalendarDayKind { TODAY, TOMORROW, LATER }

/** One day of the agenda. [date] is null for entries whose air date couldn't be read. */
data class CalendarDay(
    val date: LocalDate?,
    val kind: CalendarDayKind,
    val entries: List<WebsiteCalendarEntry>
)

/** `YYYY-MM-DD` as a calendar day, or null when it isn't one (including days that don't exist). */
internal fun parseAirDate(value: String): LocalDate? = try {
    LocalDate.parse(value.trim())
} catch (_: DateTimeParseException) {
    null
}

internal fun dayKind(date: LocalDate, today: LocalDate): CalendarDayKind =
    when (ChronoUnit.DAYS.between(today, date)) {
        0L -> CalendarDayKind.TODAY
        1L -> CalendarDayKind.TOMORROW
        else -> CalendarDayKind.LATER
    }

/**
 * The entries grouped by air day, earliest first, each day in series-name order. An air date the
 * website sent in a shape this can't read is kept, under a day of its own at the end, rather than
 * dropped without a trace. An episode listed twice appears once.
 */
internal fun groupCalendarByDay(entries: List<WebsiteCalendarEntry>, today: LocalDate): List<CalendarDay> {
    val unique = entries.distinctBy { "${it.seriesImdbId}:${it.season}:${it.episode}" }
    val (dated, undated) = unique.partition { parseAirDate(it.airDate) != null }
    val days = dated
        .groupBy { parseAirDate(it.airDate)!! }
        .toSortedMap()
        .map { (date, dayEntries) ->
            CalendarDay(
                date = date,
                kind = dayKind(date, today),
                entries = dayEntries.sortedWith(compareBy({ it.seriesName.lowercase() }, { it.season }, { it.episode }))
            )
        }
    return if (undated.isEmpty()) days else days + CalendarDay(null, CalendarDayKind.LATER, undated)
}

/** `S2:E5`, as the website labels an episode. */
internal fun episodeCode(season: Int, episode: Int): String = "S$season:E$episode"
