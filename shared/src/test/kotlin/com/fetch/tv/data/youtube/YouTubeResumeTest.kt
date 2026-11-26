package com.fetch.tv.data.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When both apps resume a YouTube video, and which places they keep. */
class YouTubeResumeTest {

    private val hour = 3_600_000L

    @Test
    fun `a position mid-video is worth resuming, the very start and end aren't`() {
        assertEquals(30 * 60_000L, YouTubeResume.resumableMs(30 * 60_000L, hour))
        assertNull("first seconds", YouTubeResume.resumableMs(5_000, hour))
        assertNull("under 3%", YouTubeResume.resumableMs(60_000, hour))
        assertNull("last 5%", YouTubeResume.resumableMs(58 * 60_000L, hour))
        assertNull("unknown length", YouTubeResume.resumableMs(30_000, 0))
    }

    @Test
    fun `YouTube's percentage skips videos barely started or nearly done`() {
        assertEquals(300_000L, YouTubeResume.percentPositionMs(50, 600_000))
        assertNull(YouTubeResume.percentPositionMs(2, 600_000))
        assertNull(YouTubeResume.percentPositionMs(97, 600_000))
        assertNull(YouTubeResume.percentPositionMs(null, 600_000))
        assertNull(YouTubeResume.percentPositionMs(50, 0))
        assertEquals(3, YouTubeResume.resumablePercent(3))
        assertNull(YouTubeResume.resumablePercent(96))
    }

    @Test
    fun `the start is Fetch's place, unless YouTube is clearly ahead, else YouTube's percentage`() {
        val tenMinutes = 600_000L
        assertEquals(240_000L, YouTubeResume.startPositionMs(240_000, watchedPercent = 42, durationMs = tenMinutes))
        assertEquals(360_000L, YouTubeResume.startPositionMs(240_000, watchedPercent = 60, durationMs = tenMinutes))
        // Fetch's place is too close to the start to count; YouTube's percent takes over.
        assertEquals(300_000L, YouTubeResume.startPositionMs(5_000, watchedPercent = 50, durationMs = tenMinutes))
        assertNull(YouTubeResume.startPositionMs(null, watchedPercent = null, durationMs = tenMinutes))
        assertNull("finished elsewhere", YouTubeResume.startPositionMs(240_000, watchedPercent = 100, durationMs = tenMinutes))
    }

    @Test
    fun `saving moves the video to the front and keeps the newest`() {
        val a = YouTubeResumePoint("a", 600_000, hour, 1)
        val b = YouTubeResumePoint("b", 600_000, hour, 2)
        val updated = YouTubeResume.updatePoints(listOf(a, b), "b", 900_000, hour, nowMs = 3, max = 2)
        assertEquals(listOf("b", "a"), updated.map { it.videoId })
        assertEquals(900_000L, updated.first().positionMs)
        val capped = YouTubeResume.updatePoints(updated, "c", 900_000, hour, nowMs = 4, max = 2)
        assertEquals(listOf("c", "b"), capped.map { it.videoId })
    }

    @Test
    fun `a finished video is forgotten, so it starts over`() {
        val a = YouTubeResumePoint("a", 600_000, hour, 1)
        assertTrue(YouTubeResume.updatePoints(listOf(a), "a", hour - 1_000, hour, nowMs = 2, max = 10).isEmpty())
    }

    @Test
    fun `a tile's length label is read, anything else isn't`() {
        assertEquals(253_000L, YouTubeResume.durationLabelMs("4:13"))
        assertEquals(3_723_000L, YouTubeResume.durationLabelMs("1:02:03"))
        assertNull(YouTubeResume.durationLabelMs("LIVE"))
        assertNull(YouTubeResume.durationLabelMs(null))
        assertNull(YouTubeResume.durationLabelMs("0:00"))
        assertNull(YouTubeResume.durationLabelMs("4:75"))
        assertNull(YouTubeResume.durationLabelMs("12"))
    }
}
