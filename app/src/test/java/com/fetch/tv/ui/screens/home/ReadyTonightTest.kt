package com.fetch.tv.ui.screens.home

import com.fetch.tv.data.local.CachedBestMatch
import com.fetch.tv.data.local.READY_WINDOW_MS
import com.fetch.tv.data.local.isReady
import com.fetch.tv.data.local.mergeBestMatchEntry
import com.fetch.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadyTonightTest {
    private val now = 10L * READY_WINDOW_MS

    private fun inProgress(videoId: String) = ContinueWatchingItem.InProgress(
        WatchProgress(
            contentId = "show", contentType = "series", name = "Show", poster = null, backdrop = null,
            logo = null, videoId = videoId, season = 1, episode = 1, episodeTitle = null,
            position = 10, duration = 100, lastWatched = 1
        )
    )

    private fun nextUp(videoId: String, hasAired: Boolean = true, shuffle: Boolean = false) =
        ContinueWatchingItem.NextUp(
            NextUpInfo(
                "next", "series", "Next", null, null, null, videoId, 1, 2, null,
                thumbnail = null, hasAired = hasAired, lastWatched = 1, sortTimestamp = 1
            ),
            shufflePlayback = shuffle
        )

    private fun key(videoId: String) = "series|$videoId"
    private fun match(cached: Boolean, at: Long) = CachedBestMatch(listOf("4K"), isCached = cached, savedAtMs = at)

    @Test
    fun `a pick is ready only when cached and confirmed within the window`() {
        assertTrue(match(cached = true, at = now - 1000).isReady(now))
        assertFalse(match(cached = false, at = now - 1000).isReady(now))
        assertFalse(match(cached = true, at = now - READY_WINDOW_MS - 1).isReady(now))
        assertFalse("a time from the future isn't trusted", match(cached = true, at = now + 1000).isReady(now))
    }

    @Test
    fun `an unchanged pick is re-saved only after an hour, to record the new check`() {
        val current = mapOf("k" to match(cached = true, at = now))
        assertNull(mergeBestMatchEntry(current, "k", match(cached = true, at = now + 60_000)))
        assertNotNull(mergeBestMatchEntry(current, "k", match(cached = true, at = now + 2 * 60 * 60_000L)))
        assertNotNull("a changed pick saves at once", mergeBestMatchEntry(current, "k", match(cached = false, at = now + 1)))
    }

    @Test
    fun `ready tonight keeps row order and skips unknown episodes`() {
        val items = listOf(
            inProgress("a"),
            nextUp("b"),
            nextUp("c", hasAired = false),
            nextUp("d", shuffle = true)
        )
        val ready = setOf(key("b"), key("a"), key("c"), key("d"))

        assertEquals(listOf(items[0], items[1]), readyTonightItems(items, ready))
    }

    @Test
    fun `candidates skip recent picks and recent attempts and stop at the limit`() {
        val items = (1..10).map { nextUp("v$it") }
        val entries = mapOf(key("v1") to match(cached = false, at = now - 1000))
        val attempts = mapOf(key("v2") to now - 1000, key("v3") to now - READY_CHECK_INTERVAL_MS - 1)

        val picked = readyCheckCandidates(items, entries, attempts, now).map { it.videoId }

        assertEquals(listOf("v3", "v4", "v5", "v6", "v7", "v8"), picked)
    }

    @Test
    fun `candidates carry what the previewer needs`() {
        val request = readyCheckCandidates(listOf(nextUp("v1")), emptyMap(), emptyMap(), now).single()

        assertEquals(ReadyCheckRequest(key("v1"), "series", "v1", 1, 2, "next"), request)
    }
}
