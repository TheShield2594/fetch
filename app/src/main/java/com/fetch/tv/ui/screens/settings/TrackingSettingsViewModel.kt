package com.fetch.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.core.tracking.StremioLinkState
import com.fetch.tv.core.tracking.TrackingImportService
import com.fetch.tv.core.tracking.TrackingImportStatus
import com.fetch.tv.core.tracking.TrackingProviderId
import com.fetch.tv.core.tracking.TrackingSourceController
import com.fetch.tv.core.tracking.TrackingSourceSelection
import com.fetch.tv.core.tracking.availableLibrarySourceModes
import com.fetch.tv.core.tracking.availableWatchProgressSources
import com.fetch.tv.core.tracking.effectiveTrackingSourceSelection
import com.fetch.tv.data.anilist.AniListApi
import com.fetch.tv.data.anilist.AniListAuthStore
import com.fetch.tv.data.local.TraktAuthDataStore
import com.fetch.tv.data.local.TraktSettingsDataStore
import com.fetch.tv.data.local.WatchProgressSource
import com.fetch.tv.data.simkl.SimklAnimeIdPreference
import com.fetch.tv.data.simkl.SimklAuthRepository
import com.fetch.tv.data.simkl.SimklSyncRepository
import com.fetch.tv.data.mdblist.MdbListAuthStore
import com.fetch.tv.data.website.FetchWebsiteStore
import com.fetch.tv.domain.model.LibrarySourceMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TrackingSettingsUiState(
    val watchProgressSource: WatchProgressSource = WatchProgressSource.FETCH_SYNC,
    val librarySourceMode: LibrarySourceMode = LibrarySourceMode.LOCAL,
    val connectedProviderIds: Set<TrackingProviderId> = emptySet(),
    val simklAnimeIdPreference: SimklAnimeIdPreference = SimklAnimeIdPreference.DEFAULT,
    val isReady: Boolean = false
) {
    val availableWatchProgressSources: List<WatchProgressSource>
        get() = availableWatchProgressSources(connectedProviderIds)

    val availableLibrarySourceModes: List<LibrarySourceMode>
        get() = availableLibrarySourceModes(connectedProviderIds)

    /** Connected sources whose history can be imported into the source of truth. */
    val importSources: List<WatchProgressSource>
        get() = availableWatchProgressSources.filter { it != watchProgressSource }
}

/** AniList is signed in from the phone hub; the TV only shows it and signs it out. */
data class AniListAccountUiState(
    val configured: Boolean = false,
    val connected: Boolean = false,
    val username: String? = null
)

@HiltViewModel
class TrackingSettingsViewModel @Inject constructor(
    private val sourceController: TrackingSourceController,
    private val settingsDataStore: TraktSettingsDataStore,
    private val simklSyncRepository: SimklSyncRepository,
    private val importService: TrackingImportService,
    traktAuthDataStore: TraktAuthDataStore,
    simklAuthRepository: SimklAuthRepository,
    mdbListAuth: MdbListAuthStore,
    websiteStore: FetchWebsiteStore,
    profiles: ProfileManager,
    private val aniListAuth: AniListAuthStore,
    aniListApi: AniListApi
) : ViewModel() {
    private val _uiState = MutableStateFlow(TrackingSettingsUiState())
    val uiState: StateFlow<TrackingSettingsUiState> = _uiState.asStateFlow()
    val importStatus: StateFlow<TrackingImportStatus> = importService.status
    val stremioLink: StateFlow<StremioLinkState> = importService.stremioLink
    val aniList: StateFlow<AniListAccountUiState> = aniListAuth.state
        .map { state ->
            AniListAccountUiState(
                configured = aniListApi.isConfigured,
                connected = state.isAuthenticated(),
                username = state.username?.takeIf { state.isAuthenticated() }
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AniListAccountUiState(configured = aniListApi.isConfigured))

    init {
        viewModelScope.launch {
            val connected = combine(
                traktAuthDataStore.state,
                simklAuthRepository.state,
                mdbListAuth.state,
                websiteStore.session,
                profiles.activeProfileId
            ) { trakt, simkl, mdblist, website, profileId ->
                if (mdblist.scope.profileId != profileId) null else buildSet<TrackingProviderId> {
                    if (trakt.isAuthenticated) add(TrackingProviderId.TRAKT)
                    if (simkl.isAuthenticated) add(TrackingProviderId.SIMKL)
                    if (mdblist.isAuthenticated) add(TrackingProviderId.MDBLIST)
                    if (website != null) add(TrackingProviderId.FETCH_WEBSITE)
                }
            }
            combine(
                sourceController.watchProgressSource,
                sourceController.librarySourceMode,
                connected,
                settingsDataStore.simklAnimeIdPreference
            ) { watchProgressSource, librarySourceMode, connectedProviderIds, animeIdPref ->
                if (connectedProviderIds == null) return@combine TrackingSettingsUiState()
                val effective = effectiveTrackingSourceSelection(
                    requested = TrackingSourceSelection(watchProgressSource, librarySourceMode),
                    connectedProviderIds = connectedProviderIds
                )
                TrackingSettingsUiState(
                    watchProgressSource = effective.watchProgressSource,
                    librarySourceMode = effective.librarySourceMode,
                    connectedProviderIds = connectedProviderIds,
                    simklAnimeIdPreference = animeIdPref,
                    isReady = true
                )
            }.collect { state ->
                _uiState.value = state
                if (state.isReady && mdbListAuth.scope().profileId == profiles.activeProfileId.value) {
                    sourceController.reconcileConnectedProviders(state.connectedProviderIds)
                }
            }
        }
    }

    fun selectWatchProgressSource(source: WatchProgressSource) {
        viewModelScope.launch {
            sourceController.selectWatchProgressSource(source)
        }
    }

    fun selectLibrarySourceMode(mode: LibrarySourceMode) {
        viewModelScope.launch {
            sourceController.selectLibrarySourceMode(mode)
        }
    }

    fun selectSimklAnimeIdPreference(preference: SimklAnimeIdPreference) {
        viewModelScope.launch {
            settingsDataStore.setSimklAnimeIdPreference(preference)
            simklSyncRepository.invalidateProjections(preference)
        }
    }

    fun importFrom(source: WatchProgressSource) {
        if (source in _uiState.value.importSources) importService.start(source)
    }

    fun acknowledgeImport() = importService.acknowledge()

    /** Shows a Stremio link code; the import starts once it is approved on a phone. */
    fun startStremioImport() {
        if (_uiState.value.isReady) importService.startStremioLink()
    }

    fun cancelStremioImport() = importService.cancelStremioLink()

    fun importFromAniList() {
        if (_uiState.value.isReady && aniList.value.connected) importService.startAniList()
    }

    fun disconnectAniList() {
        viewModelScope.launch { aniListAuth.clear() }
    }
}
