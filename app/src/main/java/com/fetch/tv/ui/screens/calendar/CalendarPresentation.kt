package com.fetch.tv.ui.screens.calendar

import com.fetch.tv.data.website.WebsiteCalendarEntry
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/** The ranges the agenda offers, as on the website. */
internal val CalendarRanges = listOf(14, 30, 60, 90)
internal const val DefaultCalendarRange = 30

/** How many days back the website calendar also reads, for what aired recently and whether it was watched. */
internal const val CalendarPastDays = 7

/** How a day heading reads: relative for the next two days, the date after that. */
enum class CalendarDayKind { PAST, TODAY, TOMORROW, LATER }

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

/** When [entry] airs in [zone], from its `airsAt`; null when the source didn't say or sent nonsense. */
internal fun calendarAirTime(entry: WebsiteCalendarEntry, zone: ZoneId): ZonedDateTime? =
    entry.airsAt?.takeIf { it.isNotBlank() }?.let { value ->
        try {
            OffsetDateTime.parse(value.trim()).atZoneSameInstant(zone)
        } catch (_: DateTimeParseException) {
            null
        }
    }

/** The local day [entry] airs: from its air time where known, else its `airDate`. */
internal fun calendarDay(entry: WebsiteCalendarEntry, zone: ZoneId): LocalDate? =
    calendarAirTime(entry, zone)?.toLocalDate() ?: parseAirDate(entry.airDate)

internal fun dayKind(date: LocalDate, today: LocalDate): CalendarDayKind =
    when (val offset = ChronoUnit.DAYS.between(today, date)) {
        0L -> CalendarDayKind.TODAY
        1L -> CalendarDayKind.TOMORROW
        else -> if (offset < 0) CalendarDayKind.PAST else CalendarDayKind.LATER
    }

/**
 * The entries grouped by air day, earliest first; within a day, those with a known air time first
 * in time order, then by series name. An entry's day is its local air day where its air time is
 * known (so a 02:00 UTC premiere sits on the evening before in the Americas). An air date the
 * website sent in a shape this can't read is kept, under a day of its own at the end, rather than
 * dropped without a trace. An episode listed twice appears once.
 */
internal fun groupCalendarByDay(
    entries: List<WebsiteCalendarEntry>,
    today: LocalDate,
    zone: ZoneId = ZoneId.systemDefault()
): List<CalendarDay> {
    val unique = entries.distinctBy { "${it.seriesImdbId}:${it.season}:${it.episode}" }
    val (dated, undated) = unique.partition { calendarDay(it, zone) != null }
    // Within a day, what has a known air time comes first, in time order; the rest by name.
    val order = compareBy<WebsiteCalendarEntry, java.time.Instant?>(nullsLast()) { calendarAirTime(it, zone)?.toInstant() }
        .thenBy { it.seriesName.lowercase() }
        .thenBy { it.season }
        .thenBy { it.episode }
    val days = dated
        .groupBy { calendarDay(it, zone)!! }
        .toSortedMap()
        .map { (date, dayEntries) ->
            CalendarDay(
                date = date,
                kind = dayKind(date, today),
                entries = dayEntries.sortedWith(order)
            )
        }
    return if (undated.isEmpty()) days else days + CalendarDay(null, CalendarDayKind.LATER, undated)
}

/**
 * The 16:9 picture for a calendar row: the episode's still, else the title's backdrop. Null when
 * there is neither, and the row shows the poster instead.
 */
internal fun calendarWideArtwork(entry: WebsiteCalendarEntry): String? =
    entry.still?.takeIf { it.isNotBlank() } ?: entry.backdrop?.takeIf { it.isNotBlank() }

/** `S2:E5`, as the website labels an episode; null when either number is missing. */
internal fun episodeCode(season: Int?, episode: Int?): String? =
    if (season == null || episode == null) null else "S$season:E$episode"

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

/** `series:season:episode` for every episode the profile has watched, by [seriesBaseId]. */
internal fun watchedEpisodeKeys(
    progress: List<com.fetch.tv.domain.model.WatchProgress>,
    watched: List<com.fetch.tv.domain.model.WatchedItem>
): Set<String> = buildSet {
    watched.forEach { item ->
        if (item.season != null && item.episode != null) add("${seriesBaseId(item.contentId)}:${item.season}:${item.episode}")
    }
    progress.forEach { item ->
        if (item.season != null && item.episode != null && item.isCompleted()) {
            add("${seriesBaseId(item.contentId)}:${item.season}:${item.episode}")
        }
    }
}

/** Trakt allows a calendar request this many days long at most. */
internal const val TraktCalendarMaxDays = 33

/** The (start, days) requests that cover [days] days from [start], none longer than Trakt allows. */
internal fun traktCalendarChunks(start: LocalDate, days: Int): List<Pair<LocalDate, Int>> =
    (0 until days step TraktCalendarMaxDays).map { offset ->
        start.plusDays(offset.toLong()) to minOf(TraktCalendarMaxDays, days - offset)
    }

