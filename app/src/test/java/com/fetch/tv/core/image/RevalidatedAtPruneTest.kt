package com.fetch.tv.core.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RevalidatedAtPruneTest {
    private val cooldownMs = 10L * 60 * 1000
    private val threshold = StaleWhileRevalidateCacheStrategy.REVALIDATED_AT_PRUNE_THRESHOLD

    @Test
    fun `keeps every entry below the threshold`() {
        val map = HashMap<String, Long>()
        repeat(threshold) { StaleWhileRevalidateCacheStrategy.markRevalidated("u$it", now = 0L, map = map) }
        assertEquals(threshold, map.size)
    }

    @Test
    fun `drops entries past the cooldown once above the threshold`() {
        val map = HashMap<String, Long>()
        repeat(threshold) { StaleWhileRevalidateCacheStrategy.markRevalidated("old$it", now = 0L, map = map) }
        StaleWhileRevalidateCacheStrategy.markRevalidated("fresh", now = cooldownMs, map = map)
        assertEquals(mapOf("fresh" to cooldownMs), map)
    }

    @Test
    fun `keeps entries still inside the cooldown`() {
        val map = HashMap<String, Long>()
        repeat(threshold) { StaleWhileRevalidateCacheStrategy.markRevalidated("recent$it", now = 1_000L, map = map) }
        StaleWhileRevalidateCacheStrategy.markRevalidated("fresh", now = 2_000L, map = map)
        assertEquals(threshold + 1, map.size)
        assertTrue("fresh" in map)
    }
}
