package com.fetch.desktop.party

import com.fetch.desktop.party.realtime.RealtimeChannel
import com.fetch.desktop.party.realtime.RealtimeChannelConfig
import com.fetch.desktop.party.realtime.RealtimeSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** The party's live channel, as [PartyState] uses it; [RealtimePartyChannel] in the app. */
interface PartyChannelPort {
    val isConnected: Boolean

    fun open(
        scope: CoroutineScope,
        onMessage: (event: String, payload: JsonObject) -> Unit,
        onPresence: (joins: Map<String, JsonObject>, leaves: Map<String, JsonObject>) -> Unit,
        onConnected: (Boolean) -> Unit,
    )

    fun send(event: String, payload: JsonObject)
    fun track(payload: JsonObject)
    fun close()
    /** Closes at once, from any thread (the app is closing). */
    fun closeNow() = close()
}

/**
 * The TV's `PartyChannel`: the private Realtime channel `watch-party:<party id>`, broadcasts
 * not echoed or acknowledged, and presence under this member's id.
 */
class RealtimePartyChannel(
    private val socket: RealtimeSocket,
    private val partyId: String,
    private val memberId: String,
) : PartyChannelPort {
    private var channel: RealtimeChannel? = null
    private var statusJob: Job? = null

    override val isConnected: Boolean
        get() = channel?.status?.value == RealtimeChannel.Status.JOINED

    override fun open(
        scope: CoroutineScope,
        onMessage: (String, JsonObject) -> Unit,
        onPresence: (Map<String, JsonObject>, Map<String, JsonObject>) -> Unit,
        onConnected: (Boolean) -> Unit,
    ) {
        val created = socket.channel(
            "watch-party:$partyId",
            RealtimeChannelConfig(presenceKey = memberId, isPrivate = true, receiveOwnBroadcasts = false, acknowledgeBroadcasts = false)
        )
        created.onBroadcast = { event, payload -> if (event in EVENTS) onMessage(event, payload) }
        created.onPresence = { change -> onPresence(change.joins.mapValues { it.value.state }, change.leaves.mapValues { it.value.state }) }
        channel = created
        statusJob = scope.launch {
            var last: Boolean? = null
            created.status.collect { status ->
                val joined = status == RealtimeChannel.Status.JOINED
                if (joined != last) {
                    last = joined
                    onConnected(joined)
                }
            }
        }
        created.subscribe()
        socket.connect()
    }

    override fun send(event: String, payload: JsonObject) {
        val current = channel ?: return
        if (current.status.value != RealtimeChannel.Status.JOINED) return
        current.broadcast(event, payload)
    }

    override fun track(payload: JsonObject) {
        val current = channel ?: return
        if (current.status.value != RealtimeChannel.Status.JOINED) return
        current.track(payload)
    }

    override fun close() {
        statusJob?.cancel()
        statusJob = null
        channel?.unsubscribe()
        channel = null
        socket.disconnect()
    }

    override fun closeNow() {
        statusJob?.cancel()
        channel = null
        socket.closeNow()
    }

    companion object {
        val EVENTS = setOf("state", "state_request", "status", "control", "member_joined", "member_left", "ended", "media", "stream")
    }
}
