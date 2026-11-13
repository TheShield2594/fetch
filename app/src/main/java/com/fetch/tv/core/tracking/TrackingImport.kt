package com.fetch.tv.core.tracking

import com.fetch.tv.data.local.WatchProgressSource
import com.fetch.tv.data.mediaserver.ServerItemRef
import com.fetch.tv.domain.model.WatchProgress
import com.fetch.tv.domain.model.WatchedItem
import kotlinx.coroutines.CancellationException

/**
 * One-off import of watch state from another connected source into the source of truth.
 *
 * The watch progress source picked in Settings is the only thing Continue Watching, watched
 * marks and resume points are read from; nothing merges sources at read time. To bring history
 * kept elsewhere along, the user imports it once: every title the other source has watched is
 * marked watched in the source of truth (marks are only ever added), and every resume point is
 * copied unless the source of truth already has something at least as recent for that title.
 */
data class TrackingImportSnapshot(
    val watched: List<WatchedItem> = emptyList(),
    val inProgress: List<WatchProgress> = emptyList(),
    /** Series whose watched episodes the source holds but couldn't be read out (see [TrackingImportResult.unreadable]). */
    val unreadable: Int = 0
)

/** Where an import comes from: a connected source, or an account that is only ever read once. */
sealed interface TrackingImportOrigin {
    data class Connected(val source: WatchProgressSource) : TrackingImportOrigin

    /** A Stremio account, signed in for the import only and signed out afterwards. */
    data object Stremio : TrackingImportOrigin
}

data class TrackingImportWriteResult(
    val written: Int,
    val failed: Int = 0
)

/** Something history can be imported from. */
interface TrackingImportReader {
    val origin: TrackingImportOrigin

    /** Of [TrackingCapability.WATCHED_READ], [TrackingCapability.WATCHED_WRITE], [TrackingCapability.PROGRESS_READ] and [TrackingCapability.PROGRESS_WRITE]. */
    val capabilities: Set<TrackingCapability>

    suspend fun read(): TrackingImportSnapshot
}

/** A source that can be read from and, as the source of truth, written to. */
interface TrackingImportEndpoint : TrackingImportReader {
    val source: WatchProgressSource

    override val origin: TrackingImportOrigin get() = TrackingImportOrigin.Connected(source)

    suspend fun writeWatched(items: List<WatchedItem>): TrackingImportWriteResult

    suspend fun writeProgress(items: List<WatchProgress>): TrackingImportWriteResult

    /** Called once after the writes, so the source of truth reads back what was imported. */
    suspend fun finish() = Unit
}

data class TrackingImportResult(
    val source: TrackingImportOrigin,
    val target: WatchProgressSource,
    val watchedImported: Int = 0,
    /** Already watched in the source of truth, or a whole-series mark nobody can copy safely. */
    val watchedSkipped: Int = 0,
    val progressImported: Int = 0,
    /** The source of truth has a newer resume point or watch for the title, or it was finished in the source. */
    val progressSkipped: Int = 0,
    /** Sent but not accepted (unknown to the target, or the request failed). */
    val failed: Int = 0,
    /** Capabilities the import needed but the source can't read or the source of truth can't write. */
    val unsupported: Set<TrackingCapability> = emptySet(),
    /** Series whose episode history the source has but Fetch couldn't map to episodes. */
    val unreadable: Int = 0
) {
    val imported: Int get() = watchedImported + progressImported
    val skipped: Int get() = watchedSkipped + progressSkipped
}

internal data class TrackingImportPlan(
    val watched: List<WatchedItem>,
    val watchedSkipped: Int,
    val progress: List<WatchProgress>,
    val progressSkipped: Int
)

