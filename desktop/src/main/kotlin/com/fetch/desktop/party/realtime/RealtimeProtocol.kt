package com.fetch.desktop.party.realtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * One Phoenix channels message, protocol v1 (`vsn=1.0.0`): a JSON object with `topic`, `event`,
 * `payload` and `ref`, plus `join_ref` on messages sent on a joined channel. Supabase Realtime
 * speaks this, and so does supabase-kt, which the TV app uses.
 */
data class PhoenixMessage(
    val topic: String,
    val event: String,
    val payload: JsonObject = JsonObject(emptyMap()),
    val ref: String? = null,
    val joinRef: String? = null,
)

object PhoenixEvents {
    const val JOIN = "phx_join"
    const val LEAVE = "phx_leave"
    const val REPLY = "phx_reply"
    const val ERROR = "phx_error"
    const val CLOSE = "phx_close"
    const val HEARTBEAT = "heartbeat"
    const val ACCESS_TOKEN = "access_token"
    const val BROADCAST = "broadcast"
    const val PRESENCE = "presence"
    const val PRESENCE_STATE = "presence_state"
    const val PRESENCE_DIFF = "presence_diff"
    const val SYSTEM = "system"

    /** The socket-level topic heartbeats go to. */
    const val PHOENIX_TOPIC = "phoenix"
}

/** Encodes and decodes [PhoenixMessage]s, and builds the Supabase Realtime payloads. */
object RealtimeProtocol {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(message: PhoenixMessage): String = buildJsonObject {
        put("topic", message.topic)
        put("event", message.event)
        put("payload", message.payload)
        put("ref", message.ref?.let(::JsonPrimitive) ?: JsonNull)
        message.joinRef?.let { put("join_ref", it) }
    }.toString()

    /** The message in [text], or null when it isn't one. */
    fun decode(text: String): PhoenixMessage? {
        val obj = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        val topic = obj.string("topic") ?: return null
        val event = obj.string("event") ?: return null
        return PhoenixMessage(
            topic = topic,
            event = event,
            payload = obj["payload"] as? JsonObject ?: JsonObject(emptyMap()),
            ref = obj.string("ref"),
            joinRef = obj.string("join_ref"),
        )
    }

    /** Supabase Realtime prefixes every channel name with `realtime:` (as supabase-kt does). */
    fun topicOf(channel: String): String = "realtime:$channel"

    /**
     * The `phx_join` payload for a channel: broadcast and presence settings, no Postgres
     * changes, whether it is a private (RLS-authorized) channel, and the user's JWT.
     */
    fun joinPayload(config: RealtimeChannelConfig, accessToken: String?): JsonObject = buildJsonObject {
        putJsonObject("config") {
            putJsonObject("broadcast") {
                put("self", config.receiveOwnBroadcasts)
                put("ack", config.acknowledgeBroadcasts)
            }
            putJsonObject("presence") {
                put("key", config.presenceKey)
            }
            putJsonArray("postgres_changes") {}
            put("private", config.isPrivate)
        }
        if (accessToken != null) put("access_token", accessToken)
    }

    fun heartbeat(ref: String) = PhoenixMessage(PhoenixEvents.PHOENIX_TOPIC, PhoenixEvents.HEARTBEAT, JsonObject(emptyMap()), ref)

    fun broadcastPayload(event: String, payload: JsonObject): JsonObject = buildJsonObject {
        put("type", "broadcast")
        put("event", event)
        put("payload", payload)
    }

    fun trackPayload(state: JsonObject): JsonObject = buildJsonObject {
        put("type", "presence")
        put("event", "track")
        put("payload", state)
    }

    fun untrackPayload(): JsonObject = buildJsonObject {
        put("type", "presence")
        put("event", "untrack")
    }

    fun accessTokenPayload(token: String): JsonObject = buildJsonObject { put("access_token", token) }

