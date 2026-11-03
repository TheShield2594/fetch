package com.fetch.desktop.party

import com.fetch.desktop.sync.SyncClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/**
 * The watch party RPCs on the sync server (PostgREST `POST /rest/v1/rpc/watch_party_*`), with
 * the TV app's parameters (`core/party/PartyApi.kt`). [rpc] sends one with the signed-in
 * session and returns the response body; see [SyncClient.callRpc].
 */
class PartyApi(private val rpc: suspend (name: String, params: JsonObject?) -> String) {

    constructor(client: SyncClient) : this(client::callRpc)

    internal suspend fun create(identity: PartyIdentity, media: PartyMedia, playback: PartyPlayback?): PartySnapshot =
        call("watch_party_create", buildJsonObject {
            putIdentity(identity)
            put("p_media", json.encodeToJsonElement(media))
            put("p_playback", playback?.let { json.encodeToJsonElement(it) } ?: JsonNull)
        })

    internal suspend fun join(code: String, identity: PartyIdentity): PartySnapshot =
        call("watch_party_join", buildJsonObject {
            put("p_code", code)
            putIdentity(identity)
        })

    suspend fun preview(code: String): PartyPreview =
        json.decodeFromString(PartyPreview.serializer(), rpc("watch_party_preview", buildJsonObject { put("p_code", code) }))

    internal suspend fun heartbeat(partyId: String, deviceId: String): PartySnapshot =
        call("watch_party_heartbeat", member(partyId, deviceId))

    internal suspend fun update(partyId: String, deviceId: String, media: PartyMedia?, playback: PartyPlayback?): PartySnapshot =
        call("watch_party_update", buildJsonObject {
            put("p_party_id", partyId)
            put("p_device_id", deviceId)
            media?.let { put("p_media", json.encodeToJsonElement(it)) }
            playback?.let { put("p_playback", json.encodeToJsonElement(it)) }
        })

    internal suspend fun leave(partyId: String, deviceId: String): PartySnapshot =
        call("watch_party_leave", member(partyId, deviceId))

    internal suspend fun end(partyId: String, deviceId: String): PartySnapshot =
        call("watch_party_end", member(partyId, deviceId))

    internal suspend fun setHostOnly(partyId: String, deviceId: String, hostOnly: Boolean): PartySnapshot =
        call("watch_party_set_host_only", buildJsonObject {
            put("p_party_id", partyId)
            put("p_device_id", deviceId)
            put("p_host_only", hostOnly)
        })

    internal suspend fun removeMember(partyId: String, deviceId: String, memberId: String): PartySnapshot =
        call("watch_party_remove_member", buildJsonObject {
            put("p_party_id", partyId)
            put("p_device_id", deviceId)
            put("p_member_id", memberId)
        })

    /** The server's time in epoch milliseconds. */
    suspend fun clock(): Long = rpc("watch_party_clock", null).trim().trim('"').toLong()

    /** Whether the server has watch parties switched on. */
    suspend fun enabled(): Boolean = rpc("watch_party_enabled", null).trim().trim('"').toBooleanStrict()

    private suspend fun call(function: String, params: JsonObject): PartySnapshot =
        json.decodeFromString(PartySnapshot.serializer(), rpc(function, params))

    private fun member(partyId: String, deviceId: String) = buildJsonObject {
        put("p_party_id", partyId)
        put("p_device_id", deviceId)
    }

    private fun JsonObjectBuilder.putIdentity(identity: PartyIdentity) {
        put("p_device_id", identity.deviceId)
        put("p_display_name", identity.displayName)
        put("p_avatar_url", identity.avatarUrl)
        put("p_avatar_color", identity.avatarColor)
    }

    companion object {
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }
    }
}
