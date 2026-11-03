package com.fetch.desktop.party.realtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class RealtimeSocketTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val server = FakeRealtimeServer()
    @Volatile private var token = "jwt-1"
    private val topic = "realtime:watch-party:p1"

    @After
    fun tearDown() = scope.cancel()

    private fun socket(heartbeatMs: Long = 60_000) = RealtimeSocket(
        url = "wss://sync.example.com/realtime/v1/websocket?apikey=k&vsn=1.0.0",
        transport = server,
        scope = scope,
        accessToken = { token },
        heartbeatMs = heartbeatMs,
        reconnectDelaysMs = listOf(20),
        rejoinDelayMs = 20,
        joinTimeoutMs = 2_000,
    )

    private fun joined(socket: RealtimeSocket): RealtimeChannel {
        val channel = socket.channel("watch-party:p1", RealtimeChannelConfig(presenceKey = "m1", isPrivate = true))
        channel.subscribe()
        waitUntil { server.connections.isNotEmpty() }
        server.last.open()
        waitUntil { server.last.sentEvents(PhoenixEvents.JOIN).isNotEmpty() }
        server.last.replyToJoin(topic)
        waitUntil { channel.status.value == RealtimeChannel.Status.JOINED }
        return channel
    }

    @Test
    fun `subscribing connects and joins the private channel with the token`() {
        val socket = socket()
        joined(socket)
        assertEquals("wss://sync.example.com/realtime/v1/websocket?apikey=k&vsn=1.0.0", server.last.url)
        val join = server.last.sentEvents(PhoenixEvents.JOIN).single()
        assertEquals(topic, join.topic)
        assertEquals(join.ref, join.joinRef)
        assertEquals("jwt-1", join.payload["access_token"]!!.jsonPrimitive.content)
        assertEquals("m1", join.payload["config"]!!.jsonObject["presence"]!!.jsonObject["key"]!!.jsonPrimitive.content)
        assertTrue(socket.connected.value)
    }

    @Test
    fun `broadcasts go out and come in, presence is passed on`() {
        val socket = socket()
        val channel = joined(socket)
        val received = Collections.synchronizedList(mutableListOf<Pair<String, JsonObject>>())
        val presence = Collections.synchronizedList(mutableListOf<PresenceChange>())
        channel.onBroadcast = { event, payload -> received += event to payload }
        channel.onPresence = { presence += it }

        channel.broadcast("state", buildJsonObject { put("seq", 1) })
        channel.track(buildJsonObject { put("member_id", "m1") })
        waitUntil { server.last.sentEvents(PhoenixEvents.PRESENCE).isNotEmpty() }
        val sent = server.last.sentEvents(PhoenixEvents.BROADCAST).single()
        assertEquals("state", sent.payload["event"]!!.jsonPrimitive.content)
        assertEquals(server.last.sentEvents(PhoenixEvents.JOIN).single().ref, sent.joinRef)
        assertEquals("track", server.last.sentEvents(PhoenixEvents.PRESENCE).single().payload["event"]!!.jsonPrimitive.content)

        server.last.receive(PhoenixMessage(topic, PhoenixEvents.BROADCAST, RealtimeProtocol.broadcastPayload("control", buildJsonObject { put("host_only", true) })))
        server.last.receive(PhoenixMessage(topic, PhoenixEvents.PRESENCE_STATE, Json.parse("""{"m2":{"metas":[{"phx_ref":"x","member_id":"m2"}]}}""")))
        server.last.receive(PhoenixMessage("realtime:other", PhoenixEvents.BROADCAST, RealtimeProtocol.broadcastPayload("state", JsonObject(emptyMap()))))
        waitUntil { received.isNotEmpty() && presence.isNotEmpty() }
        assertEquals("control", received.single().first)
        assertEquals(setOf("m2"), presence.single().joins.keys)
    }

    @Test
    fun `a dropped connection reconnects and rejoins`() {
        val socket = socket()
        val channel = joined(socket)
        server.last.drop()
        waitUntil { channel.status.value == RealtimeChannel.Status.CLOSED }
        waitUntil { server.connections.size == 2 }
        server.last.open()
        waitUntil { server.last.sentEvents(PhoenixEvents.JOIN).isNotEmpty() }
        server.last.replyToJoin(topic)
        waitUntil { channel.status.value == RealtimeChannel.Status.JOINED }
    }

    @Test
    fun `a refused join is retried`() {
        val socket = socket()
        val channel = socket.channel("watch-party:p1", RealtimeChannelConfig(presenceKey = "m1", isPrivate = true))
        channel.subscribe()
        waitUntil { server.connections.isNotEmpty() }
        server.last.open()
        waitUntil { server.last.sentEvents(PhoenixEvents.JOIN).size == 1 }
        server.last.replyToJoin(topic, status = "error")
        waitUntil { server.last.sentEvents(PhoenixEvents.JOIN).size == 2 }
        server.last.replyToJoin(topic)
        waitUntil { channel.status.value == RealtimeChannel.Status.JOINED }
    }

    @Test
    fun `heartbeats are sent, a missed reply reconnects, and a new token is pushed`() {
        val socket = socket(heartbeatMs = 60)
        joined(socket)
        val first = server.last
        waitUntil { first.sentEvents(PhoenixEvents.HEARTBEAT).isNotEmpty() }
        val beat = first.sentEvents(PhoenixEvents.HEARTBEAT).first()
        assertEquals("phoenix", beat.topic)
        // Answer it, and refresh the token: the next heartbeat brings the new one to the channel.
        token = "jwt-2"
        first.receive(PhoenixMessage("phoenix", PhoenixEvents.REPLY, buildJsonObject { put("status", "ok") }, beat.ref))
        waitUntil { first.sentEvents(PhoenixEvents.ACCESS_TOKEN).isNotEmpty() }
        assertEquals("jwt-2", first.sentEvents(PhoenixEvents.ACCESS_TOKEN).first().payload["access_token"]!!.jsonPrimitive.content)
        // Stop answering: the socket gives up on this connection and opens another.
        waitUntil { server.connections.size == 2 }
        assertTrue(first.closed)
    }

    @Test
    fun `unsubscribe leaves and disconnect closes`() {
        val socket = socket()
        val channel = joined(socket)
        channel.unsubscribe()
        waitUntil { server.last.sentEvents(PhoenixEvents.LEAVE).isNotEmpty() }
        assertEquals(RealtimeChannel.Status.CLOSED, channel.status.value)
        socket.disconnect()
        waitUntil { server.last.closed }
    }

    private object Json {
        fun parse(text: String): JsonObject = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
    }
}
