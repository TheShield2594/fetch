package com.fetch.desktop.sync

import com.fetch.desktop.data.ProgressEntry
import com.fetch.desktop.data.ProgressFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchSyncTest {

    private fun watchedEvent(eventId: Long, videoId: String, deleted: Boolean = false) =
        RemoteWatchedEvent(eventId, deleted, SyncMerge.watchedItem(videoId, now = eventId)!!)

    private fun episode(contentId: String, season: Int, episode: Int, position: Long = 300, lastWatched: Long = 10) = ProgressEntry(
        contentId = contentId, contentType = "series", name = contentId, videoId = "$contentId:$season:$episode",
        season = season, episode = episode, positionMs = position, durationMs = 1000, lastWatched = lastWatched,
    )

    private fun remoteProgress(contentId: String, season: Int, episode: Int, position: Long = 300, lastWatched: Long = 10) =
        RemoteProgress(contentId, "series", "$contentId:$season:$episode", season, episode, position, 1000, lastWatched)

    // --- Watched history ---

    @Test
    fun `an episode un-watched on another device stays un-watched here and isn't pushed back`() {
        val local = listOf("tt1:1:5", "tt1:1:6")
        val watched = WatchSync.applyWatchedDelta(local, listOf(watchedEvent(7, "tt1:1:5", deleted = true)), PendingWatchOps())
        assertEquals(listOf("tt1:1:6"), watched)
        // Applying the server's change queues nothing: only local writes go through recordLocal.
        val pending = WatchSync.recordLocal(PendingWatchOps(), ProgressFile(watched = local), ProgressFile(watched = local), now = 1)
        assertTrue(pending.isEmpty)
    }

    @Test
    fun `a later snapshot drops what the server no longer has, unless it is pending here`() {
        val pending = PendingWatchOps().watchedUpsert("tt3", at = 5)
        val pull = WatchSync.applyWatchedSnapshot(
            watched = listOf("tt1:1:5", "tt2", "tt3", "srv1:jf:abc"),
            remote = listOf(SyncMerge.watchedItem("tt2", 1)!!),
            pending = pending,
            initial = false,
            now = 9,
        )
        // tt1:1:5 was un-watched elsewhere; tt3 waits to go up; the media-server id never syncs.
        assertEquals(setOf("tt2", "tt3", "srv1:jf:abc"), pull.watched.toSet())
        assertEquals(pending, pull.pending)
    }

    @Test
    fun `the first snapshot only adds, and queues local marks the server lacks`() {
        val pull = WatchSync.applyWatchedSnapshot(
            watched = listOf("tt1", "tt2"),
            remote = listOf(SyncMerge.watchedItem("tt2", 1)!!, SyncMerge.watchedItem("tt9:1:1", 1)!!),
            pending = PendingWatchOps(),
            initial = true,
            now = 9,
        )
        assertEquals(listOf("tt1", "tt2", "tt9:1:1"), pull.watched)
        assertEquals(mapOf("tt1" to 9L), pull.pending.watchedUpserts)
    }

    @Test
    fun `an un-mark made offline is queued, survives the pull and is retried until sent`() {
        val before = ProgressFile(watched = listOf("tt1:1:5", "tt1:1:6"))
        val after = before.copy(watched = listOf("tt1:1:6"))
        val pending = WatchSync.recordLocal(PendingWatchOps(), before, after, now = 3)
        assertEquals(setOf("tt1:1:5"), pending.watchedDeletes)

        // The push failed (offline). The next pull still lists it on the server (a snapshot) or
        // replays an old upsert (the delta log): it stays un-watched here.
        val snapshot = WatchSync.applyWatchedSnapshot(
            after.watched, listOf(SyncMerge.watchedItem("tt1:1:5", 1)!!, SyncMerge.watchedItem("tt1:1:6", 1)!!), pending, initial = false, now = 4,
        )
        assertEquals(listOf("tt1:1:6"), snapshot.watched)
        assertEquals(listOf("tt1:1:6"), WatchSync.applyWatchedDelta(after.watched, listOf(watchedEvent(8, "tt1:1:5")), pending))
        // Still queued for the next push; gone once the server took it.
        assertEquals(setOf("tt1:1:5"), snapshot.pending.watchedDeletes)
        assertTrue(pending.acknowledged(watchedDeletes = setOf("tt1:1:5")).isEmpty)
    }

    @Test
    fun `a mark made here beats a delete from the server until it is sent`() {
        val pending = WatchSync.recordLocal(PendingWatchOps(), ProgressFile(), ProgressFile(watched = listOf("tt1")), now = 3)
        assertEquals(mapOf("tt1" to 3L), pending.watchedUpserts)
        assertEquals(listOf("tt1"), WatchSync.applyWatchedDelta(listOf("tt1"), listOf(watchedEvent(9, "tt1", deleted = true)), pending))
    }

    @Test
    fun `delta events apply in order`() {
        val events = listOf(watchedEvent(2, "tt1", deleted = true), watchedEvent(1, "tt1"), watchedEvent(3, "tt2"))
        assertEquals(listOf("tt2"), WatchSync.applyWatchedDelta(emptyList(), events, PendingWatchOps()))
    }

    @Test
    fun `an acknowledgement keeps a change made again while the push was on its way`() {
        val sent = PendingWatchOps().watchedUpsert("tt1", at = 1)
        val changedAgain = sent.watchedUpsert("tt1", at = 2)
        assertEquals(mapOf("tt1" to 2L), changedAgain.acknowledged(watchedUpserts = sent.watchedUpserts).watchedUpserts)
    }

    // --- Continue Watching ---

    @Test
    fun `only this app's own positions are queued`() {
        val mine = episode("tt1", 1, 2)
        val trakt = episode("tt2", 1, 1).copy(source = "trakt")
        val upNext = episode("tt3", 1, 1).copy(upNext = true, positionMs = 0, durationMs = 0)
        val pending = WatchSync.recordLocal(PendingWatchOps(), ProgressFile(), ProgressFile(entries = listOf(mine, trakt, upNext)), now = 1)
        assertEquals(setOf("tt1_s1e2"), pending.progressUpserts.keys)
        assertEquals(300L, pending.progressUpserts.getValue("tt1_s1e2").positionMs)
    }

    @Test
    fun `finishing an episode sends it at 100 percent, as the TV's markAsCompleted does`() {
        val before = ProgressFile(entries = listOf(episode("tt1", 1, 2)))
        val after = ProgressFile(
            entries = listOf(episode("tt1", 1, 3).copy(upNext = true, positionMs = 0, durationMs = 0)),
            watched = listOf("tt1:1:2"),
        )
        val pending = WatchSync.recordLocal(PendingWatchOps(), before, after, now = 50)
        val sent = pending.progressUpserts.getValue("tt1_s1e2")
        assertEquals(sent.durationMs, sent.positionMs)
        assertEquals(50L, sent.lastWatched)
        assertEquals(setOf("tt1:1:2"), pending.watchedUpserts.keys)
    }

    @Test
    fun `removing a title from Continue Watching deletes all its keys on the server`() {
        val entry = episode("tt1", 1, 2)
        val pending = WatchSync.queueRemoval(
            PendingWatchOps().progressUpsert("tt1_s1e2", PendingProgress.of(remoteProgress("tt1", 1, 2))),
            entry,
            remoteKeys = setOf("tt1_s1e1", "tt1_s1e2", "tt10_s1e1", "tt2"),
        )
        assertEquals(setOf("tt1_s1e1", "tt1_s1e2", "tt1"), pending.progressDeletes)
        assertTrue(pending.progressUpserts.isEmpty())

        // Hidden here, it isn't brought back by the server's copy before the delete is sent.
        val hidden = ProgressFile(entries = listOf(entry.copy(hidden = true)))
        val pull = WatchSync.applyProgressDelta(
            hidden, listOf(RemoteProgressEvent(5, false, remoteProgress("tt1", 1, 2, lastWatched = 99))), pending, setOf("tt1_s1e2"),
        )
        assertTrue(pull.file.entries.single().hidden)
    }

    @Test
    fun `a title removed from Continue Watching on another device goes here too`() {
        val local = ProgressFile(entries = listOf(episode("tt1", 1, 2), episode("tt2", 1, 1)))
        val delta = WatchSync.applyProgressDelta(
            local, listOf(RemoteProgressEvent(4, true, remoteProgress("tt1", 1, 2))), PendingWatchOps(), setOf("tt1_s1e2", "tt2_s1e1"),
        )
        assertEquals(listOf("tt2"), delta.file.entries.map { it.contentId })
        assertEquals(setOf("tt2_s1e1"), delta.remoteKeys)

        // The same through a snapshot (no delta log): a key the server held before and lacks now.
        val snapshot = WatchSync.applyProgressSnapshot(
            local, listOf(remoteProgress("tt2", 1, 1)), PendingWatchOps(), setOf("tt1_s1e2", "tt2_s1e1"), initial = false,
        )
        assertEquals(listOf("tt2"), snapshot.file.entries.map { it.contentId })
        assertTrue(snapshot.pending.isEmpty)
    }

    @Test
    fun `a position saved here and not yet sent survives a delete from the server`() {
        val local = ProgressFile(entries = listOf(episode("tt1", 1, 2)))
        val pending = WatchSync.recordLocal(PendingWatchOps(), ProgressFile(), local, now = 1)
        val pull = WatchSync.applyProgressDelta(local, listOf(RemoteProgressEvent(4, true, remoteProgress("tt1", 1, 2))), pending, setOf("tt1_s1e2"))
        assertEquals(local, pull.file)
    }

    @Test
    fun `newer activity elsewhere wins, and a finished position doesn't become a Continue Watching entry`() {
        val local = ProgressFile(entries = listOf(episode("tt1", 1, 2, lastWatched = 10), episode("tt2", 1, 1, lastWatched = 10)))
        val pull = WatchSync.applyProgressDelta(
            local,
            listOf(
                RemoteProgressEvent(1, false, remoteProgress("tt1", 1, 3, position = 500, lastWatched = 20)),
                RemoteProgressEvent(2, false, remoteProgress("tt2", 1, 1, position = 950, lastWatched = 20)),
                RemoteProgressEvent(3, false, remoteProgress("tt3", 1, 1, position = 990, lastWatched = 20)),
            ),
            PendingWatchOps(),
            emptySet(),
        )
        assertEquals("tt1:1:3", pull.file.entries.single { it.contentId == "tt1" }.videoId)
        assertFalse(pull.file.entries.any { it.contentId == "tt2" || it.contentId == "tt3" })
        assertEquals(setOf("tt1_s1e3", "tt2_s1e1", "tt3_s1e1"), pull.remoteKeys)
    }

    @Test
    fun `the first progress snapshot queues local positions the server lacks or has older`() {
        val local = ProgressFile(entries = listOf(episode("tt1", 1, 2, lastWatched = 30), episode("tt2", 1, 1, lastWatched = 10)))
        val pull = WatchSync.applyProgressSnapshot(
            local, listOf(remoteProgress("tt2", 1, 1, lastWatched = 10)), PendingWatchOps(), emptySet(), initial = true,
        )
        assertEquals(setOf("tt1_s1e2"), pull.pending.progressUpserts.keys)
        assertEquals(setOf("tt2_s1e1"), pull.remoteKeys)
        assertEquals(2, pull.file.entries.size)
    }
}
