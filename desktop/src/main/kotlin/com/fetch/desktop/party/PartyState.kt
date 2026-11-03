package com.fetch.desktop.party

import com.fetch.desktop.sync.SyncAuthException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark

/** The signed-in sync server, as a party needs it. */
interface PartyBackend {
    val api: PartyApi
    /** This install's id on the sync server (`p_device_id`), the TV's `SyncClientIdentity`. */
    val deviceId: String
    /** Member avatars are only shown from the server's own storage. */
    val storageUrl: String
    fun openChannel(partyId: String, memberId: String): PartyChannelPort
}

/** Who this device is in a party: the active profile. */
data class PartyProfile(val name: String, val imageUrl: String?, val color: String?)

/** The TV's intervals; tests shorten them. */
data class PartyTimings(
    val heartbeat: Duration = 2.minutes,
    val persistDelay: Duration = 1.5.seconds,
    val coordinatorTick: Duration = 0.5.seconds,
    val resumeGrace: Duration = 3.seconds,
    val waitLimit: Duration = 15.seconds,
    val clockSampleGap: Duration = 0.4.seconds,
    val clockSamples: Int = 4,
    val presenceDelay: Duration = 0.25.seconds,
)

/** What the player's sync loop needs from the party (see [PartyPlayerSync]). */
interface PartySyncHost {
    val state: StateFlow<PartyUiState>
    val playback: StateFlow<PartyPlayback?>
    fun nowMs(): Long
    fun publishPlayback(paused: Boolean, positionMs: Long, rate: Float)
    fun publishMedia(media: PartyMedia, positionMs: Long)
    fun shareStream(stream: PartyStream?)
    fun setLocalStatus(status: PartyMemberStatus, mediaKey: String?, behindMs: Long? = null)
}

