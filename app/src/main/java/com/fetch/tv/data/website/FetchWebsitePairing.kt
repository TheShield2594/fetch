package com.fetch.tv.data.website

import android.os.Build
import com.fetch.tv.core.sync.backend.SyncBackendKind
import com.fetch.tv.core.sync.backend.SyncBackends
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Where a pairing has got to. */
sealed interface WebsitePairingState {
    /** Nothing in progress: the pairing UI starts a hub code, or asks for an address. */
    data object EnterAddress : WebsitePairingState

    /** Getting a code: from the pairing hub, or from the website at a typed address. */
    data object Starting : WebsitePairingState

    /**
     * Showing the code (and QR) while waiting for someone to confirm it on the website.
     * [viaHub] for a code from the pairing hub, which any Fetch website can confirm, so there
     * is no [serverUrl] yet; [confirmUrl] is then the hub's `/link` page.
     */
    data class ShowingCode(
        val code: String,
        val confirmUrl: String?,
        val serverUrl: String?,
        val expiresAtEpochMs: Long,
        val viaHub: Boolean = false
    ) : WebsitePairingState

    /** A hub code was confirmed by the website at [serverUrl]; collecting this TV's sign-in from it. */
    data class Connecting(val serverUrl: String) : WebsitePairingState

    data object Expired : WebsitePairingState
    data class Paired(val deviceName: String) : WebsitePairingState

    /**
     * Pairing couldn't start or finish. [message] is the error to show; null with [notFound]
     * false means the address isn't one at all, [notFound] that nothing there answers as a
     * Fetch website. [hubUnavailable]: the pairing hub couldn't be reached for a code, so the
     * website has to be picked or typed instead. [handoffFrom]: a website confirmed the hub code
     * but this TV couldn't collect its sign-in from that address.
     */
    data class Failed(
        val message: String?,
        val notFound: Boolean = false,
        val hubUnavailable: Boolean = false,
        val handoffFrom: String? = null
    ) : WebsitePairingState
}

/**
 * Pairs this TV with a Fetch website. One pairing at a time for the whole app, so it can be
 * started from the TV's own dialog or from a phone (the phone hub's "Fetch website" page,
 * after scanning the QR code on the TV) and both show the same code.
 *
 * The usual way needs no address at all: [startHub] gets a code from the pairing hub
 * (watchfetch.tv, see [FetchPairingHub]), any Fetch website signed in on a phone or computer
 * confirms it, and the hub tells this TV where that website is. Websites found on the LAN and
 * a typed address ([start]) remain for a website that has the hub turned off, or when the hub
 * can't be reached.
 *
 * The address can be the website's (what people open in a browser) or its API's; the API is
 * found from either by [FetchWebsiteClient.resolveServer]. The QR code for confirming then
 * points at the website address that worked, so it opens on a phone even when the server's
 * own idea of its address (`FETCH_WEB_PUBLIC`) was never set.
 */
