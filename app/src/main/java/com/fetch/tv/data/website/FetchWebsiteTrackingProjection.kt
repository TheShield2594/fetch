package com.fetch.tv.data.website

import com.fetch.tv.core.tracking.TrackingProviderId
import com.fetch.tv.core.tracking.selectPreferredTrackingNextUpSeeds
import com.fetch.tv.domain.model.LibraryEntry
import com.fetch.tv.domain.model.LibraryListPrivacy
import com.fetch.tv.domain.model.LibraryListTab
import com.fetch.tv.domain.model.WatchProgress
import com.fetch.tv.domain.model.WatchedItem
import java.time.Instant
import java.time.OffsetDateTime

/** What the website holds for one of its profiles, as last read by this TV. */
data class FetchWebsiteTrackingSnapshot(
    /** The TV profile slot this was read for; a snapshot for another slot is never shown. */
    val slot: Int,
    val websiteProfileId: String,
    val playback: List<WebsitePlayback> = emptyList(),
    val watchState: WebsiteWatchState = WebsiteWatchState(),
    val lists: List<WebsiteList> = emptyList(),
    val listItems: Map<String, List<WebsiteListItem>> = emptyMap(),
    val fetchedAtMs: Long = 0L,
    /** The profile has a PIN nobody has entered on this TV, so the website showed nothing. */
    val locked: Boolean = false
)

internal val FETCH_WEBSITE_PROVIDER_STORAGE_ID = TrackingProviderId.FETCH_WEBSITE.storageId

/** Library list keys for website lists: `fetch:list:<list id>`. */
internal const val FETCH_WEBSITE_LIST_KEY_PREFIX = "fetch:list:"

internal fun fetchWebsiteListKey(listId: String): String = FETCH_WEBSITE_LIST_KEY_PREFIX + listId

internal fun fetchWebsiteListId(key: String): String? =
    key.takeIf { it.startsWith(FETCH_WEBSITE_LIST_KEY_PREFIX) }?.removePrefix(FETCH_WEBSITE_LIST_KEY_PREFIX)?.takeIf(String::isNotBlank)

/** The website's own threshold: a scrobble stop at 80% or more records the watch. */
internal const val FETCH_WEBSITE_COMPLETION_PERCENT = 80f

internal fun fetchWebsiteTimestamp(value: String?): Long {
    if (value.isNullOrBlank()) return 0L
    return runCatching { Instant.parse(value).toEpochMilli() }
        .recoverCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
        .getOrDefault(0L)
}

/**
 * Watch state as the TV's tracking layer wants it: Continue Watching from the website's
 * resume points, watched badges and Next Up from its watch history.
 */
internal class FetchWebsiteProgressProjection(snapshot: FetchWebsiteTrackingSnapshot?) {
    private val state = snapshot?.watchState ?: WebsiteWatchState()
    private val dropped = state.dropped.toSet()

    val watchedMovieIds: Set<String> = state.movies.mapTo(linkedSetOf()) { it.imdbId }

    val watchedShowEpisodes: Map<String, Set<Pair<Int, Int>>> = state.episodes
        .groupBy(WebsiteWatchedEpisode::imdbId)
        .mapValues { (_, episodes) -> episodes.mapTo(linkedSetOf()) { it.season to it.episode } }

    private val lastWatchByKey: Map<String, Long> = buildMap {
        state.movies.forEach { put(movieKey(it.imdbId), fetchWebsiteTimestamp(it.watchedAt)) }
        state.episodes.forEach { put(episodeKey(it.imdbId, it.season, it.episode), fetchWebsiteTimestamp(it.watchedAt)) }
    }

    val watchedItems: List<WatchedItem> = (
        state.movies.map { movie ->
            WatchedItem(
                contentId = movie.imdbId,
                contentType = "movie",
                title = movie.name ?: movie.imdbId,
                watchedAt = fetchWebsiteTimestamp(movie.watchedAt),
                poster = movie.poster,
                trackingProviderId = FETCH_WEBSITE_PROVIDER_STORAGE_ID
            )
        } + state.episodes.map { episode ->
            WatchedItem(
                contentId = episode.imdbId,
                contentType = "series",
                title = episode.name ?: episode.imdbId,
                season = episode.season,
                episode = episode.episode,
                watchedAt = fetchWebsiteTimestamp(episode.watchedAt),
                poster = episode.poster,
                trackingProviderId = FETCH_WEBSITE_PROVIDER_STORAGE_ID
            )
        }
    ).sortedByDescending(WatchedItem::watchedAt)

