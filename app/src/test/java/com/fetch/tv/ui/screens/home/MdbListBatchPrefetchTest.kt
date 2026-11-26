package com.fetch.tv.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TheShield2594/fetch#823. */
class MdbListBatchPrefetchTest {

    private val cwStyle = setOf(MODERN_CONTINUE_WATCHING_ROW_KEY, MODERN_UPCOMING_ROW_KEY)

    @Test
    fun `targets the focused row and the next catalog row`() {
        val keys = listOf("a", MODERN_UPCOMING_ROW_KEY, "b", "c")
        assertEquals(listOf("a", "b"), mdbBatchTargetRowKeys(keys, "a") { it in cwStyle })
    }

    @Test
    fun `the last row targets only itself`() {
        assertEquals(listOf("c"), mdbBatchTargetRowKeys(listOf("a", "c"), "c") { it in cwStyle })
    }

    @Test
    fun `continue watching rows and unknown rows target nothing`() {
        val keys = listOf(MODERN_CONTINUE_WATCHING_ROW_KEY, "a")
        assertEquals(emptyList<String>(), mdbBatchTargetRowKeys(keys, MODERN_CONTINUE_WATCHING_ROW_KEY) { it in cwStyle })
        assertEquals(emptyList<String>(), mdbBatchTargetRowKeys(keys, "gone") { it in cwStyle })
    }

    @Test
    fun `misses and failures expire after their own wait`() {
        var now = 0L
        val cache = MdbListNegativeCache(missTtlMs = 100, failureTtlMs = 10, clock = { now })
        cache.recordMiss("miss")
        cache.recordFailure("fail")
        assertTrue(cache.contains("miss"))
        assertTrue(cache.contains("fail"))

        now = 10
        assertTrue(cache.contains("miss"))
        assertFalse(cache.contains("fail"))

        now = 100
        assertFalse(cache.contains("miss"))
        assertEquals(0, cache.size)
    }

    @Test
    fun `the cache is bounded and drops the oldest entry first`() {
        val cache = MdbListNegativeCache(maxEntries = 2, clock = { 0L })
        cache.recordMiss("a")
        cache.recordMiss("b")
        cache.recordMiss("a") // refreshed: now the newest
        cache.recordMiss("c")

        assertEquals(2, cache.size)
        assertFalse(cache.contains("b"))
        assertTrue(cache.contains("a"))
        assertTrue(cache.contains("c"))
    }
}
