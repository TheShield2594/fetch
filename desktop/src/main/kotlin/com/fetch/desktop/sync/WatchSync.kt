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
    /**
     * Watched ids the server dropped (un-marked on another device) that were marked here, so an
     * import from Trakt, Simkl or the website that still lists one doesn't mark it again and send
     * it back ([WatchSync.dropTombstoned]). An id leaves when the server has it again.
     */
    val watchedTombstones: Set<String> = emptySet(),
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

    /** The title a progress key belongs to: `tt1` for `tt1_s2e5` and for `tt1`. */
    fun contentIdOfKey(key: String): String = EPISODE_KEY.matchEntire(key)?.groupValues?.get(1) ?: key

    private val EPISODE_KEY = Regex("^(.+)_s\\d+e\\d+$")

    /**
     * Queues what changed between [before] and [after], a write made on this computer: watched
     * marks added or removed, positions saved, and a title that left Continue Watching because
     * its video was just marked watched (sent at 100%, as the TV's `markAsCompleted` does).
     * Removing a title by hand is [queueRemoval]'s, starting over [queueStartOver]'s and a
     * playback that reached its end [queueCompleted]'s.
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
            // Only a video that became watched in this write: an entry cleared for any other reason
            // (starting over an episode watched before) isn't a completion.
            val newlyWatched = after.watched.toSet() - before.watched.toSet()
            was.forEach { (key, p) ->
                if (key !in isNow && p.videoId in newlyWatched) {
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

    /**
     * A video played to its end here: its key at 100%, as the TV's `markAsCompleted` sends it,
     * whether or not a position of it had been saved before (one that ended in a single jump
     * past 90% never had one).
     */
    fun queueCompleted(pending: PendingWatchOps, p: PendingProgress): PendingWatchOps {
        if (ServerItemRef.isServerId(p.contentId) || p.durationMs <= 0) return pending
        val key = if (p.season != null && p.episode != null) "${p.contentId}_s${p.season}e${p.episode}" else p.contentId
        return pending.progressUpsert(key, p.copy(positionMs = p.durationMs))
    }

    /**
     * "Start over": the cleared position goes from the server too (and a pending upsert of it is
     * dropped), so a later snapshot doesn't bring it back to Continue Watching.
     */
    fun queueStartOver(pending: PendingWatchOps, entry: ProgressEntry): PendingWatchOps {
        if (entry.source != null || entry.upNext || ServerItemRef.isServerId(entry.contentId)) return pending
        return pending.progressDelete(listOf(keyOf(entry)))
    }

    /**
     * [imported], a merge of Trakt, Simkl or website watch state into [current], without the
     * marks it adds back that the sync server dropped ([WatchSyncState.watchedTombstones]): the
     * server's deletion stands instead of being undone and pushed back as a local mark.
     */
    fun dropTombstoned(current: ProgressFile, imported: ProgressFile, tombstones: Set<String>): ProgressFile {
        if (tombstones.isEmpty() || imported.watched == current.watched) return imported
        val had = current.watched.toSet()
        val blocked = imported.watched.filter { it !in had && it in tombstones }.toSet()
        return if (blocked.isEmpty()) imported else imported.copy(watched = imported.watched.filter { it !in blocked })
    }

    /**
     * [tombstones] after a pull took [before] to [after]: local marks the server dropped join,
     * and ids the server has ([serverHas]) or that are marked here again leave.
     */
    fun watchedTombstones(tombstones: Set<String>, before: List<String>, after: List<String>, serverHas: Collection<String>): Set<String> {
        val now = after.toSet()
        return tombstones - serverHas.toSet() - now + (before.toSet() - now).filter(::syncableWatchedId)
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
        val (merged, queued) = applyProgress(file, upserts, deletes, pending)
        return ProgressPull(
            file = merged,
            pending = queued,
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
        val (merged, applied) = applyProgress(file, byKey.values.toList(), deletes, pending)
        var queued = applied
        if (initial) {
            progressView(merged).forEach { (key, p) ->
                val theirs = byKey[key]
                if (key !in queued.progressDeletes && (theirs == null || theirs.lastWatchedEpochMs < p.lastWatched)) {
                    queued = queued.progressUpsert(key, p)
                }
            }
        }
        return ProgressPull(merged, queued, byKey.keys)
    }

    /**
     * Applies the server's progress changes to Continue Watching (one entry per title here):
     * - a delete removes this app's entry with that key, unless it has a pending upsert, and the
     *   title's generated Up Next, unless something of the title is pending here (the title was
     *   removed elsewhere after an episode finished here was sent);
     * - a title removed here (its hidden entry, stamped with the removal time) keeps positions
     *   from before the removal out, and they are queued for deletion: the server deletes exact
     *   keys, and one not known when the title was removed would otherwise bring it back;
     * - a finished position (90% or more) newer than the entry with that key removes it;
     * - the title's newest position becomes its entry when it is in progress and newer than the
     *   one here (a removed title comes back on newer activity elsewhere), unless that one has a
     *   pending upsert or the key has a pending delete. A newer finished one keeps an older
     *   unfinished episode from becoming the entry.
     */
    private fun applyProgress(
        file: ProgressFile,
        upserts: List<RemoteProgress>,
        deletes: Set<String>,
        pending: PendingWatchOps,
    ): Pair<ProgressFile, PendingWatchOps> {
        fun ownKey(entry: ProgressEntry) = entry.source == null && !entry.upNext
        fun finished(p: RemoteProgress) = p.positionMs.toFloat() / p.durationMs >= WatchProgress.COMPLETED_THRESHOLD
        var entries = file.entries
        var queued = pending
        val gone = deletes.filter { it !in pending.progressUpserts }.toSet()
        if (gone.isNotEmpty()) {
            val titles = gone.map(::contentIdOfKey).toSet()
            val pendingTitles = pending.progressUpserts.keys.map(::contentIdOfKey).toSet()
            entries = entries.filterNot {
                // A hidden entry is the record of a removal here; it stays.
                (ownKey(it) && !it.hidden && keyOf(it) in gone) ||
                    (it.source == null && it.upNext && it.contentId in titles && it.contentId !in pendingTitles)
            }
        }
        val usable = upserts.filter { it.key !in pending.progressDeletes && it.positionMs > 0 && it.durationMs > 0 && !ServerItemRef.isServerId(it.contentId) }
        usable.forEach { p ->
            val removed = entries.firstOrNull { it.contentId == p.contentId && it.hidden }
            if (removed != null && p.lastWatchedEpochMs <= removed.lastWatched) queued = queued.progressDelete(listOf(p.key))
        }
        usable.filter(::finished).forEach { p ->
            entries = entries.filterNot {
                it.contentId == p.contentId && ownKey(it) && keyOf(it) == p.key &&
                    it.lastWatched < p.lastWatchedEpochMs && keyOf(it) !in pending.progressUpserts
            }
        }
        val newest = usable.groupBy { it.contentId }.values.map { list -> list.maxBy { it.lastWatchedEpochMs } }.filterNot(::finished)
        val known = entries.associateBy { it.contentId }
        SyncMerge.progressEntries(newest, known).forEach { incoming ->
            val local = entries.firstOrNull { it.contentId == incoming.contentId }
            if (local != null && ownKey(local) && !local.hidden && keyOf(local) in pending.progressUpserts) return@forEach
            if (local == null || incoming.lastWatched > local.lastWatched) {
                entries = entries.filter { it.contentId != incoming.contentId } + incoming
            }
        }
        return (if (entries == file.entries) file else file.copy(entries = entries)) to queued
    }
}
