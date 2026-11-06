package com.fetch.desktop.sync

import com.fetch.desktop.data.JsonFile
import com.fetch.desktop.data.ProgressEntry
import com.fetch.desktop.data.ProgressFile
import com.fetch.desktop.sync.SyncMerge.videoId
import com.fetch.tv.data.mediaserver.ServerItemRef
import com.fetch.tv.domain.model.WatchProgress
import kotlinx.serialization.Serializable
import java.io.File

/** A progress upsert waiting to go to the sync server, as [RemoteProgress] holds it. */
@Serializable
data class PendingProgress(
    val contentId: String,
    val contentType: String,
    val videoId: String,
    val season: Int? = null,
    val episode: Int? = null,
    val positionMs: Long,
    val durationMs: Long,
    val lastWatched: Long,
) {
    fun toRemote(key: String) = RemoteProgress(contentId, contentType, videoId, season, episode, positionMs, durationMs, lastWatched, key)

    companion object {
        fun of(p: RemoteProgress) = PendingProgress(p.contentId, p.contentType, p.videoId, p.season, p.episode, p.positionMs, p.durationMs, p.lastWatchedEpochMs)
    }
}

/**
 * This profile's watch-state changes not yet on the sync server, the TV's
 * `WatchStateMutationStore`: an upsert of a key drops a pending delete of it and the other way
 * round. Watched items are keyed by Cinemeta video id ("tt1", "tt1:2:5"), progress by the
 * server's progress key ("tt1", "tt1_s2e5").
 */
@Serializable
data class PendingWatchOps(
    /** Video id to the time it was marked watched. */
    val watchedUpserts: Map<String, Long> = emptyMap(),
    val watchedDeletes: Set<String> = emptySet(),
    val progressUpserts: Map<String, PendingProgress> = emptyMap(),
    val progressDeletes: Set<String> = emptySet(),
) {
    val isEmpty: Boolean
        get() = watchedUpserts.isEmpty() && watchedDeletes.isEmpty() && progressUpserts.isEmpty() && progressDeletes.isEmpty()

    fun watchedUpsert(videoId: String, at: Long) = copy(watchedUpserts = watchedUpserts + (videoId to at), watchedDeletes = watchedDeletes - videoId)
    fun watchedDelete(videoId: String) = copy(watchedUpserts = watchedUpserts - videoId, watchedDeletes = watchedDeletes + videoId)
    fun progressUpsert(key: String, p: PendingProgress) = copy(progressUpserts = progressUpserts + (key to p), progressDeletes = progressDeletes - key)
    fun progressDelete(keys: Collection<String>) = copy(progressUpserts = progressUpserts - keys.toSet(), progressDeletes = progressDeletes + keys)

    /** Drops what was sent, unless it was changed again while it was on its way. */
    fun acknowledged(
        watchedUpserts: Map<String, Long> = emptyMap(),
        watchedDeletes: Set<String> = emptySet(),
        progressUpserts: Map<String, PendingProgress> = emptyMap(),
        progressDeletes: Set<String> = emptySet(),
    ) = PendingWatchOps(
        watchedUpserts = this.watchedUpserts.filterNot { (id, at) -> watchedUpserts[id] == at },
        watchedDeletes = this.watchedDeletes - watchedDeletes,
        progressUpserts = this.progressUpserts.filterNot { (key, p) -> progressUpserts[key] == p },
        progressDeletes = this.progressDeletes - progressDeletes,
    )
}

/**
 * How far this profile's watched history and Continue Watching are synced with the account, kept
 * in the profile's folder (`watch-sync.json`). The cursors are the last event ids pulled from the
 * server's delta logs, as the TV keeps them per profile; null means take a full snapshot first.
 */
@Serializable
data class WatchSyncState(
    /** The account this belongs to (server URL and user id); another account starts over. */
    val account: String? = null,
    val watchedCursor: Long? = null,
    val progressCursor: Long? = null,
    /** Whether a first snapshot was taken, so later snapshots (no delta log) can remove items. */
    val watchedInitialized: Boolean = false,
    val progressInitialized: Boolean = false,
    val pending: PendingWatchOps = PendingWatchOps(),
    /**
     * Every progress key the server holds for this profile as far as we know, so removing a title
     * from Continue Watching deletes all of its keys, as the TV's `resolveRemoteDeleteKeys` does.
     */
    val remoteProgressKeys: Set<String> = emptySet(),
)

fun watchSyncFile(dir: File) = JsonFile(File(dir, "watch-sync.json"), WatchSyncState.serializer()) { WatchSyncState() }

data class WatchedPull(val watched: List<String>, val pending: PendingWatchOps)

