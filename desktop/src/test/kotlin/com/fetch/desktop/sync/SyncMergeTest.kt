package com.fetch.desktop.sync

import com.fetch.desktop.data.LibraryEntry
import com.fetch.desktop.data.ProgressEntry
import com.fetch.desktop.sync.SyncMerge.videoId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMergeTest {

    private fun local(id: String) = LibraryEntry(id = id, type = "movie", name = id)
    private fun remote(id: String) = RemoteLibraryItem(id = id, type = "movie", name = id)

    @Test
    fun `first sync copies each side's titles to the other`() {
        val merge = SyncMerge.library(listOf(local("a")), listOf(remote("b")), SyncBase())
        assertEquals(setOf("a", "b"), merge.local.map { it.id }.toSet())
        assertEquals(listOf("a"), merge.push.map { it.id })
        assertTrue(merge.deleteRemote.isEmpty())
        assertEquals(setOf("a", "b"), merge.base.libraryIds.toSet())
    }

    @Test
    fun `a title removed here is deleted there, and the other way round`() {
        val base = SyncBase(listOf("a", "b"))
        // "a" was removed on this computer, "b" on the TV.
        val merge = SyncMerge.library(listOf(local("b")), listOf(remote("a")), base)
        assertTrue(merge.local.isEmpty())
        assertEquals(listOf("a"), merge.deleteRemote.map { it.id })
        assertTrue(merge.push.isEmpty())
        assertTrue(merge.base.libraryIds.isEmpty())
    }

    @Test
    fun `watched ids round-trip between Cinemeta ids and the server's items`() {
        val item = SyncMerge.watchedItem("tt9:2:5", now = 7)!!
        assertEquals(RemoteWatchedItem("tt9", "series", season = 2, episode = 5, watchedAtEpochMs = 7), item)
        assertEquals("tt9:2:5", item.videoId())
        val push = SyncMerge.watchedToPush(listOf("tt1", "tt9:2:5"), listOf(item), now = 7)
        assertEquals(listOf("tt1"), push.map { it.videoId() })
        assertEquals("movie", push.single().contentType)
    }

    @Test
    fun `only this app's real positions are pushed, and pulled ones keep known names`() {
        val mine = ProgressEntry(contentId = "tt1", contentType = "movie", name = "One", videoId = "tt1", positionMs = 10, durationMs = 100, lastWatched = 5)
        val trakt = mine.copy(contentId = "tt2", videoId = "tt2", source = "trakt")
        val upNext = mine.copy(contentId = "tt3", videoId = "tt3", upNext = true)
        assertEquals(listOf("tt1"), SyncMerge.progressToPush(listOf(mine, trakt, upNext)).map { it.contentId })

        val pulled = SyncMerge.progressEntries(
            listOf(
                RemoteProgress("tt1", "movie", "tt1", positionMs = 50, durationMs = 100, lastWatchedEpochMs = 9),
                RemoteProgress("tt1", "movie", "tt1", positionMs = 20, durationMs = 100, lastWatchedEpochMs = 3)
            ),
            known = mapOf("tt1" to mine)
        )
        assertEquals("One", pulled.single().name)
        assertEquals(50L, pulled.single().positionMs)
    }
}
