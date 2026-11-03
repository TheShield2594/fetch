package com.fetch.desktop.detail

import com.fetch.desktop.AppInfo
import com.fetch.tv.domain.model.TraktCommentReview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/** Whose comments: a movie, a show, or one episode of a show. */
data class CommentsTarget(val kind: Kind, val pathId: String, val season: Int? = null, val episode: Int? = null) {
    enum class Kind { MOVIE, SHOW, EPISODE }

    val path: String
        get() = when (kind) {
            Kind.MOVIE -> "movies/$pathId/comments/likes"
            Kind.SHOW -> "shows/$pathId/comments/likes"
            Kind.EPISODE -> "shows/$pathId/seasons/$season/episodes/$episode/comments/likes"
        }

    companion object {
        /**
         * The target for a title's id (`tt…`, `tt…:s:e`, `tmdb:…`), as the TV resolves it: an IMDb
         * id goes straight in; a TMDB-only title needs [TraktCommentsClient.resolveTmdb].
         */
        fun of(id: String, imdbId: String?, isSeries: Boolean, season: Int? = null, episode: Int? = null): CommentsTarget? {
            val imdb = (imdbId ?: id).substringBefore(':').takeIf { it.startsWith("tt") } ?: return null
            return when {
                isSeries && season != null && episode != null -> CommentsTarget(Kind.EPISODE, imdb, season, episode)
                isSeries -> CommentsTarget(Kind.SHOW, imdb)
                else -> CommentsTarget(Kind.MOVIE, imdb)
            }
        }
    }
}

data class TraktCommentsPage(val items: List<TraktCommentReview>, val page: Int, val pageCount: Int, val itemCount: Int) {
    val hasMore: Boolean get() = page < pageCount
}

/**
 * Trakt's comments and reviews for titles and episodes, sorted by likes, 100 a page, as the TV's
 * `TraktCommentsService`. Comments are public: only the app's client id is sent. Pages are kept
 * ten minutes.
 */
class TraktCommentsClient(
    private val http: OkHttpClient,
    private val clientId: () -> String?,
    private val baseUrl: String = "https://api.trakt.tv/",
    private val now: () -> Long = System::currentTimeMillis
) {
    private data class Cached(val page: TraktCommentsPage, val at: Long)

    private val cache = ConcurrentHashMap<String, Cached>()

    val available: Boolean get() = !clientId().isNullOrBlank()

    suspend fun page(target: CommentsTarget, page: Int = 1): TraktCommentsPage {
        val key = "${target.path}|$page"
        cache[key]?.takeIf { now() - it.at <= CACHE_MS }?.let { return it.page }
        val id = clientId()?.takeIf { it.isNotBlank() } ?: error("Trakt isn't configured in this build")
        val url = baseUrl.trimEnd('/') + "/" + target.path + "?page=$page&limit=$LIMIT"
        val result = withContext(Dispatchers.IO) {
            http.newCall(request(url, id)).execute().use { r ->
                Triple(r.code, r.body?.string().orEmpty(), r.headers)
            }
        }
        val (code, body, headers) = result
        val comments = when {
            code == 404 -> emptyList()
            code !in 200..299 -> throw IllegalStateException("Couldn't load Trakt comments (HTTP $code)")
            else -> runCatching { json.decodeFromString<List<CommentDto>>(body) }.getOrDefault(emptyList())
        }
        val out = TraktCommentsPage(
            items = comments.filter { !it.comment.isNullOrBlank() }.map { it.toReview() },
            page = page,
            pageCount = headers["X-Pagination-Page-Count"]?.toIntOrNull() ?: page,
            itemCount = headers["X-Pagination-Item-Count"]?.toIntOrNull() ?: comments.size
        )
        cache[key] = Cached(out, now())
        return out
    }

    /** Trakt's IMDb id (or Trakt id) for a TMDB-only title, for its comments. */
    suspend fun resolveTmdb(tmdbId: Int, isSeries: Boolean): String? = try {
        val id = clientId()?.takeIf { it.isNotBlank() } ?: return null
        val type = if (isSeries) "show" else "movie"
        val body = withContext(Dispatchers.IO) {
            http.newCall(request(baseUrl.trimEnd('/') + "/search/tmdb/$tmdbId?type=$type", id)).execute().use { if (it.isSuccessful) it.body?.string() else null }
        } ?: return null
        val hit = json.decodeFromString<List<SearchDto>>(body).firstOrNull { it.type == type }
        val ids = (if (isSeries) hit?.show else hit?.movie)?.ids
        ids?.imdb?.takeIf { it.isNotBlank() } ?: ids?.trakt?.toString() ?: ids?.slug
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun request(url: String, clientId: String) = Request.Builder().url(url)
        .header("Content-Type", "application/json")
        .header("User-Agent", "Fetch/${AppInfo.version}")
        .header("trakt-api-key", clientId)
        .header("trakt-api-version", "2")
        .get().build()

    companion object {
        const val LIMIT = 100
        const val CACHE_MS = 10 * 60_000L
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        private val INLINE_SPOILER = Regex("\\[spoiler\\].*?\\[/spoiler\\]", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val SPOILER_TAG = Regex("\\[/?spoiler\\]", RegexOption.IGNORE_CASE)

        fun containsInlineSpoilers(comment: String?): Boolean = !comment.isNullOrBlank() && INLINE_SPOILER.containsMatchIn(comment)

        /** The comment without its `[spoiler]` tags (the card hides spoilers itself). */
        fun stripSpoilerMarkup(comment: String?): String =
            comment.orEmpty().replace(SPOILER_TAG, "").replace(Regex("[\\t ]+"), " ").trim()
    }

    @Serializable
    private data class UserDto(val username: String? = null, val name: String? = null)

    @Serializable
    private data class UserStatsDto(val rating: Int? = null)

    @Serializable
    private data class CommentDto(
        val id: Long,
        @SerialName("created_at") val createdAt: String? = null,
        @SerialName("updated_at") val updatedAt: String? = null,
        val comment: String? = null,
        val spoiler: Boolean? = null,
        val review: Boolean? = null,
        val likes: Int? = null,
        @SerialName("user_stats") val userStats: UserStatsDto? = null,
        val user: UserDto? = null
    ) {
        fun toReview() = TraktCommentReview(
            id = id,
            authorDisplayName = user?.name?.takeIf { it.isNotBlank() } ?: user?.username?.takeIf { it.isNotBlank() } ?: "Trakt user",
            authorUsername = user?.username?.takeIf { it.isNotBlank() },
            comment = stripSpoilerMarkup(comment),
            spoiler = spoiler == true,
            containsInlineSpoilers = containsInlineSpoilers(comment),
            review = review == true,
            likes = likes ?: 0,
            rating = userStats?.rating,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }

    @Serializable
    private data class IdsDto(val trakt: Long? = null, val slug: String? = null, val imdb: String? = null)

    @Serializable
    private data class MediaDto(val ids: IdsDto? = null)

    @Serializable
    private data class SearchDto(val type: String? = null, val movie: MediaDto? = null, val show: MediaDto? = null)
}
