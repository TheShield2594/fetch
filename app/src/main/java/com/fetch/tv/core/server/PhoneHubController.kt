package com.fetch.tv.core.server

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
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
import com.fetch.tv.core.profile.TvBackup
import com.fetch.tv.core.remote.RemoteCommand
import com.fetch.tv.core.remote.RemoteCommandBus
import com.fetch.tv.core.streams.ranking.StreamRankingRules
import com.fetch.tv.core.util.readWordmarkPngBytes
import com.fetch.tv.core.util.withAppLocale
import com.fetch.tv.data.anilist.AniListApi
import com.fetch.tv.data.anilist.AniListApiException
import com.fetch.tv.data.anilist.AniListAuthStore
import com.fetch.tv.data.local.LayoutPreferenceDataStore
import com.fetch.tv.data.mal.MalApi
import com.fetch.tv.data.mal.MalApiException
import com.fetch.tv.data.mal.MalAuthStore
import com.fetch.tv.data.local.MDBListSettingsDataStore
import com.fetch.tv.data.local.PlayerSettingsDataStore
import com.fetch.tv.data.local.StreamBadgeSettingsDataStore
import com.fetch.tv.data.local.TraktAuthDataStore
import com.fetch.tv.data.mediaserver.ServerException
import com.fetch.tv.data.mediaserver.ServerFailure
import com.fetch.tv.data.mediaserver.ServerRepository
import com.fetch.tv.data.mediaserver.ServerSignInMethod
import com.fetch.tv.data.remote.api.MDBListApi
import com.fetch.tv.data.repository.TraktAuthService
import com.fetch.tv.data.repository.TraktProgressService
import com.fetch.tv.data.repository.TraktTokenPollResult
import com.fetch.tv.data.simkl.SimklAuthRepository
import com.fetch.tv.data.simkl.SimklPinPollResult
import com.fetch.tv.data.website.FetchWebsitePairing
import com.fetch.tv.data.website.FetchWebsiteStore
import com.fetch.tv.data.website.WebsitePairingState
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
private const val ANILIST_TOKEN_LIFETIME_MS = 364L * 24 * 60 * 60 * 1000
private const val MAL_SIGN_IN_LIFETIME_MS = 20L * 60 * 1000

internal data class MalPendingSignIn(val verifier: String, val state: String, val createdAt: Long)

internal data class MalCallback(val code: String, val state: String?)

/**
 * What the phone pasted: the code on its own, or the callback page's whole address
 * (`…/mal-callback.html?code=…&state=…`), whose state is then checked too.
 */
