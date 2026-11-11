package com.fetch.tv.data.website

import android.os.Build
import com.fetch.tv.core.sync.backend.SyncBackendKind
import com.fetch.tv.core.sync.backend.SyncBackends
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Where a pairing has got to. */
sealed interface WebsitePairingState {
    /** Asking for the website's address. */
    data object EnterAddress : WebsitePairingState

    /** Finding the website's API from the address, then asking it for a code. */
    data object Starting : WebsitePairingState

    /** Showing the code (and QR) while waiting for someone to confirm it on the website. */
    data class ShowingCode(
        val code: String,
        val confirmUrl: String?,
        val serverUrl: String,
        val expiresAtEpochMs: Long
    ) : WebsitePairingState

    data object Expired : WebsitePairingState
    data class Paired(val deviceName: String) : WebsitePairingState

    /**
     * Pairing couldn't start or finish. [message] is the error to show; null with [notFound]
     * false means the address isn't one at all, [notFound] that nothing there answers as a
     * Fetch website.
     */
    data class Failed(val message: String?, val notFound: Boolean = false) : WebsitePairingState
}

/**
 * Pairs this TV with a Fetch website. One pairing at a time for the whole app, so it can be
 * started from the TV's own dialog or from a phone (the phone hub's "Fetch website" page,
 * after scanning the QR code on the TV) and both show the same code.
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

    @Synchronized
    fun start(address: String) {
        job?.cancel()
        lastAddress = address.trim()
        if (FetchServerAddress.normalize(address) == null) {
            _state.value = WebsitePairingState.Failed(null)
            return
        }
        _state.value = WebsitePairingState.Starting
        job = scope.launch { run(address) }
    }

    /**
     * Starts pairing with [address] and waits for the code, for a phone that asked. Returns
     * the first state past [WebsitePairingState.Starting]: the code, or why there is none.
     */
    suspend fun startAndAwaitCode(address: String, timeoutMs: Long = 30_000): WebsitePairingState {
        start(address)
        return withTimeoutOrNull(timeoutMs) { state.first { it !is WebsitePairingState.Starting } }
            ?: WebsitePairingState.Failed(null, notFound = true)
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
                _state.value = WebsitePairingState.Failed(null, notFound = true)
                return
            }
            val serverUrl = server.apiUrl
            val start = client.startPairing(serverUrl, deviceName())
            val expiresAt = runCatching { Instant.parse(start.expiresAt).toEpochMilli() }
                .getOrDefault(System.currentTimeMillis() + 5 * 60_000L)
            // The website address that just worked beats the one the server was configured
            // with, which compose fills with an example IP until someone changes it.
            val confirmUrl = server.webUrl?.let { FetchServerAddress.confirmUrl(it, start.code) } ?: start.confirmUrl
            _state.value = WebsitePairingState.ShowingCode(start.code, confirmUrl, serverUrl, expiresAt)
            val intervalMs = start.intervalSec.coerceIn(1, 30) * 1_000L
            while (true) {
                delay(intervalMs)
                when (val poll = client.pollPairing(serverUrl, start.pollSecret)) {
                    WebsitePairingPoll.Pending -> {
                        if (System.currentTimeMillis() > expiresAt) {
                            _state.value = WebsitePairingState.Expired
                            return
                        }
                    }
                    WebsitePairingPoll.Expired -> {
                        _state.value = WebsitePairingState.Expired
                        return
                    }
                    is WebsitePairingPoll.Approved -> {
                        val name = poll.deviceName.ifBlank { deviceName() }
                        store.save(FetchWebsiteSession(serverUrl, poll.token, name))
                        // Pairing (here, from a phone, or in the first-run Server step) is choosing the website.
                        backends.choose(SyncBackendKind.FETCH_WEBSITE)
                        _state.value = WebsitePairingState.Paired(name)
                        sync.requestSync()
                        return
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _state.value = WebsitePairingState.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun deviceName(): String =
        listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { !it.isNullOrBlank() }
            .distinct()
            .joinToString(" ")
            .ifBlank { "Android TV" }
}
