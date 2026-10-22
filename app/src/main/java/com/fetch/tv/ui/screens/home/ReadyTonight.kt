package com.fetch.tv.ui.screens.home

import androidx.compose.runtime.compositionLocalOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import com.fetch.tv.data.local.BestMatchCacheDataStore
import com.fetch.tv.data.local.CachedBestMatch
import com.fetch.tv.data.local.isReady
import com.fetch.tv.domain.model.LibraryEntry
import com.fetch.tv.domain.model.LibraryListTab
import com.fetch.tv.domain.model.LibrarySourceMode

/** How long a title waits between background ready checks, whether or not the last one found a pick. */
internal const val READY_CHECK_INTERVAL_MS = 12L * 60L * 60L * 1000L

/** How long Continue Watching must stay unchanged before a background check starts. */
internal const val READY_CHECK_SETTLE_MS = 5_000L

/** "Ready tonight" shows only with at least this many titles. */
internal const val READY_TONIGHT_MIN_ITEMS = 2

/** How many watchlist movies, newest first, Home considers for Ready tonight. */
internal const val READY_WATCHLIST_MAX_MOVIES = 20

/** The most titles one Home load checks in the background. */
internal const val READY_CHECK_MAX_TITLES = 6

/**
 * The Best match cache key of the video this card plays, or null when it isn't known yet: a
 * shuffled show picks its episode on press, and an episode that hasn't aired has no streams.
 */
internal fun ContinueWatchingItem.bestMatchKey(): String? {
    if (shufflePlayback) return null
    return when (this) {
        is ContinueWatchingItem.InProgress ->
            BestMatchCacheDataStore.contentKey(progress.contentType, progress.videoId)
        is ContinueWatchingItem.NextUp ->
            if (info.hasAired) BestMatchCacheDataStore.contentKey(info.contentType, info.videoId) else null
    }
}

/** How often Home re-reads readiness, so a pick past its window loses its dot without a new save. */
internal const val READY_REFRESH_MS = 30L * 60L * 1000L

/** Emits the time now and then every [periodMs], to recompute readiness as windows expire. */
internal fun readinessTicks(periodMs: Long = READY_REFRESH_MS): Flow<Long> = flow {
    while (true) {
        emit(System.currentTimeMillis())
        delay(periodMs)
    }
}

/** The Best match keys that are ready right now, provided by Home for its cards. */
internal val LocalReadyBestMatchKeys = compositionLocalOf<Set<String>> { emptySet() }

/** Keys of every cached pick that is ready to start instantly right now. */
internal fun readyBestMatchKeys(entries: Map<String, CachedBestMatch>, nowMs: Long): Set<String> =
    entries.filterValues { it.isReady(nowMs) }.keys

/**
 * Movies on the watchlist, newest first. With a tracking service that is its watchlist tab; the
 * local library has no separate watchlist, so all of it counts. Series are left out: which
 * episode to start isn't known until they're in Continue Watching.
 */
internal fun watchlistMovies(
    entries: List<LibraryEntry>,
    tabs: List<LibraryListTab>,
    mode: LibrarySourceMode,
    max: Int = READY_WATCHLIST_MAX_MOVIES
): List<LibraryEntry> {
    val movies = entries.filter { it.type.equals("movie", ignoreCase = true) }
    val onWatchlist = if (mode == LibrarySourceMode.LOCAL) {
        movies
    } else {
        val watchlistKeys = tabs.filter { it.type == LibraryListTab.Type.WATCHLIST }.map { it.key }.toSet()
        movies.filter { entry -> entry.listKeys.any { it in watchlistKeys } }
    }
    return onWatchlist.sortedByDescending { it.listedAt }.take(max)
}

internal fun LibraryEntry.bestMatchKey(): String = BestMatchCacheDataStore.contentKey("movie", id)

/** Watchlist movies whose Best match is ready, leaving out any already in [exceptKeys]. */
internal fun readyWatchlistMovies(
    movies: List<LibraryEntry>,
    readyKeys: Set<String>,
    exceptKeys: Set<String>
): List<LibraryEntry> = movies.filter { movie ->
    val key = movie.bestMatchKey()
    key in readyKeys && key !in exceptKeys
}

/** Continue Watching items whose Best match is ready, in the row's own order. */
internal fun readyTonightItems(
    items: List<ContinueWatchingItem>,
    readyKeys: Set<String>
): List<ContinueWatchingItem> = items.filter { item -> item.bestMatchKey()?.let { it in readyKeys } == true }

/** What a background ready check asks the previewer for. */
internal data class ReadyCheckRequest(
    val key: String,
    val type: String,
    val videoId: String,
    val season: Int?,
    val episode: Int?,
    val contentId: String
)

internal fun LibraryEntry.readyCheckRequest(): ReadyCheckRequest = ReadyCheckRequest(
    key = bestMatchKey(),
    type = "movie",
    videoId = id,
    season = null,
    episode = null,
    contentId = id
)

internal fun ContinueWatchingItem.readyCheckRequest(): ReadyCheckRequest? {
    val key = bestMatchKey() ?: return null
    return when (this) {
        is ContinueWatchingItem.InProgress -> ReadyCheckRequest(
            key = key,
            type = progress.contentType,
            videoId = progress.videoId,
            season = progress.season,
            episode = progress.episode,
            contentId = progress.contentId
        )
        is ContinueWatchingItem.NextUp -> ReadyCheckRequest(
            key = key,
            type = info.contentType,
            videoId = info.videoId,
            season = info.season,
            episode = info.episode,
            contentId = info.contentId
        )
    }
}

/**
 * The titles a background check should ask about now, Continue Watching first and then the
 * watchlist: those with no pick confirmed and no attempt made in the last
 * [READY_CHECK_INTERVAL_MS], at most [max]. [lastAttemptMs] holds when each key was last asked
 * about, found or not.
 */
internal fun readyCheckCandidates(
    items: List<ContinueWatchingItem>,
    entries: Map<String, CachedBestMatch>,
    lastAttemptMs: Map<String, Long>,
    nowMs: Long,
    watchlist: List<LibraryEntry> = emptyList(),
    max: Int = READY_CHECK_MAX_TITLES
): List<ReadyCheckRequest> = (items.asSequence().mapNotNull { it.readyCheckRequest() } +
    watchlist.asSequence().map { it.readyCheckRequest() })
    .distinctBy { it.key }
    .filter { request ->
        val confirmedRecently = entries[request.key]?.let { nowMs - it.savedAtMs in 0 until READY_CHECK_INTERVAL_MS } == true
        val askedRecently = lastAttemptMs[request.key]?.let { nowMs - it in 0 until READY_CHECK_INTERVAL_MS } == true
        !confirmedRecently && !askedRecently
    }
    .take(max)
    .toList()
