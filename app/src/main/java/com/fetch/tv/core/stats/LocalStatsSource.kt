package com.fetch.tv.core.stats

import com.fetch.tv.BuildConfig
import com.fetch.tv.core.tmdb.TmdbService
import com.fetch.tv.core.tracking.TrackingProviderId
import com.fetch.tv.data.local.TmdbSettingsDataStore
import com.fetch.tv.data.remote.api.TmdbApi
import com.fetch.tv.domain.model.WatchedItem
import com.fetch.tv.domain.repository.WatchProgressRepository
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

/** The watch history couldn't be read, so stats built from it would be wrong rather than empty. */
class StatsHistoryException(message: String) : Exception(message)

/**
 * The active profile's watch history, for stats worked out on the TV.
 *
 * With Trakt as the tracking service it's read from Trakt, which has every watched movie and
 * episode with its genres, runtime and rating. With any other (Simkl, MDBList, a media server,
 * or this TV's own history) it's the watched list the TV already keeps, with genres, runtime,
 * rating and poster looked up on TMDB for the [MAX_DETAIL_LOOKUPS] most recently watched titles.
 */
@Singleton
class LocalStatsSource internal constructor(
    private val progress: WatchProgressRepository,
    private val trakt: TraktWatchHistoryReader,
    private val tmdbService: TmdbService,
    private val tmdbApi: TmdbApi,
    private val tmdbSettings: TmdbSettingsDataStore,
    private val apiKey: String
) {
    @Inject
    constructor(
        progress: WatchProgressRepository,
        trakt: TraktWatchHistoryReader,
        tmdbService: TmdbService,
        tmdbApi: TmdbApi,
        tmdbSettings: TmdbSettingsDataStore
    ) : this(progress, trakt, tmdbService, tmdbApi, tmdbSettings, BuildConfig.TMDB_API_KEY)

    // Looked-up details outlive the screen, so reopening it doesn't look everything up again.
    private val detailsCache = ConcurrentHashMap<String, LocalTitle>()

    suspend fun read(): LocalWatchHistory = withContext(Dispatchers.IO) {
        if (progress.activeTrackingProviderName() == TrackingProviderId.TRAKT.name) {
            return@withContext trakt.read() ?: throw StatsHistoryException("Couldn't read your watch history from Trakt")
        }
        // A remote source that hasn't answered yet counts as no history rather than holding the screen.
        val watched = withTimeoutOrNull(HISTORY_TIMEOUT_MS) { progress.watchedItems.first() }.orEmpty()
        withDetails(watchedItemsHistory(watched))
    }

    private suspend fun withDetails(history: LocalWatchHistory): LocalWatchHistory {
        if (apiKey.isBlank() || history.titles.isEmpty()) return history
        val language = tmdbSettings.settings.value.language.ifBlank { "en" }
        val lastWatched = history.watches.groupBy { it.titleId }.mapValues { (_, w) -> w.maxOf { it.watchedAtMs } }
        val recent = history.titles.values.sortedByDescending { lastWatched[it.id] ?: 0L }.take(MAX_DETAIL_LOOKUPS)
        val permits = Semaphore(MAX_CONCURRENT_LOOKUPS)
        val detailed = coroutineScope {
            recent.map { title -> async { permits.withPermit { withTmdbDetails(title, language) } } }.awaitAll()
        }
        return history.copy(
            titles = history.titles + detailed.associateBy { it.id },
            detailsPartial = history.titles.size > recent.size
        )
    }

    private suspend fun withTmdbDetails(title: LocalTitle, language: String): LocalTitle {
        val cacheKey = "${title.type}:${title.id}:$language"
        detailsCache[cacheKey]?.let { cached -> return title.mergedWith(cached) }
        return try {
            val isMovie = title.type == "movie"
            val tmdbId = tmdbService.ensureTmdbId(title.id, if (isMovie) "movie" else "series")?.toIntOrNull()
                ?: return title
            val response = if (isMovie) tmdbApi.getMovieDetails(tmdbId, apiKey, language) else tmdbApi.getTvDetails(tmdbId, apiKey, language)
            val details = response.body()?.takeIf { response.isSuccessful } ?: return title
            val found = LocalTitle(
                id = title.id,
                type = title.type,
                name = details.title ?: details.name ?: title.name,
                poster = details.posterPath?.let { "$POSTER_BASE$it" },
                genres = details.genres.orEmpty().map { it.name },
                runtimeMinutes = details.runtime ?: details.episodeRunTime?.firstOrNull(),
                rating = details.voteAverage?.takeIf { it > 0 }
            )
            detailsCache[cacheKey] = found
            title.mergedWith(found)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            title
        }
    }

    private companion object {
        const val MAX_DETAIL_LOOKUPS = 300
        const val MAX_CONCURRENT_LOOKUPS = 8
        const val HISTORY_TIMEOUT_MS = 15_000L
        const val POSTER_BASE = "https://image.tmdb.org/t/p/w342"
    }
}

/** [this] title with what [found] adds; the history's own name and poster are kept when it has them. */
private fun LocalTitle.mergedWith(found: LocalTitle): LocalTitle = copy(
    name = name.takeIf { it.isNotBlank() && it != id } ?: found.name,
    poster = poster?.takeIf { it.isNotBlank() } ?: found.poster,
    genres = genres.ifEmpty { found.genres },
    runtimeMinutes = runtimeMinutes ?: found.runtimeMinutes,
    rating = rating ?: found.rating
)

/** `tt1:2:3` → `tt1`, `tmdb:42:1:2` → `tmdb:42`. */
internal fun titleIdOf(id: String): String {
    val parts = id.trim().substringBefore('/').split(':')
    val prefixed = parts.size >= 2 && parts[0].isNotEmpty() && !parts[0].startsWith("tt") && parts[0].none(Char::isDigit)
    return if (prefixed) "${parts[0]}:${parts[1]}" else parts[0]
}

/**
 * The TV's watched list as a history: each movie once, each episode once, at its latest date.
 * A series marked watched with no episodes in it has nothing to count.
 */
internal fun watchedItemsHistory(items: List<WatchedItem>): LocalWatchHistory {
    val titles = linkedMapOf<String, LocalTitle>()
    val watches = mutableMapOf<String, LocalWatch>()
    items.sortedByDescending { it.watchedAt }.forEach { item ->
        val id = titleIdOf(item.contentId).takeIf { it.isNotBlank() } ?: return@forEach
        val isMovie = item.contentType.equals("movie", ignoreCase = true) && item.season == null
        titles.getOrPut(id) {
            LocalTitle(
                id = id,
                type = if (isMovie) "movie" else "series",
                name = item.title.takeIf { it.isNotBlank() } ?: id,
                poster = item.poster?.takeIf { it.isNotBlank() }
            )
        }
        val key = when {
            isMovie -> "movie:$id"
            item.season != null && item.episode != null -> "episode:$id:${item.season}:${item.episode}"
            else -> return@forEach
        }
        // Newest first, so the first one seen for a key is its latest watch.
        watches.getOrPut(key) {
            LocalWatch(id, if (isMovie) WatchKind.MOVIE else WatchKind.EPISODE, item.watchedAt)
        }
    }
    return LocalWatchHistory(watches.values.toList(), titles)
}
