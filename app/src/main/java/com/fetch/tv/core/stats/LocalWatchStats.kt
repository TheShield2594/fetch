package com.fetch.tv.core.stats

import com.fetch.tv.data.website.WebsiteDetailedStats
import com.fetch.tv.data.website.WebsiteGenreCount
import com.fetch.tv.data.website.WebsiteMonthlyCount
import com.fetch.tv.data.website.WebsiteRatedTitle
import com.fetch.tv.data.website.WebsiteWatchStats
import com.fetch.tv.data.website.WebsiteYearInReview
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Watch statistics worked out on the TV, for every sync method but the Fetch website (which
 * works its own out). They come out in the website's shapes so one screen shows either.
 *
 * The history they're built from has one date per movie and per episode: the last time it was
 * watched. So a title watched twice counts once, on its later date, where the website, which
 * keeps every play, would count both.
 */

enum class WatchKind { MOVIE, EPISODE }

/** A movie, or one episode of a series, and when it was last watched. [titleId] is the movie's or the series' id. */
data class LocalWatch(
    val titleId: String,
    val kind: WatchKind,
    val watchedAtMs: Long,
    val plays: Int = 1
)

/** What's known about a watched movie or series. [rating] is the community score out of ten. */
data class LocalTitle(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val genres: List<String> = emptyList(),
    val runtimeMinutes: Int? = null,
    val rating: Double? = null
)

data class LocalWatchHistory(
    val watches: List<LocalWatch>,
    val titles: Map<String, LocalTitle>,
    /** True when titles' genres, runtimes and ratings were only looked up for the most recent of them. */
    val detailsPartial: Boolean = false
)

/** The day a watch falls on where the viewer is. */
internal fun LocalWatch.localDate(zone: ZoneId): LocalDate = Instant.ofEpochMilli(watchedAtMs).atZone(zone).toLocalDate()

private fun List<LocalWatch>.dated(): List<LocalWatch> = filter { it.watchedAtMs > 0 }

internal fun localTotals(history: LocalWatchHistory, now: Instant): WebsiteWatchStats {
    val movies = history.watches.count { it.kind == WatchKind.MOVIE }
    val episodes = history.watches.count { it.kind == WatchKind.EPISODE }
    val weekAgo = now.minusSeconds(7 * 24 * 60 * 60).toEpochMilli()
    return WebsiteWatchStats(
        totalMovies = movies,
        totalEpisodes = episodes,
        totalPlays = history.watches.sumOf { it.plays.coerceAtLeast(1) },
        playsThisWeek = history.watches.count { it.watchedAtMs >= weekAgo }
    )
}

/** The longest run of consecutive days with something watched, and the run that ends today (or 0). */
internal fun streaks(days: Collection<LocalDate>, today: LocalDate): Pair<Int, Int> {
    val sorted = days.toSortedSet()
    var longest = 0
    var current = 0
    var run = 0
    var previous: LocalDate? = null
    for (day in sorted) {
        run = if (previous != null && previous.plusDays(1) == day) run + 1 else 1
        longest = maxOf(longest, run)
        if (day == today) current = run
        previous = day
    }
    return longest to current
}

/** Counts per genre, most first, then by name. Each title counts once per genre. */
private fun genreCounts(titles: Collection<LocalTitle>, limit: Int): List<WebsiteGenreCount> =
    titles.flatMap { title -> title.genres.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() } }
        .groupingBy { it.lowercase() }
        .eachCount()
        .let { counts ->
            // Keep a readable spelling of each genre: the first one seen.
            val spelling = titles.flatMap { it.genres }.groupBy { it.trim().lowercase() }.mapValues { it.value.first() }
            counts.entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(limit)
                .map { WebsiteGenreCount(spelling[it.key]?.trim() ?: it.key, it.value) }
        }

internal fun localDetailedStats(history: LocalWatchHistory, today: LocalDate, zone: ZoneId): WebsiteDetailedStats {
    val dated = history.watches.dated()
    val (longest, current) = streaks(dated.map { it.localDate(zone) }, today)

    val thisMonth = YearMonth.from(today)
    val byMonth = dated.groupBy { YearMonth.from(it.localDate(zone)) }
    val monthly = (11 downTo 0).map { back ->
        val month = thisMonth.minusMonths(back.toLong())
        val watches = byMonth[month].orEmpty()
        WebsiteMonthlyCount(
            month = month.toString(),
            movies = watches.count { it.kind == WatchKind.MOVIE },
            episodes = watches.count { it.kind == WatchKind.EPISODE }
        )
    }

    val watchedTitles = history.watches.map { it.titleId }.distinct().mapNotNull { history.titles[it] }
    val topRated = watchedTitles
        .filter { it.rating != null && it.rating > 0 }
        .sortedWith(compareByDescending<LocalTitle> { it.rating }.thenBy { it.id })
        .take(10)
        .map { WebsiteRatedTitle(imdbId = it.id, name = it.name, type = it.type, rating = it.rating, poster = it.poster) }

    return WebsiteDetailedStats(
        monthly = monthly,
        genreDistribution = genreCounts(watchedTitles, limit = 15),
        currentStreak = current,
        longestStreak = longest,
        topRated = topRated
    )
}

/**
 * One year of the history. The website's year picks are the profile's own ratings, which the TV
 * doesn't have, so [WebsiteYearInReview.topRated] stays empty.
 */
internal fun localYearInReview(history: LocalWatchHistory, year: Int, zone: ZoneId): WebsiteYearInReview {
    val inYear = history.watches.dated().filter { it.localDate(zone).year == year }
    val runtime = inYear.sumOf { watch -> history.titles[watch.titleId]?.runtimeMinutes ?: 0 }
    val genres = inYear.flatMap { watch -> history.titles[watch.titleId]?.genres.orEmpty().map { it.trim() } }
        .filter { it.isNotEmpty() }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .take(5)
        .map { WebsiteGenreCount(it.key, it.value) }
    val byMonth = inYear.groupingBy { it.localDate(zone).monthValue - 1 }.eachCount()
    val busiest = byMonth.entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key }).firstOrNull()
    return WebsiteYearInReview(
        year = year,
        totalMovies = inYear.count { it.kind == WatchKind.MOVIE },
        totalEpisodes = inYear.count { it.kind == WatchKind.EPISODE },
        totalRuntimeMinutes = runtime,
        topGenres = genres,
        topRated = emptyList(),
        busiestMonth = busiest?.key,
        busiestMonthCount = busiest?.value ?: 0
    )
}
