package com.fetch.tv.ui.screens.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fetch.tv.BuildConfig
import com.fetch.tv.core.parental.ParentalControls
import com.fetch.tv.core.server.givesStreams
import com.fetch.tv.data.local.ExperienceModeDataStore
import com.fetch.tv.data.local.LayoutPreferenceDataStore
import com.fetch.tv.data.local.MDBListSettingsDataStore
import com.fetch.tv.data.local.TmdbSettingsDataStore
import com.fetch.tv.data.local.TraktAuthDataStore
import com.fetch.tv.data.mediaserver.ServerRepository
import com.fetch.tv.domain.model.ExperienceMode
import com.fetch.tv.domain.repository.AddonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The wizard's steps, in order. */
enum class FirstRunStep { SERVER, SOURCES, METADATA, EXPERIENCE }

/** What is set up so far, shown on the steps so a phone change shows up on the TV as it happens. */
data class FirstRunStatus(
    val addonCount: Int = 0,
    val streamAddonCount: Int = 0,
    val mediaServers: List<String> = emptyList(),
    val tmdbEnabled: Boolean = false,
    val mdbListKeySet: Boolean = false,
    val traktUser: String? = null,
    val traktConnected: Boolean = false
)

@HiltViewModel
class FirstRunWizardViewModel @Inject constructor(
    private val experienceModeDataStore: ExperienceModeDataStore,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    addonRepository: AddonRepository,
    mdbListSettingsDataStore: MDBListSettingsDataStore,
    traktAuthDataStore: TraktAuthDataStore,
    serverRepository: ServerRepository,
    parentalControls: ParentalControls
) : ViewModel() {
    /**
     * A profile behind a parent PIN can't change settings from here, so it only picks the
     * experience, as before the wizard; the phone pages would refuse it anyway.
     */
    val steps: List<FirstRunStep> =
        if (parentalControls.needsGate()) listOf(FirstRunStep.EXPERIENCE) else FirstRunStep.entries

    /** TMDB enrichment needs the app's TMDB key, which some builds don't have. */
    val tmdbAvailable: Boolean = BuildConfig.TMDB_API_KEY.isNotBlank()

    val status: StateFlow<FirstRunStatus> = combine(
        addonRepository.getInstalledAddons(),
        serverRepository.uiState,
        tmdbSettingsDataStore.settings,
        mdbListSettingsDataStore.settings,
        traktAuthDataStore.state
    ) { addons, servers, tmdb, mdbList, trakt ->
        FirstRunStatus(
            addonCount = addons.size,
            streamAddonCount = addons.count { it.enabled && it.givesStreams() },
            mediaServers = servers.connections.map { it.name },
            tmdbEnabled = tmdb.enabled,
            mdbListKeySet = mdbList.apiKey.isNotBlank(),
            traktUser = trakt.username,
            traktConnected = trakt.isAuthenticated
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FirstRunStatus())

    fun setTmdbEnabled(enabled: Boolean) {
        viewModelScope.launch { tmdbSettingsDataStore.setEnabled(enabled) }
    }

    suspend fun choose(mode: ExperienceMode) {
        experienceModeDataStore.setMode(mode)
        layoutPreferenceDataStore.markHomeSetUp()
    }
}
