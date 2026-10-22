package com.fetch.tv.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = android.app.Application::class)
class BestMatchCacheEntriesTest {

    @Test
    fun `entries survive an encode and decode`() {
        val entries = mapOf(
            "series|tt1:2:6" to CachedBestMatch(listOf("4K", "DV", "TrueHD Atmos"), isCached = true, savedAtMs = 10L),
            "movie|tt2" to CachedBestMatch(listOf("1080p"), isCached = false, savedAtMs = 20L)
        )

        assertEquals(entries, decodeBestMatchEntries(encodeBestMatchEntries(entries)))
    }

    @Test
    fun `unreadable data reads as an empty cache`() {
        assertTrue(decodeBestMatchEntries("not json").isEmpty())
        assertTrue(decodeBestMatchEntries(null).isEmpty())
    }

    @Test
    fun `trimming keeps the newest entries`() {
        val entries = (1..5).associate { "movie|tt$it" to CachedBestMatch(listOf("4K"), false, savedAtMs = it.toLong()) }

        val trimmed = trimBestMatchEntries(entries, max = 2)

        assertEquals(setOf("movie|tt5", "movie|tt4"), trimmed.keys)
    }

    @Test
    fun `content key lowercases the type`() {
        assertEquals("series|tt1:2:6", BestMatchCacheDataStore.contentKey("Series", "tt1:2:6"))
    }
}
