package com.fetch.desktop.party.realtime

import com.fetch.desktop.party.realtime.RealtimeProtocol.string
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/** A WebSocket, abstracted so tests can stand in for the server. */
interface RealtimeTransport {
    fun open(url: String, listener: Listener): Connection

    interface Connection {
        fun send(text: String): Boolean
        fun close(code: Int = 1000, reason: String? = null)
    }

    interface Listener {
        fun onOpen()
        fun onMessage(text: String)
        /** The socket closed, cleanly ([error] null) or not. */
        fun onClosed(error: Throwable?)
    }
}

/** OkHttp's WebSocket. */
class OkHttpRealtimeTransport(http: OkHttpClient) : RealtimeTransport {
    // Phoenix heartbeats keep the socket alive; a read timeout would only cut a quiet one.
    private val client = http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    override fun open(url: String, listener: RealtimeTransport.Listener): RealtimeTransport.Connection {
        val socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()
            override fun onMessage(webSocket: WebSocket, text: String) = listener.onMessage(text)
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.onClosed(null)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = listener.onClosed(t)
        })
        return object : RealtimeTransport.Connection {
            override fun send(text: String): Boolean = socket.send(text)
            override fun close(code: Int, reason: String?) {
                socket.close(code, reason)
            }
        }
    }
}

