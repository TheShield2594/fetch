package com.fetch.tv.ui.screens.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fetch.tv.core.server.PhoneHubController
import com.fetch.tv.data.website.DiscoveredFetchServer
import com.fetch.tv.data.website.FetchServerDiscovery
import com.fetch.tv.data.website.FetchWebsiteClient
import com.fetch.tv.data.website.FetchWebsitePairing
import com.fetch.tv.data.website.FetchWebsiteSession
import com.fetch.tv.data.website.FetchWebsiteStore
import com.fetch.tv.data.website.FetchWebsiteSyncService
import com.fetch.tv.data.website.WebsitePairingState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Settings → Account → Fetch website: pairs this TV with a Fetch website by a code confirmed
 * there (from the pairing hub, so no address is needed), and signs it out again. The pairing itself lives in [FetchWebsitePairing], so a phone
 * that scanned the QR code on the address step can start it too.
 */
@HiltViewModel
class FetchWebsiteViewModel @Inject constructor(
    private val store: FetchWebsiteStore,
    private val client: FetchWebsiteClient,
    private val sync: FetchWebsiteSyncService,
    private val pairingFlow: FetchWebsitePairing,
    private val phoneHub: PhoneHubController,
    discovery: FetchServerDiscovery
) : ViewModel() {
    val session: StateFlow<FetchWebsiteSession?> = store.session

    /** Fetch websites announced on the LAN; the network is only browsed while something shows them. */
    val discoveredServers: StateFlow<List<DiscoveredFetchServer>> = discovery.servers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val signedOutByWebsite: StateFlow<Boolean> = store.signedOutByWebsite

    val pairing: StateFlow<WebsitePairingState> = pairingFlow.state

    /**
     * The phone page for typing the website's address, while the address step shows it:
     * scanning it opens the TV's phone hub straight on its Fetch website page.
     */
    val phoneEntryUrl: StateFlow<String?> = phoneHub.state
        .map { it.urlFor(WEBSITE_PHONE_PAGE) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private var phoneEntryShown = false

    /** The address to start from: the last one paired with or tried. */
    val lastServerUrl: String get() = pairingFlow.lastAddress.ifBlank { store.lastServerUrl.orEmpty() }

    init {
        // Opening the screen is when someone is looking, so it is when finding out the
        // website revoked this TV matters most.
        if (store.isPaired) viewModelScope.launch { runCatching { client.checkSession() } }
    }

    fun startPairing(address: String) = pairingFlow.start(address)

    /** A code from the pairing hub, which any Fetch website can confirm: no address needed. */
    fun startHubPairing() = pairingFlow.startHub()

    /** "Get a new code": the same way as last time, a hub code or the address last typed. */
    fun retryPairing() = pairingFlow.retry()

    fun resetPairing() = pairingFlow.reset()

    fun signOut() = pairingFlow.signOut()

    fun dismissSignedOutNotice() = store.dismissSignedOutNotice()

    fun syncNow() = sync.requestSync()

    /** The address step is on screen: serve the phone page its QR code points at. */
    fun onPhoneEntryShown() {
        if (phoneEntryShown) return
        phoneEntryShown = true
        phoneHub.onScreenOpened()
    }

    fun onPhoneEntryHidden() {
        if (!phoneEntryShown) return
        phoneEntryShown = false
        phoneHub.onScreenClosed()
    }

    override fun onCleared() {
        onPhoneEntryHidden()
        super.onCleared()
    }

    private companion object {
        const val WEBSITE_PHONE_PAGE = "website"
    }
}
