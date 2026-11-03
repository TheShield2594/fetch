package com.fetch.desktop.party

import com.fetch.desktop.party.realtime.waitUntil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class FakePartyChannel : PartyChannelPort {
    val sent: MutableList<Pair<String, JsonObject>> = Collections.synchronizedList(mutableListOf())
    val tracked: MutableList<JsonObject> = Collections.synchronizedList(mutableListOf())
    @Volatile var closed = false
    private var onMessage: ((String, JsonObject) -> Unit)? = null
    private var onPresence: ((Map<String, JsonObject>, Map<String, JsonObject>) -> Unit)? = null
    private var onConnected: ((Boolean) -> Unit)? = null
    @Volatile private var connected = false

    override val isConnected: Boolean get() = connected

    override fun open(
        scope: CoroutineScope,
        onMessage: (String, JsonObject) -> Unit,
        onPresence: (Map<String, JsonObject>, Map<String, JsonObject>) -> Unit,
        onConnected: (Boolean) -> Unit,
    ) {
        this.onMessage = onMessage
        this.onPresence = onPresence
        this.onConnected = onConnected
    }

    override fun send(event: String, payload: JsonObject) {
        if (connected) sent += event to payload
    }

    override fun track(payload: JsonObject) {
        if (connected) tracked += payload
    }

    override fun close() {
        closed = true
    }

    fun connect(value: Boolean = true) {
        connected = value
        onConnected?.invoke(value)
    }

    fun deliver(event: String, payload: String) = onMessage!!.invoke(event, Json.parseToJsonElement(payload).jsonObject)
    fun presence(joins: Map<String, JsonObject>, leaves: Map<String, JsonObject> = emptyMap()) = onPresence!!.invoke(joins, leaves)
    fun sentEvents(event: String) = synchronized(sent) { sent.filter { it.first == event }.map { it.second } }
}

class PartyStateTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val calls = Collections.synchronizedList(mutableListOf<Pair<String, JsonObject?>>())
    private val channel = FakePartyChannel()
    @Volatile private var signedIn = true
    @Volatile private var snapshot = snapshot()

    @After
    fun tearDown() = scope.cancel()

    private fun snapshot(host: String = "m1", self: String = "m1", hostOnly: Boolean = false, playback: String = "null") = """
        {"party":{"id":"p1","code":"ABC234","host_member_id":"$host","host_only":$hostOnly,"max_members":3,
          "media":{"type":"movie","meta_id":"tt1","meta_type":"movie","title":"Heat"},"playback":$playback},
         "members":[{"id":"m1","display_name":"Sam","avatar_url":"https://evil.example.com/a.png"},{"id":"m2","display_name":"Alex","avatar_url":"https://sync.example.com/storage/v1/object/avatars/x.png"}],
         "member_id":"$self","server_time_ms":${System.currentTimeMillis()}}
    """.trimIndent()

    private val backend = object : PartyBackend {
        override val api = PartyApi { name, params ->
            calls += name to params
            when (name) {
                "watch_party_enabled" -> "true"
                "watch_party_clock" -> System.currentTimeMillis().toString()
                "watch_party_preview" -> """{"media":{"type":"movie","meta_id":"tt1","meta_type":"movie","title":"Heat"},"members":1,"max_members":3}"""
                "watch_party_leave", "watch_party_end" -> """{"ok":true}"""
                else -> snapshot
            }
        }
        override val deviceId = "fetch-desktop-dev"
        override val storageUrl = "https://sync.example.com/storage/v1/object/"
        override fun openChannel(partyId: String, memberId: String): PartyChannelPort = channel
    }

    private fun state(capable: Boolean? = true) = PartyState(
        parentScope = scope,
        connect = { backend },
        signedIn = { signedIn },
        profile = { PartyProfile("Sam", null, "#1E88E5") },
        capability = { capable },
        timings = PartyTimings(
            heartbeat = 10.minutes,
            persistDelay = 40.milliseconds,
            coordinatorTick = 20.milliseconds,
            resumeGrace = 0.milliseconds,
            waitLimit = 10.minutes,
            clockSamples = 0,
            presenceDelay = 5.milliseconds,
        ),
    ).also {
        it.refreshAvailability()
        waitUntil { it.available.value }
    }

    private fun calls(name: String) = synchronized(calls) { calls.filter { it.first == name } }

    @Test
    fun `availability follows discovery and the enabled RPC`() {
        val party = state()
        assertTrue(party.canUse)
        val off = PartyState(scope, { backend }, { true }, { PartyProfile("", null, null) }, capability = { false })
        off.refreshAvailability()
        Thread.sleep(100)
        assertFalse(off.available.value)
        runBlocking { assertEquals(PartyError.Unavailable, off.join("ABC234")) }
        signedIn = false
        runBlocking { assertEquals(PartyError.SignInRequired, party.join("ABC234")) }
    }

    @Test
    fun `joining opens the channel, then tracks presence and asks for the state`() = runBlocking {
        val party = state()
        assertNull(party.join("abc-234"))
        val joined = party.state.value.party!!
        assertEquals("p1", joined.id)
        assertEquals("ABC234", calls("watch_party_join").single().second!!["p_code"]!!.jsonPrimitive.content)
        assertEquals("Sam", calls("watch_party_join").single().second!!["p_display_name"]!!.jsonPrimitive.content)
        // Avatars only from the server's own storage.
        assertNull(joined.member("m1")!!.avatarUrl)
        assertEquals("https://sync.example.com/storage/v1/object/avatars/x.png", joined.member("m2")!!.avatarUrl)
        assertFalse(joined.connected)

        channel.connect()
        waitUntil { party.state.value.party?.connected == true && channel.sentEvents("state_request").isNotEmpty() }
        assertEquals("m1", channel.tracked.single()["member_id"]!!.jsonPrimitive.content)
        assertEquals("browsing", channel.sentEvents("status").single()["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `another member's state is followed with a notice, stale ones are not`() = runBlocking {
        snapshot = snapshot(playback = """{"seq":1,"member_id":"m1","paused":false,"position_ms":1000,"rate":1.0,"at":1000,"media_key":"movie|tt1||"}""")
        val party = state()
        val notices = Collections.synchronizedList(mutableListOf<PartyNotice>())
        scope.launch { party.notices.collect { notices += it } }
        party.join("ABC234")
        assertEquals(1L, party.playback.value!!.seq)
        channel.connect()
        channel.deliver("state", """{"seq":2,"member_id":"m2","paused":true,"position_ms":5000,"rate":1.0,"at":2000,"media_key":"movie|tt1||"}""")
        waitUntil { party.playback.value?.seq == 2L && notices.isNotEmpty() }
        assertEquals(PartyNotice("Alex", PartyAction.Paused, 5000, 1f), notices.single())
        channel.deliver("state", """{"seq":1,"member_id":"m2","paused":false,"position_ms":0,"rate":1.0,"at":0}""")
        channel.deliver("state", """{"seq":3,"member_id":"m2","paused":false,"position_ms":0,"rate":1.0,"at":0,"media_key":"series|other|1|1"}""")
        Thread.sleep(100)
        assertEquals(2L, party.playback.value!!.seq)
    }

    @Test
    fun `with host-only control only the host's state counts`() = runBlocking {
        snapshot = snapshot(host = "m2", self = "m1", hostOnly = true)
        val party = state()
        party.join("ABC234")
        channel.connect()
        assertFalse(party.state.value.party!!.canControl)
        channel.deliver("state", """{"seq":5,"member_id":"m3","paused":true,"position_ms":0,"rate":1.0,"at":0}""")
        channel.deliver("state", """{"seq":4,"member_id":"m2","paused":true,"position_ms":9000,"rate":1.0,"at":0}""")
        waitUntil { party.playback.value?.seq == 4L }
        // This member can't publish.
        party.publishPlayback(paused = false, positionMs = 0, rate = 1f)
        Thread.sleep(100)
        assertTrue(channel.sentEvents("state").none { it["member_id"]!!.jsonPrimitive.content == "m1" && it["seq"]!!.jsonPrimitive.content == "5" })
        assertEquals(4L, party.playback.value!!.seq)
    }

    @Test
    fun `publishing playback broadcasts it and saves it on the server shortly after`() = runBlocking {
        val party = state()
        party.join("ABC234")
        channel.connect()
        party.publishPlayback(paused = true, positionMs = 42_000, rate = 1.25f)
        waitUntil { channel.sentEvents("state").isNotEmpty() }
        val sent = channel.sentEvents("state").last()
        assertEquals("true", sent["paused"]!!.jsonPrimitive.content)
        assertEquals("42000", sent["position_ms"]!!.jsonPrimitive.content)
        assertEquals("movie|tt1||", sent["media_key"]!!.jsonPrimitive.content)
        waitUntil { calls("watch_party_update").isNotEmpty() }
        assertEquals("42000", calls("watch_party_update").last().second!!["p_playback"]!!.jsonObject["position_ms"]!!.jsonPrimitive.content)
    }

    @Test
    fun `media changes, control changes and removal are events`() = runBlocking {
        snapshot = snapshot(host = "m2", self = "m1")
        val party = state()
        val events = Collections.synchronizedList(mutableListOf<PartyEvent>())
        scope.launch { party.events.collect { events += it } }
        party.join("ABC234")
        channel.connect()
        channel.deliver("media", """{"media":{"type":"series","meta_id":"tt2","meta_type":"series","season":1,"episode":3,"title":"Show"},"playback":{"seq":9,"member_id":"m2","paused":false,"position_ms":0,"rate":1.0,"at":0,"media_key":"series|tt2|1|3"}}""")
        waitUntil { events.any { it is PartyEvent.MediaChanged } }
        assertEquals("series|tt2|1|3", party.state.value.party!!.media.key)
        assertEquals(9L, party.playback.value!!.seq)
        channel.deliver("control", """{"host_only":true}""")
        waitUntil { events.any { it is PartyEvent.ControlChanged } }
        assertTrue(party.state.value.party!!.hostOnly)
        channel.deliver("stream", """{"member_id":"m2","stream":{"media_key":"series|tt2|1|3","url":"https://debrid.example.com/a.mkv"}}""")
        waitUntil { party.sharedStream.value != null }
        channel.deliver("member_left", """{"member_id":"m1","reason":"removed"}""")
        waitUntil { events.any { it == PartyEvent.Removed } }
        assertNull(party.state.value.party)
        assertTrue(channel.closed)
        assertEquals("You were removed from the watch party", PartyEvent.Removed.message)
    }

    @Test
    fun `presence and status updates fill in the members' state`() = runBlocking {
        val party = state()
        party.join("ABC234")
        channel.connect()
        channel.presence(mapOf("m2" to buildJsonObject { put("member_id", "m2"); put("status", "loading"); put("media_key", "movie|tt1||") }))
        waitUntil { party.state.value.party!!.presence["m2"]?.status == PartyMemberStatus.Loading }
        channel.deliver("status", """{"member_id":"m2","status":"ready","media_key":"movie|tt1||"}""")
        waitUntil { party.state.value.party!!.presence["m2"]?.status == PartyMemberStatus.Ready }
        channel.presence(emptyMap(), leaves = mapOf("m2" to JsonObject(emptyMap())))
        waitUntil { "m2" !in party.state.value.party!!.presence }
        // Someone left: ask again where everyone is.
        waitUntil { channel.sentEvents("state_request").size >= 2 }
    }

    @Test
    fun `the coordinator waits for a member who is loading, then carries on`() = runBlocking {
        val party = state()
        party.join("ABC234")
        channel.connect()
        party.publishPlayback(paused = false, positionMs = 1_000, rate = 1f)
        waitUntil { party.playback.value?.paused == false }
        channel.deliver("status", """{"member_id":"m2","status":"loading","media_key":"movie|tt1||"}""")
        waitUntil { party.playback.value?.waiting == true }
        assertTrue(party.playback.value!!.paused)
        channel.deliver("status", """{"member_id":"m2","status":"ready","media_key":"movie|tt1||"}""")
        waitUntil { party.playback.value?.waiting == false && party.playback.value?.paused == false }
    }

    @Test
    fun `leaving tells the server and ends the session`() = runBlocking {
        val party = state()
        party.join("ABC234")
        party.leave()
        waitUntil { calls("watch_party_leave").isNotEmpty() }
        assertNull(party.state.value.party)
        assertEquals("p1", calls("watch_party_leave").single().second!!["p_party_id"]!!.jsonPrimitive.content)
        assertEquals("fetch-desktop-dev", calls("watch_party_leave").single().second!!["p_device_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a join the server refuses comes back as its error`() = runBlocking {
        snapshot = """{"error":"party_not_found"}"""
        val party = state()
        assertEquals(PartyError.NotFound, party.join("ABC234"))
        assertNull(party.state.value.party)
        assertEquals("Heat", party.preview("ABC234").media!!.title)
    }

    @Test
    fun `signing out ends the party`() = runBlocking {
        val party = state()
        party.join("ABC234")
        signedIn = false
        waitUntil { party.state.value.party == null }
    }
}
