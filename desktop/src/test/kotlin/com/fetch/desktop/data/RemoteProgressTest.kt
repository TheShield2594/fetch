package com.fetch.desktop.data

import com.fetch.desktop.TrackingService
import com.fetch.desktop.TrackingState.Companion.toProgressEntry
import com.fetch.desktop.TrackingState.Companion.videoIds
import com.fetch.desktop.tracking.RemotePlayback
import com.fetch.desktop.tracking.RemoteWatched
import com.fetch.desktop.tracking.TrackedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteProgressTest {

    private val remoteEpisode = RemotePlayback(
        item = TrackedItem("tt100", TrackedItem.TYPE_SERIES, season = 2, episode = 5),
        progressPercent = 42f,
        pausedAtEpochMs = 2_000,
        showTitle = "Show",
        episodeTitle = "Five"
    ).toProgressEntry(TrackingService.TRAKT)!!

    @Test
    fun `a Trakt episode becomes a Cinemeta video id and resumes by percentage`() {
        assertEquals("tt100:2:5", remoteEpisode.videoId)
        val file = WatchHistory.mergeRemote(ProgressFile(), listOf(remoteEpisode), emptySet())
        assertEquals("42.0%", WatchHistory.resumeStart(file, "tt100:2:5"))
        assertEquals("42% watched", remainingLabel(file.entries.single()))
        assertEquals(listOf("tt100"), WatchHistory.continueWatching(file).map { it.contentId })
    }

    @Test
    fun `newer local progress wins over an older remote one, and the reverse`() {
        val local = ProgressEntry(
            contentId = "tt100", contentType = "series", name = "Show", videoId = "tt100:2:6",
            positionMs = 600_000, durationMs = 3_000_000, lastWatched = 5_000
        )
        val kept = WatchHistory.mergeRemote(ProgressFile(entries = listOf(local)), listOf(remoteEpisode), emptySet())
        assertEquals("tt100:2:6", kept.entries.single().videoId)
        assertEquals("600", WatchHistory.resumeStart(kept, "tt100:2:6"))

        val replaced = WatchHistory.mergeRemote(ProgressFile(entries = listOf(local.copy(lastWatched = 1_000))), listOf(remoteEpisode), emptySet())
        assertEquals("tt100:2:5", replaced.entries.single().videoId)
    }

    @Test
    fun `remote watched history joins the local list`() {
        val ids = RemoteWatched(movies = setOf("tt9"), episodes = mapOf("tt100" to setOf(1 to 1))).videoIds()
        val file = WatchHistory.mergeRemote(ProgressFile(watched = listOf("tt5")), emptyList(), ids)
        assertTrue(file.watched.containsAll(listOf("tt5", "tt9", "tt100:1:1")))
    }

    @Test
    fun `a finished remote percentage doesn't resume`() {
        val done = remoteEpisode.copy(remotePercent = 95f)
        assertNull(WatchHistory.resumeStart(ProgressFile(entries = listOf(done)), done.videoId))
    }
}
