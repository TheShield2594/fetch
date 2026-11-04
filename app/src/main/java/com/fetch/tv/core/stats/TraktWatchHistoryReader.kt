package com.fetch.tv.core.stats

import com.fetch.tv.core.trakt.traktPosterUrl
import com.fetch.tv.data.remote.api.TraktApi
import com.fetch.tv.data.remote.dto.trakt.TraktWatchedMovieItemDto
import com.fetch.tv.data.remote.dto.trakt.TraktWatchedShowItemDto
import com.fetch.tv.data.repository.TraktAuthService
import com.fetch.tv.data.repository.normalizeContentId
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import retrofit2.Response

/**
 * Everything Trakt has as watched, for the stats: each movie and each episode with the last time
 * it was watched, and each title's genres, runtime and community rating (Trakt's `extended=full`).
 */
@Singleton
class TraktWatchHistoryReader @Inject constructor(
    private val traktApi: TraktApi,
    private val traktAuthService: TraktAuthService
) {
    /** Null when Trakt couldn't be read. */
    suspend fun read(): LocalWatchHistory? {
        val movies = pages { auth, page -> traktApi.getWatched(auth, "movies", extended = EXTENDED, page = page, limit = PAGE_LIMIT) }
            ?: return null
        val shows = pages { auth, page -> traktApi.getWatchedShows(auth, extended = EXTENDED, page = page, limit = PAGE_LIMIT) }
            ?: return null
        return traktHistory(movies, shows)
    }

    private suspend fun <T> pages(fetch: suspend (auth: String, page: Int) -> Response<List<T>>): List<T>? {
        val items = mutableListOf<T>()
        for (page in 1..MAX_PAGES) {
            val response = traktAuthService.executeAuthorizedRequest { auth -> fetch(auth, page) } ?: return null
            if (!response.isSuccessful) return null
            val body = response.body().orEmpty()
            items += body
            val pageCount = response.headers()["X-Pagination-Page-Count"]?.toIntOrNull()
            if (body.isEmpty() || pageCount == null || page >= pageCount) return items
        }
        return items
    }

    private companion object {
        // Genres, runtime and rating come with `full`; posters with `images`.
        const val EXTENDED = "full,images"
        const val PAGE_LIMIT = 250
        const val MAX_PAGES = 200
    }
}

private fun parseTraktInstant(value: String?): Long =
    value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L

internal fun traktHistory(movies: List<TraktWatchedMovieItemDto>, shows: List<TraktWatchedShowItemDto>): LocalWatchHistory {
    val titles = mutableMapOf<String, LocalTitle>()
    val watches = mutableListOf<LocalWatch>()
    movies.forEach { item ->
        val movie = item.movie ?: return@forEach
        val id = normalizeContentId(movie.ids).takeIf { it.isNotBlank() } ?: return@forEach
        titles[id] = LocalTitle(
            id = id,
            type = "movie",
            name = movie.title ?: id,
            poster = movie.images.traktPosterUrl(),
            genres = movie.genres.orEmpty(),
            runtimeMinutes = movie.runtime,
            rating = movie.rating
        )
        watches += LocalWatch(id, WatchKind.MOVIE, parseTraktInstant(item.lastWatchedAt), item.plays ?: 1)
    }
    shows.forEach { item ->
        val show = item.show ?: return@forEach
        val id = normalizeContentId(show.ids).takeIf { it.isNotBlank() } ?: return@forEach
        titles[id] = LocalTitle(
            id = id,
            type = "series",
            name = show.title ?: id,
            poster = show.images.traktPosterUrl(),
            genres = show.genres.orEmpty(),
            runtimeMinutes = show.runtime,
            rating = show.rating
        )
        item.seasons.orEmpty().forEach { season ->
            season.episodes.orEmpty().forEach { episode ->
                watches += LocalWatch(id, WatchKind.EPISODE, parseTraktInstant(episode.lastWatchedAt), episode.plays ?: 1)
            }
        }
    }
    return LocalWatchHistory(watches, titles)
}
