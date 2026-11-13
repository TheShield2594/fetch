package com.fetch.desktop.tracking

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TraktClientTest {

    private fun client(fake: FakeHttp) = TraktClient(fake.client, testCredentials, appVersion = "test")

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    @Test
    fun `device token statuses map to poll results`() = runBlocking {
        assertEquals(TokenPoll.Pending, client(FakeHttp.always(400)).pollDeviceToken("dc"))
        assertEquals(TokenPoll.SlowDown, client(FakeHttp.always(429)).pollDeviceToken("dc"))
        assertEquals(TokenPoll.Expired, client(FakeHttp.always(410)).pollDeviceToken("dc"))
        assertEquals(TokenPoll.Denied, client(FakeHttp.always(418)).pollDeviceToken("dc"))
        assertTrue(client(FakeHttp.always(404)).pollDeviceToken("dc") is TokenPoll.Failed)
        assertTrue(client(FakeHttp.always(409)).pollDeviceToken("dc") is TokenPoll.Failed)
    }

    @Test
    fun `an approved device token computes its expiry`() = runBlocking {
        val fake = FakeHttp.always(
            200,
            """{"access_token":"a","token_type":"bearer","expires_in":7776000,"refresh_token":"r",
               "scope":"public","created_at":1700000000}"""
        )
        val poll = client(fake).pollDeviceToken("dc")
        assertEquals(TokenPoll.Success(TrackingTokens("a", "r", 1_700_000_000L + 7_776_000L)), poll)
        val sent = fake.sent.single()
        assertEquals("/oauth/device/token", sent.request.url.encodedPath)
        assertEquals(json("""{"code":"dc","client_id":"trakt-id","client_secret":"trakt-secret"}"""), json(sent.body))
        assertEquals(null, sent.request.header("Authorization"))
    }

    @Test
    fun `refresh sends the refresh grant`() = runBlocking {
        val fake = FakeHttp.always(200, """{"access_token":"a2","refresh_token":"r2","expires_in":10,"created_at":5}""")
        assertEquals(TrackingTokens("a2", "r2", 15), client(fake).refresh(testTokens))
        assertEquals(
            json(
                """{"refresh_token":"refresh-1","client_id":"trakt-id","client_secret":"trakt-secret",
                   "redirect_uri":"urn:ietf:wg:oauth:2.0:oob","grant_type":"refresh_token"}"""
            ),
            json(fake.sent.single().body)
        )
    }

    @Test
    fun `movie scrobble body and headers`() = runBlocking {
        val fake = FakeHttp.always(201, """{"action":"start"}""")
        client(fake).scrobble(testTokens, ScrobbleAction.START, TrackedItem("tt0111161", "movie"), 12.3456f)
        val sent = fake.sent.single()
        assertEquals("POST", sent.request.method)
        assertEquals("https://api.trakt.tv/scrobble/start", sent.request.url.toString())
        assertEquals("trakt-id", sent.request.header("trakt-api-key"))
        assertEquals("2", sent.request.header("trakt-api-version"))
        assertEquals("Bearer access-1", sent.request.header("Authorization"))
        assertEquals(json("""{"movie":{"ids":{"imdb":"tt0111161"}},"progress":12.35}"""), json(sent.body))
    }

    @Test
    fun `episode scrobble body`() = runBlocking {
        val fake = FakeHttp.always(201)
        client(fake).scrobble(testTokens, ScrobbleAction.PAUSE, TrackedItem("tt0903747", "series", 2, 5), 50f)
        val sent = fake.sent.single()
        assertEquals("/scrobble/pause", sent.request.url.encodedPath)
        assertEquals(
            json("""{"show":{"ids":{"imdb":"tt0903747"}},"episode":{"season":2,"number":5},"progress":50.0}"""),
            json(sent.body)
        )
    }

    @Test
    fun `items without an imdb id or an episode are not scrobbled`() = runBlocking {
        val fake = FakeHttp.always(201)
        client(fake).scrobble(testTokens, ScrobbleAction.START, TrackedItem("tmdb:123", "movie"), 1f)
        client(fake).scrobble(testTokens, ScrobbleAction.START, TrackedItem("tt0903747", "series"), 1f)
        assertTrue(fake.sent.isEmpty())
    }

    @Test
    fun `playback parses movies and episodes, newest first`() = runBlocking {
        val fake = FakeHttp.always(
            200,
            """[
              {"progress":10.5,"paused_at":"2024-01-01T10:00:00.000Z","id":1,"type":"movie",
               "movie":{"title":"Inception","year":2010,"ids":{"trakt":1,"imdb":"tt1375666"}}},
              {"progress":42.0,"paused_at":"2024-02-01T10:00:00.000Z","id":2,"type":"episode",
               "episode":{"season":1,"number":3,"title":"...And the Bag's in the River","ids":{}},
               "show":{"title":"Breaking Bad","year":2008,"ids":{"imdb":"tt0903747"}}},
              {"progress":5,"paused_at":"2024-03-01T10:00:00.000Z","type":"movie","movie":{"title":"No ids","ids":{}}}
            ]"""
        )
        val playback = client(fake).playback(testTokens)
        assertEquals("/sync/playback", fake.sent.single().request.url.encodedPath)
        assertEquals(
            listOf(
                RemotePlayback(
                    TrackedItem("tt0903747", "series", 1, 3, "Breaking Bad", 2008),
                    42f, 1_706_781_600_000L, "Breaking Bad", "...And the Bag's in the River"
                ),
                RemotePlayback(
                    TrackedItem("tt1375666", "movie", title = "Inception", year = 2010),
                    10.5f, 1_704_103_200_000L, null, null
                ),
            ),
            playback
        )
    }

    @Test
    fun `watched collects movies and episodes`() = runBlocking {
        val fake = FakeHttp { request ->
            200 to when (request.url.encodedPath) {
                "/sync/watched/movies" -> """[{"plays":1,"movie":{"ids":{"imdb":"tt1"}}},{"movie":{"ids":{}}}]"""
                else -> """[{"show":{"ids":{"imdb":"tt2"}},"seasons":[{"number":1,"episodes":[{"number":1},{"number":2}]}]}]"""
            }
        }
        assertEquals(
            RemoteWatched(setOf("tt1"), mapOf("tt2" to setOf(1 to 1, 1 to 2))),
            client(fake).watched(testTokens)
        )
    }

    @Test
    fun `watchlist toggles post to add and remove`() = runBlocking {
        val fake = FakeHttp.always(201)
        client(fake).setWatchlisted(testTokens, TrackedItem("tt1", "movie"), on = true)
        client(fake).setWatchlisted(testTokens, TrackedItem("tt2", "series", 1, 1), on = false)
        assertEquals("/sync/watchlist", fake.sent[0].request.url.encodedPath)
        assertEquals(json("""{"movies":[{"ids":{"imdb":"tt1"}}]}"""), json(fake.sent[0].body))
        assertEquals("/sync/watchlist/remove", fake.sent[1].request.url.encodedPath)
        assertEquals(json("""{"shows":[{"ids":{"imdb":"tt2"}}]}"""), json(fake.sent[1].body))
    }

    @Test
    fun `history for an episode nests season and episode`() = runBlocking {
        val fake = FakeHttp.always(201)
        client(fake).addToHistory(testTokens, TrackedItem("tt2", "series", 3, 4))
        assertEquals(
            json("""{"shows":[{"ids":{"imdb":"tt2"},"seasons":[{"number":3,"episodes":[{"number":4}]}]}]}"""),
            json(fake.sent.single().body)
        )
    }

    @Test
    fun `history at a given time names it, for a movie and an episode`() = runBlocking {
        val at = java.time.Instant.parse("2019-03-01T12:00:00Z").toEpochMilli()
        val fake = FakeHttp.always(201)
        client(fake).addToHistory(testTokens, TrackedItem("tt1", "movie"), watchedAtMs = at)
        client(fake).addToHistory(testTokens, TrackedItem("tt2", "series", 3, 4), watchedAtMs = at)
        assertEquals(
            json("""{"movies":[{"ids":{"imdb":"tt1"},"watched_at":"2019-03-01T12:00:00Z"}]}"""),
            json(fake.sent[0].body)
        )
        assertEquals(
            json("""{"shows":[{"ids":{"imdb":"tt2"},"seasons":[{"number":3,"episodes":[{"number":4,"watched_at":"2019-03-01T12:00:00Z"}]}]}]}"""),
            json(fake.sent[1].body)
        )
    }

    @Test(expected = TrackingAuthException::class)
    fun `401 throws TrackingAuthException`(): Unit = runBlocking {
        client(FakeHttp.always(401)).playback(testTokens)
    }

    @Test(expected = TrackingAuthException::class)
    fun `a refused refresh token throws TrackingAuthException`(): Unit = runBlocking {
        client(FakeHttp.always(400)).refresh(testTokens)
    }

    @Test
    fun `a duplicate scrobble is not an error`() = runBlocking {
        client(FakeHttp.always(409)).scrobble(testTokens, ScrobbleAction.STOP, TrackedItem("tt1", "movie"), 95f)
    }
}
