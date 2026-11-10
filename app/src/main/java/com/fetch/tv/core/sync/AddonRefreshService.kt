package com.fetch.tv.core.sync

import android.util.Log
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.data.repository.AddonRepositoryImpl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AddonRefreshService"

/** The refresh button in addon settings: pulls the account's addon list and tells screens to reload. */
@Singleton
class AddonRefreshService @Inject constructor(
    private val addonSyncService: AddonSyncService,
    private val addonRepository: AddonRepositoryImpl,
    private val profileManager: ProfileManager
) {
    // Outlives the screen that asked, so leaving addon settings doesn't cancel a refresh halfway.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _manualAddonRefreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits after a manual addon refresh, so screens holding catalogs can re-request them. */
    val manualAddonRefreshes: SharedFlow<Unit> = _manualAddonRefreshes.asSharedFlow()

    fun requestAddonSyncNow() {
        val profileId = profileManager.activeProfileId.value
        scope.launch {
            try {
                if (addonSyncService.canSync) {
                    addonRepository.isSyncingFromRemote = true
                    try {
                        val remoteAddonUrls = addonSyncService.getRemoteAddonUrls().getOrThrow()
                        addonRepository.reconcileWithRemoteAddonUrls(
                            remoteUrls = remoteAddonUrls,
                            removeMissingLocal = true
                        )
                        Log.d(TAG, "Manual addon sync pulled ${remoteAddonUrls.size} addons for profile $profileId")
                    } finally {
                        addonRepository.isSyncingFromRemote = false
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Manual addon sync failed for profile $profileId", error)
            } finally {
                // The user asked for a refresh, so let screens holding catalogs re-request them
                // even when the addon list itself came back unchanged.
                _manualAddonRefreshes.tryEmit(Unit)
            }
        }
    }
}
