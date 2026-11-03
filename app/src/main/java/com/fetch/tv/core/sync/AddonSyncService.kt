package com.fetch.tv.core.sync

import android.util.Log
import com.fetch.tv.core.profile.ProfileManager
import com.fetch.tv.core.sync.backend.SyncAddon
import com.fetch.tv.core.sync.backend.SyncBackends
import com.fetch.tv.data.local.AddonPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AddonSyncService"

/** The active profile's installed addons, through whichever sync backend is in use ([SyncBackends]). */
@Singleton
class AddonSyncService @Inject constructor(
    private val addonPreferences: AddonPreferences,
    private val profileManager: ProfileManager,
    private val backends: SyncBackends
) {
    /** Whether there is anywhere to sync to. */
    val canSync: Boolean get() = backends.canSync

    /** The profile whose addon list the active one uses: its own, or the primary's for a profile set to share them. */
    private fun ownerProfileId(): Int {
        val active = profileManager.activeProfile
        return if (active != null && !active.isPrimary && active.usesPrimaryAddons) 1 else profileManager.activeProfileId.value
    }

    /** Sends the active profile's addons, in order, with their on/off states and names. */
    suspend fun pushToRemote(): Result<Unit> = withContext(Dispatchers.IO) {
        val backend = backends.active ?: return@withContext Result.success(Unit)
        val localUrls = addonPreferences.installedAddonUrls.first()
        val userSetNames = addonPreferences.userSetNames.first()
        val enabledStates = addonPreferences.addonEnabledStates.first()
        val addons = localUrls.map { url ->
            val canonicalUrl = canonicalizeUrl(url)
            SyncAddon(
                url = url,
                enabled = enabledStates[canonicalUrl] ?: true,
                name = (userSetNames[canonicalUrl] ?: userSetNames[url])?.takeIf { it.isNotBlank() }
            )
        }
        backend.pushAddons(ownerProfileId(), addons)
            .onSuccess { Log.d(TAG, "Pushed ${addons.size} addons to ${backend.kind}") }
            .onFailure { Log.e(TAG, "Failed to push addons to ${backend.kind}", it) }
    }

    /** The account's addon URLs, in order; empty when it has none. See [pullRemoteAddonUrls]. */
    suspend fun getRemoteAddonUrls(): Result<List<String>> = pullRemoteAddonUrls().map { it.orEmpty() }

    /**
     * The account's addon URLs, in order, with their names and on/off states written into the
     * addon preferences on the way. Null when the account has never had a list for this profile.
     */
    suspend fun pullRemoteAddonUrls(): Result<List<String>?> = withContext(Dispatchers.IO) {
        val backend = backends.active
            ?: return@withContext Result.failure(IllegalStateException("No sync backend is connected"))
        backend.pullAddons(ownerProfileId()).map { remote ->
            if (!remote.isNullOrEmpty()) {
                addonPreferences.setUserSetNames(
                    remote.mapNotNull { addon -> addon.name?.let { canonicalizeUrl(addon.url) to it } }.toMap()
                )
                addonPreferences.setAddonEnabledStates(remote.associate { canonicalizeUrl(it.url) to it.enabled })
            }
            remote?.map { it.url }
        }.onFailure { Log.e(TAG, "Failed to get remote addon URLs from ${backend.kind}", it) }
    }

    private fun canonicalizeUrl(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        val queryStart = trimmed.indexOf('?')
        val path = if (queryStart >= 0) trimmed.substring(0, queryStart) else trimmed
        val query = if (queryStart >= 0) trimmed.substring(queryStart) else ""
        val cleanPath = if (path.endsWith("/manifest.json", ignoreCase = true)) {
            path.dropLast("/manifest.json".length).trimEnd('/')
        } else {
            path.trimEnd('/')
        }
        return cleanPath + query
    }
}
