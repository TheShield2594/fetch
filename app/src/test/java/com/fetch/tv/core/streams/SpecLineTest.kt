package com.fetch.tv.core.streams

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpecLineTest {

    @Test
    fun `parts follow leading, labels, then cached`() {
        val line = SpecLine.of(listOf("4K", "DV P8", "18.2 GB"), isCached = true, cachedLabel = "Cached", leading = "38m left")

        assertEquals(listOf("38m left", "4K", "DV P8", "18.2 GB", "Cached"), line?.parts)
        assertEquals("38m left · 4K · DV P8 · 18.2 GB · Cached", line?.text)
    }

    @Test
    fun `cached label only when the stream is cached`() {
        assertEquals("1080p", SpecLine.of(listOf("1080p"), isCached = false, cachedLabel = "Cached")?.text)
    }

    @Test
    fun `blank parts are dropped`() {
        val line = SpecLine.of(listOf("4K", " ", ""), isCached = false, cachedLabel = "Cached", leading = "")

        assertEquals(listOf("4K"), line?.parts)
    }

    @Test
    fun `nothing known gives no line`() {
        assertNull(SpecLine.of(emptyList(), isCached = false, cachedLabel = "Cached"))
    }

    @Test
    fun `cached alone still makes a line`() {
        assertEquals("Cached", SpecLine.of(emptyList(), isCached = true, cachedLabel = "Cached")?.text)
    }
}
