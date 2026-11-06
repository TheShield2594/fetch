package com.fetch.desktop.sync

import com.fetch.desktop.data.JsonFile
import com.fetch.desktop.data.ProgressFile
import com.fetch.desktop.sync.SyncMerge.videoId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The sync server's watched-history and watch-progress calls, as [SyncClient] makes them. */
interface WatchRemote {
    /** The account these calls act for: server URL and user id ([WatchStateSync.accountKey]). */
    val account: String
    suspend fun watchedDeltaCursor(profileId: Int): Long
    suspend fun pullWatchedDelta(profileId: Int, sinceEventId: Long, limit: Int): List<RemoteWatchedEvent>
    suspend fun pullWatched(profileId: Int): List<RemoteWatchedItem>
    suspend fun pushWatched(profileId: Int, items: Collection<RemoteWatchedItem>)
    suspend fun deleteWatched(profileId: Int, keys: Collection<RemoteWatchedKey>)
    suspend fun progressDeltaCursor(profileId: Int): Long
    suspend fun pullProgressDelta(profileId: Int, sinceEventId: Long, limit: Int): List<RemoteProgressEvent>
    suspend fun pullProgress(profileId: Int): List<RemoteProgress>
    suspend fun pushProgress(profileId: Int, entries: Collection<RemoteProgress>)
    suspend fun deleteProgress(profileId: Int, keys: Collection<String>)
}

fun SyncClient.watchRemote(): WatchRemote = object : WatchRemote {
    private val client = this@watchRemote
    override val account = WatchStateSync.accountKey(client.server.baseUrl, client.session.userId)
    override suspend fun watchedDeltaCursor(profileId: Int) = client.watchedDeltaCursor(profileId)
    override suspend fun pullWatchedDelta(profileId: Int, sinceEventId: Long, limit: Int) = client.pullWatchedDelta(profileId, sinceEventId, limit)
    override suspend fun pullWatched(profileId: Int) = client.pullWatched(profileId)
    override suspend fun pushWatched(profileId: Int, items: Collection<RemoteWatchedItem>) = client.pushWatched(profileId, items)
    override suspend fun deleteWatched(profileId: Int, keys: Collection<RemoteWatchedKey>) = client.deleteWatched(profileId, keys)
    override suspend fun progressDeltaCursor(profileId: Int) = client.progressDeltaCursor(profileId)
    override suspend fun pullProgressDelta(profileId: Int, sinceEventId: Long, limit: Int) = client.pullProgressDelta(profileId, sinceEventId, limit)
    override suspend fun pullProgress(profileId: Int) = client.pullProgress(profileId)
    override suspend fun pushProgress(profileId: Int, entries: Collection<RemoteProgress>) = client.pushProgress(profileId, entries)
    override suspend fun deleteProgress(profileId: Int, keys: Collection<String>) = client.deleteProgress(profileId, keys)
}

/**
 * One profile's watched history and Continue Watching against the sync server ([WatchSync] has
 * the rules): the saved [WatchSyncState] and the pull, apply and push. [progress] reads the
 * profile's watch state; [write] replaces it and says whether it reached the disk.
 */
