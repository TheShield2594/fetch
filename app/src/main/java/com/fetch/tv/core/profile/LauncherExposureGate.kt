package com.fetch.tv.core.profile

import com.fetch.tv.core.parental.ProfilePinManager
import com.fetch.tv.data.local.ProfileRestrictionsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
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
    /**
     * The answer [mayExposeActiveProfile] would give, as it changes: a profile switch, a PIN set or
     * removed, or parental limits changed (including by a website sync while Fetch is in the
     * background). Emits once the active profile has loaded, then on every change.
     */
    val exposureChanges: Flow<Boolean> = combine(
        profileManager.loadedActiveProfileId.filterNotNull(),
        restrictionsDataStore.restrictions,
        pinManager.pinEnabled
    ) { activeId, restrictions, pins ->
        restrictions.values.none { it.isRestricted } && pins[activeId] != true
    }.distinctUntilChanged()

    suspend fun mayExposeActiveProfile(): Boolean {
        // The loaded id, not activeProfileId: that one starts as a placeholder (profile 1) on its
        // own collector, so "ready" could pair with the wrong profile's PIN state.
        val activeId = withTimeoutOrNull(PROFILE_READY_TIMEOUT_MS) {
            profileManager.loadedActiveProfileId.first { it != null }
        } ?: return false
        if (restrictionsDataStore.restrictions.first().values.any { it.isRestricted }) return false
        return pinManager.pinEnabled.first()[activeId] != true
    }

    private companion object {
        const val PROFILE_READY_TIMEOUT_MS = 2_000L
    }
}
