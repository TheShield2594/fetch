package com.fetch.tv.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.fetch.tv.data.youtube.YouTubeResumePoint

/** The pure parts of the YouTube resume store and recent searches. */
class YouTubeLocalStoresTest {

    private val hour = 3_600_000L

    @Test
    fun `resume points survive being stored`() {
        val points = listOf(YouTubeResumePoint("a-_b", 1, 2, 3), YouTubeResumePoint("c", 4, 5, 6))
        assertEquals(points, decodeResumePoints(encodeResumePoints(points)))
        assertTrue(decodeResumePoints(null).isEmpty())
        assertEquals(listOf(points[1]), decodeResumePoints("broken\n" + encodeResumePoints(listOf(points[1]))))
    }

    @Test
    fun `a search goes first, once, ignoring case, and the oldest drop off`() {
        val recent = addRecent(listOf("cats", "Dogs", "birds"), "dogs", max = 3)
        assertEquals(listOf("dogs", "cats", "birds"), recent)
        assertEquals(listOf("fish", "dogs"), addRecent(recent, "fish", max = 2))
    }

    @Test
    fun `recent searches survive being stored`() {
        val searches = listOf("lofi beats", "nasa")
        assertEquals(searches, decodeRecentSearches(encodeRecentSearches(searches)))
        assertTrue(decodeRecentSearches(null).isEmpty())
        assertEquals(listOf("a b"), decodeRecentSearches(encodeRecentSearches(listOf("a\nb"))))
    }
}