/**
 * Air times by `series:season:episode`, from the profile's Trakt calendar: keyed by the show's
 * IMDb id and by `tmdb:<id>`, as a series can be followed by either.
 */
internal fun traktAirTimes(items: List<com.fetch.tv.data.remote.dto.trakt.TraktCalendarShowDto>): Map<String, String> = buildMap {
    items.forEach { item ->
        val airsAt = item.firstAired?.takeIf { it.isNotBlank() } ?: return@forEach
        val season = item.episode?.season ?: return@forEach
        val episode = item.episode.number ?: return@forEach
        val ids = item.show?.ids ?: return@forEach
        ids.imdb?.takeIf { it.isNotBlank() }?.let { put("$it:$season:$episode", airsAt) }
        ids.tmdb?.let { put("tmdb:$it:$season:$episode", airsAt) }
    }
}

/** [entry] with its air time from [airTimes] where Trakt knows it. */
internal fun withAirTime(entry: WebsiteCalendarEntry, airTimes: Map<String, String>): WebsiteCalendarEntry {
    if (entry.isMovie || entry.airsAt != null) return entry
    return airTimes["${entry.seriesImdbId}:${entry.season}:${entry.episode}"]?.let { entry.copy(airsAt = it) } ?: entry
}

/** The ids of every film the profile has watched, from its history and finished playback. */
internal fun watchedMovieIds(
    progress: List<com.fetch.tv.domain.model.WatchProgress>,
    watched: List<com.fetch.tv.domain.model.WatchedItem>
): Set<String> = buildSet {
    watched.forEach { if (it.season == null && !isSeriesEntry(it.contentType, null)) add(it.contentId) }
    progress.forEach { if (it.season == null && !isSeriesEntry(it.contentType, null) && it.isCompleted()) add(it.contentId) }
}

/**
 * A watchlist film as a calendar entry when it's released in the [pastDays] days before [today]
 * or the [days] after; null when it has no readable release date or falls outside that.
 */
internal fun movieEntry(
    movie: com.fetch.tv.domain.model.LibraryEntry,
    releaseDate: String?,
    fallbackPoster: String?,
    backdrop: String?,
    overview: String?,
    today: LocalDate,
    pastDays: Int,
    days: Int,
    watchedIds: Set<String>
): WebsiteCalendarEntry? {
    val date = releaseDate?.let(::parseAirDate) ?: return null
    if (date.isBefore(today.minusDays(pastDays.toLong())) || date.isAfter(today.plusDays(days.toLong()))) return null
    return WebsiteCalendarEntry(
        seriesImdbId = movie.id,
        seriesName = movie.name,
        poster = movie.poster ?: fallbackPoster,
        airDate = date.toString(),
        overview = (movie.description ?: overview)?.takeIf { it.isNotBlank() },
        kind = "movie",
        backdrop = movie.background ?: backdrop,
        watched = movie.id in watchedIds || movie.imdbId?.let { it in watchedIds } == true
    )
}

/**
 * [series]' most recently aired episode as a calendar entry when it aired in the [pastDays] days
 * before [today] (or today), marked watched from [watchedKeys]; null otherwise.
 */
internal fun recentEntry(
    series: StartedSeries,
    last: com.fetch.tv.data.remote.api.TmdbNextEpisode?,
    fallbackPoster: String?,
    today: LocalDate,
    pastDays: Int,
    watchedKeys: Set<String>,
    backdrop: String? = null,
    /** The episode's still; the caller leaves it null when spoilers are hidden and it's unwatched. */
    still: String? = null
): WebsiteCalendarEntry? {
    val airDate = last?.airDate?.let(::parseAirDate) ?: return null
    if (airDate.isAfter(today) || airDate.isBefore(today.minusDays(pastDays.toLong()))) return null
    val season = last.seasonNumber ?: return null
    val episode = last.episodeNumber ?: return null
    return WebsiteCalendarEntry(
        seriesImdbId = series.id,
        seriesName = series.name,
        poster = series.poster ?: fallbackPoster,
        season = season,
        episode = episode,
        episodeName = last.name?.takeIf { it.isNotBlank() },
        airDate = airDate.toString(),
        overview = last.overview?.takeIf { it.isNotBlank() },
        backdrop = backdrop,
        still = still,
        watched = "${series.id}:$season:$episode" in watchedKeys
    )
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
    days: Int,
    /** The series' backdrop, the row's picture when there's no [still]. */
    backdrop: String? = null,
    /** The episode's still; the caller leaves it null under the spoiler setting. */
    still: String? = null
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
        overview = next.overview?.takeIf { it.isNotBlank() },
        backdrop = backdrop,
        still = still
    )
}
