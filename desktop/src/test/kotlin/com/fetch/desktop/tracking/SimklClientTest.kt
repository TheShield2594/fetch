package com.fetch.desktop.tracking

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimklClientTest {

    private fun client(fake: FakeHttp) = SimklClient(fake.client, testCredentials, appVersion = "test")

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    @Test
    fun `pin start returns the code and interval`() = runBlocking {
        val fake = FakeHttp.always(
            200,
            """{"result":"OK","device_code":"DEVICE_CODE","user_code":"ABCDE",
               "verification_url":"https://simkl.com/pin/","expires_in":900,"interval":5}"""
        )
        assertEquals(DeviceCode("ABCDE", "https://simkl.com/pin/", "ABCDE", 5, 900), client(fake).startPinAuth())
        val url = fake.sent.single().request.url
        assertEquals("/oauth/pin", url.encodedPath)
        assertEquals("simkl-id", url.queryParameter("client_id"))
        assertEquals("fetch", url.queryParameter("app-name"))
        assertEquals("test", url.queryParameter("app-version"))
    }

    @Test
    fun `pin poll maps OK and KO`() = runBlocking {
        val ok = FakeHttp.always(200, """{"result":"OK","access_token":"tok"}""")
        assertEquals(TokenPoll.Success(TrackingTokens("tok", null, null)), client(ok).pollPin("ABCDE"))
        assertEquals("/oauth/pin/ABCDE", ok.sent.single().request.url.encodedPath)

        val ko = FakeHttp.always(200, """{"result":"KO","message":"Authorization pending"}""")
        assertEquals(TokenPoll.Pending, client(ko).pollPin("ABCDE"))
    }

    @Test
    fun `episode scrobble body and headers`() = runBlocking {
        val fake = FakeHttp.always(201, "{}")
        client(fake).scrobble(
            testTokens, ScrobbleAction.STOP,
            TrackedItem("tt0903747", "series", 1, 2, title = "Breaking Bad", year = 2008), 91.234f
        )
        val sent = fake.sent.single()
        assertEquals("/scrobble/stop", sent.request.url.encodedPath)
        assertEquals("Bearer access-1", sent.request.header("Authorization"))
        assertEquals("simkl-id", sent.request.header("simkl-api-key"))
        assertEquals(
            json(
                """{"progress":91.23,"show":{"title":"Breaking Bad","year":2008,"ids":{"imdb":"tt0903747"}},
                   "episode":{"season":1,"number":2}}"""
            ),
            json(sent.body)
        )
    }

    @Test
    fun `movie scrobble body`() = runBlocking {
        val fake = FakeHttp.always(201, "{}")
        client(fake).scrobble(testTokens, ScrobbleAction.START, TrackedItem("tt1375666", "movie"), 0f)
        assertEquals(json("""{"progress":0.0,"movie":{"ids":{"imdb":"tt1375666"}}}"""), json(fake.sent.single().body))
    }

    @Test
    fun `a 409 on stop is success`() = runBlocking {
        client(FakeHttp.always(409)).scrobble(testTokens, ScrobbleAction.STOP, TrackedItem("tt1", "movie"), 95f)
    }

    @Test(expected = TrackingAuthException::class)
    fun `401 throws TrackingAuthException`(): Unit = runBlocking {
        client(FakeHttp.always(401)).playback(testTokens)
    }

    @Test
    fun `playback parses movies and episodes`() = runBlocking {
        val fake = FakeHttp.always(
            200,
            """[{"id":1,"progress":33.3,"paused_at":"2024-01-01T10:00:00Z","type":"episode",
                 "episode":{"season":1,"number":4,"title":"Ep"},
                 "show":{"title":"Show","year":2020,"ids":{"simkl":5,"imdb":"tt9"}}},
                {"id":2,"progress":5,"paused_at":"2023-01-01T10:00:00Z","type":"movie",
                 "movie":{"title":"Film","year":2001,"ids":{"imdb":"tt8"}}}]"""
        )
        assertEquals(
            listOf(
                RemotePlayback(TrackedItem("tt9", "series", 1, 4, "Show", 2020), 33.3f, 1_704_103_200_000L, "Show", "Ep"),
                RemotePlayback(TrackedItem("tt8", "movie", title = "Film", year = 2001), 5f, 1_672_567_200_000L, null, null),
            ),
            client(fake).playback(testTokens)
        )
    }

    @Test
    fun `watched and watchlist read all-items`() = runBlocking {
        val fake = FakeHttp { request ->
            val path = request.url.encodedPath
            200 to when {
                path == "/sync/all-items/movies" ->
                    """{"movies":[{"status":"completed","movie":{"title":"A","ids":{"imdb":"tt1"}}},
                                  {"status":"plantowatch","movie":{"title":"B","ids":{"imdb":"tt2"}}}]}"""
                path == "/sync/all-items/shows" ->
                    """{"shows":[{"status":"watching","show":{"title":"S","ids":{"imdb":"tt3"}},
                       "seasons":[{"number":1,"episodes":[{"number":1,"watched_at":"2024-01-01T00:00:00Z"},{"number":2}]}]}]}"""
                path == "/sync/all-items/movies/plantowatch" ->
                    """{"movies":[{"status":"plantowatch","added_to_watchlist_at":"2024-01-01T00:00:00Z",
                       "movie":{"title":"B","year":1999,"ids":{"imdb":"tt2"}}}]}"""
                else -> "null"
            }
        }
        assertEquals(RemoteWatched(setOf("tt1"), mapOf("tt3" to setOf(1 to 1))), client(fake).watched(testTokens))
        assertEquals(
            listOf(RemoteListItem("tt2", "movie", "B", 1999, 1_704_067_200_000L)),
            client(fake).watchlist(testTokens)
        )
    }

    @Test
    fun `history at a given time names it`() = runBlocking {
        val at = java.time.Instant.parse("2019-03-01T12:00:00Z").toEpochMilli()
        val fake = FakeHttp.always(201)
        client(fake).addToHistory(testTokens, TrackedItem("tt2", "series", 3, 4, title = "Show"), watchedAtMs = at)
        assertEquals(
            json("""{"shows":[{"title":"Show","ids":{"imdb":"tt2"},"seasons":[{"number":3,"episodes":[{"number":4,"watched_at":"2019-03-01T12:00:00Z"}]}]}]}"""),
            json(fake.sent.single().body)
        )
    }

    @Test
    fun `watchlist toggles use add-to-list and history remove`() = runBlocking {
        val fake = FakeHttp.always(201)
        client(fake).setWatchlisted(testTokens, TrackedItem("tt1", "movie", title = "A"), on = true)
        client(fake).setWatchlisted(testTokens, TrackedItem("tt2", "series"), on = false)
        assertEquals("/sync/add-to-list", fake.sent[0].request.url.encodedPath)
        assertEquals(
            json("""{"movies":[{"to":"plantowatch","title":"A","ids":{"imdb":"tt1"}}]}"""),
            json(fake.sent[0].body)
        )
        assertEquals("/sync/history/remove", fake.sent[1].request.url.encodedPath)
        assertEquals(json("""{"shows":[{"ids":{"imdb":"tt2"}}]}"""), json(fake.sent[1].body))
        assertTrue(fake.sent.all { it.request.url.queryParameter("client_id") == "simkl-id" })
    }
}