/** Decides what to copy. Pure, so the timestamp rules are testable without a provider. */
internal fun planTrackingImport(
    source: TrackingImportSnapshot,
    target: TrackingImportSnapshot
): TrackingImportPlan {
    val targetWatched = TrackingImportIndex(target.watched) { WatchKey(it.contentId, it.season, it.episode) }
    val targetProgress = TrackingImportIndex(target.inProgress) { WatchKey(it.contentId, it.season, it.episode) }
    val sourceWatched = TrackingImportIndex(source.watched) { WatchKey(it.contentId, it.season, it.episode) }

    var watchedSkipped = 0
    val watched = mutableListOf<WatchedItem>()
    latestBy(source.watched, { WatchKey(it.contentId, it.season, it.episode) }, WatchedItem::watchedAt)
        .forEach { item ->
            val key = WatchKey(item.contentId, item.season, item.episode)
            if (item.isWholeSeriesMark() || ServerItemRef.isServerId(item.contentId) ||
                targetWatched.find(key).isNotEmpty()
            ) {
                watchedSkipped++
            } else {
                watched += item
            }
        }

    var progressSkipped = 0
    val progress = mutableListOf<WatchProgress>()
    latestBy(
        source.inProgress.filter { it.isInProgress() },
        { WatchKey(it.contentId, it.season, it.episode) },
        WatchProgress::lastWatched
    ).forEach { item ->
        val key = WatchKey(item.contentId, item.season, item.episode)
        val finishedInSource = sourceWatched.find(key).any { mark ->
            mark.watchedAt > 0L && mark.watchedAt >= item.lastWatched
        }
        val targetHasNewer = targetProgress.find(key).any { existing ->
            !isStrictlyNewer(item.lastWatched, existing.lastWatched)
        } || targetWatched.find(key).any { mark ->
            !isStrictlyNewer(item.lastWatched, mark.watchedAt)
        }
        if (finishedInSource || targetHasNewer || ServerItemRef.isServerId(item.contentId) ||
            (item.season == null) != (item.episode == null)
        ) {
            progressSkipped++
        } else {
            progress += item
        }
    }
    return TrackingImportPlan(watched, watchedSkipped, progress, progressSkipped)
}

/**
 * Whether [candidate] may replace [existing]. An unknown time (0) on either side never wins, so
 * data in the source of truth whose age can't be told is left alone.
 */
private fun isStrictlyNewer(candidate: Long, existing: Long): Boolean =
    candidate > 0L && existing > 0L && candidate > existing

private fun WatchedItem.isWholeSeriesMark(): Boolean =
    episode == null && !contentType.trim().lowercase().let { it == "movie" || it == "film" }

private fun <T> latestBy(items: List<T>, key: (T) -> WatchKey, time: (T) -> Long): List<T> {
    val index = TrackingImportIndex(emptyList(), key)
    val kept = mutableListOf<T>()
    items.sortedByDescending(time).forEach { item ->
        if (index.find(key(item)).isEmpty()) {
            index.add(item)
            kept += item
        }
    }
    return kept
}

private data class WatchKey(val contentId: String, val season: Int?, val episode: Int?)

/**
 * Looks titles up by any id they share: one source may know a show as `tt123` and another as
 * `tmdb:456` plus `imdb:tt123`, so each entry is indexed under every id its content id carries.
 */
private class TrackingImportIndex<T>(items: List<T>, private val key: (T) -> WatchKey) {
    private val byAlias = mutableMapOf<Triple<String, Int?, Int?>, MutableList<T>>()

    init {
        items.forEach(::add)
    }

    fun add(item: T) {
        val key = key(item)
        aliases(key.contentId).forEach { alias ->
            byAlias.getOrPut(Triple(alias, key.season, key.episode), ::mutableListOf) += item
        }
    }

    fun find(key: WatchKey): List<T> = aliases(key.contentId)
        .flatMap { alias -> byAlias[Triple(alias, key.season, key.episode)].orEmpty() }
        .distinct()
}

internal fun trackingImportAliases(contentId: String): Set<String> = aliases(contentId)

