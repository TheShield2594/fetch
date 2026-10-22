package com.fetch.tv.ui.screens.home

import com.fetch.tv.data.local.CachedBestMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeroResumeSpecLineTest {

    @Test
    fun `cached match adds the stream details after the time left`() {
        val match = CachedBestMatch(listOf("4K", "DV", "Atmos"), isCached = true, savedAtMs = 1L)

        assertEquals(
            "38m left · 4K · DV · Atmos · Cached",
            heroResumeSpecLine("38m left", match, cachedLabel = "Cached")?.text
        )
    }

    @Test
    fun `without a cached match only the time left is shown`() {
        assertEquals("38m left", heroResumeSpecLine("38m left", null, cachedLabel = "Cached")?.text)
    }

    @Test
    fun `an uncached pick is not called cached`() {
        val match = CachedBestMatch(listOf("1080p"), isCached = false, savedAtMs = 1L)

        assertEquals("1080p", heroResumeSpecLine(null, match, cachedLabel = "Cached")?.text)
    }

    @Test
    fun `nothing known gives no spec line`() {
        assertNull(heroResumeSpecLine(null, null, cachedLabel = "Cached"))
    }
}
