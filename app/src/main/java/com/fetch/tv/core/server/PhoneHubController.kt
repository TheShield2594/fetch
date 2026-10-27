package com.fetch.tv.core.server

import android.content.Context
import android.util.Log
import com.fetch.tv.R
import com.fetch.tv.core.addon.AddonInstallOutcome
import com.fetch.tv.core.addon.addonConfigureUrl
import com.fetch.tv.core.addon.installOrReplace
import com.fetch.tv.core.addon.isConfigurable
import com.fetch.tv.core.addon.needsConfiguration
import com.fetch.tv.core.network.NetworkResult
import com.fetch.tv.core.parental.ParentalControls
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.core.profile.ProfileTransfer
import com.fetch.tv.core.remote.RemoteCommand
import com.fetch.tv.core.remote.RemoteCommandBus
import com.fetch.tv.core.streams.ranking.StreamRankingRules
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
import com.fetch.tv.domain.repository.AddonRepository
import com.fetch.tv.ui.screens.addon.AddonManagementAccess
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PhoneHubController"

/** How long the hub keeps serving a paired phone after the TV screen closes, with no requests. */
private const val REMOTE_IDLE_TIMEOUT_MS = 10 * 60_000L

private const val MANIFEST_TIMEOUT_MS = 15_000L

/** Whether an addon offers streams, which is what a new install is missing. */
internal fun com.fetch.tv.domain.model.Addon.givesStreams(): Boolean = resources.any { it.name == "stream" }

/**
 * An addon a phone sent, waiting for someone at the TV to install or decline it. [resources]
 * and [catalogCount] come from its manifest, so the TV shows what it is before it is trusted.
 */
data class PhoneAddonRequest(
    val id: String,
    val url: String,
    val name: String,
    val description: String?,
    val resources: List<String>,
    val catalogCount: Int,
    val installing: Boolean = false
)