class WatchStateSync(
    private val stateFile: JsonFile<WatchSyncState>,
    private val progress: () -> ProgressFile,
    private val write: (ProgressFile) -> Boolean,
) {
    var state: WatchSyncState = stateFile.load()
        private set

    /** Held while pulling, applying and pushing, so a push can't race a pull. */
    private val mutex = Mutex()

    /**
     * Whether the last write of the watch state failed, so what is in memory may not be on disk.
     * A cursor can't move on until a write succeeds again: a retried pull that finds memory
     * already up to date must still save it, or a restart would lose what the cursor skipped.
     */
    private var unsaved = false

    /** Writes [file] when it [changed] or an earlier write failed; true once it is on disk. */
    private fun persist(file: ProgressFile, changed: Boolean): Boolean {
        if (!changed && !unsaved) return true
        unsaved = !write(file)
        return !unsaved
    }

    private fun save(updated: WatchSyncState) {
        if (updated == state) return
        state = updated
        runCatching { stateFile.save(updated) }
    }

    /**
     * Makes [signedInAs] (an [accountKey], null when signed out) the account changes are queued
     * for. Another account's cursors and pending changes don't apply to it; changes queued
     * before any account synced do. Done before a change is queued, so one made after signing in
     * to another account isn't queued for the old one and then dropped with it.
     */
    fun adopt(signedInAs: String?) {
        if (signedInAs == null || state.account == signedInAs) return
        save(WatchSyncState(account = signedInAs, pending = if (state.account == null) state.pending else PendingWatchOps()))
    }

    /** Whether changes made here are queued: once this profile synced with an account, or while signed in. */
    private fun queues(signedInAs: String?) = state.account != null || signedInAs != null

    /** Saves a change made on this computer and queues what the server should hear of it. */
    fun saveLocal(file: ProgressFile, signedInAs: String?, now: Long) {
        val before = progress()
        persist(file, changed = true)
        if (file === before) return
        adopt(signedInAs)
        if (queues(signedInAs)) queue { WatchSync.recordLocal(it, before, file, now) }
    }

    /**
     * Saves what Trakt, Simkl, the website or a media server reported, as [saveLocal] does, but
     * without marks the sync server dropped since ([WatchSync.dropTombstoned]).
     */
    fun saveImported(file: ProgressFile, signedInAs: String?, now: Long) {
        adopt(signedInAs)
        saveLocal(WatchSync.dropTombstoned(progress(), file, state.watchedTombstones), signedInAs, now)
    }

    /** Queues a change made here that [WatchSync.recordLocal] can't see from the file alone. */
    fun queueLocal(signedInAs: String?, change: (PendingWatchOps, WatchSyncState) -> PendingWatchOps) {
        adopt(signedInAs)
        if (queues(signedInAs)) queue { change(it, state) }
    }

    private fun queue(change: (PendingWatchOps) -> PendingWatchOps) {
        val pending = change(state.pending)
        if (pending != state.pending) save(state.copy(pending = pending))
    }

    /** Whether [remote]'s account has changes waiting to be sent. */
    fun hasPendingFor(remote: WatchRemote) = state.account == remote.account && !state.pending.isEmpty

    /**
     * The TV's watched-items and watch-progress sync: pull the server's delta logs from the saved
     * cursors (a full snapshot the first time, or when the log can't be read), apply them unless a
     * pending local change of the same key disagrees, then push the pending changes. A cursor
     * moves on only once what it covers is saved, so a failed write is pulled again next time.
     */
    suspend fun sync(remote: WatchRemote, profileId: Int) = mutex.withLock {
        adopt(remote.account)

        // Watched history.
        val watchedDelta = state.watchedCursor?.let { from -> deltaOrNull { pullWatchedEvents(remote, profileId, from) } }
        if (watchedDelta != null) {
            val (events, cursor) = watchedDelta
            val before = progress()
            val watched = WatchSync.applyWatchedDelta(before.watched, events, state.pending)
            val saved = persist(if (watched === before.watched) before else before.copy(watched = watched), changed = watched !== before.watched)
            // The server has an id when its last event in the batch is an upsert: an upsert and
            // then a delete of a tombstoned id leaves it tombstoned.
            val lastPerId = events.sortedBy { it.eventId }.associateBy { it.item.videoId() }
            val serverHas = lastPerId.filterValues { !it.deleted }.keys
            val tombstones = WatchSync.watchedTombstones(state.watchedTombstones, before.watched, watched, serverHas)
            save(if (saved) state.copy(watchedCursor = cursor, watchedTombstones = tombstones) else state)
        } else {
            val cursor = deltaOrNull { remote.watchedDeltaCursor(profileId) }
            val items = remote.pullWatched(profileId)
            val before = progress()
            val pull = WatchSync.applyWatchedSnapshot(before.watched, items, state.pending, !state.watchedInitialized, System.currentTimeMillis())
            val changed = pull.watched != before.watched
            val saved = persist(if (changed) before.copy(watched = pull.watched) else before, changed)
            val tombstones = WatchSync.watchedTombstones(state.watchedTombstones, before.watched, pull.watched, items.map { it.videoId() })
            save(
                if (saved) state.copy(watchedCursor = cursor, watchedInitialized = true, pending = pull.pending, watchedTombstones = tombstones)
                else state.copy(pending = pull.pending)
            )
        }

        // Continue Watching.
        val progressDelta = state.progressCursor?.let { from -> deltaOrNull { pullProgressEvents(remote, profileId, from) } }
        if (progressDelta != null) {
            val (events, cursor) = progressDelta
            val before = progress()
            val pull = WatchSync.applyProgressDelta(before, events, state.pending, state.remoteProgressKeys)
            val saved = persist(pull.file, changed = pull.file !== before)
            save(
                if (saved) state.copy(progressCursor = cursor, pending = pull.pending, remoteProgressKeys = pull.remoteKeys)
                else state.copy(pending = pull.pending)
            )
        } else {
            val cursor = deltaOrNull { remote.progressDeltaCursor(profileId) }
            val entries = remote.pullProgress(profileId)
            val before = progress()
            val pull = WatchSync.applyProgressSnapshot(before, entries, state.pending, state.remoteProgressKeys, !state.progressInitialized)
            val saved = persist(pull.file, changed = pull.file !== before)
            save(
                if (saved) state.copy(progressCursor = cursor, progressInitialized = true, pending = pull.pending, remoteProgressKeys = pull.remoteKeys)
                else state.copy(pending = pull.pending)
            )
        }

        pushPending(remote, profileId)
    }

    /** Sends the pending changes now (a mark or a removal by hand), when they are [remote]'s account's. */
    suspend fun push(remote: WatchRemote, profileId: Int) = mutex.withLock {
        if (state.account == remote.account) pushPending(remote, profileId)
    }

    /**
     * Sends the pending changes, deletes first, as the TV's pushToRemote does. Each batch leaves
     * the queue only once the server took it; after a failure the rest waits for the next sync.
     */
    private suspend fun pushPending(remote: WatchRemote, id: Int) {
        val pending = state.pending
        if (pending.isEmpty) return
        pending.watchedDeletes.chunked(PUSH_BATCH).forEach { batch ->
            remote.deleteWatched(id, batch.mapNotNull { videoId ->
                SyncMerge.watchedItem(videoId, 0L)?.let { RemoteWatchedKey(it.contentId, it.season, it.episode) }
            })
            save(state.copy(pending = state.pending.acknowledged(watchedDeletes = batch.toSet())))
        }
        pending.watchedUpserts.entries.chunked(PUSH_BATCH).forEach { batch ->
            remote.pushWatched(id, batch.mapNotNull { (videoId, at) -> SyncMerge.watchedItem(videoId, at) })
            save(state.copy(pending = state.pending.acknowledged(watchedUpserts = batch.associate { it.key to it.value })))
        }
        pending.progressDeletes.chunked(PUSH_BATCH).forEach { batch ->
            remote.deleteProgress(id, batch)
            save(state.copy(
                pending = state.pending.acknowledged(progressDeletes = batch.toSet()),
                remoteProgressKeys = state.remoteProgressKeys - batch.toSet(),
            ))
        }
        pending.progressUpserts.entries.chunked(PUSH_BATCH).forEach { batch ->
            remote.pushProgress(id, batch.map { (key, p) -> p.toRemote(key) })
            save(state.copy(
                pending = state.pending.acknowledged(progressUpserts = batch.associate { it.key to it.value }),
                remoteProgressKeys = state.remoteProgressKeys + batch.map { it.key },
            ))
        }
    }

    /** A delta read, or null when the server can't give one (then a snapshot is taken, as on TV). */
    private suspend fun <T> deltaOrNull(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: SyncAuthException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private suspend fun pullWatchedEvents(remote: WatchRemote, id: Int, from: Long): Pair<List<RemoteWatchedEvent>, Long> {
        val events = mutableListOf<RemoteWatchedEvent>()
        var cursor = from
        while (true) {
            val page = remote.pullWatchedDelta(id, cursor, DELTA_PAGE)
            if (page.isEmpty()) break
            events += page
            cursor = maxOf(cursor, page.maxOf { it.eventId })
            if (page.size < DELTA_PAGE) break
        }
        return events to cursor
    }

    private suspend fun pullProgressEvents(remote: WatchRemote, id: Int, from: Long): Pair<List<RemoteProgressEvent>, Long> {
        val events = mutableListOf<RemoteProgressEvent>()
        var cursor = from
        while (true) {
            val page = remote.pullProgressDelta(id, cursor, DELTA_PAGE)
            if (page.isEmpty()) break
            events += page
            cursor = maxOf(cursor, page.maxOf { it.eventId })
            if (page.size < DELTA_PAGE) break
        }
        return events to cursor
    }

    companion object {
        /** The TV's delta page size for watched items and progress. */
        const val DELTA_PAGE = 900
        const val PUSH_BATCH = 500

        fun accountKey(baseUrl: String, userId: String) = "$baseUrl|$userId"
    }
}
