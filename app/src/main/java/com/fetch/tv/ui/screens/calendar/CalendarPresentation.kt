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

// ─── The calendar from the TV's own watch history ───
//
// Used when the website isn't the sync method: the series come from whichever tracking source
// the profile uses (Trakt, Simkl, MDBList, a media server or this TV's own history) and each
// one's next episode from TMDB, the way the website builds its own calendar.

/** A series the profile has started, as the calendar needs it. */
data class StartedSeries(
    val id: String,
    val name: String,
    val poster: String?,
    val lastWatchedMs: Long
)

/** How many series to look up, most recently watched first: the same limit as the website. */
internal const val CalendarSeriesLimit = 30

/** TMDB statuses that rule out another episode. Everything else is looked up. */
private val NoFurtherEpisodes = setOf("ended", "canceled", "cancelled")

internal fun isFinishedSeriesStatus(status: String?): Boolean = status?.trim()?.lowercase() in NoFurtherEpisodes

private fun isSeriesEntry(type: String, season: Int?): Boolean =
    season != null || type.lowercase() in setOf("series", "tv", "show", "anime")

/** `tt123:1:2` and `tt123` are the same series, as are `tmdb:42:1:2` and `tmdb:42`. */
internal fun seriesBaseId(id: String): String {
    val parts = id.trim().substringBefore('/').split(':')
    val prefixed = parts.size >= 2 && parts[0].isNotEmpty() && !parts[0].startsWith("tt") && parts[0].none(Char::isDigit)
    return if (prefixed) "${parts[0]}:${parts[1]}" else parts[0]
}

/**
 * The series in [progress] and [watched], once each, most recently watched first. A series
 * keeps the name and poster of its most recent entry that has them.
 */
internal fun startedSeries(
    progress: List<com.fetch.tv.domain.model.WatchProgress>,
    watched: List<com.fetch.tv.domain.model.WatchedItem>,
    limit: Int = CalendarSeriesLimit
): List<StartedSeries> {
    val all = progress.filter { isSeriesEntry(it.contentType, it.season) }
        .map { StartedSeries(seriesBaseId(it.contentId), it.name, it.poster, it.lastWatched) } +
        watched.filter { isSeriesEntry(it.contentType, it.season) }
            .map { StartedSeries(seriesBaseId(it.contentId), it.title, it.poster, it.watchedAt) }
    return all
        .filter { it.id.isNotBlank() }
        .groupBy { it.id }
        .map { (_, entries) ->
            val byRecency = entries.sortedByDescending { it.lastWatchedMs }
            byRecency.first().copy(
                name = byRecency.firstOrNull { it.name.isNotBlank() }?.name ?: byRecency.first().id,
                poster = byRecency.firstNotNullOfOrNull { it.poster?.takeIf(String::isNotBlank) }
            )
        }
        .sortedByDescending { it.lastWatchedMs }
        .take(limit)
}

/**
 * [series]' next episode as a calendar entry when it airs between [today] and [days] days
 * after it; null when there's none, it has aired, it's further out, or the show has ended.
 */
internal fun upcomingEntry(
    series: StartedSeries,
    next: com.fetch.tv.data.remote.api.TmdbNextEpisode?,
    status: String?,
    fallbackPoster: String?,
    today: LocalDate,
    days: Int
): WebsiteCalendarEntry? {
    if (next == null || isFinishedSeriesStatus(status)) return null
    val airDate = next.airDate?.let(::parseAirDate) ?: return null
    if (airDate.isBefore(today) || airDate.isAfter(today.plusDays(days.toLong()))) return null
    val season = next.seasonNumber ?: return null
    val episode = next.episodeNumber ?: return null
    return WebsiteCalendarEntry(
        seriesImdbId = series.id,
        seriesName = series.name,
        poster = series.poster ?: fallbackPoster,
        season = season,
        episode = episode,
        episodeName = next.name?.takeIf { it.isNotBlank() },
        airDate = airDate.toString(),
        overview = next.overview?.takeIf { it.isNotBlank() }
    )
}