/** Addons offered on the phone's Addons page as one-tap links. */
internal val PHONE_ADDON_SUGGESTIONS = listOf(
    PhoneHubAddonSuggestion(
        name = "OpenSubtitles v3",
        description = "Subtitles in many languages for movies and series",
        url = "https://opensubtitles-v3.strem.io/manifest.json"
    )
)

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
    private val parentalControls: ParentalControls,
    private val profileTransfer: ProfileTransfer,
    private val addonRepository: AddonRepository,
    private val profileManager: ProfileManager
) : PhoneHubActions {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
            Log.w(TAG, "Phone hub task failed", error)
        }
    )
    private val _state = MutableStateFlow(PhoneHubState())
    val state: StateFlow<PhoneHubState> = _state.asStateFlow()

    private val _pendingAddon = MutableStateFlow<PhoneAddonRequest?>(null)
    /** The addon a phone asked to install, while it waits for the TV; shown by [PhoneAddonConfirmDialog]. */
    val pendingAddon: StateFlow<PhoneAddonRequest?> = _pendingAddon.asStateFlow()
    private val addonProposals = ConcurrentHashMap<String, PhoneHubAddonProposal>()

    private var server: PhoneHubServer? = null
    @Volatile private var screenOpen = false
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
        // Nobody is left to answer it.
        rejectPendingAddon()
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
        _pendingAddon.value = null
        addonProposals.clear()
        _state.value = PhoneHubState()
    }

    private fun publishState() {
        val current = server ?: return
        _state.update { it.copy(running = true, pairedPhones = current.pairedSessionCount) }
    }

    private fun buildSections(): Map<String, NanoHTTPD> = mapOf(
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
        val trakt = traktAuthDataStore.getCurrentState()
        val simkl = simklAuthRepository.state.value
        return PhoneHubAccountStatus(
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

    // ─── Addons ───

    override suspend fun installedAddons(): List<PhoneHubAddon> =
        addonRepository.getInstalledAddons().first().map { addon ->
            PhoneHubAddon(
                name = addon.displayName.ifBlank { addon.name.ifBlank { addon.baseUrl } },
                streams = addon.givesStreams()
            )
        }

    override fun addonSuggestions(): List<PhoneHubAddonSuggestion> = PHONE_ADDON_SUGGESTIONS

    /** Why the active profile can't take an addon now, or null when it can. */
    private fun addonInstallBlocked(): String? =
        settingsBlocked()?.message
            ?: "This profile uses the main profile's addons. Add it from the main profile instead."
                .takeIf { AddonManagementAccess.isReadOnly(profileManager.activeProfile) }

    override suspend fun proposeAddon(url: String): PhoneHubAddonProposal {
        addonInstallBlocked()?.let { return PhoneHubAddonProposal(PhoneHubAddonStatus.FAILED, message = it) }
        // The TV asks for confirmation only where the phone code is on screen; after that the
        // hub keeps serving the remote, but nobody would see the question.
        if (!screenOpen) {
            return PhoneHubAddonProposal(
                PhoneHubAddonStatus.FAILED,
                message = "Open Settings → Configure from phone on the TV first, so it can ask before installing."
            )
        }
        val addon = when (val result = withTimeoutOrNull(MANIFEST_TIMEOUT_MS) { addonRepository.fetchAddon(url) }) {
            is NetworkResult.Success -> result.data
            is NetworkResult.Error -> return PhoneHubAddonProposal(
                PhoneHubAddonStatus.FAILED,
                message = "Couldn't read that addon" + (result.message.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ".")
            )
            else -> return PhoneHubAddonProposal(PhoneHubAddonStatus.FAILED, message = "The addon didn't answer. Check the link and try again.")
        }
        val name = addon.displayName.ifBlank { addon.name.ifBlank { url } }
        val configureUrl = addonConfigureUrl(url).takeIf { addon.isConfigurable }
        if (addon.needsConfiguration) {
            return PhoneHubAddonProposal(
                PhoneHubAddonStatus.NEEDS_CONFIGURATION,
                name = name,
                configureUrl = addonConfigureUrl(url),
                message = "$name has to be set up first."
            )
        }
        val alreadyInstalled = addonRepository.getInstalledAddons().first()
            .any { it.baseUrl.trimEnd('/').equals(url.trimEnd('/'), ignoreCase = true) }
        if (alreadyInstalled) {
            return PhoneHubAddonProposal(PhoneHubAddonStatus.INSTALLED, name = name, message = "$name is already on this TV.")
        }

        val request = PhoneAddonRequest(
            id = UUID.randomUUID().toString(),
            url = url,
            name = name,
            description = addon.description?.takeIf { it.isNotBlank() },
            resources = addon.resources.map { it.name }.distinct(),
            catalogCount = addon.catalogs.size
        )
        val proposal = PhoneHubAddonProposal(PhoneHubAddonStatus.PENDING, id = request.id, name = name, configureUrl = configureUrl)
        // Reading the manifest can take a while: check again, under the lock onScreenClosed()
        // and stop() hold, that someone is still there to answer before publishing.
        synchronized(this) {
            if (!screenOpen) {
                return PhoneHubAddonProposal(
                    PhoneHubAddonStatus.FAILED,
                    message = "The TV closed the phone code screen. Open it again, then send the link again."
                )
            }
            val previous = _pendingAddon.value
            if (previous?.installing == true) {
                return PhoneHubAddonProposal(PhoneHubAddonStatus.FAILED, message = "The TV is installing another addon. Try again in a moment.")
            }
            // One request at a time on the TV: a newer one replaces one nobody answered.
            previous?.let { addonProposals[it.id] = PhoneHubAddonProposal(PhoneHubAddonStatus.REJECTED, id = it.id, name = it.name) }
            addonProposals[request.id] = proposal
            _pendingAddon.value = request
        }
        return proposal
    }

    override fun addonProposal(id: String): PhoneHubAddonProposal? = addonProposals[id]

    /** Installs the addon the phone sent, as someone at the TV just confirmed. */
    fun confirmPendingAddon() {
        val request = _pendingAddon.value?.takeUnless { it.installing } ?: return
        // The profile may have changed, or settings locked, since the phone asked.
        addonInstallBlocked()?.let { reason ->
            addonProposals[request.id] = PhoneHubAddonProposal(PhoneHubAddonStatus.FAILED, id = request.id, name = request.name, message = reason)
            _pendingAddon.compareAndSet(request, null)
            return
        }
        val installing = request.copy(installing = true)
        // Only the request on screen: a newer one may have just replaced it.
        if (!_pendingAddon.compareAndSet(request, installing)) return
        scope.launch {
            try {
                addonProposals[request.id] = when (val outcome = addonRepository.installOrReplace(request.url)) {
                    is AddonInstallOutcome.Installed -> PhoneHubAddonProposal(PhoneHubAddonStatus.INSTALLED, id = request.id, name = request.name)
                    is AddonInstallOutcome.NeedsConfiguration -> PhoneHubAddonProposal(
                        PhoneHubAddonStatus.NEEDS_CONFIGURATION,
                        id = request.id,
                        name = request.name,
                        configureUrl = addonConfigureUrl(request.url),
                        message = "${request.name} has to be set up first."
                    )
                    is AddonInstallOutcome.Failed -> PhoneHubAddonProposal(
                        PhoneHubAddonStatus.FAILED,
                        id = request.id,
                        name = request.name,
                        message = outcome.message.ifBlank { "Couldn't install ${request.name}." }
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Installing an addon from the phone failed", error)
                addonProposals[request.id] = PhoneHubAddonProposal(
                    PhoneHubAddonStatus.FAILED,
                    id = request.id,
                    name = request.name,
                    message = "Couldn't install ${request.name}."
                )
            } finally {
                // Whatever happened, the dialog must not stay stuck on Installing.
                _pendingAddon.compareAndSet(installing, null)
            }
        }
    }

    fun rejectPendingAddon() {
        val request = _pendingAddon.value?.takeUnless { it.installing } ?: return
        addonProposals[request.id] = PhoneHubAddonProposal(PhoneHubAddonStatus.REJECTED, id = request.id, name = request.name)
        _pendingAddon.compareAndSet(request, null)
    }

    // A profile file holds another profile's history, so it's behind the same lock as settings.
    override suspend fun transferableProfiles(): List<Pair<Int, String>> =
        if (parentalControls.needsGate()) emptyList() else profileTransfer.exportableProfiles().map { it.id to it.name }

    override suspend fun exportProfile(profileId: Int): Result<JsonObject> {
        settingsBlocked()?.let { return Result.failure(IllegalStateException(it.message)) }
        return try {
            Result.success(profileTransfer.export(profileId))
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalArgumentException) {
            Result.failure(error)
        } catch (error: Exception) {
            Log.w(TAG, "Profile export from phone failed", error)
            Result.failure(IllegalStateException("Couldn't export the profile."))
        }
    }

    override suspend fun importProfile(document: JsonObject): PhoneHubResult {
        settingsBlocked()?.let { return it }
        return try {
            val profile = profileTransfer.import(document)
            PhoneHubResult(true, "Added ${profile.name}. Choose it on the TV from the profile picker.")
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalArgumentException) {
            PhoneHubResult(false, error.message)
        } catch (error: Exception) {
            Log.w(TAG, "Profile import from phone failed", error)
            PhoneHubResult(false, "Couldn't add the profile.")
        }
    }
}
