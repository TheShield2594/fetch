package com.fetch.tv.core.sync

import com.fetch.tv.domain.model.WatchProgress
import com.fetch.tv.domain.model.WatchedItem
import com.fetch.tv.domain.model.WatchedMutationKey
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingWatchStateDecoderTest {
    private val gson = Gson()

    @Test
    fun `queued entries are stored under stable keys`() {
        val progressJson = JsonParser.parseString(gson.toJson(PendingProgressUpsert("k", progress()))).asJsonObject
        val watchedJson = JsonParser.parseString(
            gson.toJson(PendingWatchedUpsert(WatchedMutationKey("tt1", 1, 2), watched()))
        ).asJsonObject

        assertEquals(setOf("key", "progress"), progressJson.keySet())
        assertEquals(setOf("key", "item"), watchedJson.keySet())
    }

    @Test
    fun `entries round trip`() {
        val progress = PendingProgressUpsert("k", progress())
        val watched = PendingWatchedUpsert(WatchedMutationKey("tt1", 1, 2), watched())

        assertEquals(progress, PendingWatchStateDecoder.progressUpsert(gson, gson.toJson(progress)))
        assertEquals(watched, PendingWatchStateDecoder.watchedUpsert(gson, gson.toJson(watched)))
    }

    @Test
    fun `progress entry written by a minified build is still read`() {
        val progressJson = gson.toJson(progress())
        val legacy = """{"a":"k","b":$progressJson}"""

        assertEquals(PendingProgressUpsert("k", progress()), PendingWatchStateDecoder.progressUpsert(gson, legacy))
    }

    @Test
    fun `watched entry written by a minified build is still read`() {
        val keyJson = gson.toJson(WatchedMutationKey("tt1", 1, 2))
        val itemJson = gson.toJson(watched())
        val legacy = """{"a":$keyJson,"b":$itemJson}"""

        assertEquals(
            PendingWatchedUpsert(WatchedMutationKey("tt1", 1, 2), watched()),
            PendingWatchStateDecoder.watchedUpsert(gson, legacy)
        )
    }

    @Test
    fun `a current entry with a damaged key is dropped, not read by its other values`() {
        val damaged = """{"key":5,"note":"other","progress":${gson.toJson(progress())}}"""

        assertNull(PendingWatchStateDecoder.progressUpsert(gson, damaged))
    }

    @Test
    fun `unreadable entries are dropped`() {
        assertNull(PendingWatchStateDecoder.progressUpsert(gson, "not json"))
        assertNull(PendingWatchStateDecoder.progressUpsert(gson, """{"key":"k","progress":{}}"""))
        assertNull(PendingWatchStateDecoder.watchedUpsert(gson, """{"key":{"contentId":"tt1"}}"""))
    }

    private fun progress() = WatchProgress(
        contentId = "tt1",
        contentType = "series",
        name = "Show",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "tt1:1:2",
        season = 1,
        episode = 2,
        episodeTitle = null,
        position = 1_000L,
        duration = 10_000L,
        lastWatched = 5L
    )

    private fun watched() = WatchedItem(
        contentId = "tt1",
        contentType = "series",
        title = "Show",
        season = 1,
        episode = 2,
        watchedAt = 7L
    )
}
