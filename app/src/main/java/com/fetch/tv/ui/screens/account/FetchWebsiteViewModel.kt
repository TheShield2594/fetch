package com.fetch.tv.ui.screens.account

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fetch.tv.data.website.DiscoveredFetchServer
import com.fetch.tv.data.website.FetchServerDiscovery
import com.fetch.tv.data.website.FetchWebsiteClient
import com.fetch.tv.data.website.FetchWebsiteSession
import com.fetch.tv.data.website.FetchWebsiteStore
import com.fetch.tv.data.website.FetchWebsiteSyncService
import com.fetch.tv.data.website.WebsitePairingPoll
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant

/** Where a pairing has got to. */
sealed interface WebsitePairingState {
    /** Asking for the website's address. */
    data object EnterAddress : WebsitePairingState
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
    data class Failed(val message: String?) : WebsitePairingState
}

/**
 * Settings → Account → Fetch website: pairs this TV with a Fetch website by a code confirmed
 * there, and signs it out again.
 */
@HiltViewModel
class FetchWebsiteViewModel @Inject constructor(
    private val store: FetchWebsiteStore,
    private val client: FetchWebsiteClient,
    private val sync: FetchWebsiteSyncService,
    discovery: FetchServerDiscovery
) : ViewModel() {
    val session: StateFlow<FetchWebsiteSession?> = store.session

    /** Fetch websites announced on the LAN; the network is only browsed while something shows them. */
    val discoveredServers: StateFlow<List<DiscoveredFetchServer>> = discovery.servers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val signedOutByWebsite: StateFlow<Boolean> = store.signedOutByWebsite

    private val _pairing = MutableStateFlow<WebsitePairingState>(WebsitePairingState.EnterAddress)
    val pairing: StateFlow<WebsitePairingState> = _pairing.asStateFlow()
    private var pairingJob: Job? = null

    val lastServerUrl: String get() = store.lastServerUrl.orEmpty()

    init {
        // Opening the screen is when someone is looking, so it is when finding out the
        // website revoked this TV matters most.
        if (store.isPaired) viewModelScope.launch { runCatching { client.checkSession() } }
    }

    fun startPairing(address: String) {
        val serverUrl = FetchWebsiteClient.normalizeServerUrl(address)
        if (serverUrl == null) {
            _pairing.value = WebsitePairingState.Failed(null)
            return
        }
        pairingJob?.cancel()
        pairingJob = viewModelScope.launch {
            _pairing.value = WebsitePairingState.Starting
            try {
                val start = client.startPairing(serverUrl, deviceName())
                val expiresAt = runCatching { Instant.parse(start.expiresAt).toEpochMilli() }
                    .getOrDefault(System.currentTimeMillis() + 5 * 60_000L)
                _pairing.value = WebsitePairingState.ShowingCode(start.code, start.confirmUrl, serverUrl, expiresAt)
                val intervalMs = start.intervalSec.coerceIn(1, 30) * 1_000L
                while (true) {
                    delay(intervalMs)
                    when (val poll = client.pollPairing(serverUrl, start.pollSecret)) {
                        WebsitePairingPoll.Pending -> {
                            if (System.currentTimeMillis() > expiresAt) {
                                _pairing.value = WebsitePairingState.Expired
                                return@launch
                            }
                        }
                        WebsitePairingPoll.Expired -> {
                            _pairing.value = WebsitePairingState.Expired
                            return@launch
                        }
                        is WebsitePairingPoll.Approved -> {
                            val name = poll.deviceName.ifBlank { deviceName() }
                            store.save(FetchWebsiteSession(serverUrl, poll.token, name))
                            _pairing.value = WebsitePairingState.Paired(name)
                            sync.requestSync()
                            return@launch
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _pairing.value = WebsitePairingState.Failed(error.message)
            }
        }
    }

    fun resetPairing() {
        pairingJob?.cancel()
        pairingJob = null
        _pairing.value = WebsitePairingState.EnterAddress
    }

    fun signOut() {
        store.clear()
        resetPairing()
    }

    fun dismissSignedOutNotice() = store.dismissSignedOutNotice()

    fun syncNow() = sync.requestSync()

    override fun onCleared() {
        pairingJob?.cancel()
        super.onCleared()
    }

    private fun deviceName(): String =
        listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { !it.isNullOrBlank() }
            .distinct()
            .joinToString(" ")
            .ifBlank { "Android TV" }
}
