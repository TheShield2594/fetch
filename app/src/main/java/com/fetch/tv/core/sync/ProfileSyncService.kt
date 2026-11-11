package com.fetch.tv.core.sync

import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.core.sync.backend.SyncBackends
import com.fetch.tv.domain.model.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

sealed class SetProfilePinResult {
    object Success : SetProfilePinResult()
    object CurrentPinRequired : SetProfilePinResult()
    data class Failure(val throwable: Throwable) : SetProfilePinResult()
}

/** This TV's profiles, through the sync backend in use. PINs go through `FetchWebsiteProfilePins`. */
@Singleton
class ProfileSyncService @Inject constructor(
    private val profileManager: ProfileManager,
    private val backends: SyncBackends
) {
    /** Sends every profile on this TV to the sync backend in use. */
    suspend fun pushToRemote(): Result<Unit> = withContext(Dispatchers.IO) {
        backends.active?.pushProfiles() ?: Result.success(Unit)
    }

    /**
     * Reads the account's profiles into this TV, with whatever the backend keeps next to them
     * (parental limits; which profiles have a PIN). Returns the TV's profiles afterwards.
     */
    suspend fun pullFromRemote(force: Boolean = false): Result<List<UserProfile>> = withContext(Dispatchers.IO) {
        val backend = backends.active ?: return@withContext Result.success(profileManager.profiles.value)
        backend.pullProfiles(force).map { profileManager.profiles.value }
    }

    suspend fun deleteProfileData(profileId: Int): Result<Unit> = withContext(Dispatchers.IO) {
        backends.active?.deleteProfileData(profileId) ?: Result.success(Unit)
    }
}
