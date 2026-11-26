package com.fetch.tv.ui.screens.home

import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.WatchProgress
import com.fetch.tv.domain.model.WatchedItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import java.util.concurrent.atomic.AtomicBoolean

/*
 * Helpers that keep Home's watch-history work off the main thread and out of playback
 * (TheShield2594/fetch#824), and the bounded caches of TheShield2594/fetch#826.
 */

/**
 * Collects this flow only while [playerOpen] is false. While the player is open nothing is
 * collected, so nothing downstream recomputes; the downstream keeps the last value it had (a
 * `combine` keeps its latest input). Once the player closes the flow is collected again, so a
 * shared upstream with a replay hands over its current value and the work runs once.
 *
 * The first value is let through even with the player open, so a `combine` downstream is never
 * left waiting on it for a whole episode.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> Flow<T>.pausedWhilePlayerOpen(playerOpen: Flow<Boolean>): Flow<T> = flow {
    val emitted = AtomicBoolean(false)
    val upstream = this@pausedWhilePlayerOpen.onEach { emitted.set(true) }
    emitAll(
        playerOpen.distinctUntilChanged().flatMapLatest { open ->
            when {
                !open -> upstream
                emitted.get() -> emptyFlow()
                else -> upstream.take(1)
            }
        }
    )
}

/**
 * Watched episodes per series for Episode Shuffle: marked-watched items plus completed progress.
 */
internal fun groupWatchedEpisodesForShuffle(
    watched: List<WatchedItem>,
    progress: List<WatchProgress>
): Map<String, Set<Pair<Int, Int>>> {
    val byContentId = HashMap<String, MutableSet<Pair<Int, Int>>>()
    watched.forEach { item ->
        val season = item.season ?: return@forEach
        val episode = item.episode ?: return@forEach
        byContentId.getOrPut(item.contentId) { LinkedHashSet() }.add(season to episode)
    }
    progress.forEach { item ->
        if (!item.isCompleted()) return@forEach
        val season = item.season ?: return@forEach
        val episode = item.episode ?: return@forEach
        byContentId.getOrPut(item.contentId) { LinkedHashSet() }.add(season to episode)
    }
    return byContentId
}

/**
 * The furthest watched episode (highest season, then episode) of each of [contentIds], in one
 * pass over [watched]. Replaces a scan of every watched item per seed.
 */
internal fun latestWatchedEpisodeByContentId(
    watched: List<WatchedItem>,
    contentIds: Set<String>
): Map<String, WatchedItem> {
    if (contentIds.isEmpty()) return emptyMap()
    val latest = HashMap<String, WatchedItem>()
    val order = compareBy<WatchedItem>({ it.season }, { it.episode })
    watched.forEach { item ->
        if (item.season == null || item.episode == null || item.contentId !in contentIds) return@forEach
        val current = latest[item.contentId]
        // Strictly greater, so the first of equal episodes wins, as maxWithOrNull did.
        if (current == null || order.compare(item, current) > 0) latest[item.contentId] = item
    }
    return latest
}

/**
 * Home status key to content id for the series and shows among [items], first occurrence wins.
 * The series-watched observer is keyed on this, so it restarts only when the set changes.
 */
internal fun homeSeriesStatusKeys(items: Sequence<MetaPreview>): Map<String, String> {
    val byKey = LinkedHashMap<String, String>()
    items
        .filter { it.apiType.equals("series", ignoreCase = true) || it.apiType.equals("tv", ignoreCase = true) }
        .forEach { item ->
            val key = homeItemStatusKey(item.id, item.apiType)
            if (key !in byKey) byKey[key] = item.id
        }
    return byKey
}

/**
 * A synchronized map that keeps at most [maxSize] entries, evicting the least recently used.
 *
 * Reads reorder entries, so iterate only while holding the map's lock, and never call `get` on
 * the map while iterating its keys: remove through `entries` instead.
 */
internal fun <K, V> boundedLruMap(
    maxSize: Int,
    onEvict: ((key: K, value: V) -> Unit)? = null
): MutableMap<K, V> {
    require(maxSize > 0)
    val lru = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
            val evict = size > maxSize
            if (evict && eldest != null) onEvict?.invoke(eldest.key, eldest.value)
            return evict
        }
    }
    return java.util.Collections.synchronizedMap(lru)
}

/**
 * Most items a Home catalog row shows and paginates to. Holding Right on a long catalog otherwise
 * grows the row (and its focus requesters, keys and image requests) without limit for the rest
 * of the session. 300 is several minutes of scrolling at TV repeat rates, well past what anyone
 * browses inline. Only the row Home displays is capped: the stored row keeps its paging, so the
 * See all grid, which reads it, can load the rest.
 */
internal const val HOME_CATALOG_ROW_MAX_ITEMS = 300

/** Stops a paginated row at [maxItems]: trims the extra and turns further loading off. */
internal fun com.fetch.tv.domain.model.CatalogRow.cappedForHome(
    maxItems: Int = HOME_CATALOG_ROW_MAX_ITEMS
): com.fetch.tv.domain.model.CatalogRow =
    if (items.size < maxItems) this else copy(items = items.take(maxItems), hasMore = false)