    /** A received `broadcast`: its event name and payload. */
    fun broadcastOf(payload: JsonObject): Pair<String, JsonObject>? {
        val event = payload.string("event") ?: return null
        val inner = payload["payload"] as? JsonObject ?: JsonObject(emptyMap())
        return event to inner
    }

    /** A `phx_reply`'s status ("ok" or "error"). */
    fun replyStatus(payload: JsonObject): String? = payload.string("status")

    internal fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
}

/** How a channel is joined; the TV's party channel uses the defaults with its member id as the key. */
data class RealtimeChannelConfig(
    val presenceKey: String = "",
    val isPrivate: Boolean = false,
    val receiveOwnBroadcasts: Boolean = false,
    val acknowledgeBroadcasts: Boolean = false,
)

/** One presence entry: Phoenix's `phx_ref` and what the client tracked. */
data class RealtimePresence(val ref: String?, val state: JsonObject)

/** What changed in a channel's presence: keys with new entries, and keys whose entries left. */
data class PresenceChange(
    val joins: Map<String, RealtimePresence>,
    val leaves: Map<String, RealtimePresence>,
) {
    val isEmpty: Boolean get() = joins.isEmpty() && leaves.isEmpty()
}

/**
 * A channel's presence, kept the way Phoenix.Presence's client does from `presence_state` (the
 * whole list, on join) and `presence_diff` (joins and leaves since). Each key holds a list of
 * entries ("metas"), one per connection tracking under that key.
 */
class PresenceTracker {
    private val state = LinkedHashMap<String, List<RealtimePresence>>()

    val current: Map<String, List<RealtimePresence>> get() = state.toMap()

    /**
     * A full `presence_state`. Every key in it is reported as joined (supabase-kt does the same,
     * so a listener that cleared its list after a disconnect fills it again), and keys that were
     * here but are no longer are reported as left.
     */
    fun syncState(payload: JsonObject): PresenceChange {
        val next = decodeEntries(payload)
        val leaves = state.filterKeys { it !in next }.mapValues { it.value.last() }
        state.clear()
        state.putAll(next)
        return PresenceChange(joins = next.mapValues { it.value.last() }, leaves = leaves)
    }

    /** A `presence_diff`: `{"joins": {...}, "leaves": {...}}`. */
    fun syncDiff(payload: JsonObject): PresenceChange {
        val joins = decodeEntries(payload["joins"] as? JsonObject)
        val leaves = decodeEntries(payload["leaves"] as? JsonObject)
        joins.forEach { (key, entries) ->
            val existing = state[key].orEmpty()
            val existingRefs = existing.mapNotNull { it.ref }.toSet()
            state[key] = existing + entries.filter { it.ref == null || it.ref !in existingRefs }
        }
        leaves.forEach { (key, entries) ->
            val existing = state[key] ?: return@forEach
            val leavingRefs = entries.mapNotNull { it.ref }.toSet()
            val remaining = existing.filter { it.ref == null || it.ref !in leavingRefs }
            if (remaining.isEmpty()) state.remove(key) else state[key] = remaining
        }
        return PresenceChange(joins = joins.mapValues { it.value.last() }, leaves = leaves.mapValues { it.value.last() })
    }

    fun clear() = state.clear()

    private fun decodeEntries(obj: JsonObject?): Map<String, List<RealtimePresence>> {
        if (obj == null) return emptyMap()
        val result = LinkedHashMap<String, List<RealtimePresence>>()
        obj.forEach { (key, value) ->
            val metas = ((value as? JsonObject)?.get("metas") as? JsonArray).orEmpty()
            val entries = metas.mapNotNull { meta -> (meta as? JsonObject)?.let(::presenceOf) }
            if (entries.isNotEmpty()) result[key] = entries
        }
        return result
    }

    private fun presenceOf(meta: JsonObject): RealtimePresence {
        val ref = (meta["phx_ref"] as? JsonPrimitive)?.contentOrNull
        val state = JsonObject(meta.filterKeys { it != "phx_ref" && it != "phx_ref_prev" })
        return RealtimePresence(ref, state)
    }
}
