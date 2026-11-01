package com.fetch.tv.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of the YouTube resume store and recent searches. */
class YouTubeLocalStoresTest {

    private val hour = 3_600_000L

    @Test
    fun `a position mid-video is worth resuming, the very start and end aren't`() {
        assertEquals(30 * 60_000L, resumableMs(30 * 60_000L, hour))
        assertNull("first seconds", resumableMs(5_000, hour))
        assertNull("under 3%", resumableMs(60_000, hour))
        assertNull("last 5%", resumableMs(58 * 60_000L, hour))
        assertNull("unknown length", resumableMs(30_000, 0))
    }

    @Test
    fun `saving moves the video to the front and keeps the newest`() {
        val a = YouTubeResumePoint("a", 600_000, hour, 1)
        val b = YouTubeResumePoint("b", 600_000, hour, 2)
        val updated = updateResumePoints(listOf(a, b), "b", 900_000, hour, nowMs = 3, max = 2)
        assertEquals(listOf("b", "a"), updated.map { it.videoId })
        assertEquals(900_000L, updated.first().positionMs)
        val capped = updateResumePoints(updated, "c", 900_000, hour, nowMs = 4, max = 2)
        assertEquals(listOf("c", "b"), capped.map { it.videoId })
    }

    @Test
    fun `a finished video is forgotten, so it starts over`() {
        val a = YouTubeResumePoint("a", 600_000, hour, 1)
        assertTrue(updateResumePoints(listOf(a), "a", hour - 1_000, hour, nowMs = 2, max = 10).isEmpty())
    }

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
