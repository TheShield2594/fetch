package com.fetch.tv.core.di

import org.junit.Assert.assertEquals
import org.junit.Test

class HttpDiskCacheSizeTest {
    private val mb = 1024L * 1024

    @Test
    fun `a nearly full stick gets the minimum`() {
        assertEquals(HTTP_DISK_CACHE_MIN_BYTES, httpDiskCacheSizeBytes(200 * mb))
    }

    @Test
    fun `scales with free space between the bounds`() {
        assertEquals(20 * mb, httpDiskCacheSizeBytes(1000 * mb))
    }

    @Test
    fun `never more than the old fixed 50 MB`() {
        assertEquals(HTTP_DISK_CACHE_MAX_BYTES, httpDiskCacheSizeBytes(64_000 * mb))
        assertEquals(50 * mb, HTTP_DISK_CACHE_MAX_BYTES)
    }
}
