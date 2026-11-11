package com.fetch.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.data.local.BundledAvatars
import com.fetch.tv.domain.model.UserProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileSettingsViewModel @Inject constructor(
    private val profileManager: ProfileManager,
    private val parentalControls: com.fetch.tv.core.parental.ParentalControls
) : ViewModel() {

    val lockUnrestrictedProfiles: StateFlow<Boolean> = parentalControls.lockUnrestrictedProfiles
    val parentPinSet: StateFlow<Boolean> = parentalControls.parentPinSet

    fun setLockUnrestrictedProfiles(enabled: Boolean) {
        viewModelScope.launch { parentalControls.setLockUnrestrictedProfiles(enabled) }
    }

    val isPrimaryProfileActive: StateFlow<Boolean> = profileManager.activeProfileId
        .map { it == 1 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val profiles: StateFlow<List<UserProfile>> = profileManager.profiles

    val rememberLastProfileEnabled: StateFlow<Boolean> = profileManager.rememberLastProfileEnabled

    /** Each profile's avatar image. */
    val avatarImageUrls: StateFlow<Map<Int, String>> = profiles.map { list ->
        list.mapNotNull { profile ->
            val url = profile.avatarUrl?.takeIf { it.isNotBlank() }
                ?: profile.avatarId?.let(BundledAvatars::imageUrl)
            url?.let { profile.id to it }
        }.toMap()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun setRememberLastProfileEnabled(enabled: Boolean) {
        viewModelScope.launch { profileManager.setRememberLastProfileEnabled(enabled) }
    }
}
