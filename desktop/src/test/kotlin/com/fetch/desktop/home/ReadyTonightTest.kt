package com.fetch.desktop.home

import com.fetch.desktop.data.LibraryEntry
import com.fetch.desktop.data.ProgressEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ReadyTonightTest {
    private val now = 10_000_000_000L
    private val today = LocalDate.of(2026, 11, 3)

    private fun entry(id: String, video: String = id, type: String = "movie", upNext: Boolean = false, airDate: String? = null) =
        ProgressEntry(contentId = id, contentType = type, name = id, videoId = video, positionMs = 100, durationMs = 1000, lastWatched = 1, upNext = upNext, airDate = airDate,
            season = if (type == "series") 1 else null, episode = if (type == "series") 2 else null)

    @Test
    fun `a pick is ready while cached and confirmed in the last day`() {
        assertTrue(ReadyTonight.isReady(CachedBestMatch(listOf("4K"), isCached = true, savedAtMs = now - 1000), now))
        assertFalse(ReadyTonight.isReady(CachedBestMatch(listOf("4K"), isCached = false, savedAtMs = now), now))
        assertFalse(ReadyTonight.isReady(CachedBestMatch(listOf("4K"), isCached = true, savedAtMs = now - ReadyTonight.READY_WINDOW_MS - 1), now))
    }

    @Test
    fun `an unchanged pick is only re-saved after an hour`() {
        val key = ReadyTonight.contentKey("Movie", "tt1")
        assertEquals("movie|tt1", key)
        val first = mapOf(key to CachedBestMatch(listOf("4K"), true, now))
        assertNull(ReadyTonight.merge(first, key, CachedBestMatch(listOf("4K"), true, now + 1000)))
        assertEquals(now + ReadyTonight.RECHECK_TOUCH_MS, ReadyTonight.merge(first, key, CachedBestMatch(listOf("4K"), true, now + ReadyTonight.RECHECK_TOUCH_MS))!![key]!!.savedAtMs)
        assertEquals(listOf("1080p"), ReadyTonight.merge(first, key, CachedBestMatch(listOf("1080p"), true, now + 1))!![key]!!.labels)
    }

    @Test
    fun `ready entries keep the row's order and skip unaired episodes`() {
        val ready = setOf("movie|a", "series|b:1:2", "series|c:1:2")
        val entries = listOf(entry("a"), entry("b", "b:1:2", "series"), entry("c", "c:1:2", "series", upNext = true, airDate = "2026-12-01"))
        assertEquals(listOf("a", "b"), ReadyTonight.readyEntries(entries, ready, today).map { it.contentId })
    }

    @Test
    fun `watchlist movies are the newest movies, minus Continue Watching's`() {
        val list = listOf(
            LibraryEntry("m1", "movie", "Old", addedAt = 1),
            LibraryEntry("s1", "series", "Show", addedAt = 5),
            LibraryEntry("m2", "movie", "New", addedAt = 9)
        )
        val movies = ReadyTonight.watchlistMovies(list)
        assertEquals(listOf("m2", "m1"), movies.map { it.id })
        assertEquals(listOf("m1"), ReadyTonight.readyMovies(movies, setOf("movie|m1", "movie|m2"), exceptKeys = setOf("movie|m2")).map { it.id })
    }

    @Test
    fun `background checks skip what was confirmed or asked lately, at most six`() {
        val entries = (1..8).map { entry("m$it") }
        val cache = mapOf("movie|m1" to CachedBestMatch(emptyList(), true, now - 1000))
        val asked = mapOf("movie|m2" to now - 1000, "movie|m3" to now - ReadyTonight.CHECK_INTERVAL_MS - 1)
        val picked = ReadyTonight.candidates(entries, cache, asked, now, today = today)
        assertEquals(listOf("m3", "m4", "m5", "m6", "m7", "m8"), picked.map { it.videoId })
    }
}
