package com.fetch.tv.updater

import org.junit.Assert.assertEquals
import org.junit.Test

class ApkDownloaderProgressTest {

    @Test
    fun `percent is whole and clamped`() {
        assertEquals(0, downloadPercent(0L, 1_000L))
        assertEquals(0, downloadPercent(9L, 1_000L))
        assertEquals(1, downloadPercent(10L, 1_000L))
        assertEquals(50, downloadPercent(500L, 1_000L))
        assertEquals(100, downloadPercent(1_000L, 1_000L))
        assertEquals(100, downloadPercent(2_000L, 1_000L))
    }

    @Test
    fun `unknown size reports zero`() {
        assertEquals(0, downloadPercent(12_345L, null))
        assertEquals(0, downloadPercent(12_345L, 0L))
    }

    @Test
    fun `large files don't overflow`() {
        val total = 3L * 1024 * 1024 * 1024
        assertEquals(50, downloadPercent(total / 2, total))
    }
}
