package com.fetch.desktop.tracking

import com.fetch.desktop.data.DesktopSettings
import com.fetch.desktop.data.ProgressEntry
import com.fetch.desktop.data.ProgressFile
import com.fetch.desktop.sync.SyncSettingsBlob
import com.fetch.desktop.sync.applyTo
import com.fetch.desktop.sync.changedFrom
import com.fetch.desktop.sync.toRemoteSharedSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingOptionsTest {
    private fun entry(id: String, at: Long, source: String? = null) =
        ProgressEntry(contentId = id, contentType = "movie", name = id, videoId = id, positionMs = 1, durationMs = 10, lastWatched = at, source = source)

    @Test
    fun `the Continue Watching window drops what's older, and 0 keeps everything`() {
        val day = 86_400_000L
        val now = 100 * day
        val file = ProgressFile(entries = listOf(entry("new", now - 2 * day), entry("old", now - 90 * day)))
        assertEquals(listOf("new"), withinWindow(file, 60, now).entries.map { it.contentId })
        assertEquals(2, withinWindow(file, 0, now).entries.size)
        assertEquals(0, TrackingOptions.normalizeDays(0))
        assertEquals(7, TrackingOptions.normalizeDays(3))
    }

    @Test
    fun `only the chosen service's resume points count while it's connected`() {
        val playback = listOf(entry("a", 1, "trakt"), entry("b", 1, "simkl"))
        val both = setOf(WatchProgressSourceOption.TRAKT, WatchProgressSourceOption.SIMKL)
        assertEquals(listOf("b"), playbackForSource(playback, WatchProgressSourceOption.SIMKL, both).map { it.contentId })
        assertEquals(emptyList<ProgressEntry>(), playbackForSource(playback, WatchProgressSourceOption.FETCH_SYNC, both + WatchProgressSourceOption.FETCH_SYNC))
        // Not connected: everything counts, as before.
        assertEquals(2, playbackForSource(playback, WatchProgressSourceOption.MDBLIST, both).size)
    }

    @Test
    fun `simkl anime ids follow the preference with the TV's fallbacks`() {
        val ids = mapOf("imdb" to "tt1", "mal" to "5", "kitsu" to "7", "tvdb" to "9")
        assertEquals("mal:5", simklContentId(ids, true, SimklAnimeIdOption.MAL))
        assertEquals("kitsu:7", simklContentId(ids, true, SimklAnimeIdOption.KITSU))
        assertEquals("tvdb:9", simklContentId(ids, true, SimklAnimeIdOption.TVDB))
        assertEquals("tt1", simklContentId(ids, true, SimklAnimeIdOption.IMDB))
        assertEquals("tt1", simklContentId(ids, false, SimklAnimeIdOption.MAL))
        assertEquals("kitsu:7", simklContentId(ids - "mal", true, SimklAnimeIdOption.MAL))
        assertEquals("mal:5", simklContentId(mapOf("mal" to "5"), true, SimklAnimeIdOption.IMDB))
    }

    @Test
    fun `the TV's trakt_settings apply here and changes go back key by key`() {
        val blob = Json.parseToJsonElement(
            """{"version":1,"features":{"trakt_settings":{
                "library_source_mode":{"type":"string","value":"FETCH_WEBSITE"},
                "watch_progress_source":{"type":"string","value":"SIMKL"},
                "continue_watching_days_cap":{"type":"int","value":30},
                "show_meta_comments":{"type":"boolean","value":false},
                "dismissed_next_up_keys":{"type":"string_set","value":["x"]}}}}"""
        ).jsonObject
        val applied = SyncSettingsBlob.sharedSettings(blob).applyTo(DesktopSettings()).tracking
        assertEquals("FETCH_WEBSITE", applied.librarySource)
        assertEquals(LibrarySourceOption.FETCH_WEBSITE, applied.library)
        // A source this build doesn't know falls back to the default.
        assertEquals(LibrarySourceOption.TRAKT, applied.copy(librarySource = "SOMETHING_NEW").library)
        assertEquals(WatchProgressSourceOption.SIMKL, applied.progress)
        assertEquals(30, applied.continueWatchingDays)
        assertFalse(applied.showComments)

        val before = DesktopSettings(tracking = applied).toRemoteSharedSettings()
        val after = DesktopSettings(tracking = applied.copy(moreLikeThisSource = "TMDB", continueWatchingDays = 0)).toRemoteSharedSettings()
        val diff = after.changedFrom(before)!!
        assertEquals(mapOf("more_like_this_source" to "TMDB"), diff.trackingStrings)
        assertEquals(mapOf("continue_watching_days_cap" to 0), diff.trackingInts)
        assertNull(diff.trackingFlags)
        val merged = SyncSettingsBlob.withSharedSettings(blob, diff)
        val trakt = merged["features"]!!.jsonObject["trakt_settings"]!!.jsonObject
        assertEquals(Json.parseToJsonElement("""{"type":"int","value":0}"""), trakt["continue_watching_days_cap"])
        assertTrue("other keys stay", "dismissed_next_up_keys" in trakt)
        assertEquals(Json.parseToJsonElement("""{"type":"string","value":"FETCH_WEBSITE"}"""), trakt["library_source_mode"])
    }

    @Test
    fun `trakt related and comments`() = runBlocking {
        val fake = FakeHttp { request ->
            when {
                request.url.encodedPath.endsWith("/related") -> 200 to
                    """[{"title":"Heat","year":1995,"ids":{"trakt":1,"imdb":"tt0113277"},"images":{"poster":["walter.trakt.tv/p.jpg"]}}]"""
                else -> 200 to
                    """[{"id":9,"comment":"Great","spoiler":true,"likes":4,"user":{"username":"sam"},"user_stats":{"rating":8}},{"id":10,"comment":" "}]"""
            }
        }
        val client = TraktClient(fake.client, testCredentials, appVersion = "test")
        val related = client.related(null, "tt0111161", isShow = false)
        assertEquals("tt0113277", related.single().id)
        assertEquals("https://walter.trakt.tv/p.jpg", related.single().poster)
        assertEquals("/movies/tt0111161/related", fake.sent[0].request.url.encodedPath)
        val comments = client.comments(null, "tt1", isShow = true)
        assertEquals(1, comments.size)
        assertEquals("sam", comments[0].author)
        assertEquals(8, comments[0].rating)
        assertTrue(comments[0].spoiler)
        assertEquals("/shows/tt1/comments/likes", fake.sent[1].request.url.encodedPath)
    }

    @Test
    fun `simkl related resolves the id, then reads recommendations`() = runBlocking {
        val fake = FakeHttp { request ->
            when {
                request.url.encodedPath == "/search/id" -> 200 to """[{"type":"anime","ids":{"simkl":42}}]"""
                request.url.encodedPath == "/anime/42" -> 200 to
                    """{"users_recommendations":[{"title":"A","year":2020,"type":"anime","poster":"ab/cd","ids":{"simkl":1,"mal":55,"imdb":"tt9"}}],
                        "similar":[{"title":"A","ids":{"simkl":1}},{"title":"B","type":"movie","ids":{"simkl":2,"imdb":"tt8"}}]}"""
                else -> 404 to ""
            }
        }
        val client = SimklClient(fake.client, testCredentials, appVersion = "test", animeIdPreference = { SimklAnimeIdOption.MAL })
        val related = client.related("tt0111161")
        assertEquals(listOf("mal:55", "tt8"), related.map { it.id })
        assertTrue(related[1].isMovie)
        assertTrue(related[0].poster!!.contains("simkl.in/posters/ab/cd_m.webp"))
    }
}
