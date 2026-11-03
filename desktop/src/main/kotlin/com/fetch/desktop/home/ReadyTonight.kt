package com.fetch.desktop.home

import com.fetch.desktop.data.JsonFile
import com.fetch.desktop.data.LibraryEntry
import com.fetch.desktop.data.ProgressEntry
import com.fetch.desktop.data.WatchHistory
import kotlinx.serialization.Serializable
import java.io.File
import java.time.LocalDate

/** The last Best match for one video, as the TV's `CachedBestMatch`. */
@Serializable
data class CachedBestMatch(
    /** Picture, HDR, sound, size, most important first ("4K", "DV", "Atmos"). */
    val labels: List<String>,
    val isCached: Boolean,
    val savedAtMs: Long
)

@Serializable
data class BestMatchFile(val entries: Map<String, CachedBestMatch> = emptyMap())

fun bestMatchFile(dir: File) = JsonFile(File(dir, "best-match.json"), BestMatchFile.serializer()) { BestMatchFile() }

/**
 * Ready tonight, as the TV's `home/ReadyTonight.kt`: titles whose Best match is on a debrid
 * service's cache and was confirmed in the last [READY_WINDOW_MS], so Play starts at once.
 * Continue Watching's come first, then watchlist movies, and the row shows with two or more.
 */
object ReadyTonight {
    /** How long a cached pick counts as ready; debrid caches can drop files. */
    const val READY_WINDOW_MS = 24L * 60L * 60L * 1000L
    /** An unchanged pick is re-saved at most this often, only to record that it was checked again. */
    const val RECHECK_TOUCH_MS = 60L * 60L * 1000L
    /** How long a title waits between background checks, found or not. */
    const val CHECK_INTERVAL_MS = 12L * 60L * 60L * 1000L
    /** How long Continue Watching stays unchanged before a background check starts. */
    const val CHECK_SETTLE_MS = 5_000L
    const val MIN_ITEMS = 2
    const val WATCHLIST_MAX_MOVIES = 20
    /** The most titles one Home visit checks in the background. */
    const val CHECK_MAX_TITLES = 6
    const val MAX_ENTRIES = 200

    /** The key the TV uses: lowercased type and the video id. */
    fun contentKey(type: String, videoId: String): String = "${type.lowercase()}|$videoId"

    fun isReady(entry: CachedBestMatch, nowMs: Long): Boolean = entry.isCached && nowMs - entry.savedAtMs in 0..READY_WINDOW_MS

    fun readyKeys(entries: Map<String, CachedBestMatch>, nowMs: Long): Set<String> = entries.filterValues { isReady(it, nowMs) }.keys

    /** [current] with [entry] saved under [key], or null when nothing needs writing (unchanged and confirmed lately). */
    fun merge(current: Map<String, CachedBestMatch>, key: String, entry: CachedBestMatch): Map<String, CachedBestMatch>? {
        val existing = current[key]
        val unchanged = existing != null && existing.labels == entry.labels && existing.isCached == entry.isCached
        if (unchanged && entry.savedAtMs - existing!!.savedAtMs < RECHECK_TOUCH_MS) return null
        val merged = current - key + (key to entry)
        // The newest [MAX_ENTRIES] picks.
        return if (merged.size <= MAX_ENTRIES) merged else merged.entries.sortedByDescending { it.value.savedAtMs }.take(MAX_ENTRIES).associate { it.toPair() }
    }

    /** The key of the video this Continue Watching card plays; null for an episode that hasn't aired. */
    fun ProgressEntry.bestMatchKey(today: LocalDate = LocalDate.now()): String? {
        if (WatchHistory.isUpcoming(this, today)) return null
        return contentKey(contentType, videoId)
    }

    fun LibraryEntry.bestMatchKey(): String = contentKey("movie", id)

    /** Continue Watching entries that are ready, in the row's order. */
    fun readyEntries(entries: List<ProgressEntry>, readyKeys: Set<String>, today: LocalDate = LocalDate.now()): List<ProgressEntry> =
        entries.filter { e -> e.bestMatchKey(today)?.let { it in readyKeys } == true }

    /** Movies from a watchlist, newest first; series wait until they're in Continue Watching. */
    fun watchlistMovies(entries: List<LibraryEntry>, max: Int = WATCHLIST_MAX_MOVIES): List<LibraryEntry> =
        entries.filter { it.type.equals("movie", ignoreCase = true) }.sortedByDescending { it.addedAt }.take(max)

    /** Ready watchlist movies, minus any already in Continue Watching ([exceptKeys]). */
    fun readyMovies(movies: List<LibraryEntry>, readyKeys: Set<String>, exceptKeys: Set<String>): List<LibraryEntry> =
        movies.filter { m -> m.bestMatchKey().let { it in readyKeys && it !in exceptKeys } }

    /** What a background check asks the addons for. */
    data class CheckRequest(val key: String, val type: String, val videoId: String, val name: String)

    /**
     * The titles to ask about now, Continue Watching first: those with no pick confirmed and no
     * attempt in the last [CHECK_INTERVAL_MS], at most [max].
     */
    fun candidates(
        entries: List<ProgressEntry>,
        cache: Map<String, CachedBestMatch>,
        lastAttemptMs: Map<String, Long>,
        nowMs: Long,
        watchlist: List<LibraryEntry> = emptyList(),
        today: LocalDate = LocalDate.now(),
        max: Int = CHECK_MAX_TITLES
    ): List<CheckRequest> = (
        entries.asSequence().mapNotNull { e -> e.bestMatchKey(today)?.let { CheckRequest(it, e.contentType, e.videoId, e.name) } } +
            watchlist.asSequence().map { CheckRequest(it.bestMatchKey(), "movie", it.id, it.name) }
        )
        .distinctBy { it.key }
        .filter { r ->
            val confirmed = cache[r.key]?.let { nowMs - it.savedAtMs in 0 until CHECK_INTERVAL_MS } == true
            val asked = lastAttemptMs[r.key]?.let { nowMs - it in 0 until CHECK_INTERVAL_MS } == true
            !confirmed && !asked
        }
        .take(max)
        .toList()
}
