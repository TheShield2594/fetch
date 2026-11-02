package com.fetch.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fetch.desktop.sync.DeviceLogin
import com.fetch.desktop.sync.DeviceLoginPoll
import com.fetch.desktop.sync.DiscoveredServer
import com.fetch.desktop.sync.SyncAuthClient
import com.fetch.desktop.sync.SyncClient
import com.fetch.desktop.sync.SyncSession
import com.fetch.desktop.sync.SyncState
import com.fetch.desktop.sync.SyncStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The optional sync server (Settings › Account on TV): your own Supabase-compatible Fetch
 * backend. One account for the whole app; each profile syncs its own data to it.
 */
class SyncAccountState(
    private val scope: CoroutineScope,
    private val store: SyncStore = SyncStore(),
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    var state: SyncState by mutableStateOf(store.load())
        private set
    /** A server found at an address, before signing in. */
    var discovered: DiscoveredServer? by mutableStateOf(null)
        private set
    var deviceLogin: DeviceLogin? by mutableStateOf(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var error: String? by mutableStateOf(null)
    var lastSyncedAt: Long? by mutableStateOf(null)
    private var deviceJob: Job? = null

    val signedIn: Boolean get() = state.server != null && state.session != null

    /** Looks up a Fetch server at [address] (its /.well-known/fetch). */
    fun findServer(address: String) = work {
        discovered = SyncAuthClient.discover(http, address.trim())
    }

    fun signIn(email: String, password: String) = work {
        val server = discovered ?: return@work
        val session = SyncAuthClient(http, server.config).signIn(email.trim(), password)
        save(state.copy(server = server.config, session = session))
    }

    fun signUp(email: String, password: String) = work {
        val server = discovered ?: return@work
        val session = SyncAuthClient(http, server.config).signUp(email.trim(), password)
        if (session == null) {
            error = "Check your email to confirm the account, then sign in."
        } else {
            save(state.copy(server = server.config, session = session))
        }
    }

    /** Sign in by approving a code on another device, as the TV app does. */
    fun startDeviceLogin() {
        val server = discovered ?: return
        deviceJob?.cancel()
        deviceJob = scope.launch {
            error = null
            try {
                val auth = SyncAuthClient(http, server.config)
                val login = auth.startDeviceLogin(deviceName = "Fetch Desktop")
                deviceLogin = login
                while (true) {
                    delay(login.pollIntervalSec.coerceAtLeast(2) * 1000L)
                    when (val poll = auth.pollDeviceLogin(login)) {
                        DeviceLoginPoll.Pending -> Unit
                        DeviceLoginPoll.Approved -> {
                            save(state.copy(server = server.config, session = auth.exchangeDeviceLogin(login)))
                            deviceLogin = null
                            return@launch
                        }
                        is DeviceLoginPoll.Ended -> {
                            deviceLogin = null
                            error = "Sign-in ${poll.status}. Try again."
                            return@launch
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                deviceLogin = null
                error = e.message ?: "Couldn't reach the server"
            }
        }
    }

    fun cancelDeviceLogin() {
        deviceJob?.cancel()
        deviceLogin = null
    }

    fun signOut() {
        val server = state.server
        val session = state.session
        save(state.copy(session = null))
        discovered = null
        if (server != null && session != null) scope.launch { runCatching { SyncAuthClient(http, server).signOut(session) } }
    }

    /** A client for the signed-in account, or null. Token refreshes are saved as they happen. */
    fun client(): SyncClient? {
        val server = state.server ?: return null
        val session = state.session ?: return null
        val origin = state.originClientId ?: SyncClient.newOriginClientId().also { save(state.copy(originClientId = it)) }
        return SyncClient(http, SyncAuthClient(http, server), session, origin, onSessionChanged = ::updateSession)
    }

    private fun updateSession(session: SyncSession) {
        scope.launch { save(state.copy(session = session)) }
    }

    /** The server rejected the sign-in for good: sign out, keep the server. */
    fun sessionExpired() {
        save(state.copy(session = null))
        error = "Your sync server signed you out. Sign in again in Settings › Account."
    }

    private fun save(updated: SyncState) {
        state = updated
        runCatching { store.save(updated) }
    }

    private fun work(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            error = null
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Couldn't reach the server"
            } finally {
                busy = false
            }
        }
    }
}
