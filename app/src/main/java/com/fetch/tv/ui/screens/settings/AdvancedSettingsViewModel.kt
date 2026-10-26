package com.fetch.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fetch.tv.core.runtime.AppRestarter
import com.fetch.tv.data.local.AddonTimeoutPreferences
import com.fetch.tv.data.local.DeviceLocalPlayerPreferences
import com.fetch.tv.data.local.ImagePerformancePreferences
import com.fetch.tv.data.local.LayoutPreferenceDataStore
import com.fetch.tv.data.local.PlayerSettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AdvancedSettingsUiState(
    val fastHorizontalNavigationEnabled: Boolean = false,
    val smoothBringIntoViewEnabled: Boolean = true,
    val composeHighlighterEnabled: Boolean = false,
    val playbackIssueReportsEnabled: Boolean = false,
    val playerStatsHudEnabled: Boolean = false,
    val rgb565Enabled: Boolean = true,
    val hardwareBitmapsEnabled: Boolean = false,
    val addonTimeoutSeconds: Int = AddonTimeoutPreferences.DEFAULT_TIMEOUT_SECONDS,
    val skipSlowAddons: Boolean = false
)

sealed class AdvancedSettingsEvent {
    data class SetFastHorizontalNavigationEnabled(val enabled: Boolean) : AdvancedSettingsEvent()
    data class SetSmoothBringIntoViewEnabled(val enabled: Boolean) : AdvancedSettingsEvent()
    data class SetComposeHighlighterEnabled(val enabled: Boolean) : AdvancedSettingsEvent()
    data class SetPlaybackIssueReportsEnabled(val enabled: Boolean) : AdvancedSettingsEvent()
    data class SetPlayerStatsHudEnabled(val enabled: Boolean) : AdvancedSettingsEvent()
    data class SetRgb565Enabled(val enabled: Boolean) : AdvancedSettingsEvent()
    data class SetHardwareBitmapsEnabled(val enabled: Boolean) : AdvancedSettingsEvent()
    data class SetAddonTimeoutSeconds(val seconds: Int) : AdvancedSettingsEvent()
    data class SetSkipSlowAddons(val enabled: Boolean) : AdvancedSettingsEvent()
}

@HiltViewModel
class AdvancedSettingsViewModel @Inject constructor(
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val deviceLocalPlayerPreferences: DeviceLocalPlayerPreferences,
    private val imagePerformancePreferences: ImagePerformancePreferences,
    private val addonTimeoutPreferences: AddonTimeoutPreferences,
    private val appRestarter: AppRestarter
) : ViewModel() {
    private val _uiState = MutableStateFlow(AdvancedSettingsUiState())
    val uiState: StateFlow<AdvancedSettingsUiState> = _uiState.asStateFlow()

    init {
        _uiState.update {
            it.copy(
                rgb565Enabled = imagePerformancePreferences.rgb565Enabled,
                hardwareBitmapsEnabled = imagePerformancePreferences.hardwareBitmapsEnabled
            )
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.fastHorizontalNavigationEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(fastHorizontalNavigationEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.smoothBringIntoViewEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(smoothBringIntoViewEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.composeHighlighterEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(composeHighlighterEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            playerSettingsDataStore.playerSettings.collectLatest { settings ->
                _uiState.update { it.copy(playbackIssueReportsEnabled = settings.playbackIssueReportsEnabled) }
            }
        }
        viewModelScope.launch {
            deviceLocalPlayerPreferences.playerStatsHudEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(playerStatsHudEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            addonTimeoutPreferences.timeoutSeconds.collectLatest { seconds ->
                _uiState.update { it.copy(addonTimeoutSeconds = seconds) }
            }
        }
        viewModelScope.launch {
            addonTimeoutPreferences.skipSlowAddons.collectLatest { enabled ->
                _uiState.update { it.copy(skipSlowAddons = enabled) }
            }
        }
    }

    fun onEvent(event: AdvancedSettingsEvent) {
        when (event) {
            is AdvancedSettingsEvent.SetFastHorizontalNavigationEnabled -> {
                viewModelScope.launch {
                    layoutPreferenceDataStore.setFastHorizontalNavigationEnabled(event.enabled)
                }
            }
            is AdvancedSettingsEvent.SetSmoothBringIntoViewEnabled -> {
                viewModelScope.launch {
                    layoutPreferenceDataStore.setSmoothBringIntoViewEnabled(event.enabled)
                }
            }
            is AdvancedSettingsEvent.SetComposeHighlighterEnabled -> {
                viewModelScope.launch {
                    layoutPreferenceDataStore.setComposeHighlighterEnabled(event.enabled)
                }
            }
            is AdvancedSettingsEvent.SetPlaybackIssueReportsEnabled -> {
                viewModelScope.launch {
                    playerSettingsDataStore.setPlaybackIssueReportsEnabled(event.enabled)
                }
            }
            is AdvancedSettingsEvent.SetPlayerStatsHudEnabled -> {
                viewModelScope.launch {
                    deviceLocalPlayerPreferences.setPlayerStatsHudEnabled(event.enabled)
                }
            }
            is AdvancedSettingsEvent.SetRgb565Enabled -> {
                if (imagePerformancePreferences.setRgb565Enabled(event.enabled)) {
                    _uiState.update { it.copy(rgb565Enabled = event.enabled) }
                    appRestarter.restart()
                }
            }
            is AdvancedSettingsEvent.SetHardwareBitmapsEnabled -> {
                if (imagePerformancePreferences.setHardwareBitmapsEnabled(event.enabled)) {
                    _uiState.update { it.copy(hardwareBitmapsEnabled = event.enabled) }
                    appRestarter.restart()
                }
            }
            is AdvancedSettingsEvent.SetAddonTimeoutSeconds -> {
                addonTimeoutPreferences.setTimeoutSeconds(event.seconds)
            }
            is AdvancedSettingsEvent.SetSkipSlowAddons -> {
                addonTimeoutPreferences.setSkipSlowAddons(event.enabled)
            }
        }
    }
}
