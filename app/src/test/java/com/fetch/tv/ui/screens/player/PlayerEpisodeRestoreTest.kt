package com.fetch.tv.ui.screens.player

import androidx.lifecycle.SavedStateHandle
import com.fetch.tv.ui.navigation.Screen
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerEpisodeRestoreTest {

    private val requestId = PlaybackRequests.put(PlaybackRequest("https://cdn.example/s01e01.mkv?token=secret"))

    @After
    fun tearDown() {
        PlaybackRequests.remove(requestId)
    }

    private fun launchedOnFirstEpisode() = SavedStateHandle(
        mapOf(
            "requestId" to requestId,
            "title" to "Show",
            "contentType" to "series",
            "contentId" to "tt1",
            "contentName" to "Show",
            "videoId" to "tt1:1:1",
            "season" to "1",
            "episode" to "1",
            "episodeTitle" to "Pilot",
            "startFromBeginning" to "true",
            "returnToDetailOnBack" to "true",
            "infoHash" to "",
            "audioDelayMs" to "-100",
            "profileId" to "3",
        )
    )

    private fun switchToSecondEpisode(handle: SavedStateHandle) {
        handle.rememberEpisodeForRestore(
            requestId = requestId,
            request = PlaybackRequest(
                streamUrl = "https://cdn.example/s01e02.mkv?token=a+b",
                headers = mapOf("Referer" to "https://addon.example/"),
                sources = listOf("tracker:udp://tracker.example:80")
            ),
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
            audioDelayMs = 250
        )
    }

    @Test
    fun `a restored player rebuilds the episode it switched to`() {
        val handle = launchedOnFirstEpisode()
        switchToSecondEpisode(handle)

        val args = PlayerNavigationArgs.from(handle)
        assertEquals("https://cdn.example/s01e02.mkv?token=a+b", args.streamUrl)
        assertEquals("https://addon.example/", JSONObject(args.headersJson!!).getString("Referer"))
        assertEquals("tt1:1:2", args.videoId)
        assertEquals(2, args.initialEpisode)
        assertEquals("Part 2: 50% & more", args.initialEpisodeTitle)
        assertEquals("Show S01E02 1080p.mkv", args.filename)
        assertEquals(123L, args.videoSize)
        assertNull(args.infoHash)
        assertFalse(args.startFromBeginning)
        assertEquals(250, args.audioDelayMs)
        // Untouched launch args survive.
        assertEquals("Show", args.title)
    }

    @Test
    fun `no stream url or headers are kept in saved state`() {
        val handle = launchedOnFirstEpisode()
        switchToSecondEpisode(handle)

        handle.keys().forEach { key ->
            val value = handle.get<Any?>(key)?.toString().orEmpty()
            assertFalse("$key holds a stream URL", value.contains("cdn.example"))
            assertFalse("$key holds a header", value.contains("addon.example"))
        }
        assertEquals("https://cdn.example/s01e02.mkv?token=a+b", PlaybackRequests.get(requestId)?.streamUrl)
    }

    @Test
    fun `the player route carries a request id instead of the stream`() {
        val route = Screen.Player.createRoute(requestId = requestId, title = "Show")

        assertTrue(route.startsWith("player/$requestId/"))
        assertFalse(route.contains("cdn.example"))
        assertFalse(Screen.Player.route.contains("{streamUrl}"))
        assertFalse(Screen.Player.route.contains("{headers}"))
        assertFalse(Screen.Player.route.contains("{sources}"))
    }

    @Test
    fun `after a process restore the player has no stream and resolves it from the stream screen`() {
        val handle = launchedOnFirstEpisode()
        switchToSecondEpisode(handle)
        // The process died: the in-memory request is gone, saved state survived.
        PlaybackRequests.remove(requestId)

        assertFalse(PlaybackRequests.isAvailable(handle.get<String>("requestId")))
        assertEquals("", PlayerNavigationArgs.from(handle).streamUrl)

        val route = playerRestoreStreamRoute(handle)
        assertNotNull(route)
        route!!
        assertTrue(route, route.startsWith("stream/tt1%3A1%3A2/series/Show?"))
        assertTrue(route, route.contains("season=1&episode=2&"))
        assertTrue(route, route.contains("startFromBeginning=false"))
        assertTrue(route, route.contains("returnToDetailOnBack=true"))
        assertTrue(route, route.contains("profileId=3"))
    }

    @Test
    fun `restore without content identity gives no route`() {
        assertNull(playerRestoreStreamRoute(SavedStateHandle(mapOf("title" to "Clip"))))
    }
}