/**
 * A Supabase Realtime connection: one WebSocket to `/realtime/v1/websocket` carrying Phoenix
 * channels (protocol v1), as supabase-kt's `Realtime` does for the TV app.
 *
 * - A heartbeat every [heartbeatMs]; a heartbeat still unanswered when the next is due counts
 *   as a dead connection, which is closed and reconnected.
 * - Reconnects after [reconnectDelaysMs] (the last delay repeats), and rejoins every channel
 *   that was subscribed.
 * - [accessToken] is read for each join and again with every heartbeat; a new token (the
 *   session was refreshed) is sent to each joined channel as an `access_token` message.
 *
 * Everything runs on one thread ([dispatcher]); listeners are called there.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeSocket(
    private val url: String,
    private val transport: RealtimeTransport,
    scope: CoroutineScope,
    private val accessToken: suspend () -> String?,
    private val heartbeatMs: Long = 15_000,
    private val reconnectDelaysMs: List<Long> = listOf(1_000, 2_000, 5_000, 10_000),
    internal val rejoinDelayMs: Long = 2_000,
    internal val joinTimeoutMs: Long = 10_000,
    dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) {
    internal val scope = CoroutineScope(scope.coroutineContext + Job(scope.coroutineContext[Job]) + dispatcher)

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val channels = mutableListOf<RealtimeChannel>()
    @Volatile private var connection: RealtimeTransport.Connection? = null
    private var connectionId = 0
    private var ref = 0L
    private var pendingHeartbeat: String? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    @Volatile private var closedOnPurpose = false
    internal var lastToken: String? = null
        private set

    /** A channel on [name] (without the `realtime:` prefix); call [RealtimeChannel.subscribe]. */
    fun channel(name: String, config: RealtimeChannelConfig): RealtimeChannel {
        val channel = RealtimeChannel(this, RealtimeProtocol.topicOf(name), config)
        scope.launch { channels += channel }
        return channel
    }

    fun connect() {
        scope.launch {
            closedOnPurpose = false
            if (connection == null && reconnectJob?.isActive != true) open()
        }
    }

    /** Closes the socket for good; channels are left first. */
    fun disconnect() {
        // The app is going away (its scope is cancelled): close now, the server drops presence.
        if (!scope.isActive) {
            connection?.close(1000, null)
            return
        }
        scope.launch {
            closedOnPurpose = true
            reconnectJob?.cancel()
            heartbeatJob?.cancel()
            channels.toList().forEach { it.leaveNow() }
            channels.clear()
            val current = connection
            connection = null
            connectionId++
            _connected.value = false
            current?.close(1000, null)
            scope.coroutineContext[Job]?.cancel()
        }
    }

    /** Closes the WebSocket right away, from any thread (the app is closing). */
    fun closeNow() {
        closedOnPurpose = true
        connection?.close(1000, null)
        scope.coroutineContext[Job]?.cancel()
    }

    internal fun makeRef(): String = (++ref).toString()

    internal fun push(message: PhoenixMessage): Boolean = connection?.send(RealtimeProtocol.encode(message)) == true

    internal fun remove(channel: RealtimeChannel) {
        channels -= channel
    }

    internal suspend fun token(): String? {
        val token = runCatching { accessToken() }.getOrNull() ?: lastToken
        lastToken = token
        return token
    }

    private fun open() {
        val id = ++connectionId
        pendingHeartbeat = null
        connection = transport.open(url, object : RealtimeTransport.Listener {
            override fun onOpen() {
                scope.launch { if (id == connectionId) onOpened() }
            }

            override fun onMessage(text: String) {
                scope.launch { if (id == connectionId) RealtimeProtocol.decode(text)?.let(::route) }
            }

            override fun onClosed(error: Throwable?) {
                scope.launch { if (id == connectionId) onConnectionLost() }
            }
        })
    }

    private fun onOpened() {
        reconnectAttempt = 0
        _connected.value = true
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch { heartbeatLoop() }
        channels.toList().forEach { it.onSocketOpen() }
    }

    private fun onConnectionLost() {
        connection = null
        connectionId++
        heartbeatJob?.cancel()
        pendingHeartbeat = null
        _connected.value = false
        channels.toList().forEach { it.onSocketClosed() }
        if (!closedOnPurpose) scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        val wait = reconnectDelaysMs.getOrElse(reconnectAttempt) { reconnectDelaysMs.last() }
        reconnectAttempt++
        reconnectJob = scope.launch {
            delay(wait)
            if (!closedOnPurpose && connection == null) open()
        }
    }

    private suspend fun heartbeatLoop() {
        while (true) {
            delay(heartbeatMs)
            if (pendingHeartbeat != null) {
                // The last one was never answered: the connection is gone.
                connection?.close(1000, "heartbeat timeout")
                onConnectionLost()
                return
            }
            val heartbeatRef = makeRef()
            pendingHeartbeat = heartbeatRef
            push(RealtimeProtocol.heartbeat(heartbeatRef))
            refreshToken()
        }
    }

    /** Sends a refreshed access token to every joined channel. */
    internal suspend fun refreshToken() {
        val before = lastToken
        val token = token() ?: return
        if (token == before) return
        channels.filter { it.status.value == RealtimeChannel.Status.JOINED }.forEach { it.sendAccessToken(token) }
    }

    private fun route(message: PhoenixMessage) {
        if (message.topic == PhoenixEvents.PHOENIX_TOPIC) {
            if (message.event == PhoenixEvents.REPLY && message.ref == pendingHeartbeat) pendingHeartbeat = null
            return
        }
        channels.filter { it.topic == message.topic }.forEach { it.handle(message) }
    }

    companion object {
        /** `wss://<server>/realtime/v1/websocket?apikey=<key>&vsn=1.0.0` for a backend at [baseUrl]. */
        fun urlFor(baseUrl: String, apiKey: String): String {
            val http = baseUrl.trim().trimEnd('/').toHttpUrl().newBuilder()
                .addPathSegments("realtime/v1/websocket")
                .addQueryParameter("apikey", apiKey)
                .addQueryParameter("vsn", "1.0.0")
                .build()
                .toString()
            return when {
                http.startsWith("https://") -> "wss://" + http.removePrefix("https://")
                http.startsWith("http://") -> "ws://" + http.removePrefix("http://")
                else -> http
            }
        }
    }
}

/**
 * One Realtime channel: joined with [RealtimeProtocol.joinPayload], rejoined after an error or a
 * reconnect, with broadcast send/receive and presence (track, `presence_state`, `presence_diff`).
 * Callbacks run on the socket's thread.
 */
