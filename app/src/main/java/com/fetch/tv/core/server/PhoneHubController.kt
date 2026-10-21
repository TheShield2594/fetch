package com.fetch.tv.core.server

import android.content.Context
import android.util.Log
import com.fetch.tv.R
import com.fetch.tv.core.debrid.DebridApiKeyValidator
import com.fetch.tv.core.debrid.DebridProviders
import com.fetch.tv.core.parental.ParentalControls
import com.fetch.tv.core.remote.RemoteCommand
import com.fetch.tv.core.remote.RemoteCommandBus
import com.fetch.tv.core.streams.ranking.StreamRankingRules
import com.fetch.tv.data.local.DebridSettingsDataStore
import com.fetch.tv.data.local.LayoutPreferenceDataStore
import com.fetch.tv.data.local.MDBListSettingsDataStore
import com.fetch.tv.data.local.PlayerSettingsDataStore
import com.fetch.tv.data.local.StreamBadgeSettingsDataStore
import com.fetch.tv.data.local.TraktAuthDataStore
import com.fetch.tv.data.mediaserver.ServerException
import com.fetch.tv.data.mediaserver.ServerRepository
import com.fetch.tv.data.mediaserver.ServerSignInMethod
import com.fetch.tv.data.remote.api.MDBListApi
import com.fetch.tv.data.repository.TraktAuthService
import com.fetch.tv.data.repository.TraktProgressService
import com.fetch.tv.data.repository.TraktTokenPollResult
import com.fetch.tv.data.simkl.SimklAuthRepository
import com.fetch.tv.data.simkl.SimklPinPollResult
import dagger.hilt.android.qualifiers.ApplicationContext
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PhoneHubController"

/** How long the hub keeps serving a paired phone after the TV screen closes, with no requests. */
private const val REMOTE_IDLE_TIMEOUT_MS = 10 * 60_000L

data class PhoneHubState(
    val url: String? = null,
    val running: Boolean = false,
    val pairedPhones: Int = 0,
    val error: String? = null
)

/**
 * Owns the phone hub server. It starts when the "Configure from phone" screen opens. When
 * the screen closes the server stops, unless a phone has paired, in which case it keeps
 * serving the remote until it has been idle for [REMOTE_IDLE_TIMEOUT_MS].
 */
