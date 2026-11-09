package com.fetch.tv.data.local

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ContinueWatchingEnrichmentCacheDecodeTest {
    private val gson = Gson()

    @Test
    fun `entries without their required fields are dropped`() {
        val stale = gson.fromJson<List<CachedNextUpItem?>>(
            """[{"a":"tt1","b":"series"}]""",
            object : TypeToken<List<CachedNextUpItem?>>() {}.type
        )
        assertNull(sanitizeNextUp(stale.single()))

        val staleInProgress = gson.fromJson<List<CachedInProgressItem?>>(
            """[{"a":"tt1"}]""",
            object : TypeToken<List<CachedInProgressItem?>>() {}.type
        )
        assertNull(sanitizeInProgress(staleInProgress.single()))
    }

    @Test
    fun `a stored entry round trips and missing genres become empty`() {
        val item = CachedInProgressItem(
            contentId = "tt1",
            contentType = "movie",
            name = "Film",
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "tt1",
            season = null,
            episode = null,
            episodeTitle = null,
            position = 1L,
            duration = 2L,
            lastWatched = 3L,
            progressPercent = null
        )
        val json = gson.toJson(listOf(item)).replace(""","genres":[]""", "")
        assertFalse(json.contains("genres"))
        val decoded = gson.fromJson<List<CachedInProgressItem?>>(
            json,
            object : TypeToken<List<CachedInProgressItem?>>() {}.type
        )

        assertEquals(item, sanitizeInProgress(decoded.single()))
    }
}