class RealtimeChannel internal constructor(
    private val socket: RealtimeSocket,
    val topic: String,
    private val config: RealtimeChannelConfig,
) {
    enum class Status { CLOSED, JOINING, JOINED }

    private val _status = MutableStateFlow(Status.CLOSED)
    val status: StateFlow<Status> = _status.asStateFlow()

    var onBroadcast: (event: String, payload: JsonObject) -> Unit = { _, _ -> }
    var onPresence: (PresenceChange) -> Unit = {}

    private val presence = PresenceTracker()
    val presenceState: Map<String, List<RealtimePresence>> get() = presence.current

    private var joinRef: String? = null
    private var wanted = false
    private var timeoutJob: Job? = null
    private var rejoinJob: Job? = null

    /** Joins now, or as soon as the socket is open, and again after every reconnect. */
    fun subscribe() {
        socket.scope.launch {
            wanted = true
            if (socket.connected.value) join() else socket.connect()
        }
    }

    /** Sends a broadcast; false when the channel isn't joined. */
    fun broadcast(event: String, payload: JsonObject) {
        socket.scope.launch { pushJoined(PhoenixEvents.BROADCAST, RealtimeProtocol.broadcastPayload(event, payload)) }
    }

    /** Tracks [state] as this client's presence under the channel's presence key. */
    fun track(state: JsonObject) {
        socket.scope.launch { pushJoined(PhoenixEvents.PRESENCE, RealtimeProtocol.trackPayload(state)) }
    }

    fun untrack() {
        socket.scope.launch { pushJoined(PhoenixEvents.PRESENCE, RealtimeProtocol.untrackPayload()) }
    }

    fun unsubscribe() {
        socket.scope.launch { leaveNow() }
    }

    internal fun leaveNow() {
        wanted = false
        rejoinJob?.cancel()
        timeoutJob?.cancel()
        if (_status.value != Status.CLOSED) socket.push(PhoenixMessage(topic, PhoenixEvents.LEAVE, JsonObject(emptyMap()), socket.makeRef(), joinRef))
        _status.value = Status.CLOSED
        presence.clear()
        socket.remove(this)
    }

    internal fun onSocketOpen() {
        if (wanted) socket.scope.launch { join() }
    }

    internal fun onSocketClosed() {
        timeoutJob?.cancel()
        rejoinJob?.cancel()
        presence.clear()
        _status.value = Status.CLOSED
    }

    internal fun sendAccessToken(token: String) {
        pushJoined(PhoenixEvents.ACCESS_TOKEN, RealtimeProtocol.accessTokenPayload(token))
    }

    private suspend fun join() {
        if (!wanted || _status.value != Status.CLOSED || !socket.connected.value) return
        _status.value = Status.JOINING
        val token = socket.token()
        if (!wanted || _status.value != Status.JOINING) return
        val ref = socket.makeRef()
        joinRef = ref
        socket.push(PhoenixMessage(topic, PhoenixEvents.JOIN, RealtimeProtocol.joinPayload(config, token), ref, ref))
        timeoutJob?.cancel()
        timeoutJob = socket.scope.launch {
            kotlinx.coroutines.delay(socket.joinTimeoutMs)
            if (_status.value == Status.JOINING && joinRef == ref) failed()
        }
    }

    private fun failed() {
        timeoutJob?.cancel()
        presence.clear()
        _status.value = Status.CLOSED
        if (!wanted) return
        rejoinJob?.cancel()
        rejoinJob = socket.scope.launch {
            kotlinx.coroutines.delay(socket.rejoinDelayMs)
            join()
        }
    }

    private fun pushJoined(event: String, payload: JsonObject): Boolean {
        if (_status.value != Status.JOINED) return false
        return socket.push(PhoenixMessage(topic, event, payload, socket.makeRef(), joinRef))
    }

    internal fun handle(message: PhoenixMessage) {
        when (message.event) {
            PhoenixEvents.REPLY -> {
                if (message.ref == null || message.ref != joinRef || _status.value != Status.JOINING) return
                timeoutJob?.cancel()
                if (RealtimeProtocol.replyStatus(message.payload) == "ok") {
                    _status.value = Status.JOINED
                } else {
                    failed()
                }
            }
            PhoenixEvents.ERROR, PhoenixEvents.CLOSE -> {
                // phx_close also follows our own leave; then nothing is wanted any more.
                if (_status.value != Status.CLOSED) failed()
            }
            PhoenixEvents.SYSTEM -> {
                if (message.payload.string("status") == "error" && _status.value != Status.CLOSED) failed()
            }
            PhoenixEvents.BROADCAST -> {
                val (event, payload) = RealtimeProtocol.broadcastOf(message.payload) ?: return
                onBroadcast(event, payload)
            }
            PhoenixEvents.PRESENCE_STATE -> presence.syncState(message.payload).takeUnless { it.isEmpty }?.let(onPresence)
            PhoenixEvents.PRESENCE_DIFF -> presence.syncDiff(message.payload).takeUnless { it.isEmpty }?.let(onPresence)
        }
    }
}