private fun aliases(contentId: String): Set<String> {
    val trimmed = contentId.trim()
    if (trimmed.isEmpty()) return emptySet()
    val ids = parseTrackingExternalIds(trimmed)
    return buildSet {
        add(trimmed.lowercase())
        ids.imdb?.takeIf(String::isNotBlank)?.let { add("imdb:${it.lowercase()}") }
        ids.tmdb?.let { add("tmdb:$it") }
        ids.tvdb?.takeIf(String::isNotBlank)?.let { add("tvdb:${it.lowercase()}") }
        ids.trakt?.let { add("trakt:$it") }
        ids.simkl?.let { add("simkl:$it") }
        ids.mal?.let { add("mal:$it") }
        ids.anidb?.let { add("anidb:$it") }
        ids.anilist?.let { add("anilist:$it") }
        ids.kitsu?.let { add("kitsu:$it") }
        ids.mdblist?.takeIf(String::isNotBlank)?.let { add("mdblist:${it.lowercase()}") }
    }
}

class TrackingImporter(
    private val batchSize: Int = DEFAULT_BATCH_SIZE
) {
    suspend fun import(
        source: TrackingImportReader,
        target: TrackingImportEndpoint
    ): TrackingImportResult {
        require(source.origin != target.origin) { "A source can't be imported into itself" }
        val unsupported = buildSet {
            if (TrackingCapability.WATCHED_READ !in source.capabilities) add(TrackingCapability.WATCHED_READ)
            if (TrackingCapability.PROGRESS_READ !in source.capabilities) add(TrackingCapability.PROGRESS_READ)
            if (TrackingCapability.WATCHED_WRITE !in target.capabilities) add(TrackingCapability.WATCHED_WRITE)
            if (TrackingCapability.PROGRESS_WRITE !in target.capabilities) add(TrackingCapability.PROGRESS_WRITE)
        }
        val canImportWatched = TrackingCapability.WATCHED_READ !in unsupported &&
            TrackingCapability.WATCHED_WRITE !in unsupported
        val canImportProgress = TrackingCapability.PROGRESS_READ !in unsupported &&
            TrackingCapability.PROGRESS_WRITE !in unsupported
        if (!canImportWatched && !canImportProgress) {
            return TrackingImportResult(source.origin, target.source, unsupported = unsupported)
        }

        val sourceSnapshot = source.read().let { snapshot ->
            TrackingImportSnapshot(
                watched = if (TrackingCapability.WATCHED_READ in source.capabilities) snapshot.watched else emptyList(),
                inProgress = if (TrackingCapability.PROGRESS_READ in source.capabilities) snapshot.inProgress else emptyList(),
                unreadable = snapshot.unreadable
            )
        }
        val plan = planTrackingImport(sourceSnapshot, target.read())

        val watchedSkippedUnsupported = if (canImportWatched) 0 else plan.watched.size
        val progressSkippedUnsupported = if (canImportProgress) 0 else plan.progress.size
        val watchedWrite = if (canImportWatched) writeInBatches(plan.watched, target::writeWatched) else TrackingImportWriteResult(0)
        val progressWrite = if (canImportProgress) writeInBatches(plan.progress, target::writeProgress) else TrackingImportWriteResult(0)
        if (watchedWrite.written > 0 || progressWrite.written > 0) {
            runCatching { target.finish() }.onFailure { if (it is CancellationException) throw it }
        }
        return TrackingImportResult(
            source = source.origin,
            target = target.source,
            watchedImported = watchedWrite.written,
            watchedSkipped = plan.watchedSkipped + watchedSkippedUnsupported,
            progressImported = progressWrite.written,
            progressSkipped = plan.progressSkipped + progressSkippedUnsupported,
            failed = watchedWrite.failed + progressWrite.failed,
            unsupported = unsupported,
            unreadable = sourceSnapshot.unreadable
        )
    }

    private suspend fun <T> writeInBatches(
        items: List<T>,
        write: suspend (List<T>) -> TrackingImportWriteResult
    ): TrackingImportWriteResult {
        var written = 0
        var failed = 0
        items.chunked(batchSize).forEach { batch ->
            try {
                val result = write(batch)
                val accepted = result.written.coerceIn(0, batch.size)
                written += accepted
                failed += batch.size - accepted
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failed += batch.size
            }
        }
        return TrackingImportWriteResult(written, failed)
    }

    companion object {
        const val DEFAULT_BATCH_SIZE = 50
    }
}