internal fun parseMalCallback(value: String): MalCallback? {
    val text = value.trim()
    if (text.isEmpty()) return null
    if (!text.contains("code=")) {
        return text.takeIf { it.none(Char::isWhitespace) && it.length <= 2048 }?.let { MalCallback(it, null) }
    }
    val query = text.substringAfter('?', text).substringBefore('#')
    val params = query.split('&').mapNotNull { part ->
        val key = part.substringBefore('=', "")
        if (key.isEmpty()) null else key to java.net.URLDecoder.decode(part.substringAfter('='), "UTF-8")
    }.toMap()
    val code = params["code"]?.takeIf(String::isNotBlank) ?: return null
    return MalCallback(code, params["state"])
}

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
) {
    /**
     * [url] opening [path] instead of the landing page: the pairing token goes along, and
     * [PairingGuard] redirects to the path once it has set the phone's cookie.
     */
    fun urlFor(path: String): String? {
        val base = url ?: return null
        val queryStart = base.indexOf('?')
        val origin = (if (queryStart >= 0) base.substring(0, queryStart) else base).trimEnd('/')
        val query = if (queryStart >= 0) base.substring(queryStart) else ""
        return origin + "/" + path.trimStart('/') + query
    }
}

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
    private val tvBackup: TvBackup,
    private val addonRepository: AddonRepository,
    private val profileManager: ProfileManager,
    private val websiteStore: FetchWebsiteStore,
    private val websitePairing: dagger.Lazy<FetchWebsitePairing>,
    private val aniListApi: AniListApi,
    private val aniListAuth: AniListAuthStore,
    private val malApi: MalApi,
    private val malAuth: MalAuthStore
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
    /** The MyAnimeList sign-in the phone's link starts; its verifier never leaves the TV. */
    @Volatile private var malPending: MalPendingSignIn? = null

    private val logoBytes: ByteArray? by lazy {
        context.readWordmarkPngBytes()
    }

    /** A message for the phone, in the app's language rather than the system's. */
    private fun text(@StringRes id: Int, vararg args: Any): String = context.withAppLocale().getString(id, *args)

    /**
     * How many screens are showing the hub right now. Two can overlap (the first-run wizard
     * holds it throughout, and its Server step's website address QR code opens it too), so it
     * closes only when the last one does.
     */
    private var openScreens = 0

    @Synchronized
    fun onScreenOpened() {
        openScreens += 1
        start()
    }

    /** Tries again to start a hub that failed to (no network, no free port), without opening another screen. */
    @Synchronized
    fun retry() = start()

    private fun start() {
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
            resources = { context.withAppLocale().resources },
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
        openScreens = (openScreens - 1).coerceAtLeast(0)
        if (openScreens > 0) return
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
            onSettingsChanged = { settings ->
                // The hub refuses section changes while gated; this catches a write already in flight.
                if (!parentalControls.needsGate()) scope.launch { streamBadgeSettingsDataStore.setSettings(settings) }
            },
            context = context,
            logoProvider = { logoBytes }
        ),
        PhoneHubServer.SECTION_POSTERS to CustomPosterConfigServer(
            currentPatternProvider = { runBlocking { layoutPreferenceDataStore.customPosterUrlPattern.first() } },
            onPatternChanged = { pattern ->
                if (!parentalControls.needsGate()) scope.launch { layoutPreferenceDataStore.setCustomPosterUrlPattern(pattern) }
            },
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
            mediaServers = serverRepository.uiState.value.connections.map { "${it.name} (${it.userName})" },
            aniList = if (!aniListApi.isConfigured) null else aniListAuth.current().let { state ->
                PhoneHubAnimeAccountStatus(
                    connected = state.isAuthenticated(),
                    username = state.username?.takeIf { state.isAuthenticated() },
                    authorizeUrl = aniListApi.authorizeUrl
                )
            },
            mal = if (!malApi.isConfigured) null else malAuth.current().let { state ->
                val signIn = pendingMalSignIn()
                PhoneHubAnimeAccountStatus(
                    connected = state.isAuthenticated,
                    username = state.username?.takeIf { state.isAuthenticated },
                    authorizeUrl = malApi.authorizeUrl(signIn.verifier, signIn.state)
                )
            }
        )
    }

    /** The current MyAnimeList sign-in, kept while the page polls so the link stays the same. */
    private fun pendingMalSignIn(): MalPendingSignIn {
        val now = System.currentTimeMillis()
        malPending?.takeIf { now - it.createdAt < MAL_SIGN_IN_LIFETIME_MS }?.let { return it }
        return MalPendingSignIn(MalApi.newVerifier(), MalApi.newVerifier(32), now).also { malPending = it }
    }

    override suspend fun connectMal(code: String): PhoneHubResult {
        settingsBlocked()?.let { return it }
        val pending = malPending
        val parsed = parseMalCallback(code)
        if (!malApi.isConfigured || pending == null || parsed == null) {
            return PhoneHubResult(false, text(R.string.phone_web_mal_rejected))
        }
        // A pasted callback address carries the state: it must be this sign-in's.
        if (parsed.state != null && parsed.state != pending.state) return PhoneHubResult(false, text(R.string.phone_web_mal_rejected))
        val profileId = malAuth.activeProfileId()
        return try {
            val tokens = malApi.exchangeCode(parsed.code, pending.verifier)
            val user = malApi.me(tokens.accessToken)
            malAuth.save(profileId, tokens, user)
            malPending = null
            PhoneHubResult(true, text(R.string.phone_web_mal_connected, user.name))
        } catch (error: CancellationException) {
            throw error
        } catch (error: MalApiException) {
            PhoneHubResult(false, text(if (error.unauthorized) R.string.phone_web_mal_rejected else R.string.phone_web_mal_unavailable))
        } catch (error: Exception) {
            PhoneHubResult(false, text(R.string.phone_web_mal_unavailable))
        }
    }

    override suspend fun connectAniList(token: String): PhoneHubResult {
        settingsBlocked()?.let { return it }
        if (!aniListApi.isConfigured || token.isBlank()) return PhoneHubResult(false, text(R.string.phone_web_anilist_rejected))
        // The profile that was active when the token arrived, whatever happens during the check.
        val profileId = aniListAuth.activeProfileId()
        val viewer = try {
            aniListApi.viewer(token)
        } catch (error: CancellationException) {
            throw error
        } catch (error: AniListApiException) {
            return PhoneHubResult(
                false,
                text(if (error.unauthorized) R.string.phone_web_anilist_rejected else R.string.phone_web_anilist_unavailable)
            )
        } catch (error: Exception) {
            return PhoneHubResult(false, text(R.string.phone_web_anilist_unavailable))
        }
        // AniList's implicit-grant tokens last a year; a day's margin avoids using one as it lapses.
        val expiresAt = System.currentTimeMillis() + ANILIST_TOKEN_LIFETIME_MS
        aniListAuth.save(profileId, token, expiresAt, viewer.id, viewer.name)
        return PhoneHubResult(true, text(R.string.phone_web_anilist_connected, viewer.name))
    }

    // Account changes are also settings changes, so restricted profiles need the TV unlocked.
    private fun settingsBlocked(): PhoneHubResult? =
        if (parentalControls.needsGate()) PhoneHubResult(false, text(R.string.phone_hub_parental_blocked)) else null

    override fun settingsChangeBlocked(): String? = settingsBlocked()?.message

    override suspend fun websiteStatus(): PhoneHubWebsiteStatus {
        val pairing = websitePairing.get()
        return websiteStatusOf(pairing.state.value).copy(
            signedIn = websiteStore.session.value?.serverUrl,
            lastAddress = pairing.lastAddress
        )
    }

    override suspend fun connectWebsite(address: String): PhoneHubWebsiteStatus {
        settingsBlocked()?.let { return PhoneHubWebsiteStatus(state = "failed", message = it.message) }
        val pairing = websitePairing.get()
        return websiteStatusOf(pairing.startAndAwaitCode(address)).copy(lastAddress = pairing.lastAddress)
    }

    private fun websiteStatusOf(state: WebsitePairingState): PhoneHubWebsiteStatus = when (state) {
        WebsitePairingState.EnterAddress -> PhoneHubWebsiteStatus(state = "idle")
        WebsitePairingState.Starting, is WebsitePairingState.Connecting -> PhoneHubWebsiteStatus(state = "starting")
        is WebsitePairingState.ShowingCode -> PhoneHubWebsiteStatus(state = "code", code = state.code, confirmUrl = state.confirmUrl)
        WebsitePairingState.Expired -> PhoneHubWebsiteStatus(state = "expired")
        is WebsitePairingState.Paired -> PhoneHubWebsiteStatus(state = "paired")
        is WebsitePairingState.Failed -> PhoneHubWebsiteStatus(state = "failed", message = state.message, notFound = state.notFound)
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
            if (!valid) return PhoneHubResult(false, text(R.string.phone_web_mdblist_rejected))
        }
        mdbListSettingsDataStore.setApiKey(apiKey)
        return PhoneHubResult(true, text(if (apiKey.isBlank()) R.string.phone_web_mdblist_removed else R.string.phone_web_mdblist_saved))
    }

    override suspend fun connectMediaServer(
        providerId: String,
        address: String,
        username: String,
        password: String
    ): PhoneHubResult {
        settingsBlocked()?.let { return it }
        val provider = serverRepository.providers.firstOrNull { it.id == providerId && it.signInMethod == ServerSignInMethod.PASSWORD }
            ?: return PhoneHubResult(false, text(R.string.phone_web_server_unknown))
        return try {
            val connection = serverRepository.connect(provider, address.trim(), username.trim(), password)
            PhoneHubResult(true, text(R.string.phone_web_server_connected, connection.name))
        } catch (error: CancellationException) {
            throw error
        } catch (error: ServerException) {
            PhoneHubResult(false, signInMessage(error.failure, provider.displayName, provider.minimumVersion))
        } catch (error: Exception) {
            Log.w(TAG, "Media server sign-in from phone failed", error)
            PhoneHubResult(false, text(R.string.servers_error_failed))
        }
    }

    // The same wording as the TV's own sign-in dialog.
    private fun signInMessage(failure: ServerFailure, providerName: String, minimumVersion: String): String = when (failure) {
        ServerFailure.AUTH_REQUIRED -> text(R.string.servers_error_auth)
        ServerFailure.NOT_FOUND -> text(R.string.servers_error_address)
        ServerFailure.UNREACHABLE -> text(R.string.servers_error_unreachable)
        ServerFailure.UNSUPPORTED -> text(R.string.servers_error_unsupported, providerName, minimumVersion)
        ServerFailure.FORBIDDEN -> text(R.string.servers_error_forbidden)
        ServerFailure.INCOMPLETE,
        ServerFailure.FAILED -> text(R.string.servers_error_failed)
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
        if (parentalControls.needsGate()) throw IllegalArgumentException(text(R.string.phone_hub_parental_blocked))
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
            ?: text(R.string.phone_web_addon_read_only)
                .takeIf { AddonManagementAccess.isReadOnly(profileManager.activeProfile) }

    override suspend fun proposeAddon(url: String): PhoneHubAddonProposal {
        addonInstallBlocked()?.let { return PhoneHubAddonProposal(PhoneHubAddonStatus.FAILED, message = it) }
        // The TV asks for confirmation only where the phone code is on screen; after that the
        // hub keeps serving the remote, but nobody would see the question.
        if (!screenOpen) {
            return PhoneHubAddonProposal(
                PhoneHubAddonStatus.FAILED,
                message = text(R.string.phone_web_addon_open_screen)
            )
        }
        val addon = when (val result = withTimeoutOrNull(MANIFEST_TIMEOUT_MS) { addonRepository.fetchAddon(url) }) {
            is NetworkResult.Success -> result.data
            is NetworkResult.Error -> return PhoneHubAddonProposal(
                PhoneHubAddonStatus.FAILED,
                message = result.message.takeIf { it.isNotBlank() }
                    ?.let { text(R.string.phone_web_addon_unreadable_reason, it) }
                    ?: text(R.string.phone_web_addon_unreadable)
            )
            else -> return PhoneHubAddonProposal(PhoneHubAddonStatus.FAILED, message = text(R.string.phone_web_addon_no_answer))
        }
        val name = addon.displayName.ifBlank { addon.name.ifBlank { url } }
        val configureUrl = addonConfigureUrl(url).takeIf { addon.isConfigurable }
        if (addon.needsConfiguration) {
            return PhoneHubAddonProposal(
                PhoneHubAddonStatus.NEEDS_CONFIGURATION,
                name = name,
                configureUrl = addonConfigureUrl(url),
                message = text(R.string.phone_web_addon_needs_setup, name)
            )
        }
        val alreadyInstalled = addonRepository.getInstalledAddons().first()
            .any { it.baseUrl.trimEnd('/').equals(url.trimEnd('/'), ignoreCase = true) }
        if (alreadyInstalled) {
            return PhoneHubAddonProposal(PhoneHubAddonStatus.INSTALLED, name = name, message = text(R.string.phone_web_addon_already_installed, name))
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
                    message = text(R.string.phone_web_addon_screen_closed)
                )
            }
            val previous = _pendingAddon.value
            if (previous?.installing == true) {
                return PhoneHubAddonProposal(PhoneHubAddonStatus.FAILED, message = text(R.string.phone_web_addon_busy))
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
        // The profile just checked: the install is held to it (#465).
        val profileId = addonRepository.addonChangeProfileId()
        val installing = request.copy(installing = true)
        // Only the request on screen: a newer one may have just replaced it.
        if (!_pendingAddon.compareAndSet(request, installing)) return
        scope.launch {
            try {
                addonProposals[request.id] = when (val outcome = addonRepository.installOrReplace(request.url, profileId = profileId)) {
                    is AddonInstallOutcome.Installed -> PhoneHubAddonProposal(PhoneHubAddonStatus.INSTALLED, id = request.id, name = request.name)
                    is AddonInstallOutcome.NeedsConfiguration -> PhoneHubAddonProposal(
                        PhoneHubAddonStatus.NEEDS_CONFIGURATION,
                        id = request.id,
                        name = request.name,
                        configureUrl = addonConfigureUrl(request.url),
                        message = text(R.string.phone_web_addon_needs_setup, request.name)
                    )
                    is AddonInstallOutcome.Failed -> PhoneHubAddonProposal(
                        PhoneHubAddonStatus.FAILED,
                        id = request.id,
                        name = request.name,
                        message = outcome.message.ifBlank { text(R.string.phone_web_addon_install_failed, request.name) }
                    )
                    is AddonInstallOutcome.ProfileChanged -> PhoneHubAddonProposal(
                        PhoneHubAddonStatus.FAILED,
                        id = request.id,
                        name = request.name,
                        message = text(R.string.addon_install_profile_changed, request.name)
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
                    message = text(R.string.phone_web_addon_install_failed, request.name)
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
            Result.failure(IllegalStateException(text(R.string.phone_web_error_export)))
        }
    }

    override suspend fun importProfile(document: JsonObject): PhoneHubResult {
        settingsBlocked()?.let { return it }
        return try {
            val profile = profileTransfer.import(document)
            PhoneHubResult(true, text(R.string.phone_web_profile_added, profile.name))
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalArgumentException) {
            PhoneHubResult(false, error.message)
        } catch (error: Exception) {
            Log.w(TAG, "Profile import from phone failed", error)
            PhoneHubResult(false, text(R.string.phone_web_profile_add_failed))
        }
    }

    // A backup holds every profile's history and settings, so it's behind the same lock as settings.
    override suspend fun exportBackup(includeAddons: Boolean): Result<JsonObject> {
        settingsBlocked()?.let { return Result.failure(IllegalStateException(it.message)) }
        return try {
            Result.success(tvBackup.export(includeAddons))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Backup from phone failed", error)
            Result.failure(IllegalStateException(text(R.string.phone_web_error_backup)))
        }
    }

    override suspend fun restoreBackup(document: JsonObject): PhoneHubResult {
        settingsBlocked()?.let { return it }
        if (!TvBackup.isBackup(document)) return PhoneHubResult(false, text(R.string.phone_web_backup_not_a_backup))
        return try {
            val result = tvBackup.restore(document)
            val separator = text(R.string.phone_web_list_separator)
            val message = buildList {
                if (result.merged.isNotEmpty()) add(text(R.string.phone_web_backup_restored_merged, result.merged.joinToString(separator)))
                if (result.added.isNotEmpty()) add(text(R.string.phone_web_backup_restored_added, result.added.joinToString(separator)))
                if (result.skipped.isNotEmpty()) add(text(R.string.phone_web_backup_restored_skipped, result.skipped.joinToString(separator)))
                if (result.unreadable > 0) {
                    add(context.withAppLocale().resources.getQuantityString(R.plurals.phone_web_backup_restored_unreadable, result.unreadable, result.unreadable))
                }
            }.joinToString(" ")
            val restoredAny = result.merged.isNotEmpty() || result.added.isNotEmpty()
            PhoneHubResult(restoredAny, message.ifBlank { text(R.string.phone_web_backup_restore_failed) })
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalArgumentException) {
            PhoneHubResult(false, error.message)
        } catch (error: Exception) {
            Log.w(TAG, "Backup restore from phone failed", error)
            PhoneHubResult(false, text(R.string.phone_web_backup_restore_failed))
        }
    }
}
