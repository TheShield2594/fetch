package com.fetch.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRequestsTest {

    @Test
    fun `each request gets its own id`() {
        val a = PlaybackRequests.put(PlaybackRequest("https://a.example/1"))
        val b = PlaybackRequests.put(PlaybackRequest("https://a.example/1"))
        try {
            assertNotEquals(a, b)
            assertEquals("https://a.example/1", PlaybackRequests.get(a)?.streamUrl)
        } finally {
            PlaybackRequests.remove(a)
            PlaybackRequests.remove(b)
        }
    }

    @Test
    fun `update replaces and remove forgets`() {
        val id = PlaybackRequests.put(PlaybackRequest("https://a.example/1"))
        PlaybackRequests.update(id, PlaybackRequest("https://a.example/2", mapOf("X" to "1")))
        assertEquals("https://a.example/2", PlaybackRequests.get(id)?.streamUrl)
        assertEquals(mapOf("X" to "1"), PlaybackRequests.get(id)?.headers)
        PlaybackRequests.remove(id)
        assertFalse(PlaybackRequests.isAvailable(id))
    }

    @Test
    fun `missing ids are unavailable`() {
        assertNull(PlaybackRequests.get(null))
        assertNull(PlaybackRequests.get(""))
        assertFalse(PlaybackRequests.isAvailable("not-a-request"))
    }

    @Test
    fun `old requests are dropped once many are held`() {
        val ids = (1..20).map { PlaybackRequests.put(PlaybackRequest("https://a.example/$it")) }
        try {
            assertFalse(PlaybackRequests.isAvailable(ids.first()))
            assertTrue(PlaybackRequests.isAvailable(ids.last()))
        } finally {
            ids.forEach(PlaybackRequests::remove)
        }
    }
}