@Singleton
class FetchWebsitePairing @Inject constructor(
    private val store: FetchWebsiteStore,
    private val client: FetchWebsiteClient,
    private val sync: FetchWebsiteSyncService,
    private val backends: SyncBackends
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<WebsitePairingState>(WebsitePairingState.EnterAddress)
    val state: StateFlow<WebsitePairingState> = _state.asStateFlow()

    private var job: Job? = null

    /** The address the last pairing started from, for "Try again". */
    @Volatile
    var lastAddress: String = store.lastServerUrl.orEmpty()
        private set

    /** Whether the last pairing was a hub code, so "Get a new code" gets another. */
    @Volatile
    private var lastWasHub: Boolean = true

    /** Gets a code from the pairing hub; whichever website confirms it is the one this TV joins. */
    @Synchronized
    fun startHub() {
        job?.cancel()
        lastWasHub = true
        _state.value = WebsitePairingState.Starting
        job = scope.launch(start = CoroutineStart.LAZY) { runHub() }.also { it.start() }
    }

    /** "Get a new code": the same way as last time. */
    fun retry() = if (lastWasHub) startHub() else start(lastAddress)

    @Synchronized
    fun start(address: String) {
        job?.cancel()
        // Cleared now, not only when a new job replaces it: an address that isn't one starts
        // no job, and the cancelled one must not count as current and overwrite the error.
        job = null
        lastWasHub = false
        lastAddress = address.trim()
        if (FetchServerAddress.normalize(address) == null) {
            _state.value = WebsitePairingState.Failed(null)
            return
        }
        _state.value = WebsitePairingState.Starting
        job = scope.launch(start = CoroutineStart.LAZY) { run(address) }.also { it.start() }
    }

    /**
     * Starts pairing with [address] and waits for the code, for a phone that asked. Returns
     * the first state past [WebsitePairingState.Starting]: the code, or why there is none.
     */
    suspend fun startAndAwaitCode(address: String, timeoutMs: Long = 30_000): WebsitePairingState {
        start(address)
        return withTimeoutOrNull(timeoutMs) { state.first { it !is WebsitePairingState.Starting } }
            ?: run {
                // Stop looking, so the TV doesn't go on to show a code the phone was told isn't coming.
                reset()
                WebsitePairingState.Failed(null, notFound = true)
            }
    }

    @Synchronized
    fun reset() {
        job?.cancel()
        job = null
        _state.value = WebsitePairingState.EnterAddress
    }

    fun signOut() {
        store.clear()
        reset()
    }

    private suspend fun run(address: String) {
        try {
            val server = client.resolveServer(address)
            if (server == null) {
                publish(WebsitePairingState.Failed(null, notFound = true))
                return
            }
            val serverUrl = server.apiUrl
            val start = client.startPairing(serverUrl, deviceName())
            val expiresAt = runCatching { Instant.parse(start.expiresAt).toEpochMilli() }
                .getOrDefault(System.currentTimeMillis() + 5 * 60_000L)
            // The website address that just worked beats the one the server was configured
            // with, which compose fills with an example IP until someone changes it.
            val confirmUrl = server.webUrl?.let { FetchServerAddress.confirmUrl(it, start.code) } ?: start.confirmUrl
            publish(WebsitePairingState.ShowingCode(start.code, confirmUrl, serverUrl, expiresAt))
            val intervalMs = start.intervalSec.coerceIn(1, 30) * 1_000L
            while (true) {
                delay(intervalMs)
                when (val poll = client.pollPairing(serverUrl, start.pollSecret)) {
                    WebsitePairingPoll.Pending -> {
                        if (System.currentTimeMillis() > expiresAt) {
                            publish(WebsitePairingState.Expired)
                            return
                        }
                    }
                    WebsitePairingPoll.Expired -> {
                        publish(WebsitePairingState.Expired)
                        return
                    }
                    is WebsitePairingPoll.Approved -> {
                        approve(serverUrl, poll)
                        return
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            publish(WebsitePairingState.Failed(error.message ?: error.javaClass.simpleName))
        }
    }

    private suspend fun runHub() {
        try {
            val handoffSecret = FetchPairingHub.newHandoffSecret()
            val start = try {
                client.startHubPairing(deviceName(), FetchPairingHub.hash(handoffSecret))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                publish(WebsitePairingState.Failed(error.message, hubUnavailable = true))
                return
            }
            val expiresAt = runCatching { Instant.parse(start.expiresAt).toEpochMilli() }
                .getOrDefault(System.currentTimeMillis() + 10 * 60_000L)
            publish(WebsitePairingState.ShowingCode(
                code = start.code,
                confirmUrl = start.linkUrl ?: FetchPairingHub.linkUrl(start.code),
                serverUrl = null,
                expiresAtEpochMs = expiresAt,
                viaHub = true
            ))
            val intervalMs = start.intervalSec.coerceIn(1, 30) * 1_000L
            var failures = 0
            while (true) {
                delay(intervalMs)
                val poll = try {
                    client.pollHubPairing(start.pollSecret).also { failures = 0 }
                } catch (error: IOException) {
                    // A dropped poll or two is Wi-Fi, not a reason to give up on the code.
                    if (++failures >= MAX_HUB_POLL_FAILURES) throw error
                    continue
                }
                when (poll) {
                    HubPairingPoll.Pending -> if (System.currentTimeMillis() > expiresAt) {
                        publish(WebsitePairingState.Expired)
                        return
                    }
                    HubPairingPoll.Expired -> {
                        publish(WebsitePairingState.Expired)
                        return
                    }
                    is HubPairingPoll.Linked -> {
                        publish(WebsitePairingState.Connecting(poll.apiUrl))
                        collectFromWebsite(poll.apiUrl, handoffSecret)
                        return
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            publish(WebsitePairingState.Failed(error.message ?: error.javaClass.simpleName))
        }
    }

    /**
     * The website at [serverUrl] confirmed the hub code and holds a pairing whose poll secret
     * is [handoffSecret]: collect this TV's token from it. The website claims the code at the
     * hub a moment before it stores that pairing, so not finding it yet is waited out.
     */
    private suspend fun collectFromWebsite(serverUrl: String, handoffSecret: String) {
        val deadline = System.currentTimeMillis() + HANDOFF_WAIT_MS
        var lastError: Exception? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                when (val poll = client.pollPairing(serverUrl, handoffSecret)) {
                    is WebsitePairingPoll.Approved -> {
                        approve(serverUrl, poll)
                        return
                    }
                    WebsitePairingPoll.Pending, WebsitePairingPoll.Expired -> lastError = null
                }
            } catch (error: IOException) {
                lastError = error
            }
            delay(HANDOFF_POLL_MS)
        }
        publish(WebsitePairingState.Failed(lastError?.message, handoffFrom = serverUrl))
    }

    private suspend fun approve(serverUrl: String, poll: WebsitePairingPoll.Approved) {
        val name = poll.deviceName.ifBlank { deviceName() }
        val me = currentCoroutineContext()[Job]
        // Checked and saved under the lock that replacing or resetting the pairing takes, so a
        // pairing replaced or cancelled meanwhile can't sign this TV in.
        val signedIn = synchronized(this) {
            if (me == null || me !== job) return@synchronized false
            store.save(FetchWebsiteSession(serverUrl, poll.token, name))
            // Pairing (here, from a phone, or in the first-run Server step) is choosing the website.
            backends.choose(SyncBackendKind.FETCH_WEBSITE)
            _state.value = WebsitePairingState.Paired(name)
            true
        }
        if (signedIn) sync.requestSync()
    }

    /**
     * Publishes [value] only from the pairing that is still current: a job that was replaced
     * (another code, a picked server) or reset can be between a network answer and its next
     * suspension when that happens, and must not overwrite its replacement's state.
     */
    private suspend fun publish(value: WebsitePairingState) {
        val me = currentCoroutineContext()[Job]
        synchronized(this) { if (me != null && me === job) _state.value = value }
    }

    private fun deviceName(): String =
        listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { !it.isNullOrBlank() }
            .distinct()
            .joinToString(" ")
            .ifBlank { "Android TV" }

    private companion object {
        /** Hub polls in a row that may fail before the code is given up on. */
        const val MAX_HUB_POLL_FAILURES = 10

        /** How long the website gets to hand over this TV's sign-in once it confirmed the code. */
        const val HANDOFF_WAIT_MS = 30_000L
        const val HANDOFF_POLL_MS = 1_500L
    }
}
