package com.fetch.tv.ui.screens.calendar

import com.fetch.tv.BuildConfig
import com.fetch.tv.core.tmdb.TmdbService
import com.fetch.tv.data.local.TmdbSettingsDataStore
import com.fetch.tv.data.remote.api.TmdbApi
import com.fetch.tv.data.remote.api.TmdbDetailsResponse
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
    private val nowMs: () -> Long
) {
    @Inject
    constructor(
        progress: WatchProgressRepository,
        tmdbService: TmdbService,
        tmdbApi: TmdbApi,
        tmdbSettings: TmdbSettingsDataStore
    ) : this(progress, tmdbService, tmdbApi, tmdbSettings, BuildConfig.TMDB_API_KEY, System::currentTimeMillis)

    private data class CachedDetails(val details: TmdbDetailsResponse?, val fetchedAtMs: Long)

    // Changing the range or reopening the screen shouldn't look every show up again.
    private val detailsCache = ConcurrentHashMap<String, CachedDetails>()

    suspend fun upcoming(days: Int, today: LocalDate): List<WebsiteCalendarEntry> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw CalendarLookupException("This build has no TMDB key, so episode dates can't be looked up")
        // A remote source that hasn't answered yet counts as no history rather than holding the screen.
        val inProgress = withTimeoutOrNull(HISTORY_TIMEOUT_MS) { progress.allProgress.first() }.orEmpty()
        val watched = withTimeoutOrNull(HISTORY_TIMEOUT_MS) { progress.watchedItems.first() }.orEmpty()
        val series = startedSeries(inProgress, watched)
        if (series.isEmpty()) return@withContext emptyList()

        val language = tmdbSettings.settings.value.language.ifBlank { "en" }
        val permits = Semaphore(MAX_CONCURRENT_LOOKUPS)
        val lookups = coroutineScope {
            series.map { show ->
                async { permits.withPermit { show to lookUp(show.id, language) } }
            }.awaitAll()
        }
        if (lookups.none { it.second.isSuccess }) {
            throw CalendarLookupException(lookups.firstNotNullOfOrNull { it.second.exceptionOrNull()?.message } ?: "TMDB didn't answer")
        }
        lookups.mapNotNull { (show, result) ->
            val details = result.getOrNull() ?: return@mapNotNull null
            upcomingEntry(
                series = show,
                next = details.nextEpisodeToAir,
                status = details.status,
                fallbackPoster = details.posterPath?.let { "$POSTER_BASE$it" },
                today = today,
                days = days
            )
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
            detailsCache[cacheKey] = CachedDetails(details, nowMs())
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
    }
}
