package com.fetch.tv.core.profile

import com.fetch.tv.core.parental.ProfilePinManager
import com.fetch.tv.data.local.ProfileRestrictionsDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether Fetch may show the active profile's titles outside the app: the launcher's
 * Continue Watching channel, Watch Next ("Play Next") and the system search.
 *
 * Those surfaces are seen by whoever holds the remote, outside every profile lock, so they
 * stay empty on a TV where any profile has parental limits, or while the active profile has
 * a PIN. Until the active profile has loaded, the answer is no.
 */
@Singleton
class LauncherExposureGate @Inject constructor(
    private val profileManager: ProfileManager,
    private val restrictionsDataStore: ProfileRestrictionsDataStore,
    private val pinManager: ProfilePinManager
) {
    suspend fun mayExposeActiveProfile(): Boolean {
        withTimeoutOrNull(PROFILE_READY_TIMEOUT_MS) { profileManager.activeProfileReady.first { it } } ?: return false
        if (restrictionsDataStore.restrictions.first().values.any { it.isRestricted }) return false
        val activeId = profileManager.activeProfileId.value
        return pinManager.pinEnabled.first()[activeId] != true
    }

    private companion object {
        const val PROFILE_READY_TIMEOUT_MS = 2_000L
    }
}
