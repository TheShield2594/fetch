package com.fetch.desktop.sync

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SyncClientTest {

    private val changed = mutableListOf<SyncSession>()

    private fun client(http: SyncFakeHttp, session: SyncSession = testSession, now: Long = 1_000_000_000L) =
        SyncClient(
            http = http.client,
            auth = SyncAuthClient(http.client, testServer, nowEpochSec = { now }),
            session = session,
            originClientId = TEST_ORIGIN,
            onSessionChanged = { changed += it },
            nowEpochSec = { now },
        )

    private fun body(sent: SyncFakeHttp.Sent): JsonObject = Json.parseToJsonElement(sent.body).jsonObject

    /** Routes RPCs by name; anything else 404s. */
    private fun rpcs(vararg routes: Pair<String, String>): (Request) -> Pair<Int, String> {
        val map = routes.toMap()
        return { request ->
            val name = request.url.encodedPath.removePrefix("/rest/v1/rpc/")
            map[name]?.let { 200 to it } ?: (404 to "{}")
        }
    }

    @Test
    fun `rpc sends apikey and bearer token`() = runBlocking {
        val http = SyncFakeHttp(rpcs("sync_get_library_delta_cursor" to "42"))
        assertEquals(42L, client(http).libraryDeltaCursor(3))
        val sent = http.sent.single()
        assertEquals("POST", sent.request.method)
        assertEquals("https://sync.example.com/rest/v1/rpc/sync_get_library_delta_cursor", sent.request.url.toString())
        assertEquals("anon-key", sent.header("apikey"))
        assertEquals("Bearer access-1", sent.header("Authorization"))
        assertEquals(3, body(sent)["p_profile_id"]!!.jsonPrimitive.int)
    }

    @Test
    fun `a 401 refreshes the token once and retries`() = runBlocking {
        val http = SyncFakeHttp { request ->
            when {
                request.url.encodedPath == "/auth/v1/token" -> 200 to REFRESHED_TOKENS
                request.header("Authorization") == "Bearer access-1" -> 401 to """{"code":"PGRST301","message":"JWT expired"}"""
                else -> 200 to "7"
            }
        }
        val sync = client(http)
        assertEquals(7L, sync.progressDeltaCursor(1))

        assertEquals(listOf("/rest/v1/rpc/sync_get_watch_progress_delta_cursor", "/auth/v1/token", "/rest/v1/rpc/sync_get_watch_progress_delta_cursor"), http.sent.map { it.path })
        assertEquals("refresh_token", http.sent[1].request.url.queryParameter("grant_type"))
        assertEquals("refresh-1", body(http.sent[1])["refresh_token"]!!.jsonPrimitive.content)
        assertEquals("Bearer access-2", http.sent[2].header("Authorization"))
        assertEquals("access-2", sync.session.accessToken)
        assertEquals(listOf(sync.session), changed)
    }

    @Test
    fun `a rejected refresh is an auth error`() = runBlocking {
        val http = SyncFakeHttp { request ->
            if (request.url.encodedPath == "/auth/v1/token") 400 to """{"error_description":"Invalid Refresh Token: Already Used"}"""
            else 401 to """{"message":"JWT expired"}"""
        }
        try {
            client(http).pullProfiles()
            fail("expected SyncAuthException")
        } catch (e: SyncAuthException) {
            assertEquals("Invalid Refresh Token: Already Used", e.message)
        }
        assertEquals(2, http.sent.size)
        assertTrue(changed.isEmpty())
    }

    @Test
    fun `a 401 after refreshing is an auth error`() = runBlocking {
        val http = SyncFakeHttp { request ->
            if (request.url.encodedPath == "/auth/v1/token") 200 to REFRESHED_TOKENS else 401 to "{}"
        }
        try {
            client(http).pullProfiles()
            fail("expected SyncAuthException")
        } catch (_: SyncAuthException) {
        }
        assertEquals(3, http.sent.size)
    }

    @Test
    fun `an expiring token is refreshed before the call`() = runBlocking {
        val http = SyncFakeHttp { request ->
            if (request.url.encodedPath == "/auth/v1/token") 200 to REFRESHED_TOKENS else 200 to "[]"
        }
        client(http, testSession.copy(expiresAtEpochSec = 1_000_000_030L)).pullProfiles()
        assertEquals(listOf("/auth/v1/token", "/rest/v1/rpc/sync_pull_profiles"), http.sent.map { it.path })
        assertEquals("Bearer access-2", http.sent[1].header("Authorization"))
    }

    @Test
    fun `other errors carry the status and the PostgREST message`() = runBlocking {
        val http = SyncFakeHttp { 500 to """{"message":"boom"}""" }
        try {
            client(http).pullProfiles()
            fail("expected SyncException")
        } catch (e: SyncException) {
            assertFalse(e is SyncAuthException)
            assertEquals(500, e.code)
            assertTrue(e.message!!.contains("boom"))
        }
    }

    @Test
    fun `pull library pages by offset and parses rows`() = runBlocking {
        val http = SyncFakeHttp { request ->
            val offset = Json.parseToJsonElement(okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                .jsonObject["p_offset"]!!.jsonPrimitive.int
            200 to when (offset) {
                0 -> """[
                    {"id":"r1","user_id":"u","content_id":"tt1","content_type":"movie","name":"One","poster":"p1","poster_shape":"landscape","imdb_rating":7.5,"genres":["Drama"],"addon_base_url":"https://a","added_at":10,"profile_id":2},
                    {"content_id":"tt2","content_type":"series","name":"Two","poster_shape":"weird"}
                ]"""
                else -> """[{"content_id":"tt3","content_type":"movie"}]"""
            }
        }
        val items = client(http).pullLibrary(profileId = 2, pageSize = 2)
        assertEquals(listOf(0, 2), http.sent.map { body(it)["p_offset"]!!.jsonPrimitive.int })
        assertTrue(http.sent.all { body(it)["p_limit"]!!.jsonPrimitive.int == 2 && body(it)["p_profile_id"]!!.jsonPrimitive.int == 2 })
        assertEquals(
            RemoteLibraryItem("tt1", "movie", "One", "p1", "LANDSCAPE", imdbRating = 7.5f, genres = listOf("Drama"), addonBaseUrl = "https://a", addedAtEpochMs = 10),
            items[0],
        )
        assertEquals("POSTER", items[1].posterShape)
        assertEquals(3, items.size)
    }

    @Test
    fun `library delta marks deletes`() = runBlocking {
        val http = SyncFakeHttp(rpcs("sync_pull_library_delta" to """[
            {"event_id":5,"operation":"upsert","content_id":"tt1","content_type":"movie","name":"One"},
            {"event_id":6,"operation":"delete","content_id":"tt2","content_type":"series"}
        ]"""))
        val events = client(http).pullLibraryDelta(1, sinceEventId = 4)
        assertEquals(listOf(5L to false, 6L to true), events.map { it.eventId to it.deleted })
        assertEquals(RemoteLibraryKey("tt2", "series"), events[1].item.key)
        val sent = body(http.sent.single())
        assertEquals(4L, sent["p_since_event_id"]!!.jsonPrimitive.long)
        assertEquals(500, sent["p_limit"]!!.jsonPrimitive.int)
    }

    @Test
    fun `push library sends every field, nulls included, with the origin id`() = runBlocking {
        val http = SyncFakeHttp { 204 to "" }
        client(http).pushLibrary(1, listOf(RemoteLibraryItem("tt1", "movie", "One", posterShape = "square", genres = listOf("A"), addedAtEpochMs = 9)))
        val sent = body(http.sent.single())
        assertEquals("/rest/v1/rpc/sync_push_library_items", http.sent.single().path)
        val item = sent["p_items"]!!.jsonArray.single().jsonObject
        assertEquals(
            setOf("content_id", "content_type", "name", "poster", "poster_shape", "background", "description", "release_info", "imdb_rating", "genres", "addon_base_url", "added_at"),
            item.keys,
        )
        assertEquals(JsonNull, item["poster"])
        assertEquals("SQUARE", item["poster_shape"]!!.jsonPrimitive.content)
        assertEquals(9L, item["added_at"]!!.jsonPrimitive.long)
        assertEquals(1, sent["p_profile_id"]!!.jsonPrimitive.int)
        assertEquals(TEST_ORIGIN, sent["p_origin_client_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `delete library sends content keys`() = runBlocking {
        val http = SyncFakeHttp { 204 to "" }
        client(http).deleteLibrary(1, listOf(RemoteLibraryKey("tt1", "movie"), RemoteLibraryKey("tt1", "movie")))
        val sent = body(http.sent.single())
        assertEquals("/rest/v1/rpc/sync_delete_library_items", http.sent.single().path)
        assertEquals("""[{"content_id":"tt1","content_type":"movie"}]""", sent["p_keys"].toString())
        assertEquals(TEST_ORIGIN, sent["p_origin_client_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `push progress uses the TV app's keys and skips placeholders`() = runBlocking {
        val http = SyncFakeHttp { 204 to "" }
        client(http).pushProgress(
            4,
            listOf(
                RemoteProgress("tt1", "movie", "tt1", positionMs = 1000, durationMs = 5000, lastWatchedEpochMs = 77),
                RemoteProgress("tt2", "series", "tt2:1:3", season = 1, episode = 3, positionMs = 10, durationMs = 20, lastWatchedEpochMs = 78),
                RemoteProgress("tt3", "movie", "tt3", positionMs = 0, durationMs = 1, lastWatchedEpochMs = 79),
            ),
        )
        val sent = body(http.sent.single())
        assertEquals("/rest/v1/rpc/sync_push_watch_progress", http.sent.single().path)
        val entries = sent["p_entries"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, entries.size)
        assertEquals("tt1", entries[0]["progress_key"]!!.jsonPrimitive.content)
        assertFalse("season" in entries[0])
        assertEquals(1000L, entries[0]["position"]!!.jsonPrimitive.long)
        assertEquals(5000L, entries[0]["duration"]!!.jsonPrimitive.long)
        assertEquals(77L, entries[0]["last_watched"]!!.jsonPrimitive.long)
        assertEquals("tt2_s1e3", entries[1]["progress_key"]!!.jsonPrimitive.content)
        assertEquals("tt2:1:3", entries[1]["video_id"]!!.jsonPrimitive.content)
        assertEquals(4, sent["p_profile_id"]!!.jsonPrimitive.int)
        assertEquals(TEST_ORIGIN, sent["p_origin_client_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `pull progress parses rows and sends the optional filters`() = runBlocking {
        val http = SyncFakeHttp(rpcs("sync_pull_watch_progress" to """[
            {"id":"x","user_id":"u","content_id":"tt2","content_type":"series","video_id":"tt2:1:3","season":1,"episode":3,"position":10,"duration":20,"last_watched":78,"progress_key":"tt2_s1e3","profile_id":1}
        ]"""))
        val rows = client(http).pullProgress(1, sinceLastWatchedEpochMs = 50, limit = 10)
        assertEquals(listOf(RemoteProgress("tt2", "series", "tt2:1:3", 1, 3, 10, 20, 78, "tt2_s1e3")), rows)
        val sent = body(http.sent.single())
        assertEquals(50L, sent["p_since_last_watched"]!!.jsonPrimitive.long)
        assertEquals(10, sent["p_limit"]!!.jsonPrimitive.int)
    }

    @Test
    fun `progress delta and delete`() = runBlocking {
        val http = SyncFakeHttp(rpcs(
            "sync_pull_watch_progress_delta" to """[{"event_id":9,"operation":"delete","progress_key":"tt1","content_id":"tt1","content_type":"movie","position":0,"duration":0,"last_watched":0}]""",
            "sync_delete_watch_progress" to "",
        ))
        val sync = client(http)
        val event = sync.pullProgressDelta(1, 8).single()
        assertTrue(event.deleted)
        assertEquals("tt1", event.progress.key)
        sync.deleteProgress(1, listOf("tt1", " tt1 ", ""))
        assertEquals("""["tt1"]""", body(http.sent[1])["p_keys"].toString())
    }

    @Test
    fun `watched history pages, pushes explicit nulls and deletes by episode`() = runBlocking {
        val http = SyncFakeHttp(rpcs(
            "sync_pull_watched_items" to """[{"content_id":"tt1","content_type":"movie","title":"One","watched_at":5}]""",
            "sync_push_watched_items" to "",
            "sync_delete_watched_items" to "",
        ))
        val sync = client(http)
        assertEquals(listOf(RemoteWatchedItem("tt1", "movie", "One", watchedAtEpochMs = 5)), sync.pullWatched(1))
        val pull = body(http.sent[0])
        assertEquals(1, pull["p_page"]!!.jsonPrimitive.int)
        assertEquals(900, pull["p_page_size"]!!.jsonPrimitive.int)

        sync.pushWatched(1, listOf(RemoteWatchedItem("tt1", "movie", "One", watchedAtEpochMs = 5)))
        val pushed = body(http.sent[1])["p_items"]!!.jsonArray.single().jsonObject
        assertEquals(JsonNull, pushed["season"])
        assertEquals(JsonNull, pushed["episode"])

        sync.deleteWatched(1, listOf(RemoteWatchedKey("tt2", 1, 2), RemoteWatchedKey("tt1")))
        assertEquals(
            """[{"content_id":"tt2","season":1,"episode":2},{"content_id":"tt1"}]""",
            body(http.sent[2])["p_keys"].toString(),
        )
    }

    @Test
    fun `pull addons reads the table for the sync owner`() = runBlocking {
        val http = SyncFakeHttp { request ->
            when (request.url.encodedPath) {
                "/rest/v1/rpc/get_sync_owner" -> 200 to "\"owner-1\""
                "/rest/v1/addons" -> 200 to """[
                    {"id":"a","user_id":"owner-1","url":"https://b/manifest.json","name":"","enabled":false,"sort_order":1,"profile_id":2},
                    {"id":"b","user_id":"owner-1","url":"https://a/manifest.json","name":"Mine","sort_order":0,"profile_id":2}
                ]"""
                else -> 404 to "{}"
            }
        }
        val sync = client(http)
        val addons = sync.pullAddons(2)
        assertEquals(
            listOf(RemoteAddon("https://a/manifest.json", 0, true, "Mine"), RemoteAddon("https://b/manifest.json", 1, false, null)),
            addons,
        )
        val get = http.sent[1]
        assertEquals("GET", get.request.method)
        assertEquals("*", get.request.url.queryParameter("select"))
        assertEquals("eq.owner-1", get.request.url.queryParameter("user_id"))
        assertEquals("eq.2", get.request.url.queryParameter("profile_id"))
        assertEquals("Bearer access-1", get.header("Authorization"))

        sync.pullAddons(2)
        assertEquals(1, http.sent.count { it.path == "/rest/v1/rpc/get_sync_owner" })
    }

    @Test
    fun `push addons sends position as sort order`() = runBlocking {
        val http = SyncFakeHttp { 204 to "" }
        client(http).pushAddons(1, listOf(RemoteAddon("https://b", 5, enabled = false), RemoteAddon("https://a", 2, name = "A")))
        val sent = body(http.sent.single())
        assertEquals("/rest/v1/rpc/sync_push_addons", http.sent.single().path)
        assertEquals(
            """[{"url":"https://a","sort_order":0,"enabled":true,"name":"A"},{"url":"https://b","sort_order":1,"enabled":false}]""",
            sent["p_addons"].toString(),
        )
        assertEquals(TEST_ORIGIN, sent["p_origin_client_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `profiles are parsed and sorted`() = runBlocking {
        val http = SyncFakeHttp(rpcs("sync_pull_profiles" to """[
            {"profile_index":2,"name":"Kids","avatar_color_hex":"#FF0000","uses_primary_addons":true},
            {"profile_index":1,"name":"Me"}
        ]"""))
        val profiles = client(http).pullProfiles()
        assertEquals(listOf(1, 2), profiles.map { it.profileIndex })
        assertTrue(profiles[1].usesPrimaryAddons)
        assertEquals("{}", http.sent.single().body)
    }

    @Test
    fun `progress keys match the TV app`() {
        assertEquals("tt1", RemoteProgress("tt1", "movie", "tt1", positionMs = 0, durationMs = 0, lastWatchedEpochMs = 0).key)
        assertEquals("tt1_s2e5", RemoteProgress("tt1", "series", "v", 2, 5, 0, 0, 0).key)
        assertEquals("custom", RemoteProgress("tt1", "series", "v", 2, 5, 0, 0, 0, "custom").key)
    }

    @Test
    fun `origin ids look like the TV app's`() {
        val id = SyncClient.newOriginClientId()
        assertTrue(id.startsWith("fetch-desktop-"))
        assertTrue(id.length in 16..96 && id.all { it.isLetterOrDigit() || it == '-' })
    }
}
