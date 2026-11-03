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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** What `claim_sync_code` answers: whether the code was taken, and the server's reason when not. */
internal data class SyncClaim(val success: Boolean, val message: String?, val ownerId: String?)

internal fun syncClaimParams(code: String, pin: String, deviceName: String?) = buildJsonObject {
    put("p_code", code.trim().uppercase())
    put("p_pin", pin)
    if (!deviceName.isNullOrBlank()) put("p_device_name", deviceName)
}

/** The first row of `claim_sync_code`'s answer (`[{result_owner_id, success, message}]`). */
internal fun parseSyncClaim(body: String): SyncClaim? = runCatching {
    val root = kotlinx.serialization.json.Json.parseToJsonElement(body)
    val row = ((root as? JsonArray)?.firstOrNull() ?: root) as? JsonObject ?: return null
    fun text(key: String) = (row[key] as? JsonPrimitive)?.contentOrNull
    SyncClaim(
        success = (row["success"] as? JsonPrimitive)?.booleanOrNull ?: false,
        message = text("message"),
        ownerId = text("result_owner_id")
    )
}.getOrNull()

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
        save(state.copy(server = server.config, session = session, watchParty = server.watchParty))
    }

    fun signUp(email: String, password: String) = work {
        val server = discovered ?: return@work
        val session = SyncAuthClient(http, server.config).signUp(email.trim(), password)
        if (session == null) {
            error = "Check your email to confirm the account, then sign in."
        } else {
            save(state.copy(server = server.config, session = session, watchParty = server.watchParty))
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
                            save(state.copy(server = server.config, session = auth.exchangeDeviceLogin(login), watchParty = server.watchParty))
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

    /**
     * Links this computer to the account behind a sync code and its PIN (RPC `claim_sync_code`),
     * as the TV's "Enter sync code" does; [onClaimed] runs once it is linked, to pull that
     * account's data. Signed in, the code is claimed for this sign-in. Signed out, an anonymous
     * user is signed in first on the server found by [findServer], so the link has a user to hang
     * on; a code that isn't accepted signs that user out again.
     */
    fun claimSyncCode(code: String, pin: String, deviceName: String = "Fetch Desktop", onClaimed: () -> Unit = {}) = work {
        val existing = client()
        val remote = existing ?: run {
            val server = discovered ?: throw IllegalStateException("Find your sync server first.")
            val session = SyncAuthClient(http, server.config).signInAnonymously()
            save(state.copy(server = server.config, session = session, watchParty = server.watchParty))
            client() ?: throw IllegalStateException("Couldn't sign in to the sync server")
        }
        val claim = try {
            parseSyncClaim(remote.callRpc("claim_sync_code", syncClaimParams(code, pin, deviceName)))
        } catch (e: Exception) {
            if (existing == null) dropSession()
            throw e
        }
        if (claim?.success != true) {
            if (existing == null) dropSession()
            throw IllegalStateException(claim?.message?.takeIf { it.isNotBlank() } ?: "That code and PIN didn't link this computer.")
        }
        onClaimed()
    }

    /** Forgets the session (and tells the server, best effort), keeping the server found. */
    private fun dropSession() {
        val server = state.server
        val session = state.session
        save(state.copy(session = null))
        if (server != null && session != null) scope.launch { runCatching { SyncAuthClient(http, server).signOut(session) } }
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

    /**
     * Whether the server offers watch parties (its discovery's `watch_party`), as kept at sign-in.
     * An older sign-in reads the backend's `/.well-known/fetch` once; null when that fails too.
     */
    suspend fun watchPartyCapability(): Boolean? {
        state.watchParty?.let { return it }
        val server = state.server ?: return null
        val found = runCatching { SyncAuthClient.discover(http, server.url) }.getOrNull() ?: return null
        if (found.config.baseUrl == server.baseUrl) save(state.copy(watchParty = found.watchParty))
        return found.watchParty
    }

    /** The HTTP client the account uses, for the watch party's WebSocket. */
    internal val httpClient: OkHttpClient get() = http

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