data class ProgressPull(val file: ProgressFile, val pending: PendingWatchOps, val remoteKeys: Set<String>)

/**
 * Watched history and Continue Watching against the sync server, the way the TV's
 * `WatchedItemsSyncService` and `WatchProgressSyncService` do it:
 * - local changes are queued ([recordLocal], [queueRemoval]) and only those are pushed;
 * - the server's changes come from its delta logs (`sync_pull_*_delta`), whose events include
 *   deletes, and apply here unless a pending local change of the same key says otherwise;
 * - without a cursor a full snapshot is taken, and on a later snapshot anything the server no
 *   longer has goes here too, unless it has a pending change.
 * So a deletion on another device is never pushed back, and one made here is retried until sent.
 */
object WatchSync {

    /** The progress key the TV uses for [entry]: `<id>_s<season>e<episode>` or `<id>`. */
    fun keyOf(entry: ProgressEntry): String =
        if (entry.season != null && entry.episode != null) "${entry.contentId}_s${entry.season}e${entry.episode}" else entry.contentId

    /** Local entries that are this app's own positions, the ones that sync, by progress key. */
    fun progressView(file: ProgressFile): Map<String, PendingProgress> = file.entries
        .filter { it.source == null && !it.upNext && !it.hidden && it.positionMs > 0 && it.durationMs > 0 && !ServerItemRef.isServerId(it.contentId) }
        .associate { keyOf(it) to PendingProgress(it.contentId, it.contentType, it.videoId, it.season, it.episode, it.positionMs, it.durationMs, it.lastWatched) }

    /** Whether [videoId] is a watched id the server can hold and give back the same. */
    fun syncableWatchedId(videoId: String): Boolean =
        !ServerItemRef.isServerId(videoId) && SyncMerge.watchedItem(videoId, 0L)?.videoId() == videoId

    /**
     * Queues what changed between [before] and [after], a write made on this computer: watched
     * marks added or removed, positions saved, and a title that left Continue Watching because
     * its video was finished (sent at 100%, as the TV's `markAsCompleted` does). Removing a title
     * by hand is [queueRemoval]'s.
     */
    fun recordLocal(pending: PendingWatchOps, before: ProgressFile, after: ProgressFile, now: Long): PendingWatchOps {
        var result = pending
        if (before.watched != after.watched) {
            val was = before.watched.toSet()
            val isNow = after.watched.toSet()
            (isNow - was).filter(::syncableWatchedId).forEach { result = result.watchedUpsert(it, now) }
            (was - isNow).filter(::syncableWatchedId).forEach { result = result.watchedDelete(it) }
        }
        if (before.entries != after.entries) {
            val was = progressView(before)
            val isNow = progressView(after)
            isNow.forEach { (key, p) -> if (was[key] != p) result = result.progressUpsert(key, p) }
            val watched = after.watched.toSet()
            was.forEach { (key, p) ->
                if (key !in isNow && p.videoId in watched) {
                    result = result.progressUpsert(key, p.copy(positionMs = p.durationMs, lastWatched = now))
                }
            }
        }
        return result
    }

    /**
     * "Remove from Continue Watching": deletes every key the server holds for the title, the
     * title's own key and its entry's, as the TV's `removeProgress` does.
     */
    fun queueRemoval(pending: PendingWatchOps, entry: ProgressEntry, remoteKeys: Set<String>): PendingWatchOps {
        if (ServerItemRef.isServerId(entry.contentId)) return pending
        val id = entry.contentId
        val keys = remoteKeys.filter { it == id || it.startsWith("${id}_") } + keyOf(entry) + id
        return pending.progressDelete(keys.distinct())
    }

    // --- Watched history from the server ---

    /** Delta events, oldest first, applied unless a pending change of the same id disagrees. */
    fun applyWatchedDelta(watched: List<String>, events: List<RemoteWatchedEvent>, pending: PendingWatchOps): List<String> {
        val result = LinkedHashSet(watched)
        events.sortedBy { it.eventId }.forEach { event ->
            val id = event.item.videoId()
            if (event.deleted) {
                if (id !in pending.watchedUpserts) result.remove(id)
            } else {
                if (id !in pending.watchedDeletes) result.add(id)
            }
        }
        return if (result.size == watched.size && result.containsAll(watched)) watched else result.toList()
    }

