package com.fetch.tv.ui.screens.player

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerEpisodeRestoreTest {

    private fun launchedOnFirstEpisode() = SavedStateHandle(
        mapOf(
            "streamUrl" to "https://cdn.example/s01e01.mkv",
            "title" to "Show",
            "videoId" to "tt1:1:1",
            "season" to "1",
            "episode" to "1",
            "episodeTitle" to "Pilot",
            "startFromBeginning" to "true",
            "infoHash" to "",
        )
    )

    @Test
    fun `a restored player rebuilds the episode it switched to`() {
        val handle = launchedOnFirstEpisode()

        handle.rememberEpisodeForRestore(
            streamUrl = "https://cdn.example/s01e02.mkv?token=a+b",
            headersJson = """{"Referer":"https://addon.example/"}""",
            videoId = "tt1:1:2",
            season = 1,
            episode = 2,
            episodeTitle = "Part 2: 50% & more",
            streamName = "1080p WEB",
            filename = "Show S01E02 1080p.mkv",
            bingeGroup = "group-1080p",
            videoHash = "abc",
            videoSize = 123L,
            addonName = "Addon",
            addonLogo = null,
            streamDescription = null,
            infoHash = null,
            fileIdx = null,
            sources = null
        )

        val args = PlayerNavigationArgs.from(handle)
        assertEquals("https://cdn.example/s01e02.mkv?token=a+b", args.streamUrl)
        assertEquals("""{"Referer":"https://addon.example/"}""", args.headersJson)
        assertEquals("tt1:1:2", args.videoId)
        assertEquals(2, args.initialEpisode)
        assertEquals("Part 2: 50% & more", args.initialEpisodeTitle)
        assertEquals("Show S01E02 1080p.mkv", args.filename)
        assertEquals(123L, args.videoSize)
        assertNull(args.infoHash)
        assertFalse(args.startFromBeginning)
        // Untouched launch args survive.
        assertEquals("Show", args.title)
    }

    @Test
    fun `a torrent episode restores from its info hash and trackers`() {
        val handle = launchedOnFirstEpisode()

        handle.rememberEpisodeForRestore(
            streamUrl = "",
            headersJson = null,
            videoId = "tt1:1:2",
            season = 1,
            episode = 2,
            episodeTitle = null,
            streamName = null,
            filename = null,
            bingeGroup = null,
            videoHash = null,
            videoSize = null,
            addonName = null,
            addonLogo = null,
            streamDescription = null,
            infoHash = "deadbeef",
            fileIdx = 3,
            sources = listOf("tracker:udp://tracker.example:80")
        )

        val args = PlayerNavigationArgs.from(handle)
        assertEquals("", args.streamUrl)
        assertEquals("deadbeef", args.infoHash)
        assertEquals(3, args.fileIdx)
        assertEquals(listOf("udp://tracker.example:80"), args.torrentTrackers)
        assertNull(args.headersJson)
        assertNull(args.initialEpisodeTitle)
    }
}
