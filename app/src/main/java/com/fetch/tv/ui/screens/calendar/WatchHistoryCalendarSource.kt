package com.fetch.tv.ui.screens.calendar

import com.fetch.tv.BuildConfig
import com.fetch.tv.core.tmdb.TmdbService
import com.fetch.tv.data.local.LayoutPreferenceDataStore
import com.fetch.tv.data.local.TmdbSettingsDataStore
import com.fetch.tv.data.remote.api.TmdbApi
import com.fetch.tv.data.remote.api.TmdbDetailsResponse
import com.fetch.tv.data.remote.api.TmdbNextEpisode
import com.fetch.tv.domain.model.LibraryEntry
import com.fetch.tv.domain.model.LibraryListTab
import com.fetch.tv.domain.repository.LibraryRepository
import com.fetch.tv.data.website.WebsiteCalendarEntry
import com.fetch.tv.domain.repository.WatchProgressRepository
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The upcoming episodes, and how many series couldn't be looked up (TMDB failed for them), so a
 * calendar missing some can say so. A series TMDB doesn't have isn't a failure.
 */
data class CalendarLookup(val entries: List<WebsiteCalendarEntry>, val failedSeries: Int)

/** TMDB couldn't be reached for any of the series, so an empty calendar would be a guess. */
class CalendarLookupException(message: String) : Exception(message)

/**
 * Upcoming episodes worked out on the TV, for every sync method but the Fetch website (which
 * has a calendar of its own). The series are the profile's most recently watched, from whatever
 * tracking source it uses, through [WatchProgressRepository]; each one's next episode comes from
 * TMDB, as the website's does.
 */