    /**
     * Resume points still worth resuming: the website clears one when the title is watched,
     * but a watch it learns of later (an import dated after the pause) can leave one behind.
     */
    val progress: List<WatchProgress> = snapshot?.playback.orEmpty()
        .mapNotNull { playback -> playback.toWatchProgress() }
        .filter { progress ->
            val key = if (progress.season != null && progress.episode != null) {
                episodeKey(progress.contentId, progress.season, progress.episode)
            } else {
                movieKey(progress.contentId)
            }
            val watchedAt = lastWatchByKey[key]
            (watchedAt == null || watchedAt < progress.lastWatched) && !isHidden(progress.contentId)
        }
        .sortedByDescending(WatchProgress::lastWatched)

    private val episodeProgressById = progress
        .filter { it.season != null && it.episode != null }
        .groupBy(WatchProgress::contentId)

    private val seeds: List<WatchProgress> = state.episodes
        .filter { it.season > 0 && !isHidden(it.imdbId) }
        .map { episode ->
            WatchProgress(
                contentId = episode.imdbId,
                contentType = "series",
                name = episode.name ?: episode.imdbId,
                poster = episode.poster,
                backdrop = null,
                logo = null,
                videoId = "${episode.imdbId}:${episode.season}:${episode.episode}",
                season = episode.season,
                episode = episode.episode,
                episodeTitle = null,
                position = 0L,
                duration = 0L,
                lastWatched = fetchWebsiteTimestamp(episode.watchedAt),
                progressPercent = 100f,
                source = WatchProgress.SOURCE_REMOTE_HISTORY,
                trackingProviderId = FETCH_WEBSITE_PROVIDER_STORAGE_ID,
                completionThresholdOverride = FETCH_WEBSITE_COMPLETION_PERCENT / 100f
            )
        }

    fun nextUp(preferFurthest: Boolean): List<WatchProgress> =
        selectPreferredTrackingNextUpSeeds(seeds, preferFurthest)

    fun isHidden(contentId: String): Boolean = contentId in dropped

    fun isWatched(contentId: String, season: Int?, episode: Int?): Boolean =
        if (season != null && episode != null) season to episode in watchedShowEpisodes[contentId].orEmpty()
        else contentId in watchedMovieIds

    /** For a Stremio-style episode id, `tt123:2:5`; null when [videoId] isn't one. */
    fun isWatchedByVideoId(videoId: String, episode: Int): Boolean? {
        val parts = videoId.split(':')
        if (parts.size < 3 || parts.last().toIntOrNull() != episode) return null
        val season = parts[parts.lastIndex - 1].toIntOrNull() ?: return null
        val parent = parts.dropLast(2).joinToString(":")
        if (!parent.startsWith("tt")) return null
        return isWatched(parent, season, episode)
    }

    fun episodeProgress(contentId: String): Map<Pair<Int, Int>, WatchProgress> =
        episodeProgressById[contentId].orEmpty().associateBy { it.season!! to it.episode!! }

    companion object {
        val Empty = FetchWebsiteProgressProjection(null)

        private fun movieKey(imdbId: String) = "movie:$imdbId"
        private fun episodeKey(imdbId: String, season: Int, episode: Int) = "episode:$imdbId:$season:$episode"
    }
}

/** The website's ids for a title's resume points (every episode's, without [season] and [episode]). */
internal fun FetchWebsiteTrackingSnapshot.playbackIdsFor(contentId: String, season: Int?, episode: Int?): List<String> =
    playback.filter { playback ->
        playback.imdbId.equals(contentId, ignoreCase = true) &&
            (season == null || episode == null || playback.season == season && playback.episode == episode)
    }.map(WebsitePlayback::id)

