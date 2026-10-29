package com.fetch.tv.ui.screens.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.data.remote.supabase.AvatarCatalogItem
import com.fetch.tv.data.remote.supabase.AvatarRepository
import com.fetch.tv.data.repository.MemberAccessRepository
import com.fetch.tv.domain.model.CosmeticEntitlement
import com.fetch.tv.domain.model.UserProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileSettingsViewModel @Inject constructor(
    private val profileManager: ProfileManager,
    private val avatarRepository: AvatarRepository,
    private val memberAccessRepository: MemberAccessRepository,
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

    private val avatarCatalog = MutableStateFlow<List<AvatarCatalogItem>>(emptyList())
    private var avatarCatalogRequested = false

    /** Each profile's avatar image, once [loadAvatars] has fetched the catalog. */
    val avatarImageUrls: StateFlow<Map<Int, String>> = combine(profiles, avatarCatalog) { list, catalog ->
        list.mapNotNull { profile ->
            val url = profile.avatarUrl?.takeIf { it.isNotBlank() }
                ?: profile.avatarId?.let { avatarRepository.getAvatarImageUrl(it, catalog) }
            url?.let { profile.id to it }
        }.toMap()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Called by the Profiles page, so the rest of Settings doesn't load the avatar catalog. */
    fun loadAvatars() {
        if (avatarCatalogRequested) return
        avatarCatalogRequested = true
        viewModelScope.launch {
            val hasAccess = memberAccessRepository.access.value.entitlements
                .includes(CosmeticEntitlement.PROFILE_AVATARS)
            try {
                avatarCatalog.value = avatarRepository.getAvatarCatalog(hasAccess)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("ProfileSettingsVM", "Couldn't load the avatar catalog", error)
            }
        }
    }

    fun setRememberLastProfileEnabled(enabled: Boolean) {
        viewModelScope.launch { profileManager.setRememberLastProfileEnabled(enabled) }
    }
}
