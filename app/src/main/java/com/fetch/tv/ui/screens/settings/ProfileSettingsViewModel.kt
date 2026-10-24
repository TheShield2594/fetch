package com.fetch.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fetch.tv.core.profile.ProfileManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ProfileSettingsViewModel @Inject constructor(
    profileManager: ProfileManager
) : ViewModel() {

    val isPrimaryProfileActive: StateFlow<Boolean> = profileManager.activeProfileId
        .map { it == 1 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
}
