package com.fetch.tv.core.streams

import org.junit.Assert.assertEquals
import org.junit.Test

class MetaIdsTest {

    @Test
    fun `episode ids map to their content-level meta id`() {
        assertEquals("tt1234567", metaIdForVideoId("tt1234567:1:5"))
        assertEquals("mal:63375", metaIdForVideoId("mal:63375:1:5"))
        assertEquals("kitsu:12345", metaIdForVideoId("kitsu:12345:2"))
        assertEquals("tmdb:1399", metaIdForVideoId("tmdb:1399:1:2"))
    }

    @Test
    fun `content-level ids come back unchanged`() {
        // TheShield2594/fetch#314: "kitsu:123" used to be compared with "kitsu", so every anime
        // episode waited on an embedded-stream meta lookup before its addon search.
        assertEquals("kitsu:12345", metaIdForVideoId("kitsu:12345"))
        assertEquals("tt1234567", metaIdForVideoId("tt1234567"))
        assertEquals("yt_id:abc", metaIdForVideoId("yt_id:abc"))
    }
}