    /**
     * A full list from the server. The first one ([initial]) only adds: local ids the server
     * lacks are queued to go up, as before this was tracked. A later one replaces the local list
     * (the TV's `replaceWithRemoteItems`), keeping pending marks and ids that never sync.
     */
    fun applyWatchedSnapshot(
        watched: List<String>,
        remote: List<RemoteWatchedItem>,
        pending: PendingWatchOps,
        initial: Boolean,
        now: Long,
    ): WatchedPull {
        val remoteIds = remote.map { it.videoId() }.filter { it !in pending.watchedDeletes }
        val remoteSet = remoteIds.toSet()
        if (initial) {
            var queued = pending
            watched.filter { it !in remoteSet && syncableWatchedId(it) && it !in pending.watchedUpserts }
                .forEach { queued = queued.watchedUpsert(it, now) }
            return WatchedPull((watched + remoteIds).distinct(), queued)
        }
        val kept = watched.filter { it in remoteSet || it in pending.watchedUpserts || !syncableWatchedId(it) }
        return WatchedPull((kept + remoteIds).distinct(), pending)
    }

    // --- Continue Watching from the server ---

    /** Delta events, the last one per key winning, as the TV collapses each page. */
    fun applyProgressDelta(
        file: ProgressFile,
        events: List<RemoteProgressEvent>,
        pending: PendingWatchOps,
        remoteKeys: Set<String>,
    ): ProgressPull {
        val last = LinkedHashMap<String, RemoteProgressEvent>()
        events.sortedBy { it.eventId }.forEach { last[it.progress.key] = it }
        val deletes = last.filterValues { it.deleted }.keys
        val upserts = last.values.filter { !it.deleted }.map { it.progress }
        return ProgressPull(
            file = applyProgress(file, upserts, deletes, pending),
            pending = pending,
            remoteKeys = remoteKeys - deletes + upserts.map { it.key },
        )
    }

    /**
     * The server's full progress list. Keys it held at the last pull ([remoteKeys]) that are gone
     * now were deleted elsewhere. On the [initial] one, local positions the server lacks or has
     * older are queued to go up.
     */
    fun applyProgressSnapshot(
        file: ProgressFile,
        remote: List<RemoteProgress>,
        pending: PendingWatchOps,
        remoteKeys: Set<String>,
        initial: Boolean,
    ): ProgressPull {
        val byKey = remote.groupBy { it.key }.mapValues { (_, list) -> list.maxBy { it.lastWatchedEpochMs } }
        val deletes = remoteKeys - byKey.keys
        val merged = applyProgress(file, byKey.values.toList(), deletes, pending)
        var queued = pending
        if (initial) {
            progressView(merged).forEach { (key, p) ->
                val theirs = byKey[key]
                if (key !in pending.progressDeletes && (theirs == null || theirs.lastWatchedEpochMs < p.lastWatched)) {
                    queued = queued.progressUpsert(key, p)
                }
            }
        }
        return ProgressPull(merged, queued, byKey.keys)
    }

    /**
     * Applies the server's progress changes to Continue Watching (one entry per title here):
     * - a delete removes this app's entry with that key, unless it has a pending upsert;
     * - a finished position (90% or more) newer than the entry with that key removes it;
     * - another position becomes the title's entry when it is newer than the one here (a removed
     *   title comes back on newer activity elsewhere), unless that one has a pending upsert or the
     *   key has a pending delete.
     */
    private fun applyProgress(file: ProgressFile, upserts: List<RemoteProgress>, deletes: Set<String>, pending: PendingWatchOps): ProgressFile {
        fun ownKey(entry: ProgressEntry) = entry.source == null && !entry.upNext
        var entries = file.entries
        deletes.filter { it !in pending.progressUpserts }.toSet().takeIf { it.isNotEmpty() }?.let { gone ->
            entries = entries.filterNot { ownKey(it) && keyOf(it) in gone }
        }
        val usable = upserts.filter { it.key !in pending.progressDeletes && it.positionMs > 0 && it.durationMs > 0 && !ServerItemRef.isServerId(it.contentId) }
        val (finished, inProgress) = usable.partition { it.positionMs.toFloat() / it.durationMs >= WatchProgress.COMPLETED_THRESHOLD }
        finished.forEach { p ->
            entries = entries.filterNot {
                it.contentId == p.contentId && ownKey(it) && keyOf(it) == p.key &&
                    it.lastWatched < p.lastWatchedEpochMs && keyOf(it) !in pending.progressUpserts
            }
        }
        val known = entries.associateBy { it.contentId }
        SyncMerge.progressEntries(inProgress, known).forEach { incoming ->
            val local = entries.firstOrNull { it.contentId == incoming.contentId }
            if (local != null && ownKey(local) && !local.hidden && keyOf(local) in pending.progressUpserts) return@forEach
            if (local == null || incoming.lastWatched > local.lastWatched) {
                entries = entries.filter { it.contentId != incoming.contentId } + incoming
            }
        }
        return if (entries == file.entries) file else file.copy(entries = entries)
    }
}
