package com.fetch.desktop.sync

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Profiles, PINs and settings blobs, against the TV app's wire format. */
class SyncClientProfilesTest {

    private fun client(http: SyncFakeHttp) = SyncClient(
        http = http.client,
        auth = SyncAuthClient(http.client, testServer, nowEpochSec = { 1_000_000_000L }),
        session = testSession,
        originClientId = TEST_ORIGIN,
        nowEpochSec = { 1_000_000_000L },
    )

    private fun body(sent: SyncFakeHttp.Sent): JsonObject = Json.parseToJsonElement(sent.body).jsonObject

    private fun rpcs(vararg routes: Pair<String, Pair<Int, String>>): (Request) -> Pair<Int, String> {
        val map = routes.toMap()
        return { request -> map[request.url.encodedPath.removePrefix("/rest/v1/rpc/")] ?: (404 to "{}") }
    }

    @Test
    fun `pull profiles reads backgrounds too`() = runBlocking {
        val http = SyncFakeHttp(rpcs("sync_pull_profiles" to (200 to """[
            {"profile_index":1,"name":"Me","avatar_url":"https://a/1.png","profile_background_id":"bg1","profile_background_url":"https://a/bg.png","updated_at":"x"}
        ]""")))
        val me = client(http).pullProfiles().single()
        assertEquals("bg1", me.profileBackgroundId)
        assertEquals("https://a/bg.png", me.profileBackgroundUrl)
        assertEquals("https://a/1.png", me.avatarUrl)
    }

    @Test
    fun `push profiles sends the TV app's fields and the profile cap`() = runBlocking {
        val http = SyncFakeHttp { 204 to "" }
        client(http).pushProfiles(
            listOf(
                RemoteProfile(2, "Kids", "#E53935", usesPrimaryAddons = true, avatarId = "fox", avatarUrl = "https://a/fox.png"),
                RemoteProfile(1, "Me", "#1E88E5", usesPrimaryAddons = false, avatarId = "cat", profileBackgroundId = "bg1"),
            )
        )
        val sent = http.sent.single()
        assertEquals("/rest/v1/rpc/sync_push_profiles", sent.path)
        val params = body(sent)
        assertEquals(6, params["p_client_max_profiles"]!!.jsonPrimitive.int)
        assertEquals(TEST_ORIGIN, params["p_origin_client_id"]!!.jsonPrimitive.content)
        assertEquals(
            """[{"profile_index":1,"name":"Me","avatar_color_hex":"#1E88E5","uses_primary_addons":false,"uses_primary_plugins":false,""" +
                """"avatar_id":"cat","avatar_url":null,"profile_background_id":"bg1","profile_background_url":null},""" +
                """{"profile_index":2,"name":"Kids","avatar_color_hex":"#E53935","uses_primary_addons":true,"uses_primary_plugins":false,""" +
                """"avatar_id":null,"avatar_url":"https://a/fox.png","profile_background_id":null,"profile_background_url":null}]""",
            params["p_profiles"].toString(),
        )
    }

