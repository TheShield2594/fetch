package com.fetch.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fetch.tv.data.local.ThemeDataStore
import com.fetch.tv.data.repository.MemberAccessRepository
import com.fetch.tv.domain.model.AppFont
import com.fetch.tv.domain.model.AppIconOption
import com.fetch.tv.domain.model.AppTheme
import com.fetch.tv.domain.model.CosmeticEntitlements
import com.fetch.tv.domain.model.CustomThemeColors
import com.fetch.tv.domain.model.availableAppThemes
import com.fetch.tv.domain.model.resolveAppTheme
import com.fetch.tv.domain.model.resolveCustomThemeColors
import com.fetch.tv.launcher.AppIconManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ThemeSettingsUiState(
    val themesLoaded: Boolean = false,
    val selectedTheme: AppTheme = com.fetch.tv.domain.model.DEFAULT_APP_THEME,
    val customThemeColors: CustomThemeColors = CustomThemeColors.solid(CustomThemeColors.Default.second),
    val customThemeGradientEnabled: Boolean = false,
    val availableThemes: List<AppTheme> = availableAppThemes(CosmeticEntitlements.None),
    val selectedFont: AppFont = AppFont.SCHIBSTED_GROTESK,
    val availableFonts: List<AppFont> = AppFont.entries.toList(),
    val amoledMode: Boolean = false,
    val amoledSurfacesMode: Boolean = false,
    val highContrast: Boolean = false,
)

sealed class ThemeSettingsEvent {
    data class SelectTheme(val theme: AppTheme) : ThemeSettingsEvent()
    data class SaveCustomTheme(val colors: CustomThemeColors) : ThemeSettingsEvent()
    data class SelectFont(val font: AppFont) : ThemeSettingsEvent()
    data class ToggleAmoledMode(val enabled: Boolean) : ThemeSettingsEvent()
    data class ToggleAmoledSurfacesMode(val enabled: Boolean) : ThemeSettingsEvent()
    data class ToggleHighContrast(val enabled: Boolean) : ThemeSettingsEvent()
    data object DismissAppIconFailure : ThemeSettingsEvent()
}

@HiltViewModel
class ThemeSettingsViewModel @Inject constructor(
    private val themeDataStore: ThemeDataStore,
    private val memberAccessRepository: MemberAccessRepository,
    private val appIconManager: AppIconManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(ThemeSettingsUiState())
    val uiState: StateFlow<ThemeSettingsUiState> = _uiState.asStateFlow()
    val appIconState = appIconManager.state

    init {
        viewModelScope.launch {
            combine(
                themeDataStore.themeSelection,
                memberAccessRepository.access
            ) { selection, memberAccess ->
                val entitlements = memberAccess.entitlements
                Triple(
                    selection.copy(
                        theme = resolveAppTheme(selection.theme, entitlements),
                        customColors = resolveCustomThemeColors(selection.customColors, memberAccess.tier)
                    ),
                    availableAppThemes(entitlements),
                    memberAccess.tier != null
                )
            }
                .distinctUntilChanged()
                .collectLatest { (selection, availableThemes, gradientEnabled) ->
                    _uiState.update { state ->
                        state.copy(
                            themesLoaded = true,
                            selectedTheme = selection.theme ?: com.fetch.tv.domain.model.DEFAULT_APP_THEME,
                            customThemeColors = selection.customColors,
                            customThemeGradientEnabled = gradientEnabled,
                            availableThemes = availableThemes
                        )
                    }
                }
        }
        viewModelScope.launch {
            themeDataStore.selectedFont
                .distinctUntilChanged()
                .collectLatest { font ->
                    _uiState.update { state ->
                        if (state.selectedFont == font) state else state.copy(selectedFont = font)
                    }
                }
        }
        viewModelScope.launch {
            themeDataStore.amoledMode
                .distinctUntilChanged()
                .collectLatest { enabled ->
                    _uiState.update { state ->
                        if (state.amoledMode == enabled) state else state.copy(amoledMode = enabled)
                    }
                }
        }
        viewModelScope.launch {
            themeDataStore.amoledSurfacesMode
                .distinctUntilChanged()
                .collectLatest { enabled ->
                    _uiState.update { state ->
                        if (state.amoledSurfacesMode == enabled) state else state.copy(amoledSurfacesMode = enabled)
                    }
                }
        }
        viewModelScope.launch {
            themeDataStore.highContrast
                .distinctUntilChanged()
                .collectLatest { enabled ->
                    _uiState.update { state ->
                        if (state.highContrast == enabled) state else state.copy(highContrast = enabled)
                    }
                }
        }
    }

    private fun currentTheme(): AppTheme {
        return _uiState.value.selectedTheme
    }

    fun onEvent(event: ThemeSettingsEvent) {
        when (event) {
            is ThemeSettingsEvent.SelectTheme -> selectTheme(event.theme)
            is ThemeSettingsEvent.SaveCustomTheme -> saveCustomTheme(event.colors)
            is ThemeSettingsEvent.SelectFont -> selectFont(event.font)
            is ThemeSettingsEvent.ToggleAmoledMode -> setAmoledMode(event.enabled)
            is ThemeSettingsEvent.ToggleAmoledSurfacesMode -> setAmoledSurfacesMode(event.enabled)
            is ThemeSettingsEvent.ToggleHighContrast -> setHighContrast(event.enabled)
            ThemeSettingsEvent.DismissAppIconFailure -> appIconManager.clearFailure()
        }
    }

    fun selectAppIcon(option: AppIconOption): Boolean = appIconManager.select(option)

    private fun selectTheme(theme: AppTheme) {
        if (currentTheme() == theme) return
        viewModelScope.launch {
            val access = memberAccessRepository.access.value
            if (theme !in availableAppThemes(access.entitlements)) return@launch
            themeDataStore.setTheme(theme)
        }
    }

    private fun saveCustomTheme(colors: CustomThemeColors) {
        viewModelScope.launch {
            val access = memberAccessRepository.access.value
            themeDataStore.setCustomTheme(resolveCustomThemeColors(colors, access.tier))
        }
    }

    private fun selectFont(font: AppFont) {
        if (_uiState.value.selectedFont == font) return
        viewModelScope.launch {
            themeDataStore.setFont(font)
        }
    }

    private fun setAmoledMode(enabled: Boolean) {
        if (_uiState.value.amoledMode == enabled) return
        viewModelScope.launch {
            themeDataStore.setAmoledMode(enabled)
        }
    }

    private fun setAmoledSurfacesMode(enabled: Boolean) {
        if (_uiState.value.amoledSurfacesMode == enabled) return
        viewModelScope.launch {
            themeDataStore.setAmoledSurfacesMode(enabled)
        }
    }

    private fun setHighContrast(enabled: Boolean) {
        if (_uiState.value.highContrast == enabled) return
        viewModelScope.launch {
            themeDataStore.setHighContrast(enabled)
        }
    }
}