/**
 * The watch party, ported from the TV app's `PartyRepository`: create or join, members and
 * presence, host-only control, removing members, leave and end, the heartbeat, media changes,
 * the host's shared stream, and the playback state everyone follows (with the server's clock).
 * Work runs on one thread; the flows can be read from anywhere.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PartyState(
    parentScope: CoroutineScope,
    /** A backend for the signed-in account, or null. */
    private val connect: () -> PartyBackend?,
    private val signedIn: () -> Boolean,
    private val profile: () -> PartyProfile,
    /** Whether the server's discovery document lists `watch_party`; null when unknown. */
    private val capability: suspend () -> Boolean?,
    /** The server rejected the session for good. */
    private val onAuthLost: () -> Unit = {},
    private val timings: PartyTimings = PartyTimings(),
    private val clock: PartyClock = PartyClock(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) : PartySyncHost {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + dispatcher)
    private val mutex = Mutex()

    private val _state = MutableStateFlow(PartyUiState())
    override val state: StateFlow<PartyUiState> = _state.asStateFlow()

    private val _playback = MutableStateFlow<PartyPlayback?>(null)
    override val playback: StateFlow<PartyPlayback?> = _playback.asStateFlow()

    private val _events = MutableSharedFlow<PartyEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<PartyEvent> = _events.asSharedFlow()

    private val _notices = MutableSharedFlow<PartyNotice>(extraBufferCapacity = 8)
    val notices: SharedFlow<PartyNotice> = _notices.asSharedFlow()

    private val _sharedStream = MutableStateFlow<PartyStream?>(null)
    val sharedStream: StateFlow<PartyStream?> = _sharedStream.asStateFlow()

    private val _available = MutableStateFlow(false)
    /** The server offers watch parties (discovery's `watch_party`, and `watch_party_enabled`). */
    val available: StateFlow<Boolean> = _available.asStateFlow()

    /** Available and signed in: the TV's `canUse`. */
    val canUse: Boolean get() = _available.value && signedIn()

    val currentProfile: PartyProfile get() = profile()

    private var backend: PartyBackend? = null
    private var channel: PartyChannelPort? = null
    private var sessionScope: CoroutineScope? = null
    private var sessionJob: Job? = null
    private var persistJob: Job? = null
    private var presenceJob: Job? = null
    private var localPresence: PartyPresence? = null
    private var waitStartedAt: TimeMark? = null
    private var resumedAt: TimeMark? = null
    private val ignoredWaiting = mutableSetOf<String>()
    private var rejoining = false

    override fun nowMs(): Long = clock.nowMs()

    /** Checks the server again (on start-up and sign-in); a party on a server that switched it off ends. */
    fun refreshAvailability() {
        scope.launch {
            if (!signedIn()) {
                _available.value = false
                return@launch
            }
            val capable = runCatching { capability() }.getOrNull()
            if (capable == false) {
                _available.value = false
                return@launch
            }
            if (capable == true) _available.value = true
            val api = (backend ?: connect())?.api ?: return@launch
            // Unknown capability (an older sign-in): the RPC answering is the test.
            val enabled = runRemote { api.enabled() }
            if (enabled == null) {
                if (capable == null) _available.value = false
                return@launch
            }
            _available.value = enabled
            if (!enabled) finish(PartyEvent.Ended)
        }
    }

    suspend fun create(media: PartyMedia): PartyError? =
        start { api, identity -> api.create(identity, media, null) }

    suspend fun join(code: String): PartyError? =
        start { api, identity -> api.join(normalizePartyCode(code), identity) }

    suspend fun preview(code: String): PartyPreview =
        withContext(dispatcher) {
            val api = (backend ?: connect())?.api ?: return@withContext PartyPreview(error = "network")
            try {
                api.preview(normalizePartyCode(code))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log("Watch party preview failed", error)
                if (error is SyncAuthException) onAuthLost()
                PartyPreview(error = "network")
            }
        }

    fun leave() {
        scope.launch {
            mutex.withLock {
                val party = _state.value.party ?: return@withLock
                val api = backend?.api
                val device = deviceId()
                stopSession()
                if (api != null) runRemote { api.leave(party.id, device) }
            }
        }
    }

    fun end() {
        scope.launch {
            mutex.withLock {
                val party = _state.value.party ?: return@withLock
                val result = runRemote { requireApi().end(party.id, deviceId()) } ?: return@withLock
                if (result.error == null) stopSession()
            }
        }
    }

    fun removeMember(memberId: String) {
        scope.launch {
            val party = _state.value.party ?: return@launch
            val snapshot = runRemote { requireApi().removeMember(party.id, deviceId(), memberId) } ?: return@launch
            applySnapshot(snapshot)
        }
    }

    fun setHostOnly(enabled: Boolean) {
        scope.launch {
            val party = _state.value.party ?: return@launch
            if (!party.isHost || party.hostOnly == enabled) return@launch
            updateParty { it.copy(hostOnly = enabled) }
            val snapshot = runRemote { requireApi().setHostOnly(party.id, deviceId(), enabled) }
            if (snapshot?.party == null) updateParty { it.copy(hostOnly = party.hostOnly) }
            snapshot?.let(::applySnapshot)
        }
    }

    fun setSharing(enabled: Boolean) {
        scope.launch {
            val party = _state.value.party ?: return@launch
            if (!party.isHost || party.sharing == enabled) return@launch
            updateParty { it.copy(sharing = enabled) }
            if (!enabled) sendStream(null)
        }
    }

    override fun shareStream(stream: PartyStream?) {
        scope.launch {
            val party = _state.value.party ?: return@launch
            if (!party.isHost || !party.sharing || stream == _sharedStream.value) return@launch
            sendStream(stream)
        }
    }

    override fun publishPlayback(paused: Boolean, positionMs: Long, rate: Float) {
        val at = clock.nowMs()
        scope.launch { publish(paused = paused, positionMs = positionMs, rate = rate, waiting = false, at = at) }
    }

    override fun publishMedia(media: PartyMedia, positionMs: Long) {
        scope.launch {
            val party = _state.value.party ?: return@launch
            if (media == party.media || !party.isHost) return@launch
            val sameContent = media.key == party.media.key
            updateParty { it.copy(media = media) }
            val next = if (sameContent) {
                _playback.value
            } else {
                nextPlayback(party, paused = false, positionMs = positionMs, rate = 1f, waiting = false, mediaKey = media.key, at = clock.nowMs())
                    .also { _playback.value = it }
            }
            persistJob?.cancel()
            val snapshot = runRemote { requireApi().update(party.id, deviceId(), media, next) }
            snapshot?.let(::applySnapshot)
        }
    }

    override fun setLocalStatus(status: PartyMemberStatus, mediaKey: String?, behindMs: Long?) {
        scope.launch {
            val party = _state.value.party ?: return@launch
            val next = PartyPresence(memberId = party.memberId, status = status, mediaKey = mediaKey, behindMs = behindMs)
            if (next == localPresence) return@launch
            localPresence = next
            if (status == PartyMemberStatus.Ready) ignoredWaiting.remove(party.memberId)
            updateParty { it.copy(presence = it.presence + (party.memberId to next)) }
            presenceJob?.cancel()
            presenceJob = sessionScope?.launch {
                delay(timings.presenceDelay)
                runCatching { channel?.send("status", encodePresence(next)) }
            }
        }
    }

    /** Leaves without waiting (the profile is closing). */
    fun dispose() {
        val party = _state.value.party
        val api = backend?.api
        val device = backend?.deviceId
        channel?.closeNow()
        sessionJob?.cancel()
        if (party != null && api != null && device != null) {
            Thread({ runCatching { kotlinx.coroutines.runBlocking { api.leave(party.id, device) } } }, "party-leave").apply { isDaemon = true }.start()
        }
        scope.coroutineContext[Job]?.cancel()
    }

    private suspend fun start(request: suspend (PartyApi, PartyIdentity) -> PartySnapshot): PartyError? {
        if (!signedIn()) return PartyError.SignInRequired
        if (!_available.value) return PartyError.Unavailable
        return withContext(dispatcher) {
            mutex.withLock {
                val connected = connect() ?: return@withLock PartyError.SignInRequired
                _state.update { it.copy(busy = true) }
                try {
                    val sentAt = clock.mark()
                    val snapshot = request(connected.api, identity(connected))
                    partyErrorOf(snapshot.error)?.let { error ->
                        if (error == PartyError.Unavailable) _available.value = false
                        return@withLock error
                    }
                    val party = snapshot.toActiveParty(connected) ?: return@withLock PartyError.Network
                    stopSession()
                    backend = connected
                    clock.reset()
                    snapshot.serverTimeMs?.let { clock.addSample(it, sentAt) }
                    startSession(party, snapshot.party?.playback)
                    null
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    log("Watch party request failed", error)
                    if (error is SyncAuthException) {
                        onAuthLost()
                        PartyError.SignInRequired
                    } else {
                        PartyError.Network
                    }
                } finally {
                    _state.update { it.copy(busy = false) }
                }
            }
        }
    }

    private fun startSession(party: ActiveParty, playback: PartyPlayback?) {
        val connected = backend ?: return
        val job = SupervisorJob(scope.coroutineContext[Job])
        val session = CoroutineScope(dispatcher + job)
        val partyChannel = connected.openChannel(party.id, party.memberId)
        sessionJob = job
        sessionScope = session
        channel = partyChannel
        localPresence = null
        ignoredWaiting.clear()
        waitStartedAt = null
        resumedAt = null
        _playback.value = playback?.takeIf { it.mediaKey == null || it.mediaKey == party.media.key }
        _state.value = PartyUiState(party = party)
        runCatching {
            partyChannel.open(
                scope = session,
                onMessage = { event, payload -> session.launch { onMessage(event, payload) } },
                onPresence = { joins, leaves -> session.launch { onPresence(joins, leaves) } },
                onConnected = { connected -> session.launch { onConnection(connected) } },
            )
        }.onFailure { log("Watch party channel failed", it) }
        session.launch { heartbeatLoop() }
        session.launch { refineClock() }
        session.launch { coordinatorLoop() }
    }

    private fun stopSession() {
        sessionJob?.cancel()
        sessionJob = null
        sessionScope = null
        persistJob?.cancel()
        persistJob = null
        presenceJob = null
        channel?.close()
        channel = null
        localPresence = null
        _playback.value = null
        _sharedStream.value = null
        _state.value = PartyUiState()
    }

    private fun finish(event: PartyEvent) {
        val partyId = _state.value.party?.id ?: return
        scope.launch {
            mutex.withLock {
                if (_state.value.party?.id != partyId) return@withLock
                stopSession()
                _events.tryEmit(event)
            }
        }
    }

    private fun onConnection(connected: Boolean) {
        if (!connected) {
            updateParty { current -> current.copy(connected = false, presence = current.presence.filterKeys { it == current.memberId }) }
            return
        }
        updateParty { it.copy(connected = true) }
        val party = _state.value.party ?: return
        val presence = encodePresence(ownPresence(party))
        runCatching { channel?.track(presence) }
        runCatching { channel?.send("status", presence) }
        _playback.value?.let { current -> runCatching { channel?.send("state", encodePlayback(current)) } }
        if (party.isHost && party.sharing) _sharedStream.value?.let { sendStream(it) }
        requestState(party)
    }

    private suspend fun onMessage(event: String, payload: JsonObject) {
        val party = _state.value.party ?: return
        when (event) {
            "state" -> decode(payload, PartyPlayback.serializer())?.let(::accept)
            "state_request" -> {
                if (payload.string("member_id") == party.memberId) return
                runCatching { channel?.send("status", encodePresence(ownPresence(party))) }
                if (party.isHost && party.sharing) _sharedStream.value?.let { sendStream(it) }
                val current = _playback.value ?: return
                runCatching { channel?.send("state", encodePlayback(current)) }
            }
            "status" -> decode(payload, PartyPresence.serializer())?.let(::applyStatus)
            "control" -> {
                val hostOnly = (payload["host_only"] as? JsonPrimitive)?.booleanOrNull ?: return
                if (party.hostOnly == hostOnly) return
                updateParty { it.copy(hostOnly = hostOnly) }
                if (!party.isHost) _events.tryEmit(PartyEvent.ControlChanged(hostOnly))
            }
            "member_joined" -> refreshMembers(announceMemberId = payload.string("member_id"))
            "member_left" -> onMemberLeft(
                memberId = payload.string("member_id"),
                reason = payload.string("reason"),
                hostMemberId = payload.string("host_member_id"),
            )
            "ended" -> {
                if (payload.string("reason") == "disabled") _available.value = false
                finish(PartyEvent.Ended)
            }
            "media" -> {
                val media = payload["media"]?.let { decode(it, PartyMedia.serializer()) } ?: return
                val playback = payload["playback"]?.takeIf { it !is JsonNull }?.let { decode(it, PartyPlayback.serializer()) }
                applyMedia(media, playback)
            }
            "stream" -> {
                if (party.isHost || payload.string("member_id") != party.hostMemberId) return
                _sharedStream.value = payload["stream"]
                    ?.takeIf { it !is JsonNull }
                    ?.let { decode(it, PartyStream.serializer()) }
                    ?.takeIf { isShareableStreamUrl(it.url) }
            }
        }
    }

    private fun onPresence(joins: Map<String, JsonObject>, leaves: Map<String, JsonObject>) {
        val party = _state.value.party ?: return
        val presence = party.presence.toMutableMap()
        val left = leaves.keys.filter { it != party.memberId && it !in joins }
        presence -= left.toSet()
        joins.forEach { (key, value) ->
            if (key == party.memberId || key in presence) return@forEach
            val decoded = decode(value, PartyPresence.serializer()) ?: PartyPresence(memberId = key)
            presence[key] = decoded.copy(memberId = key)
            if (decoded.status == PartyMemberStatus.Ready) ignoredWaiting.remove(key)
        }
        updateParty { it.copy(presence = presence) }
        if (left.isNotEmpty()) sessionScope?.launch { requestState(party) }
    }

    private fun requestState(party: ActiveParty) {
        runCatching { channel?.send("state_request", buildJsonObject { put("member_id", party.memberId) }) }
    }

    private fun applyStatus(status: PartyPresence) {
        val party = _state.value.party ?: return
        if (status.memberId == party.memberId) return
        if (status.status == PartyMemberStatus.Ready) ignoredWaiting.remove(status.memberId)
        updateParty { it.copy(presence = it.presence + (status.memberId to status)) }
    }

    private fun ownPresence(party: ActiveParty): PartyPresence =
        localPresence ?: PartyPresence(memberId = party.memberId)

    private fun onMemberLeft(memberId: String?, reason: String?, hostMemberId: String?) {
        val party = _state.value.party ?: return
        if (memberId == null) return
        if (memberId == party.memberId) {
            if (reason == "removed") finish(PartyEvent.Removed) else rejoinOrFinish()
            return
        }
        val name = party.member(memberId)?.displayName
        if (memberId == party.hostMemberId) _sharedStream.value = null
        updateParty { current ->
            current.copy(
                members = current.members.filterNot { it.id == memberId },
                presence = current.presence - memberId,
                hostMemberId = hostMemberId ?: current.hostMemberId,
            )
        }
        ignoredWaiting.remove(memberId)
        if (name != null) _events.tryEmit(PartyEvent.MemberLeft(name))
    }

    private suspend fun refreshMembers(announceMemberId: String?) {
        val party = _state.value.party ?: return
        val snapshot = runRemote { requireApi().heartbeat(party.id, deviceId()) } ?: return
        applySnapshot(snapshot)
        val name = _state.value.party?.member(announceMemberId)?.displayName
        if (announceMemberId != null && announceMemberId != party.memberId && name != null) {
            _events.tryEmit(PartyEvent.MemberJoined(name))
        }
    }

    private fun applySnapshot(snapshot: PartySnapshot) {
        when (snapshot.error) {
            null -> Unit
            "not_member" -> {
                rejoinOrFinish()
                return
            }
            "party_ended" -> {
                finish(PartyEvent.Ended)
                return
            }
            else -> return
        }
        val info = snapshot.party ?: return
        val members = snapshot.members.withSafeAvatars()
        updateParty { current ->
            current.copy(
                members = members,
                hostMemberId = info.hostMemberId,
                maxMembers = info.maxMembers,
                hostOnly = info.hostOnly,
                presence = current.presence.filterKeys { key -> members.any { it.id == key } },
            )
        }
        if (info.media.key != _state.value.party?.media?.key) applyMedia(info.media, info.playback)
        info.playback?.let(::accept)
    }

    private fun applyMedia(media: PartyMedia, playback: PartyPlayback?) {
        val party = _state.value.party ?: return
        val changed = media.key != party.media.key
        updateParty { it.copy(media = media) }
        if (changed) {
            _playback.value = playback?.takeIf { it.mediaKey == null || it.mediaKey == media.key }
            if (_sharedStream.value?.mediaKey != media.key) _sharedStream.value = null
            ignoredWaiting.clear()
            waitStartedAt = null
            _events.tryEmit(PartyEvent.MediaChanged(media))
        } else {
            playback?.let(::accept)
        }
    }

    private fun accept(candidate: PartyPlayback) {
        val party = _state.value.party ?: return
        if (candidate.mediaKey != null && candidate.mediaKey != party.media.key) return
        if (party.hostOnly && candidate.memberId != party.hostMemberId) return
        val current = _playback.value
        if (!PartySync.isNewer(candidate, current)) return
        if (current != null && current.paused && !candidate.paused) {
            resumedAt = clock.mark()
            if (current.waiting) ignoredWaiting += PartySync.waitingMemberIds(party, emptySet())
        }
        _playback.value = candidate
        if (candidate.memberId == party.memberId) return
        val action = PartySync.actionOf(current, candidate) ?: return
        val name = party.member(candidate.memberId)?.displayName ?: return
        _notices.tryEmit(PartyNotice(name, action, candidate.positionMs, candidate.rate))
    }

    private fun publish(
        paused: Boolean,
        positionMs: Long,
        rate: Float,
        waiting: Boolean,
        at: Long = clock.nowMs(),
    ) {
        val party = _state.value.party ?: return
        if (!party.canControl) return
        val current = _playback.value
        val next = nextPlayback(party, paused, positionMs, rate, waiting, party.media.key, at)
        if (current != null && current.paused && !paused) {
            resumedAt = clock.mark()
            if (current.waiting && !waiting) ignoredWaiting += PartySync.waitingMemberIds(party, emptySet())
        }
        if (!waiting) waitStartedAt = null
        _playback.value = next
        runCatching { channel?.send("state", encodePlayback(next)) }
        schedulePersist()
    }

    private fun nextPlayback(
        party: ActiveParty,
        paused: Boolean,
        positionMs: Long,
        rate: Float,
        waiting: Boolean,
        mediaKey: String,
        at: Long,
    ) = PartyPlayback(
        seq = (_playback.value?.seq ?: 0L) + 1L,
        memberId = party.memberId,
        paused = paused,
        positionMs = positionMs.coerceAtLeast(0L),
        rate = rate.coerceIn(0.25f, 4f),
        at = at,
        mediaKey = mediaKey,
        waiting = waiting,
    )

    private fun schedulePersist() {
        persistJob?.cancel()
        persistJob = sessionScope?.launch {
            delay(timings.persistDelay)
            val party = _state.value.party ?: return@launch
            val playback = _playback.value ?: return@launch
            runRemote { requireApi().update(party.id, deviceId(), null, playback) }?.let { applySnapshot(it) }
        }
    }

    private suspend fun heartbeatLoop() {
        while (currentScopeActive()) {
            delay(timings.heartbeat)
            val party = _state.value.party ?: return
            val snapshot = runRemote { requireApi().heartbeat(party.id, deviceId()) } ?: continue
            applySnapshot(snapshot)
        }
    }

    private suspend fun refineClock() {
        repeat(timings.clockSamples) {
            delay(timings.clockSampleGap)
            val sentAt = clock.mark()
            runCatching { requireApi().clock() }.getOrNull()?.let { clock.addSample(it, sentAt) }
        }
    }

    private suspend fun coordinatorLoop() {
        while (currentScopeActive()) {
            delay(timings.coordinatorTick)
            // Signed out on this computer: the party is over here (the TV watches its auth state).
            if (!signedIn()) {
                finish(PartyEvent.Ended)
                return
            }
            val party = _state.value.party ?: return
            if (!party.connected) continue
            if (PartySync.coordinatorId(party) != party.memberId) {
                waitStartedAt = null
                continue
            }
            val playback = _playback.value ?: continue
            if (playback.mediaKey != null && playback.mediaKey != party.media.key) continue
            val waiting = PartySync.waitingMemberIds(party, ignoredWaiting)
            if (!playback.paused) {
                val graceOver = resumedAt?.let { it.elapsedNow() > timings.resumeGrace } ?: true
                if (waiting.isNotEmpty() && graceOver) {
                    val position = PartySync.expectedPositionMs(playback, clock.nowMs())
                    publish(paused = true, positionMs = position, rate = playback.rate, waiting = true)
                    waitStartedAt = clock.mark()
                }
            } else if (playback.waiting) {
                val started = waitStartedAt ?: clock.mark().also { waitStartedAt = it }
                if (waiting.isEmpty() || started.elapsedNow() > timings.waitLimit) {
                    ignoredWaiting += waiting
                    publish(paused = false, positionMs = playback.positionMs, rate = playback.rate, waiting = false)
                }
            }
        }
    }

    private fun rejoinOrFinish() {
        val party = _state.value.party ?: return
        if (rejoining) return
        rejoining = true
        scope.launch {
            try {
                mutex.withLock {
                    if (_state.value.party?.id != party.id) return@withLock
                    val connected = backend ?: return@withLock
                    val snapshot = runRemote { connected.api.join(party.code, identity(connected)) }
                    val rejoined = snapshot?.takeIf { it.error == null }?.toActiveParty(connected)
                    if (rejoined == null) {
                        stopSession()
                        _events.tryEmit(if (snapshot?.error == "removed") PartyEvent.Removed else PartyEvent.Ended)
                        return@withLock
                    }
                    if (rejoined.memberId == party.memberId) {
                        updateParty { it.copy(members = rejoined.members, hostMemberId = rejoined.hostMemberId) }
                        return@withLock
                    }
                    val playback = _playback.value
                    stopSession()
                    backend = connected
                    startSession(rejoined, playback)
                }
            } finally {
                rejoining = false
            }
        }
    }

    private fun currentScopeActive(): Boolean = sessionScope?.isActive == true

    private fun updateParty(transform: (ActiveParty) -> ActiveParty) {
        _state.update { state -> state.party?.let { state.copy(party = transform(it)) } ?: state }
    }

    private suspend fun <T> runRemote(block: suspend () -> T): T? =
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log("Watch party call failed", error)
            if (error is SyncAuthException) {
                onAuthLost()
                finish(PartyEvent.Ended)
            }
            null
        }

    private fun requireApi(): PartyApi = backend?.api ?: error("No watch party backend")

    private fun identity(connected: PartyBackend): PartyIdentity {
        val current = profile()
        return PartyIdentity(
            deviceId = connected.deviceId,
            displayName = current.name.trim().takeIf { it.isNotEmpty() }?.take(40) ?: DEFAULT_NAME,
            avatarUrl = current.imageUrl?.takeIf { it.startsWith("https://") && it.length <= 512 },
            avatarColor = current.color?.takeIf { COLOR_PATTERN.matches(it) },
        )
    }

    private fun deviceId(): String = backend?.deviceId.orEmpty()

    private fun sendStream(stream: PartyStream?) {
        val party = _state.value.party ?: return
        _sharedStream.value = stream
        val payload = buildJsonObject {
            put("member_id", party.memberId)
            put("stream", stream?.let { PartyApi.json.encodeToJsonElement(PartyStream.serializer(), it) } ?: JsonNull)
        }
        runCatching { channel?.send("stream", payload) }
    }

    private fun encodePlayback(playback: PartyPlayback): JsonObject =
        PartyApi.json.encodeToJsonElement(PartyPlayback.serializer(), playback).jsonObject

    private fun encodePresence(presence: PartyPresence): JsonObject =
        PartyApi.json.encodeToJsonElement(PartyPresence.serializer(), presence).jsonObject

    private fun <T> decode(element: JsonElement, serializer: KSerializer<T>): T? =
        runCatching { PartyApi.json.decodeFromJsonElement(serializer, element) }.getOrNull()

    private fun List<PartyMember>.withSafeAvatars(): List<PartyMember> {
        val storage = backend?.storageUrl
        return map { member -> member.copy(avatarUrl = member.avatarUrl?.takeIf { storage != null && it.startsWith(storage) }) }
    }

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull

    private fun PartySnapshot.toActiveParty(connected: PartyBackend): ActiveParty? {
        val info = party ?: return null
        val self = memberId ?: return null
        val storage = connected.storageUrl
        return ActiveParty(
            id = info.id,
            code = info.code,
            memberId = self,
            hostMemberId = info.hostMemberId,
            members = members.map { m -> m.copy(avatarUrl = m.avatarUrl?.takeIf { it.startsWith(storage) }) },
            presence = emptyMap(),
            media = info.media,
            maxMembers = info.maxMembers,
            connected = false,
            hostOnly = info.hostOnly,
            inviteLink = "fetch://party/${info.code}",
        )
    }

    private fun log(message: String, error: Throwable) {
        if (System.getenv("FETCH_PARTY_DEBUG") == "1") System.err.println("[party] $message: $error")
    }

    private companion object {
        const val DEFAULT_NAME = "Fetch"
        val COLOR_PATTERN = Regex("^#[0-9A-Fa-f]{6}$")
    }
}