    @Test
    fun `delete profile data sends the profile and origin, and refuses the primary`() = runBlocking {
        val http = SyncFakeHttp { 204 to "" }
        val sync = client(http)
        sync.deleteProfileData(3)
        assertEquals("/rest/v1/rpc/sync_delete_profile_data", http.sent.single().path)
        assertEquals("""{"p_profile_id":3,"p_origin_client_id":"$TEST_ORIGIN"}""", http.sent.single().body)
        try {
            sync.deleteProfileData(1)
            fail()
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(1, http.sent.size)
    }

    @Test
    fun `profile locks are parsed`() = runBlocking {
        val http = SyncFakeHttp(rpcs("sync_pull_profile_locks" to (200 to """[
            {"profile_index":2,"pin_enabled":true,"pin_locked_until":"2026-01-01T00:00:00Z"},
            {"profile_index":1,"pin_enabled":false}
        ]""")))
        val locks = client(http).pullProfileLocks()
        assertEquals(listOf(RemoteProfileLock(1, false), RemoteProfileLock(2, true, "2026-01-01T00:00:00Z")), locks)
        assertEquals("{}", http.sent.single().body)
    }

    @Test
    fun `set pin sends the pin, and the current one only when given`() = runBlocking {
        val http = SyncFakeHttp { 200 to "null" }
        val sync = client(http)
        assertEquals(RemotePinSetResult.SUCCESS, sync.setProfilePin(2, "1234"))
        assertEquals("""{"p_profile_id":2,"p_pin":"1234"}""", http.sent[0].body)
        sync.setProfilePin(2, "5678", currentPin = "1234")
        assertEquals("/rest/v1/rpc/set_profile_pin", http.sent[1].path)
        assertEquals("""{"p_profile_id":2,"p_pin":"5678","p_current_pin":"1234"}""", http.sent[1].body)
    }

    @Test
    fun `set pin reports a missing current pin and throws other errors`() = runBlocking {
        val required = SyncFakeHttp { 400 to """{"code":"P0001","message":"Current PIN is required"}""" }
        assertEquals(RemotePinSetResult.CURRENT_PIN_REQUIRED, client(required).setProfilePin(2, "1234"))
        val broken = SyncFakeHttp { 500 to """{"message":"boom"}""" }
        try {
            client(broken).setProfilePin(2, "1234")
            fail()
        } catch (e: SyncException) {
            assertEquals(500, e.code)
        }
    }

    @Test
    fun `clear and verify pin`() = runBlocking {
        val http = SyncFakeHttp(
            rpcs(
                "clear_profile_pin" to (204 to ""),
                "verify_profile_pin" to (200 to """[{"unlocked":false,"retry_after_seconds":30}]"""),
            )
        )
        val sync = client(http)
        sync.clearProfilePin(2, "1234")
        assertEquals("""{"p_profile_id":2,"p_current_pin":"1234"}""", http.sent[0].body)
        sync.clearProfilePin(2)
        assertEquals("""{"p_profile_id":2}""", http.sent[1].body)
        assertEquals(RemotePinVerify(unlocked = false, retryAfterSeconds = 30), sync.verifyProfilePin(2, "0000"))
        assertEquals("""{"p_profile_id":2,"p_pin":"0000"}""", http.sent[2].body)
    }

    @Test
    fun `verify pin accepts a single row or nothing`() = runBlocking {
        assertEquals(RemotePinVerify(true, 0), client(SyncFakeHttp { 200 to """{"unlocked":true}""" }).verifyProfilePin(1, "1234"))
        assertEquals(RemotePinVerify(false, 0), client(SyncFakeHttp { 200 to "[]" }).verifyProfilePin(1, "1234"))
    }

    @Test
    fun `settings blob pull and push use the TV platform`() = runBlocking {
        val http = SyncFakeHttp(
            rpcs(
                "sync_pull_profile_settings_blob" to (200 to """[{"profile_id":2,"settings_json":{"version":1,"features":{}},"updated_at":"t"}]"""),
                "sync_push_profile_settings_blob" to (204 to ""),
            )
        )
        val sync = client(http)
        val blob = sync.pullSettingsBlob(2)!!
        assertEquals(2, blob.profileId)
        assertEquals("t", blob.updatedAt)
        assertEquals("""{"p_profile_id":2,"p_platform":"tv"}""", http.sent[0].body)

        sync.pushSettingsBlob(2, blob.settingsJson)
        assertEquals(
            """{"p_profile_id":2,"p_settings_json":{"version":1,"features":{}},"p_platform":"tv","p_origin_client_id":"$TEST_ORIGIN"}""",
            http.sent[1].body,
        )
    }

    @Test
    fun `no settings blob reads as null`() = runBlocking {
        assertNull(client(SyncFakeHttp { 200 to "[]" }).pullSettingsBlob(1))
        assertNull(client(SyncFakeHttp { 200 to "[]" }).pullSharedSettings(1))
        assertNull(client(SyncFakeHttp { 200 to """[{"profile_id":1,"settings_json":null}]""" }).pullSettingsBlob(1))
    }

    private val tvBlob = """{"version":1,"features":{
        "layout_settings":{"poster_labels_enabled":{"type":"boolean","value":false},"poster_card_width_dp":{"type":"int","value":140}},
        "player_settings":{"preferred_audio_language":{"type":"string","value":"ja"}}
    },"parental":{"isKidsProfile":true,"maxRating":"PARENTAL_GUIDANCE","allowUnrated":false}}"""

    @Test
    fun `push shared settings changes only its keys of the pulled blob`() = runBlocking {
        val http = SyncFakeHttp(
            rpcs(
                "sync_pull_profile_settings_blob" to (200 to """[{"profile_id":2,"settings_json":$tvBlob}]"""),
                "sync_push_profile_settings_blob" to (204 to ""),
            )
        )
        val sync = client(http)
        assertEquals(false, sync.pullSharedSettings(2)!!.showPosterLabels)

        sync.pushSharedSettings(2, RemoteSharedSettings(showPosterLabels = true, autoplayNextEpisode = true))
        val pushed = body(http.sent.last())["p_settings_json"]!!.jsonObject
        val layout = pushed["features"]!!.jsonObject["layout_settings"]!!.jsonObject
        assertEquals("""{"type":"boolean","value":true}""", layout["poster_labels_enabled"].toString())
        assertEquals("""{"type":"int","value":140}""", layout["poster_card_width_dp"].toString())
        val player = pushed["features"]!!.jsonObject["player_settings"]!!.jsonObject
        assertEquals("""{"type":"string","value":"ja"}""", player["preferred_audio_language"].toString())
        assertEquals("""{"type":"boolean","value":true}""", player["stream_auto_play_next_episode_enabled"].toString())
        assertEquals("PARENTAL_GUIDANCE", pushed["parental"]!!.jsonObject["maxRating"]!!.jsonPrimitive.content)
    }

    @Test
    fun `parental limits are read and written in the blob, never for the primary`() = runBlocking {
        val http = SyncFakeHttp(
            rpcs(
                "sync_pull_profile_settings_blob" to (200 to """[{"profile_id":2,"settings_json":$tvBlob}]"""),
                "sync_push_profile_settings_blob" to (204 to ""),
            )
        )
        val sync = client(http)
        assertEquals(RemoteParentalLimits(true, "PARENTAL_GUIDANCE", false), sync.pullParentalLimits(2))
        assertNull(sync.pullParentalLimits(1))
        assertEquals(1, http.sent.size)

        sync.pushParentalLimits(2, RemoteParentalLimits(isKidsProfile = true, maxRating = "TEENS", allowUnrated = true))
        val pushed = body(http.sent.last())["p_settings_json"]!!.jsonObject
        assertEquals("""{"isKidsProfile":true,"maxRating":"TEENS","allowUnrated":true}""", pushed["parental"].toString())
        assertTrue("features kept", pushed["features"]!!.jsonObject.containsKey("layout_settings"))
        try {
            sync.pushParentalLimits(1, RemoteParentalLimits())
            fail()
        } catch (_: IllegalArgumentException) {
        }
        assertFalse(http.sent.any { body(it)["p_profile_id"]?.jsonPrimitive?.int == 1 })
    }
}
