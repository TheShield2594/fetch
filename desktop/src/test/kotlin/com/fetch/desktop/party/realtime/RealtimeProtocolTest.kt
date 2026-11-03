package com.fetch.desktop.party.realtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeProtocolTest {
    private fun parse(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `messages encode as Phoenix v1 objects`() {
        val text = RealtimeProtocol.encode(
            PhoenixMessage("realtime:watch-party:p1", "broadcast", RealtimeProtocol.broadcastPayload("state", buildJsonObject { put("seq", 3) }), "7", "1")
        )
        val obj = parse(text)
        assertEquals("realtime:watch-party:p1", obj["topic"]!!.jsonPrimitive.content)
        assertEquals("broadcast", obj["event"]!!.jsonPrimitive.content)
        assertEquals("7", obj["ref"]!!.jsonPrimitive.content)
        assertEquals("1", obj["join_ref"]!!.jsonPrimitive.content)
        val payload = obj["payload"]!!.jsonObject
        assertEquals("broadcast", payload["type"]!!.jsonPrimitive.content)
        assertEquals("state", payload["event"]!!.jsonPrimitive.content)
        assertEquals("3", payload["payload"]!!.jsonObject["seq"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a heartbeat goes to the phoenix topic with a null join ref`() {
        val obj = parse(RealtimeProtocol.encode(RealtimeProtocol.heartbeat("12")))
        assertEquals("phoenix", obj["topic"]!!.jsonPrimitive.content)
        assertEquals("heartbeat", obj["event"]!!.jsonPrimitive.content)
        assertEquals(JsonObject(emptyMap()), obj["payload"])
        assertEquals("12", obj["ref"]!!.jsonPrimitive.content)
        assertNull(obj["join_ref"])
    }

    @Test
    fun `the join payload is supabase-kt's private channel config with the token`() {
        val payload = RealtimeProtocol.joinPayload(
            RealtimeChannelConfig(presenceKey = "member-1", isPrivate = true),
            accessToken = "jwt"
        )
        val config = payload["config"]!!.jsonObject
        assertEquals("false", config["broadcast"]!!.jsonObject["self"]!!.jsonPrimitive.content)
        assertEquals("false", config["broadcast"]!!.jsonObject["ack"]!!.jsonPrimitive.content)
        assertEquals("member-1", config["presence"]!!.jsonObject["key"]!!.jsonPrimitive.content)
        assertEquals("true", config["private"]!!.jsonPrimitive.content)
        assertEquals("[]", config["postgres_changes"].toString())
        assertEquals("jwt", payload["access_token"]!!.jsonPrimitive.content)
    }

    @Test
    fun `track and access token payloads`() {
        val track = RealtimeProtocol.trackPayload(buildJsonObject { put("status", "ready") })
        assertEquals("presence", track["type"]!!.jsonPrimitive.content)
        assertEquals("track", track["event"]!!.jsonPrimitive.content)
        assertEquals("ready", track["payload"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals("jwt-2", RealtimeProtocol.accessTokenPayload("jwt-2")["access_token"]!!.jsonPrimitive.content)
    }

    @Test
    fun `decodes server messages and ignores garbage`() {
        val reply = RealtimeProtocol.decode("""{"topic":"realtime:x","event":"phx_reply","payload":{"status":"ok","response":{}},"ref":"1"}""")!!
        assertEquals("phx_reply", reply.event)
        assertEquals("1", reply.ref)
        assertEquals("ok", RealtimeProtocol.replyStatus(reply.payload))
        assertNull(RealtimeProtocol.decode("not json"))
        assertNull(RealtimeProtocol.decode("""{"event":"x"}"""))
        val broadcast = RealtimeProtocol.decode(
            """{"topic":"realtime:x","event":"broadcast","payload":{"type":"broadcast","event":"control","payload":{"host_only":true}},"ref":null}"""
        )!!
        assertNull(broadcast.ref)
        val (event, inner) = RealtimeProtocol.broadcastOf(broadcast.payload)!!
        assertEquals("control", event)
        assertEquals(JsonPrimitive(true), inner["host_only"])
    }

    @Test
    fun `presence state reports joins and drops phx_ref from the state`() {
        val tracker = PresenceTracker()
        val change = tracker.syncState(
            parse("""{"a":{"metas":[{"phx_ref":"r1","member_id":"a","status":"ready"}]},"b":{"metas":[{"phx_ref":"r2","member_id":"b"}]}}""")
        )
        assertEquals(setOf("a", "b"), change.joins.keys)
        assertTrue(change.leaves.isEmpty())
        assertEquals("r1", change.joins["a"]!!.ref)
        assertNull(change.joins["a"]!!.state["phx_ref"])
        assertEquals("ready", change.joins["a"]!!.state["status"]!!.jsonPrimitive.content)

        // A later full state without b: b left.
        val next = tracker.syncState(parse("""{"a":{"metas":[{"phx_ref":"r1","member_id":"a"}]}}"""))
        assertEquals(setOf("b"), next.leaves.keys)
        assertEquals(setOf("a"), tracker.current.keys)
    }

    @Test
    fun `presence diffs add and remove entries by phx_ref`() {
        val tracker = PresenceTracker()
        tracker.syncState(parse("""{"a":{"metas":[{"phx_ref":"r1","member_id":"a"}]}}"""))
        val joined = tracker.syncDiff(parse("""{"joins":{"b":{"metas":[{"phx_ref":"r2","member_id":"b"}]}},"leaves":{}}"""))
        assertEquals(setOf("b"), joined.joins.keys)
        assertEquals(setOf("a", "b"), tracker.current.keys)

        // a reconnects: a new entry joins and the old one leaves; a is still here.
        val moved = tracker.syncDiff(
            parse("""{"joins":{"a":{"metas":[{"phx_ref":"r3","member_id":"a"}]}},"leaves":{"a":{"metas":[{"phx_ref":"r1","member_id":"a"}]}}}""")
        )
        assertEquals(setOf("a"), moved.joins.keys)
        assertEquals(setOf("a"), moved.leaves.keys)
        assertEquals(listOf("r3"), tracker.current["a"]!!.map { it.ref })

        tracker.syncDiff(parse("""{"joins":{},"leaves":{"b":{"metas":[{"phx_ref":"r2"}]}}}"""))
        assertEquals(setOf("a"), tracker.current.keys)
    }

    @Test
    fun `the socket url is wss with the apikey and protocol version`() {
        assertEquals(
            "wss://sync.example.com/realtime/v1/websocket?apikey=anon-key&vsn=1.0.0",
            RealtimeSocket.urlFor("https://sync.example.com/", "anon-key")
        )
        assertEquals(
            "ws://localhost:54321/realtime/v1/websocket?apikey=k&vsn=1.0.0",
            RealtimeSocket.urlFor("http://localhost:54321", "k")
        )
    }
}
