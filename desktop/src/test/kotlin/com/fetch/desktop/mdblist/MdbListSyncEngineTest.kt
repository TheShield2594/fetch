package com.fetch.desktop.mdblist

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class MdbListSyncEngineTest {
    private val auth = MdbListAuth.ApiKey("k")

    /** A fake MDBList whose answers the test changes between syncs. */
    private class Server {
        var activities = activities("2024-06-01T00:00:00Z", watched = "2024-05-01T00:00:00Z")
        var watched = """{"movies":[{"movie":{"ids":{"imdb":"tt1","tmdb":11}}}],
            "shows":[{"show":{"ids":{"imdb":"tt2"}},"seasons":[{"number":1,"episodes":[{"number":1},{"number":2}]}]}]}"""
        var playback = """[
            {"id":1,"type":"episode","progress":30,"paused_at":"2024-05-02T10:00:00Z","show":{"title":"Lost","ids":{"imdb":"tt2"}},"episode":{"season":1,"number":3}},
            {"id":2,"type":"episode","progress":40,"paused_at":"2024-05-02T10:00:00Z","show":{"title":"Dropped","ids":{"tmdb":77}},"episode":{"season":2,"number":1}},
            {"id":3,"type":"movie","progress":20,"paused_at":"2024-05-02T10:00:00Z","movie":{"title":"Anime film","poster":"/p.jpg","ids":{"tmdb":99}}}
        ]"""
        var dropped = """{"shows":[{"show":{"ids":{"tmdb":77,"imdb":"tt77"}}}]}"""
        var seasonsDropped = """{"seasons":[]}"""
        var journal: (String?) -> FakeReply = { FakeReply(200, """{"journal":[]}""") }

        val fake = MdbListFakeHttp { request, _ ->
            when (request.url.encodedPath) {
                "/sync/last_activities" -> FakeReply(200, activities)
                "/sync/watched" -> FakeReply(200, watched)
                "/sync/playback" -> FakeReply(200, playback)
                "/sync/dropped" -> FakeReply(200, dropped)
                "/sync/seasons/dropped" -> FakeReply(200, seasonsDropped)
                "/sync/journal" -> journal(request.url.queryParameter("since"))
                else -> FakeReply(404, "{}")
            }
        }

        fun paths(): List<String> = fake.sent.map { it.path }

        companion object {
            fun activities(server: String, watched: String, paused: String = "2024-05-02T10:00:00Z", dropped: String = "2024-04-01T00:00:00Z") =
                """{"server_time":"$server","watched_at":"$watched","episode_watched_at":"$watched","journal_at":"$watched",
                    "paused_at":"$paused","dropped_at":"$dropped"}"""
        }
    }

    private fun engine(server: Server) = MdbListSyncEngine(MdbListSyncClient(server.fake.http()), now = { 5L })

    @Test
    fun `the first sync fetches everything, then only what changed`() = runBlocking {
        val server = Server()
        val engine = engine(server)
        val first = engine.synchronize(auth, 7, null)
        assertEquals(
            listOf("/sync/last_activities", "/sync/watched", "/sync/playback", "/sync/dropped", "/sync/seasons/dropped"),
            server.paths()
        )
        assertEquals(setOf("tt1", "tt2:1:1", "tt2:1:2"), first.watchedIds())
        assertEquals("2024-06-01T00:00:00Z", first.watermark)

        // Nothing moved: only the activities are asked.
        server.fake.sent.clear()
        server.activities = Server.activities("2024-06-01T01:00:00Z", watched = "2024-05-01T00:00:00Z")
        val quiet = engine.synchronize(auth, 7, first)
        assertEquals(listOf("/sync/last_activities"), server.paths())
        assertEquals(first.watched, quiet.watched)
        assertEquals("2024-06-01T01:00:00Z", quiet.watermark)

        // An episode watched and a movie removed elsewhere: the journal since the last sync.
        server.fake.sent.clear()
        server.activities = Server.activities("2024-06-02T00:00:00Z", watched = "2024-06-01T12:00:00Z")
        server.journal = { since ->
            assertEquals("2024-06-01T01:00:00Z", since)
            FakeReply(200, """{"journal":[
                {"category":"watched","item_type":"episode","status":"added","ids":{"imdb":"tt2"},"season":1,"episode":3,
                 "action_at":"2024-06-01T12:00:00Z","value_at":"2024-06-01T12:00:00Z"},
                {"category":"watched","item_type":"movie","status":"removed","ids":{"tmdb":11},"action_at":"2024-06-01T12:01:00Z"},
                {"category":"rated","item_type":"movie","status":"added","ids":{"imdb":"tt9"},"action_at":"2024-06-01T12:02:00Z"}
            ]}""")
        }
        val changed = engine.synchronize(auth, 7, quiet)
        assertEquals(listOf("/sync/last_activities", "/sync/journal"), server.paths())
        // The removal named the movie by its TMDB id only, and still matched it.
        assertEquals(setOf("tt2:1:1", "tt2:1:2", "tt2:1:3"), changed.watchedIds())
    }

    @Test
    fun `a journal MDBList can't answer, or a whole show marked watched, means the full history`() = runBlocking {
        val server = Server()
        val engine = engine(server)
        val first = engine.synchronize(auth, 7, null)
        server.activities = Server.activities("2024-06-02T00:00:00Z", watched = "2024-06-01T12:00:00Z")

        server.fake.sent.clear()
        server.journal = { FakeReply(409, """{"error":"cursor_expired"}""") }
        engine.synchronize(auth, 7, first)
        assertEquals(listOf("/sync/last_activities", "/sync/journal", "/sync/watched"), server.paths())

        server.fake.sent.clear()
        server.journal = { FakeReply(200, """{"requires_full_sync":true}""") }
        engine.synchronize(auth, 7, first)
        assertEquals(listOf("/sync/last_activities", "/sync/journal", "/sync/watched"), server.paths())

        server.fake.sent.clear()
        server.journal = {
            FakeReply(200, """{"journal":[{"category":"watched","item_type":"show","status":"added","ids":{"imdb":"tt5"},
                "action_at":"2024-06-01T12:00:00Z","value_at":"2024-06-01T12:00:00Z"}]}""")
        }
        engine.synchronize(auth, 7, first)
        assertEquals(listOf("/sync/last_activities", "/sync/journal", "/sync/watched"), server.paths())
    }

    @Test
    fun `another account, or a last sync older than the journal keeps, syncs in full`() = runBlocking {
        val server = Server()
        val engine = engine(server)
        val first = engine.synchronize(auth, 7, null)

        server.fake.sent.clear()
        engine.synchronize(auth, 8, first)
        assertTrue("/sync/watched" in server.paths())

        server.fake.sent.clear()
        server.activities = Server.activities("2024-08-01T00:00:00Z", watched = "2024-05-01T00:00:00Z")
        val later = engine.synchronize(auth, 7, first)
        assertTrue("/sync/watched" in server.paths())
        assertEquals(7L, later.accountId)
    }

    @Test
    fun `resume points come back when they changed, and dropped shows stay out of Continue Watching`() = runBlocking {
        val server = Server()
        val engine = engine(server)
        val first = engine.synchronize(auth, 7, null)
        val entries = first.continueWatching()
        // The dropped show is matched by its TMDB id; the anime film keeps its own poster.
        assertEquals(listOf("tt2:1:3", "tmdb:99"), entries.map { it.videoId })
        assertEquals("https://image.tmdb.org/t/p/w500/p.jpg", entries[1].poster)
        assertTrue(first.isDropped(MdbListIds(imdb = "tt77")))

        server.fake.sent.clear()
        server.activities = Server.activities("2024-06-01T01:00:00Z", watched = "2024-05-01T00:00:00Z", paused = "2024-06-01T00:30:00Z")
        server.playback = "[]"
        val next = engine.synchronize(auth, 7, first)
        assertEquals(listOf("/sync/last_activities", "/sync/playback"), server.paths())
        assertTrue(next.continueWatching().isEmpty())

        // A dropped season hides only that season.
        server.fake.sent.clear()
        server.activities = Server.activities("2024-06-01T02:00:00Z", watched = "2024-05-01T00:00:00Z", dropped = "2024-06-01T01:30:00Z")
        server.dropped = """{"shows":[]}"""
        server.seasonsDropped = """{"seasons":[{"show":{"ids":{"imdb":"tt2"}},"season":{"number":1}}]}"""
        val seasons = engine.synchronize(auth, 7, first)
        assertEquals(listOf("/sync/last_activities", "/sync/dropped", "/sync/seasons/dropped"), server.paths())
        assertEquals(listOf("tmdb:77:2:1", "tmdb:99"), seasons.continueWatching().map { it.videoId })
    }

    @Test
    fun `activities MDBList doesn't explain still sync, in full`() = runBlocking {
        val server = Server()
        server.activities = """{"something":"else"}"""
        val state = engine(server).synchronize(auth, 7, null)
        assertNull(state.watermark)
        assertEquals(setOf("tt1", "tt2:1:1", "tt2:1:2"), state.watchedIds())
    }

    @Test
    fun `the cache keeps the last sync on disk`() {
        val store = mdbListSyncCacheFile(Files.createTempDirectory("mdb").toFile())
        assertNull(store.load())
        val cache = MdbListSyncCache(
            accountId = 3,
            watched = listOf(MdbListWatchedItem(MdbListIds(tmdb = 5), isMovie = true)),
            dropped = listOf(MdbListDropped(MdbListIds(imdb = "tt2"), season = 1)),
            activities = MdbListActivities(mapOf("watched_at" to "x"), "2024-06-01T00:00:00Z"),
            watermark = "2024-06-01T00:00:00Z"
        )
        store.save(cache)
        assertEquals(cache, store.load())
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun `content ids MDBList can match`() {
        assertEquals(MdbListIds(imdb = "tt123"), MdbListIds.fromContentId("tt123"))
        assertEquals(MdbListIds(tmdb = 5), MdbListIds.fromContentId("tmdb:5"))
        assertEquals(MdbListIds(tvdb = 81797), MdbListIds.fromContentId("tvdb:81797"))
        assertEquals(MdbListIds(trakt = 4), MdbListIds.fromContentId("trakt:4"))
        assertEquals(MdbListIds(mdblist = "abc"), MdbListIds.fromContentId("mdblist:abc"))
        assertNull(MdbListIds.fromContentId("kitsu:1"))
        assertNull(MdbListIds.fromContentId("tmdb:0"))
        assertFalse(MdbListIds(tmdb = 5).matches(MdbListIds(tvdb = 5)))
        assertEquals("tt1", MdbListIds(imdb = "tt1", tmdb = 2).contentId)
    }
}