internal fun WebsitePlayback.toWatchProgress(): WatchProgress? {
    val isMovie = type.equals("movie", ignoreCase = true)
    if (!isMovie && (season == null || episode == null)) return null
    val durationMs = (durationSeconds ?: 0L).coerceAtLeast(0L) * 1000L
    val positionMs = (positionSeconds ?: 0L).coerceAtLeast(0L) * 1000L
    val exact = durationMs > 0L && positionMs > 0L
    return WatchProgress(
        contentId = imdbId,
        contentType = if (isMovie) "movie" else "series",
        name = name ?: imdbId,
        poster = poster,
        backdrop = null,
        logo = null,
        videoId = if (isMovie) imdbId else "$imdbId:$season:$episode",
        season = if (isMovie) null else season,
        episode = if (isMovie) null else episode,
        episodeTitle = null,
        position = if (exact) positionMs else 0L,
        duration = durationMs,
        lastWatched = fetchWebsiteTimestamp(pausedAt),
        // A save that reported only a percentage resumes by it; one with seconds lands on the second.
        progressPercent = if (exact) null else progress.toFloat().coerceIn(0f, 100f),
        source = WatchProgress.SOURCE_REMOTE_PLAYBACK,
        trackingProviderId = FETCH_WEBSITE_PROVIDER_STORAGE_ID,
        trackingProviderItemId = id,
        completionThresholdOverride = FETCH_WEBSITE_COMPLETION_PERCENT / 100f
    )
}

/** The website's lists as Library tabs and entries. */
internal class FetchWebsiteLibraryProjection(snapshot: FetchWebsiteTrackingSnapshot?) {
    val tabs: List<LibraryListTab> = snapshot?.lists.orEmpty()
        // The watchlist first, as on every other source; then the website's own order.
        .sortedBy { if (it.kind == "watchlist") 0 else 1 }
        .map { list ->
            LibraryListTab(
                key = fetchWebsiteListKey(list.id),
                title = list.name,
                type = if (list.kind == "watchlist") LibraryListTab.Type.WATCHLIST else LibraryListTab.Type.PERSONAL,
                privacy = LibraryListPrivacy.PRIVATE,
                trackingProviderId = FETCH_WEBSITE_PROVIDER_STORAGE_ID,
                supportedContentTypes = setOf("movie", "series")
            )
        }

    val watchlistKey: String? = tabs.firstOrNull { it.type == LibraryListTab.Type.WATCHLIST }?.key

    private val memberships = mutableMapOf<Pair<String, String>, Set<String>>()

    val entries: List<LibraryEntry>

    init {
        val combined = linkedMapOf<Pair<String, String>, LibraryEntry>()
        snapshot?.lists.orEmpty().forEach { list ->
            val listKey = fetchWebsiteListKey(list.id)
            snapshot?.listItems?.get(list.id).orEmpty().forEachIndexed { position, item ->
                val type = if (item.type.equals("movie", ignoreCase = true)) "movie" else "series"
                val key = type to item.imdbId
                val previous = combined[key]
                val meta = item.metadata
                val listKeys = previous?.listKeys.orEmpty() + listKey
                combined[key] = LibraryEntry(
                    id = item.imdbId,
                    type = type,
                    name = meta?.name ?: item.title ?: previous?.name ?: item.imdbId,
                    poster = meta?.poster ?: previous?.poster,
                    background = null,
                    logo = null,
                    description = null,
                    releaseInfo = meta?.year?.toString() ?: previous?.releaseInfo,
                    imdbRating = meta?.rating?.toFloat() ?: previous?.imdbRating,
                    genres = (previous?.genres.orEmpty() + meta?.genres.orEmpty()).distinct(),
                    addonBaseUrl = null,
                    listKeys = listKeys,
                    listRanks = previous?.listRanks.orEmpty() + (listKey to position),
                    listedAt = maxOf(previous?.listedAt ?: 0L, fetchWebsiteTimestamp(item.addedAt)),
                    imdbId = item.imdbId,
                    rawPosterUrl = meta?.poster ?: previous?.rawPosterUrl,
                    trackingProviderId = FETCH_WEBSITE_PROVIDER_STORAGE_ID
                )
                memberships[key] = listKeys
            }
        }
        entries = combined.values.toList()
    }

    fun membership(id: String, type: String): Set<String> = memberships[fetchWebsiteItemType(type) to id].orEmpty()

    companion object {
        val Empty = FetchWebsiteLibraryProjection(null)
    }
}

/** The website's two list item types; anything that isn't a film is a series to it. */
internal fun fetchWebsiteItemType(type: String): String =
    if (type.equals("movie", ignoreCase = true) || type.equals("film", ignoreCase = true)) "movie" else "series"
