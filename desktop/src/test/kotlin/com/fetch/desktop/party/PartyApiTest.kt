package com.fetch.desktop.party

import com.fetch.desktop.sync.REFRESHED_TOKENS
import com.fetch.desktop.sync.SyncAuthClient
import com.fetch.desktop.sync.SyncClient
import com.fetch.desktop.sync.SyncFakeHttp
import com.fetch.desktop.sync.TEST_ORIGIN
import com.fetch.desktop.sync.testServer
import com.fetch.desktop.sync.testSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PartyApiTest {
    private val identity = PartyIdentity("fetch-desktop-abc", "Sam", null, "#1E88E5")
    private val media = PartyMedia(type = "series", metaId = "tt0903747", metaType = "series", videoId = "tt0903747:1:2", season = 1, episode = 2, title = "Breaking Bad")

    private fun api(http: SyncFakeHttp) =
        PartyApi(SyncClient(http.client, SyncAuthClient(http.client, testServer), testSession, TEST_ORIGIN))

    private val snapshot = """
        {"party":{"id":"p1","code":"ABC234","host_member_id":"m1","host_only":false,"max_members":3,
          "media":{"type":"series","meta_id":"tt0903747","meta_type":"series","season":1,"episode":2,"title":"Breaking Bad"},
          "playback":{"seq":2,"member_id":"m1","paused":true,"position_ms":61000,"rate":1.0,"at":1700000000000,"media_key":"series|tt0903747|1|2"}},
         "members":[{"id":"m1","display_name":"Sam","avatar_color":"#1E88E5"}],
         "member_id":"m1","server_time_ms":1700000000500}
    """.trimIndent()

    @Test
    fun `create sends the TV's parameters with the session and decodes the snapshot`() = runBlocking {
        val http = SyncFakeHttp { 200 to snapshot }
        val result = api(http).create(identity, media, null)
        val sent = http.sent.single()
        assertEquals("/rest/v1/rpc/watch_party_create", sent.path)
        assertEquals("anon-key", sent.header("apikey"))
        assertEquals("Bearer access-1", sent.header("Authorization"))
        val body = Json.parseToJsonElement(sent.body).jsonObject
        assertEquals("fetch-desktop-abc", body["p_device_id"]!!.jsonPrimitive.content)
        assertEquals("Sam", body["p_display_name"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, body["p_avatar_url"])
        assertEquals("#1E88E5", body["p_avatar_color"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, body["p_playback"])
        val sentMedia = body["p_media"]!!.jsonObject
        assertEquals("tt0903747", sentMedia["meta_id"]!!.jsonPrimitive.content)
        assertEquals("tt0903747:1:2", sentMedia["video_id"]!!.jsonPrimitive.content)
        assertNull(sentMedia["source"]) // explicitNulls = false, as on TV

        assertEquals("p1", result.party!!.id)
        assertEquals("m1", result.memberId)
        assertEquals(61_000L, result.party!!.playback!!.positionMs)
        assertEquals("series|tt0903747|1|2", result.party!!.media.key)
        assertEquals(1_700_000_000_500L, result.serverTimeMs)
    }

    @Test
    fun `join, heartbeat, update, host-only and remove send their parameters`() = runBlocking {
        val http = SyncFakeHttp { 200 to snapshot }
        val api = api(http)
        api.join("ABC234", identity)
        api.heartbeat("p1", "dev")
        api.update("p1", "dev", null, PartyPlayback(seq = 3, memberId = "m1", paused = false, positionMs = 1000, at = 5))
        api.setHostOnly("p1", "dev", true)
        api.removeMember("p1", "dev", "m2")
        api.leave("p1", "dev")
        api.end("p1", "dev")
        assertEquals(
            listOf("watch_party_join", "watch_party_heartbeat", "watch_party_update", "watch_party_set_host_only", "watch_party_remove_member", "watch_party_leave", "watch_party_end"),
            http.sent.map { it.path.substringAfterLast('/') }
        )
        val bodies = http.sent.map { Json.parseToJsonElement(it.body).jsonObject }
        assertEquals("ABC234", bodies[0]["p_code"]!!.jsonPrimitive.content)
        assertEquals("p1", bodies[1]["p_party_id"]!!.jsonPrimitive.content)
        assertEquals("dev", bodies[1]["p_device_id"]!!.jsonPrimitive.content)
        assertFalse("p_media" in bodies[2])
        assertEquals("3", bodies[2]["p_playback"]!!.jsonObject["seq"]!!.jsonPrimitive.content)
        assertEquals("true", bodies[3]["p_host_only"]!!.jsonPrimitive.content)
        assertEquals("m2", bodies[4]["p_member_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `preview, clock and enabled`() = runBlocking {
        val http = SyncFakeHttp { request ->
            when (request.url.encodedPath.substringAfterLast('/')) {
                "watch_party_preview" -> 200 to """{"media":{"type":"movie","meta_id":"tt1","meta_type":"movie","title":"Heat"},"host":{"id":"m1","display_name":"Sam"},"members":3,"max_members":3}"""
                "watch_party_clock" -> 200 to "1700000000123"
                "watch_party_enabled" -> 200 to "true"
                else -> 404 to "{}"
            }
        }
        val api = api(http)
        val preview = api.preview("ABC234")
        assertEquals("Heat", preview.media!!.title)
        assertEquals("Sam", preview.host!!.displayName)
        assertTrue(preview.isFull)
        assertEquals(1_700_000_000_123L, api.clock())
        assertTrue(api.enabled())
    }

    @Test
    fun `logical errors come back in the snapshot`() = runBlocking {
        val http = SyncFakeHttp { 200 to """{"error":"party_full"}""" }
        val result = api(http).join("ABC234", identity)
        assertEquals(PartyError.Full, partyErrorOf(result.error))
        assertNull(result.party)
    }

    @Test
    fun `a 401 refreshes the session once and retries`() = runBlocking {
        var calls = 0
        val http = SyncFakeHttp { request ->
            when {
                request.url.encodedPath.endsWith("/token") -> 200 to REFRESHED_TOKENS
                calls++ == 0 -> 401 to """{"message":"JWT expired"}"""
                else -> 200 to "1700000000000"
            }
        }
        assertEquals(1_700_000_000_000L, api(http).clock())
        assertEquals("Bearer access-2", http.sent.last().header("Authorization"))
    }
}
