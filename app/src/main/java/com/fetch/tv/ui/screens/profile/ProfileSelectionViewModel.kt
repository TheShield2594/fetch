package com.fetch.tv.ui.screens.profile

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.core.sync.ProfileSettingsSyncService
import com.fetch.tv.core.sync.ProfileSyncService
import com.fetch.tv.core.sync.SetProfilePinResult
import com.fetch.tv.core.parental.PinVerifyResult
import com.fetch.tv.core.parental.ProfilePinManager
import com.fetch.tv.core.parental.ResetPinResult
import com.fetch.tv.data.local.LayoutPreferenceDataStore
import com.fetch.tv.data.local.PlayerSettingsDataStore
import com.fetch.tv.data.local.WatchProgressPreferences
import com.fetch.tv.data.local.WatchedItemsPreferences
import com.fetch.tv.data.local.AvatarCatalogItem
import com.fetch.tv.data.local.BundledAvatars
import com.fetch.tv.domain.model.UserProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface CreateProfileResult {
    data class Created(
        val profile: UserProfile,
        val settingsCopyResult: Result<Unit>?
    ) : CreateProfileResult

    data object Failed : CreateProfileResult
}

@HiltViewModel
class ProfileSelectionViewModel @Inject constructor(
    private val profileManager: ProfileManager,
    private val profileSyncService: ProfileSyncService,
    private val profileSettingsSyncService: ProfileSettingsSyncService,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val profilePinManager: ProfilePinManager,
    private val parentalControls: com.fetch.tv.core.parental.ParentalControls,
    private val themeDataStore: com.fetch.tv.data.local.ThemeDataStore,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val watchedItemsPreferences: WatchedItemsPreferences,
    private val watchProgressPreferences: WatchProgressPreferences
) : ViewModel() {
    val activeProfileId: StateFlow<Int> = profileManager.activeProfileId
    val profiles: StateFlow<List<UserProfile>> = profileManager.profiles

    val canAddProfile: Boolean
        get() = profileManager.canCreateProfile

    private val _avatarCatalog = MutableStateFlow<List<AvatarCatalogItem>>(emptyList())
    val avatarCatalog: StateFlow<List<AvatarCatalogItem>> = _avatarCatalog.asStateFlow()

    private val _isCreating = MutableStateFlow(false)
    val isCreating: StateFlow<Boolean> = _isCreating.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _isCopyingSettings = MutableStateFlow(false)
    val isCopyingSettings: StateFlow<Boolean> = _isCopyingSettings.asStateFlow()

    val profilePinEnabled: StateFlow<Map<Int, Boolean>> = profilePinManager.pinEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val profileRestrictions: StateFlow<Map<Int, com.fetch.tv.data.local.ProfileRestrictions>> =
        parentalControls.allRestrictions

    fun setProfileRestrictions(profileId: Int, restrictions: com.fetch.tv.data.local.ProfileRestrictions) {
        viewModelScope.launch { parentalControls.setRestrictions(profileId, restrictions) }
    }

    /**
     * The PIN states, lock setting and limits read from disk, or null until all are loaded.
     * Selection in the picker waits for this (see [pickerOpenGate]).
     */
    private val profileOpenLock: StateFlow<com.fetch.tv.core.parental.ProfileOpenLock?> =
        parentalControls.profileOpenLock
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** How opening [profileId] from the picker is gated, or null while that is still unknown. */
    fun pickerOpenGate(profileId: Int): com.fetch.tv.core.parental.PickerOpenGate? =
        com.fetch.tv.core.parental.pickerOpenGate(
            profileOpenLock.value,
            profileId,
            profileManager.loadedProfiles.value?.map { it.id }
        )

    val lockUnrestrictedProfiles: StateFlow<Boolean> = parentalControls.lockUnrestrictedProfiles

    fun setLockUnrestrictedProfiles(enabled: Boolean) {
        viewModelScope.launch { parentalControls.setLockUnrestrictedProfiles(enabled) }
    }

    val profileKidsRules: StateFlow<Map<Int, com.fetch.tv.data.local.KidsRules>> = parentalControls.allKidsRules

    /** The main profile's own lists, which a kids profile can take approved titles from. */
    val parentLists: StateFlow<List<com.fetch.tv.core.tracking.LocalList>> = parentalControls.parentLists

    fun setKidsRules(profileId: Int, rules: com.fetch.tv.data.local.KidsRules) {
        viewModelScope.launch { parentalControls.setKidsRules(profileId, rules) }
    }

    val profileTitleLists: StateFlow<Map<Int, com.fetch.tv.data.local.ProfileTitleLists>> =
        parentalControls.allTitleLists

    fun clearParentalTitle(profileId: Int, title: com.fetch.tv.data.local.ParentalTitle) {
        viewModelScope.launch {
            parentalControls.setTitleState(profileId, title, com.fetch.tv.core.parental.ParentalTitleState.BY_RATING)
        }
    }

    private val _isPinOperationInProgress = MutableStateFlow(false)
    val isPinOperationInProgress: StateFlow<Boolean> = _isPinOperationInProgress.asStateFlow()

    // Declared before init: the theme collector below can emit synchronously on
    // Main.immediate when the theme stores are already cached.
    private val _profileThemes = MutableStateFlow<Map<Int, com.fetch.tv.domain.model.AppTheme>>(emptyMap())
    val profileThemes: StateFlow<Map<Int, com.fetch.tv.domain.model.AppTheme>> = _profileThemes.asStateFlow()

    init {
        refreshProfilePinStates()
        loadAvatarCatalog()
        viewModelScope.launch {
            profiles.flatMapLatest { profileList ->
                if (profileList.isEmpty()) {
                    flowOf(emptyMap())
                } else {
                    combine(
                        profileList.map { profile ->
                            themeDataStore.observeThemeForProfile(profile.id)
                                .map { theme -> profile.id to (theme ?: com.fetch.tv.domain.model.DEFAULT_APP_THEME) }
                        }
                    ) { entries -> entries.toMap() }
                }
            }.collectLatest { themes ->
                _profileThemes.value = themes
            }
        }
    }

    fun loadAvatarCatalog() {
        if (_avatarCatalog.value.isEmpty()) _avatarCatalog.value = BundledAvatars.catalog(context)
    }

    fun getAvatarImageUrl(avatarId: String?): String? {
        if (avatarId == null) return null
        return BundledAvatars.imageUrl(avatarId)
    }

    var isSelectingProfile = false
        private set

    fun selectProfile(id: Int, onComplete: () -> Unit, onFailure: () -> Unit) {
        if (isSelectingProfile) return
        isSelectingProfile = true
        viewModelScope.launch {
            try {
                profileManager.setActiveProfile(id)
                onComplete()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e("ProfileSelectionVM", "Failed to select profile", error)
                onFailure()
            } finally {
                isSelectingProfile = false
            }
        }
    }

    fun createProfile(
        name: String,
        avatarColorHex: String,
        avatarId: String? = null,
        copyFromProfileId: Int? = null,
        copyProviderCredentials: Boolean = false,
        isKidsProfile: Boolean = false,
        usesMainAddons: Boolean = false,
        onComplete: (CreateProfileResult) -> Unit = {}
    ) {
        if (_isCreating.value) return
        viewModelScope.launch {
            _isCreating.value = true
            val result = try {
                val profile = profileManager.createProfile(
                    name = name,
                    avatarColorHex = avatarColorHex,
                    usesPrimaryAddons = usesMainAddons,
                    avatarId = avatarId
                )
                if (profile != null) {
                    // Before anything is written or copied into it: a new profile gets the
                    // current player defaults, not the ones kept for existing profiles.
                    playerSettingsDataStore.prepareNewProfile(profile.id)
                    if (isKidsProfile) {
                        parentalControls.setRestrictions(profile.id, com.fetch.tv.data.local.ProfileRestrictions.KidsPreset)
                    }
                    profileSyncService.pushToRemote()
                    val copyResult = copyFromProfileId?.let { sourceProfileId ->
                        profileSettingsSyncService.copyProfileSetup(
                            sourceProfileId = sourceProfileId,
                            targetProfileId = profile.id,
                            copyProviderCredentials = copyProviderCredentials
                        )
                    }
                    refreshProfilePinStates()
                    CreateProfileResult.Created(profile, copyResult)
                } else {
                    CreateProfileResult.Failed
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e("ProfileSelectionVM", "Failed to create profile", error)
                CreateProfileResult.Failed
            } finally {
                _isCreating.value = false
            }
            onComplete(result)
        }
    }

    fun copyProfileSettings(
        sourceProfileId: Int,
        targetProfileId: Int,
        copyProviderCredentials: Boolean,
        onComplete: (Result<Unit>) -> Unit
    ) {
        if (_isCopyingSettings.value) return
        viewModelScope.launch {
            _isCopyingSettings.value = true
            val result = try {
                profileSettingsSyncService.copyProfileSetup(
                    sourceProfileId = sourceProfileId,
                    targetProfileId = targetProfileId,
                    copyProviderCredentials = copyProviderCredentials
                )
            } finally {
                _isCopyingSettings.value = false
            }
            onComplete(result)
        }
    }

    /** Saves [profile] on the device; [onComplete] gets false when it could not be saved. */
    /** What [profileId] watched, newest first, read on the device whichever profile is active. */
    fun observeViewingActivity(profileId: Int): Flow<List<ViewingActivityEntry>> = combine(
        watchedItemsPreferences.observeAllItems(profileId),
        watchProgressPreferences.observeAllRawProgress(profileId)
    ) { watched, progress -> viewingActivity(watched, progress) }
        .flowOn(Dispatchers.Default)

    /** [profileId]'s own preferences, read from its stores whichever profile is active. */
    fun editorPreferences(profileId: Int): Flow<ProfileEditorPreferences> = combine(
        playerSettingsDataStore.playerSettingsFor(profileId),
        layoutPreferenceDataStore.focusedPosterBackdropTrailerEnabledForProfile(profileId)
    ) { player, homeTrailers -> ProfileEditorPreferences.from(player, homeTrailers) }

    /**
     * Saves [profile], then the preferences in [preferences] that differ from [previousPreferences]
     * into that profile's stores. Only changed ones are written, so a value the profile never set
     * keeps following its default.
     */
    fun updateProfile(
        profile: UserProfile,
        preferences: ProfileEditorPreferences? = null,
        previousPreferences: ProfileEditorPreferences? = null,
        onComplete: (Boolean) -> Unit = {}
    ) {
        if (_isSaving.value) return
        viewModelScope.launch {
            _isSaving.value = true
            val saved = try {
                profileManager.updateProfile(profile).also { updated ->
                    if (updated && preferences != null && preferences != previousPreferences) {
                        savePreferences(profile.id, preferences, previousPreferences)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e("ProfileSelectionVM", "Failed to update profile", error)
                false
            } finally {
                _isSaving.value = false
            }
            onComplete(saved)
            if (saved) {
                profileSyncService.pushToRemote()
                refreshProfilePinStates()
            }
        }
    }

    private suspend fun savePreferences(
        profileId: Int,
        preferences: ProfileEditorPreferences,
        previous: ProfileEditorPreferences?
    ) {
        if (preferences.audioLanguage != previous?.audioLanguage) {
            playerSettingsDataStore.setPreferredAudioLanguage(preferences.audioLanguage, profileId)
        }
        if (preferences.subtitleLanguage != previous?.subtitleLanguage) {
            playerSettingsDataStore.setSubtitlePreferredLanguage(preferences.subtitleLanguage, profileId)
        }
        if (preferences.autoplayNextEpisode != previous?.autoplayNextEpisode) {
            playerSettingsDataStore.setStreamAutoPlayNextEpisodeEnabled(preferences.autoplayNextEpisode, profileId)
        }
        if (preferences.autoplayHomeTrailers != previous?.autoplayHomeTrailers) {
            layoutPreferenceDataStore.setFocusedPosterBackdropTrailerEnabled(preferences.autoplayHomeTrailers, profileId)
        }
    }

    /** Deletes the profile on the device; [onComplete] gets false when it could not be deleted. */
    fun deleteProfile(id: Int, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val deleted = try {
                profileManager.deleteProfile(id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e("ProfileSelectionVM", "Failed to delete profile", error)
                false
            }
            onComplete(deleted)
            if (deleted) {
                profileSyncService.deleteProfileData(id)
                profileSyncService.pushToRemote()
                refreshProfilePinStates()
            }
        }
    }

    fun refreshProfilePinStates() {
        viewModelScope.launch {
            var attempt = 0
            while (attempt < 4) {
                val result = profilePinManager.refreshRemoteStates()
                if (result.isSuccess) return@launch
                Log.e(
                    "ProfileSelectionVM",
                    "Failed to refresh profile PIN states (attempt=$attempt)",
                    result.exceptionOrNull()
                )
                attempt++
                delay(2000L * attempt)
            }
        }
    }

    fun setProfilePin(
        profileId: Int,
        pin: String,
        currentPin: String? = null,
        onComplete: (SetProfilePinResult) -> Unit
    ) {
        if (_isPinOperationInProgress.value) return
        viewModelScope.launch {
            _isPinOperationInProgress.value = true
            val result = profilePinManager.setPin(profileId, pin, currentPin)
            _isPinOperationInProgress.value = false
            onComplete(result)
        }
    }

    fun resetProfilePin(
        profileId: Int,
        newPin: String,
        parentPin: String,
        onComplete: (ResetPinResult) -> Unit
    ) {
        if (_isPinOperationInProgress.value) return
        viewModelScope.launch {
            _isPinOperationInProgress.value = true
            val result = profilePinManager.resetPin(profileId, newPin, parentPin)
            _isPinOperationInProgress.value = false
            onComplete(result)
        }
    }

    fun clearProfilePin(profileId: Int, currentPin: String? = null, onComplete: (Result<PinVerifyResult>) -> Unit) {
        if (_isPinOperationInProgress.value) return
        viewModelScope.launch {
            _isPinOperationInProgress.value = true
            val result = profilePinManager.clearPin(profileId, currentPin)
            _isPinOperationInProgress.value = false
            onComplete(result)
        }
    }

    fun verifyProfilePin(profileId: Int, pin: String, onComplete: (Result<PinVerifyResult>) -> Unit) {
        if (_isPinOperationInProgress.value) return
        viewModelScope.launch {
            _isPinOperationInProgress.value = true
            val result = profilePinManager.verifyPin(profileId, pin)
            _isPinOperationInProgress.value = false
            onComplete(result)
        }
    }
}
