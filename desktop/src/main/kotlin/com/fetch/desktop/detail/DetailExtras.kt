package com.fetch.desktop.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fetch.desktop.tmdb.TmdbClient
import com.fetch.tv.domain.model.TraktCommentReview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** The ratings grid for one show. */
data class EpisodeRatingsView(val contentId: String, val ratings: Map<Pair<Int, Int>, Double> = emptyMap(), val loading: Boolean = true)

/** The comments shown for one target, page by page. */
data class CommentsView(
    val target: CommentsTarget?,
    val items: List<TraktCommentReview> = emptyList(),
    val page: Int = 0,
    val pageCount: Int = 1,
    val total: Int = 0,
    val loading: Boolean = true,
    val error: String? = null
) {
    val hasMore: Boolean get() = !loading && page < pageCount
}

/**
 * The title page's extras from the TV: the IMDb episode ratings grid and Trakt comments for the
 * title or an episode. Loaded when the page shows them, for the title on screen only.
 */
class DetailExtras(
    private val scope: CoroutineScope,
    private val tmdb: TmdbClient,
    traktClientId: () -> String?,
    http: OkHttpClient = OkHttpClient(),
    val ratingsClient: EpisodeRatingsClient = EpisodeRatingsClient(http),
    val commentsClient: TraktCommentsClient = TraktCommentsClient(http, traktClientId)
) {
    var ratings: EpisodeRatingsView? by mutableStateOf(null)
        private set
    var comments: CommentsView? by mutableStateOf(null)
        private set
    private var commentsJob: Job? = null

    fun loadRatings(contentId: String, imdbId: String?) {
        if (!ratingsClient.available || ratings?.contentId == contentId) return
        ratings = EpisodeRatingsView(contentId)
        scope.launch {
            val tmdbId = runCatching { tmdb.tmdbId(imdbId ?: contentId, true) }.getOrNull()
            val result = runCatching { ratingsClient.ratings(imdbId ?: contentId, tmdbId) }.getOrDefault(emptyMap())
            if (ratings?.contentId == contentId) ratings = EpisodeRatingsView(contentId, result, loading = false)
        }
    }

    /** Comments for a title ([season] and [episode] for one episode); a TMDB-only title is looked up on Trakt first. */
    fun loadComments(id: String, imdbId: String?, isSeries: Boolean, season: Int? = null, episode: Int? = null) {
        if (!commentsClient.available) return
        val wanted = CommentsTarget.of(id, imdbId, isSeries, season, episode)
        val current = comments
        if (current != null && current.target == wanted && wanted != null) return
        commentsJob?.cancel()
        comments = CommentsView(wanted)
        commentsJob = scope.launch {
            val target = wanted ?: TmdbClient.parseTmdbId(id)?.let { tmdbId ->
                commentsClient.resolveTmdb(tmdbId, isSeries)?.let { path ->
                    CommentsTarget(
                        if (isSeries && season != null && episode != null) CommentsTarget.Kind.EPISODE else if (isSeries) CommentsTarget.Kind.SHOW else CommentsTarget.Kind.MOVIE,
                        path, season, episode
                    )
                }
            }
            if (target == null) {
                comments = CommentsView(null, loading = false)
                return@launch
            }
            comments = CommentsView(target)
            fetchPage(target, 1)
        }
    }

    fun loadMoreComments() {
        val current = comments ?: return
        val target = current.target ?: return
        if (!current.hasMore) return
        comments = current.copy(loading = true)
        commentsJob = scope.launch { fetchPage(target, current.page + 1) }
    }

    private suspend fun fetchPage(target: CommentsTarget, page: Int) {
        try {
            val result = commentsClient.page(target, page)
            val before = comments?.takeIf { it.target == target } ?: return
            comments = before.copy(
                items = (if (page == 1) emptyList() else before.items) + result.items,
                page = result.page,
                pageCount = result.pageCount,
                total = result.itemCount,
                loading = false,
                error = null
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            comments = comments?.takeIf { it.target == target }?.copy(loading = false, error = e.message ?: "Couldn't load comments")
        }
    }
}
