package com.fetch.desktop.sync

import com.fetch.desktop.data.ProgressFile
import com.fetch.desktop.sync.SyncMerge.videoId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class WatchStateSyncTest {

    /** A sync server holding one profile's watched history and positions, with delta logs. */
    private class FakeRemote(override val account: String) : WatchRemote {
        val watchedEvents = mutableListOf<RemoteWatchedEvent>()
        val progressEvents = mutableListOf<RemoteProgressEvent>()
        val pushedWatched = mutableListOf<String>()
        val deletedWatched = mutableListOf<RemoteWatchedKey>()
        val pushedProgress = mutableListOf<RemoteProgress>()
        val deletedProgress = mutableListOf<String>()
        val watchedDeltaReads = mutableListOf<Long>()
        val progressDeltaReads = mutableListOf<Long>()

        private val cursor get() = (watchedEvents.map { it.eventId } + progressEvents.map { it.eventId }).maxOrNull() ?: 0L

        override suspend fun watchedDeltaCursor(profileId: Int) = cursor
        override suspend fun pullWatchedDelta(profileId: Int, sinceEventId: Long, limit: Int): List<RemoteWatchedEvent> {
            watchedDeltaReads += sinceEventId
            return watchedEvents.filter { it.eventId > sinceEventId }.take(limit)
        }
        override suspend fun pullWatched(profileId: Int) = emptyList<RemoteWatchedItem>()
        override suspend fun pushWatched(profileId: Int, items: Collection<RemoteWatchedItem>) { pushedWatched += items.map { it.videoId() } }
        override suspend fun deleteWatched(profileId: Int, keys: Collection<RemoteWatchedKey>) { deletedWatched += keys }
        override suspend fun progressDeltaCursor(profileId: Int) = cursor
        override suspend fun pullProgressDelta(profileId: Int, sinceEventId: Long, limit: Int): List<RemoteProgressEvent> {
            progressDeltaReads += sinceEventId
            return progressEvents.filter { it.eventId > sinceEventId }.take(limit)
        }
        override suspend fun pullProgress(profileId: Int) = emptyList<RemoteProgress>()
        override suspend fun pushProgress(profileId: Int, entries: Collection<RemoteProgress>) { pushedProgress += entries }
        override suspend fun deleteProgress(profileId: Int, keys: Collection<String>) { deletedProgress += keys }
    }

    /** The profile's watch state as AppState keeps it: in memory, with a disk write that can fail. */
    private class Profile(start: ProgressFile, state: WatchSyncState) {
        var progress = start
        var disk = start
        var writes = true
        val file = watchSyncFile(Files.createTempDirectory("watch-sync").toFile()).also { it.save(state) }
        val sync = WatchStateSync(file, progress = { progress }, write = { f ->
            progress = f
            if (writes) disk = f
            writes
        })
    }

    private val accountA = WatchStateSync.accountKey("https://a.example.com", "user-a")
    private val accountB = WatchStateSync.accountKey("https://b.example.com", "user-b")

    /** Synced with [accountA] before, so the next pull reads the delta logs from event 1. */
    private fun syncedState(account: String = accountA, pending: PendingWatchOps = PendingWatchOps()) = WatchSyncState(
        account = account, watchedCursor = 1, progressCursor = 1, watchedInitialized = true, progressInitialized = true, pending = pending,
    )

    private fun watchedEvent(eventId: Long, videoId: String, deleted: Boolean = false) =
        RemoteWatchedEvent(eventId, deleted, SyncMerge.watchedItem(videoId, now = eventId)!!)

    @Test
    fun `a mark removed on another device is applied here and never sent back`() = runBlocking {
        val profile = Profile(ProgressFile(watched = listOf("tt1:1:5", "tt1:1:6")), syncedState())
        val remote = FakeRemote(accountA).apply { watchedEvents += watchedEvent(7, "tt1:1:5", deleted = true) }

        profile.sync.sync(remote, profileId = 1)
        assertEquals(listOf("tt1:1:6"), profile.disk.watched)
        assertTrue(profile.sync.state.pending.isEmpty)
        assertTrue(remote.pushedWatched.isEmpty() && remote.deletedWatched.isEmpty())

        // A later change made here sends only itself, not the mark the server dropped.
        profile.sync.saveLocal(profile.progress.copy(watched = profile.progress.watched + "tt2"), accountA, now = 9)
        profile.sync.push(remote, profileId = 1)
        assertEquals(listOf("tt2"), remote.pushedWatched)
        assertTrue(remote.deletedWatched.isEmpty())
    }

    @Test
    fun `a change made after signing in to another account is queued for it and sent`() = runBlocking {
        // Account A's change is still pending when account B signs in.
        val profile = Profile(ProgressFile(watched = listOf("tt1", "tt2")), syncedState(pending = PendingWatchOps().watchedUpsert("tt9", 1)))
        // An un-mark made under B before B's first sync.
        profile.sync.saveLocal(profile.progress.copy(watched = listOf("tt2")), accountB, now = 5)
        assertEquals(accountB, profile.sync.state.account)
        assertEquals(PendingWatchOps().watchedDelete("tt1"), profile.sync.state.pending)
        assertNull(profile.sync.state.watchedCursor)

        // B's first sync (a snapshot that still lists tt1) keeps it un-marked and sends the delete.
        val remote = object : WatchRemote by FakeRemote(accountB) {
            override suspend fun pullWatched(profileId: Int) = listOf(SyncMerge.watchedItem("tt1", 1)!!)
        }
        val deleted = mutableListOf<RemoteWatchedKey>()
        val recording = object : WatchRemote by remote {
            override suspend fun deleteWatched(profileId: Int, keys: Collection<RemoteWatchedKey>) { deleted += keys }
        }
        profile.sync.sync(recording, profileId = 1)
        assertEquals(listOf("tt2"), profile.progress.watched)
        assertEquals(listOf(RemoteWatchedKey("tt1")), deleted)
        assertTrue(profile.sync.state.pending.isEmpty)
    }

    @Test
    fun `a cursor moves on only once what it covers is saved`() = runBlocking {
        val profile = Profile(ProgressFile(watched = listOf("tt1", "tt2")), syncedState())
        val remote = FakeRemote(accountA).apply {
            watchedEvents += watchedEvent(7, "tt1", deleted = true)
            progressEvents += RemoteProgressEvent(8, false, RemoteProgress("tt3", "movie", "tt3", positionMs = 300, durationMs = 1000, lastWatchedEpochMs = 8))
        }
        profile.writes = false
        profile.sync.sync(remote, profileId = 1)
        assertEquals(1L, profile.sync.state.watchedCursor)
        assertEquals(1L, profile.sync.state.progressCursor)
        assertEquals(listOf("tt1", "tt2"), profile.disk.watched)

        // After a restart (the disk's copy), the same events are pulled again and saved.
        profile.progress = profile.disk
        profile.writes = true
        val restarted = WatchStateSync(profile.file, progress = { profile.progress }, write = { f -> profile.progress = f; profile.disk = f; true })
        restarted.sync(remote, profileId = 1)
        assertEquals(listOf(1L, 1L), remote.watchedDeltaReads)
        assertEquals(listOf("tt2"), profile.disk.watched)
        assertEquals(listOf("tt3"), profile.disk.entries.map { it.contentId })
        assertEquals(7L, restarted.state.watchedCursor)
        assertEquals(8L, restarted.state.progressCursor)
    }

    @Test
    fun `an import from Trakt doesn't undo a mark removed on the sync server`() = runBlocking {
        val profile = Profile(ProgressFile(watched = listOf("tt1:1:5", "tt1:1:6")), syncedState())
        val remote = FakeRemote(accountA).apply { watchedEvents += watchedEvent(7, "tt1:1:5", deleted = true) }
        profile.sync.sync(remote, profileId = 1)

        // Trakt still lists it watched, and has one more.
        profile.sync.saveImported(profile.progress.copy(watched = listOf("tt1:1:6", "tt1:1:5", "tt4")), accountA, now = 9)
        assertEquals(listOf("tt1:1:6", "tt4"), profile.progress.watched)
        assertEquals(setOf("tt4"), profile.sync.state.pending.watchedUpserts.keys)
    }

    @Test
    fun `a failed write retried in the same run saves before the cursor moves on`() = runBlocking {
        val profile = Profile(ProgressFile(watched = listOf("tt1", "tt2")), syncedState())
        val remote = FakeRemote(accountA).apply {
            watchedEvents += watchedEvent(7, "tt1", deleted = true)
            progressEvents += RemoteProgressEvent(8, false, RemoteProgress("tt3", "movie", "tt3", positionMs = 300, durationMs = 1000, lastWatchedEpochMs = 8))
        }
        profile.writes = false
        profile.sync.sync(remote, profileId = 1)
        // Memory has the events; the disk doesn't.
        assertEquals(listOf("tt2"), profile.progress.watched)
        assertEquals(listOf("tt1", "tt2"), profile.disk.watched)

        // Retried in the same run while the disk still fails: memory already matches the
        // replayed events, but nothing reached the disk, so the cursors stay.
        profile.sync.sync(remote, profileId = 1)
        assertEquals(1L, profile.sync.state.watchedCursor)
        assertEquals(1L, profile.sync.state.progressCursor)

        // The disk works again: the retry saves what memory holds before moving on.
        profile.writes = true
        profile.sync.sync(remote, profileId = 1)
        assertEquals(listOf("tt2"), profile.disk.watched)
        assertEquals(listOf("tt3"), profile.disk.entries.map { it.contentId })
        assertEquals(7L, profile.sync.state.watchedCursor)
        assertEquals(8L, profile.sync.state.progressCursor)

        // A restart from the disk's copies has the events, and nothing past them is skipped.
        val restarted = WatchStateSync(profile.file, progress = { profile.disk }, write = { true })
        assertEquals(7L, restarted.state.watchedCursor)
        assertEquals(listOf("tt2"), profile.disk.watched)
    }

    @Test
    fun `an upsert then a delete in one batch leaves the mark tombstoned`() = runBlocking {
        val profile = Profile(ProgressFile(watched = listOf("tt1:1:5", "tt1:1:6")), syncedState())
        val remote = FakeRemote(accountA).apply { watchedEvents += watchedEvent(7, "tt1:1:5", deleted = true) }
        profile.sync.sync(remote, profileId = 1)
        assertEquals(setOf("tt1:1:5"), profile.sync.state.watchedTombstones)

        // Marked and un-marked again elsewhere, both in the next batch: the delete is what stands.
        remote.watchedEvents += watchedEvent(8, "tt1:1:5")
        remote.watchedEvents += watchedEvent(9, "tt1:1:5", deleted = true)
        profile.sync.sync(remote, profileId = 1)
        assertEquals(listOf("tt1:1:6"), profile.progress.watched)
        assertEquals(setOf("tt1:1:5"), profile.sync.state.watchedTombstones)

        // So Trakt still listing it doesn't mark it again or send it back.
        profile.sync.saveImported(profile.progress.copy(watched = listOf("tt1:1:6", "tt1:1:5")), accountA, now = 10)
        assertEquals(listOf("tt1:1:6"), profile.progress.watched)
        assertTrue(profile.sync.state.pending.isEmpty)
    }

    @Test
    fun `a sync of the old account that resumes after another signs in sends and drops nothing of the new one`() = runBlocking {
        val profile = Profile(ProgressFile(watched = listOf("tt1", "tt2")), syncedState(pending = PendingWatchOps().watchedUpsert("tt9", 1)))
        val pulling = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val serverA = FakeRemote(accountA)
        // A's server answers its delta pull only once B has signed in and queued a change.
        val slowA = object : WatchRemote by serverA {
            override suspend fun pullWatchedDelta(profileId: Int, sinceEventId: Long, limit: Int): List<RemoteWatchedEvent> {
                pulling.complete(Unit)
                resume.await()
                return serverA.pullWatchedDelta(profileId, sinceEventId, limit)
            }
        }
        val syncA = launch { profile.sync.sync(slowA, profileId = 1) }
        pulling.await()
        profile.sync.saveLocal(profile.progress.copy(watched = listOf("tt2")), accountB, now = 5)
        resume.complete(Unit)
        syncA.join()

        // Nothing went to A's server, and B's un-mark is still queued, for B.
        assertTrue(serverA.deletedWatched.isEmpty() && serverA.pushedWatched.isEmpty())
        assertEquals(accountB, profile.sync.state.account)
        assertEquals(PendingWatchOps().watchedDelete("tt1"), profile.sync.state.pending)
        assertNull(profile.sync.state.watchedCursor)
    }
}