@Singleton
class PhoneHubController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val remoteCommandBus: RemoteCommandBus,
    private val debridSettingsDataStore: DebridSettingsDataStore,
    private val debridApiKeyValidator: DebridApiKeyValidator,
    private val mdbListSettingsDataStore: MDBListSettingsDataStore,
    private val mdbListApi: MDBListApi,
    private val traktAuthService: TraktAuthService,
    private val traktAuthDataStore: TraktAuthDataStore,
    private val traktProgressService: TraktProgressService,
    private val simklAuthRepository: SimklAuthRepository,
    private val serverRepository: ServerRepository,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val streamBadgeSettingsDataStore: StreamBadgeSettingsDataStore,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val parentalControls: ParentalControls
) : PhoneHubActions {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
            Log.w(TAG, "Phone hub task failed", error)
        }
    )
    private val _state = MutableStateFlow(PhoneHubState())
    val state: StateFlow<PhoneHubState> = _state.asStateFlow()

    private var server: PhoneHubServer? = null
    private var screenOpen = false
    private var idleJob: Job? = null
    private var trackerJob: Job? = null
    @Volatile private var lastActivityMs = 0L
    @Volatile private var traktCode: PhoneHubDeviceCode? = null
    @Volatile private var simklCode: PhoneHubDeviceCode? = null

    private val logoBytes: ByteArray? by lazy {
        runCatching { context.resources.openRawResource(R.drawable.app_logo_wordmark).use { it.readBytes() } }.getOrNull()
    }

    @Synchronized
    fun onScreenOpened() {
        screenOpen = true
        idleJob?.cancel()
        if (server != null) {
            publishState()
            return
        }
        val ip = DeviceIpAddress.get(context)
        if (ip == null) {
            _state.value = PhoneHubState(error = context.getString(R.string.error_network_required))
            return
        }
        val started = PhoneHubServer.startOnAvailablePort(
            actions = this,
            sections = buildSections(),
            onActivity = { lastActivityMs = System.currentTimeMillis() }
        )
        if (started == null) {
            _state.value = PhoneHubState(error = context.getString(R.string.error_server_ports_unavailable))
            return
        }
        server = started
        _state.value = PhoneHubState(url = started.pairingUrl(ip), running = true)
        scope.launch {
            // Show on the TV when a phone pairs, while the screen is open.
            while (server === started && screenOpen) {
                publishState()
                delay(1_000)
            }
        }
    }

    @Synchronized
    fun onScreenClosed() {
        screenOpen = false
        val current = server ?: return
        if (current.pairedSessionCount == 0) {
            stop()
            return
        }
        lastActivityMs = System.currentTimeMillis()
        idleJob?.cancel()
        idleJob = scope.launch {
            while (true) {
                delay(30_000)
                if (System.currentTimeMillis() - lastActivityMs >= REMOTE_IDLE_TIMEOUT_MS) {
                    stop()
                    break
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        idleJob?.cancel()
        idleJob = null
        server?.stop()
        server = null
        _state.value = PhoneHubState()
    }

    private fun publishState() {
        val current = server ?: return
        _state.update { it.copy(running = true, pairedPhones = current.pairedSessionCount) }
    }

    private fun buildSections(): Map<String, NanoHTTPD> = mapOf(
        PhoneHubServer.SECTION_TEMPLATES to DebridFormatterConfigServer(
            currentSettingsProvider = {
                val settings = runBlocking { debridSettingsDataStore.settings.first() }
                DebridFormatterSettings(
                    nameTemplate = settings.streamNameTemplate,
                    descriptionTemplate = settings.streamDescriptionTemplate
                )
            },
            onSettingsChanged = { settings ->
                scope.launch {
                    debridSettingsDataStore.setStreamTemplates(
                        nameTemplate = settings.nameTemplate,
                        descriptionTemplate = settings.descriptionTemplate
                    )
                }
            },
            context = context,
            logoProvider = { logoBytes }
        ),
        PhoneHubServer.SECTION_BADGES to StreamBadgeConfigServer(
            currentSettingsProvider = { runBlocking { streamBadgeSettingsDataStore.settings.first() } },
            onSettingsChanged = { settings -> scope.launch { streamBadgeSettingsDataStore.setSettings(settings) } },
            context = context,
            logoProvider = { logoBytes }
        ),
        PhoneHubServer.SECTION_POSTERS to CustomPosterConfigServer(
            currentPatternProvider = { runBlocking { layoutPreferenceDataStore.customPosterUrlPattern.first() } },
            onPatternChanged = { pattern -> scope.launch { layoutPreferenceDataStore.setCustomPosterUrlPattern(pattern) } },
            context = context
        )
    )

    override fun sendRemote(command: RemoteCommand): Boolean = remoteCommandBus.send(command)

    override suspend fun accountStatus(): PhoneHubAccountStatus {
        val debrid = debridSettingsDataStore.settings.first()
        val trakt = traktAuthDataStore.getCurrentState()
        val simkl = simklAuthRepository.state.value
        return PhoneHubAccountStatus(
            debridKeys = DebridProviders.visible().associate { provider ->
                provider.id to debrid.apiKeyFor(provider.id).isNotBlank()
            },
            mdbListKeySet = mdbListSettingsDataStore.settings.first().apiKey.isNotBlank(),
            trakt = PhoneHubTrackerStatus(trakt.isAuthenticated, trakt.username, traktCode.takeUnless { trakt.isAuthenticated }),
            simkl = PhoneHubTrackerStatus(simkl.isAuthenticated, simkl.username, simklCode.takeUnless { simkl.isAuthenticated }),
            // Link-code providers (Plex) sign in on the TV, where the code is shown.
            mediaServerProviders = serverRepository.providers
                .filter { it.signInMethod == ServerSignInMethod.PASSWORD }
                .map { it.id to it.displayName },
            mediaServers = serverRepository.uiState.value.connections.map { "${it.name} (${it.userName})" }
        )
    }

    // Account changes are also settings changes, so restricted profiles need the TV unlocked.
    private fun settingsBlocked(): PhoneHubResult? =
        if (parentalControls.needsGate()) PhoneHubResult(false, context.getString(R.string.phone_hub_parental_blocked)) else null

    override suspend fun saveDebridKey(providerId: String, apiKey: String): PhoneHubResult {
        settingsBlocked()?.let { return it }
        if (!DebridProviders.isVisible(providerId)) {
            return PhoneHubResult(false, "Unknown service.")
        }
        if (apiKey.isNotBlank() && !debridApiKeyValidator.isValid(providerId, apiKey)) {
            return PhoneHubResult(false, context.getString(R.string.debrid_key_invalid))
        }
        debridSettingsDataStore.setProviderApiKey(providerId, apiKey)
        return PhoneHubResult(true, if (apiKey.isBlank()) "Key removed." else "Key saved.")
    }

    override suspend fun saveMdbListKey(apiKey: String): PhoneHubResult {
        settingsBlocked()?.let { return it }
        if (apiKey.isNotBlank()) {
            val valid = try {
                mdbListApi.getUser(apiKey).isSuccessful
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                false
            }
            if (!valid) return PhoneHubResult(false, "That key wasn't accepted by MDBList.")
        }
        mdbListSettingsDataStore.setApiKey(apiKey)
        return PhoneHubResult(true, if (apiKey.isBlank()) "Key removed." else "Key saved.")
    }

    override suspend fun connectMediaServer(
        providerId: String,
        address: String,
        username: String,
        password: String
    ): PhoneHubResult {
        settingsBlocked()?.let { return it }
        val provider = serverRepository.providers.firstOrNull { it.id == providerId && it.signInMethod == ServerSignInMethod.PASSWORD }
            ?: return PhoneHubResult(false, "Unknown server type.")
        return try {
            val connection = serverRepository.connect(provider, address.trim(), username.trim(), password)
            PhoneHubResult(true, "Connected to ${connection.name}.")
        } catch (error: CancellationException) {
            throw error
        } catch (error: ServerException) {
            PhoneHubResult(false, "Couldn't connect (${error.failure.name.lowercase().replace('_', ' ')}).")
        } catch (error: Exception) {
            Log.w(TAG, "Media server sign-in from phone failed", error)
            PhoneHubResult(false, "Couldn't connect.")
        }
    }

    override suspend fun startTrackerSignIn(tracker: String): Result<PhoneHubDeviceCode> {
        settingsBlocked()?.let { return Result.failure(IllegalStateException(it.message)) }
        return when (tracker) {
            "trakt" -> traktAuthService.startDeviceAuth().map { response ->
                PhoneHubDeviceCode(response.userCode, response.verificationUrl).also {
                    traktCode = it
                    pollTrakt(response.interval)
                }
            }
            "simkl" -> runCatching {
                val session = simklAuthRepository.startPinAuth()
                PhoneHubDeviceCode(session.userCode, session.verificationUri).also {
                    simklCode = it
                    pollSimkl(session.intervalSeconds, session.expiresAtEpochMs)
                }
            }
            else -> Result.failure(IllegalArgumentException("Unknown service."))
        }
    }

    private fun pollTrakt(intervalSeconds: Int) {
        trackerJob?.cancel()
        trackerJob = scope.launch {
            var interval = intervalSeconds.coerceAtLeast(1)
            while (true) {
                delay(interval * 1_000L)
                when (val poll = traktAuthService.pollDeviceToken()) {
                    TraktTokenPollResult.Pending -> Unit
                    is TraktTokenPollResult.SlowDown -> interval = poll.pollIntervalSeconds.coerceAtLeast(interval + 1)
                    is TraktTokenPollResult.Approved -> {
                        traktCode = null
                        traktProgressService.refreshNow()
                        break
                    }
                    else -> {
                        traktCode = null
                        break
                    }
                }
            }
        }
    }

    private fun pollSimkl(intervalSeconds: Int, expiresAtMs: Long) {
        trackerJob?.cancel()
        trackerJob = scope.launch {
            while (System.currentTimeMillis() < expiresAtMs) {
                delay(intervalSeconds.coerceAtLeast(1) * 1_000L)
                when (simklAuthRepository.pollPin()) {
                    SimklPinPollResult.Pending -> Unit
                    else -> break
                }
            }
            simklCode = null
        }
    }

    override suspend fun rankingRules(): StreamRankingRules =
        playerSettingsDataStore.playerSettings.first().streamRankingRules

    override suspend fun saveRankingRules(rules: StreamRankingRules) {
        if (parentalControls.needsGate()) throw IllegalArgumentException(context.getString(R.string.phone_hub_parental_blocked))
        playerSettingsDataStore.setStreamRankingRules(rules)
    }
}