@Singleton
class WatchHistoryCalendarSource internal constructor(
    private val progress: WatchProgressRepository,
    private val tmdbService: TmdbService,
    private val tmdbApi: TmdbApi,
    private val tmdbSettings: TmdbSettingsDataStore,
    private val apiKey: String,
    private val nowMs: () -> Long,
    /** The profile's spoiler setting: blur unwatched episodes. Every calendar episode is unwatched. */
    private val hideEpisodeStills: suspend () -> Boolean = { true },
    /** The films on the profile's watchlist, whatever service it's kept on. */
    private val watchlistMovies: suspend () -> List<LibraryEntry> = { emptyList() }
) {
    @Inject
    constructor(
        progress: WatchProgressRepository,
        tmdbService: TmdbService,
        tmdbApi: TmdbApi,
        tmdbSettings: TmdbSettingsDataStore,
        layoutPreferences: LayoutPreferenceDataStore,
        library: LibraryRepository
    ) : this(
        progress,
        tmdbService,
        tmdbApi,
        tmdbSettings,
        BuildConfig.TMDB_API_KEY,
        System::currentTimeMillis,
        { layoutPreferences.blurUnwatchedEpisodes.first() },
        {
            val watchlistKeys = library.listTabs.first()
                .filter { it.type == LibraryListTab.Type.WATCHLIST }
                .mapTo(HashSet()) { it.key }
            library.libraryItems.first().filter { it.type == "movie" && it.listKeys.any(watchlistKeys::contains) }
        }
    )

    private data class CachedDetails(val details: TmdbDetailsResponse?, val fetchedAtMs: Long)

    // Changing the range or reopening the screen shouldn't look every show up again.
    private val detailsCache = ConcurrentHashMap<String, CachedDetails>()

    private data class CachedSeason(val episodes: List<TmdbNextEpisode>, val fetchedAtMs: Long)

    private val seasonCache = ConcurrentHashMap<String, CachedSeason>()

    private val movieCache = ConcurrentHashMap<String, CachedDetails>()

    suspend fun upcoming(days: Int, today: LocalDate): CalendarLookup = withContext(Dispatchers.IO) {
        // Trakt and the other services fill the history some seconds after start-up; read it once
        // they have, or after the timeout, when what's there counts as the history.
        withTimeoutOrNull(HISTORY_TIMEOUT_MS) { progress.observeRemoteProgressLoaded().first { it } }
        val inProgress = withTimeoutOrNull(HISTORY_TIMEOUT_MS) { progress.allProgress.first() }.orEmpty()
        val watched = withTimeoutOrNull(HISTORY_TIMEOUT_MS) { progress.watchedItems.first() }.orEmpty()
        val series = startedSeries(inProgress, watched)
        // Watchlist films, as on the website's calendar: the most recently added first, as many
        // as series are looked up. A watchlist that can't be read leaves the series alone.
        val movies = try {
            withTimeoutOrNull(HISTORY_TIMEOUT_MS) { watchlistMovies() }.orEmpty()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }.sortedByDescending { it.listedAt }.take(CalendarSeriesLimit)
        if (series.isEmpty() && movies.isEmpty()) return@withContext CalendarLookup(emptyList(), failedSeries = 0)
        if (apiKey.isBlank()) throw CalendarLookupException("This build has no TMDB key, so episode dates can't be looked up")

        val language = tmdbSettings.settings.value.language.ifBlank { "en" }
        val permits = Semaphore(MAX_CONCURRENT_LOOKUPS)
        val watchedMovies = watchedMovieIds(inProgress, watched)
        val movieEntries = coroutineScope {
            movies.map { movie ->
                async { permits.withPermit { movie to lookUpMovie(movie, language) } }
            }.awaitAll()
        }.mapNotNull { (movie, details) ->
            details ?: return@mapNotNull null
            movieEntry(
                movie = movie,
                releaseDate = details.releaseDate,
                fallbackPoster = details.posterPath?.let { "$POSTER_BASE$it" },
                backdrop = details.backdropPath?.let { "$BACKDROP_BASE$it" },
                overview = details.overview,
                today = today,
                pastDays = CalendarPastDays,
                days = days,
                watchedIds = watchedMovies
            )
        }
        val lookups = coroutineScope {
            series.map { show ->
                async { permits.withPermit { show to lookUp(show.id, language) } }
            }.awaitAll()
        }
        // Every episode of the past week, not only the latest: the season of a show whose latest
        // episode aired in that week. Only those shows cost a second request.
        val recentSeasons = coroutineScope {
            lookups.map { (show, result) ->
                async {
                    val details = result.getOrNull() ?: return@async show.id to null
                    val last = details.lastEpisodeToAir
                    val inWindow = last?.airDate?.let(::parseAirDate)
                        ?.let { !it.isAfter(today) && !it.isBefore(today.minusDays(CalendarPastDays.toLong())) } == true
                    val season = last?.seasonNumber
                    if (!inWindow || season == null) return@async show.id to null
                    show.id to permits.withPermit { seasonEpisodes(details.id, season, language) }
                }
            }.awaitAll().toMap()
        }
        if (series.isNotEmpty() && lookups.none { it.second.isSuccess } && movieEntries.isEmpty()) {
            throw CalendarLookupException(lookups.firstNotNullOfOrNull { it.second.exceptionOrNull()?.message } ?: "TMDB didn't answer")
        }
        // With the spoiler setting on, an unaired episode's still is left out and its row shows
        // the series backdrop instead.
        val showStills = !hideEpisodeStills()
        val watchedKeys = watchedEpisodeKeys(inProgress, watched)
        val entries = lookups.flatMap { (show, result) ->
            val details = result.getOrNull() ?: return@flatMap emptyList()
            // The past week's episodes (the season's where it could be read, else the latest
            // alone), marked from the watch history; a still shows when spoilers aren't hidden,
            // or once that episode has been watched.
            val aired = recentSeasons[show.id] ?: listOfNotNull(details.lastEpisodeToAir)
            val recent = aired.mapNotNull { episode ->
                recentEntry(
                    series = show,
                    last = episode,
                    fallbackPoster = details.posterPath?.let { "$POSTER_BASE$it" },
                    today = today,
                    pastDays = CalendarPastDays,
                    watchedKeys = watchedKeys,
                    backdrop = details.backdropPath?.let { "$BACKDROP_BASE$it" }
                )?.let { entry ->
                    val still = episode.stillPath?.takeIf { it.isNotBlank() }?.takeIf { showStills || entry.watched }
                    entry.copy(still = still?.let { "$BACKDROP_BASE$it" })
                }
            }
            val upcoming = upcomingEntry(
                series = show,
                next = details.nextEpisodeToAir,
                status = details.status,
                fallbackPoster = details.posterPath?.let { "$POSTER_BASE$it" },
                today = today,
                days = days,
                backdrop = details.backdropPath?.let { "$BACKDROP_BASE$it" },
                still = details.nextEpisodeToAir?.stillPath
                    ?.takeIf { showStills && it.isNotBlank() }
                    ?.let { "$BACKDROP_BASE$it" }
            )
            recent + listOfNotNull(upcoming)
        }
        CalendarLookup(entries + movieEntries, failedSeries = lookups.count { it.second.isFailure })
    }

    /** A watchlist film's TMDB details, for its release date; null when they can't be read. */
    private suspend fun lookUpMovie(movie: LibraryEntry, language: String): TmdbDetailsResponse? {
        val cacheKey = "${movie.id}:$language"
        movieCache[cacheKey]?.takeIf { nowMs() - it.fetchedAtMs in 0 until CACHE_TTL_MS }?.let { return it.details }
        return try {
            val tmdbId = movie.tmdbId ?: tmdbService.ensureTmdbId(movie.id, "movie", movie.imdbId)?.toIntOrNull()
            val details = tmdbId?.let { id ->
                tmdbApi.getMovieDetails(id, apiKey, language).takeIf { it.isSuccessful }?.body()
            }
            if (details != null) movieCache[cacheKey] = CachedDetails(details, nowMs())
            details
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    /**
     * One season's episodes, as the calendar reads them; null when TMDB can't be asked, and the
     * caller falls back to the latest episode alone.
     */
    private suspend fun seasonEpisodes(tmdbId: Int, season: Int, language: String): List<TmdbNextEpisode>? {
        val cacheKey = "$tmdbId:$season:$language"
        seasonCache[cacheKey]?.takeIf { nowMs() - it.fetchedAtMs in 0 until CACHE_TTL_MS }?.let { return it.episodes }
        return try {
            val response = tmdbApi.getTvSeasonDetails(tmdbId, season, apiKey, language)
            val episodes = response.body()?.episodes?.takeIf { response.isSuccessful } ?: return null
            episodes.map { episode ->
                TmdbNextEpisode(
                    seasonNumber = season,
                    episodeNumber = episode.episodeNumber,
                    name = episode.name,
                    overview = episode.overview,
                    airDate = episode.airDate,
                    stillPath = episode.stillPath
                )
            }.also { seasonCache[cacheKey] = CachedSeason(it, nowMs()) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    /** The series' TMDB details; success with null when TMDB has no such series. */
    private suspend fun lookUp(id: String, language: String): Result<TmdbDetailsResponse?> {
        val cacheKey = "$id:$language"
        detailsCache[cacheKey]?.takeIf { nowMs() - it.fetchedAtMs in 0 until CACHE_TTL_MS }?.let { return Result.success(it.details) }
        return try {
            val tmdbId = tmdbService.ensureTmdbId(id, "series")?.toIntOrNull()
            val details = tmdbId?.let {
                val response = tmdbApi.getTvDetails(it, apiKey, language)
                if (!response.isSuccessful && response.code() != 404) {
                    throw CalendarLookupException("TMDB answered ${response.code()}")
                }
                response.body()
            }
            // Only an answer is kept: a null can be a lookup that failed for now, and the next
            // open should try it again rather than leave the series off for an hour.
            if (details != null) detailsCache[cacheKey] = CachedDetails(details, nowMs())
            Result.success(details)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private companion object {
        const val MAX_CONCURRENT_LOOKUPS = 6
        const val HISTORY_TIMEOUT_MS = 15_000L
        const val CACHE_TTL_MS = 60 * 60 * 1000L
        const val POSTER_BASE = "https://image.tmdb.org/t/p/w342"
        const val BACKDROP_BASE = "https://image.tmdb.org/t/p/w780"
    }
}
