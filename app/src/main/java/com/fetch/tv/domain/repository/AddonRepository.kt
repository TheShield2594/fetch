package com.fetch.tv.domain.repository

import com.fetch.tv.core.network.NetworkResult
import com.fetch.tv.domain.model.Addon
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

interface AddonRepository {
    fun getInstalledAddons(): Flow<List<Addon>>
    suspend fun fetchAddon(baseUrl: String): NetworkResult<Addon>
    suspend fun addAddon(url: String)
    suspend fun removeAddon(url: String)
    suspend fun setAddonOrder(urls: List<String>)
    suspend fun setAddonEnabled(url: String, enabled: Boolean)

    /**
     * Swaps an installed addon's URL for [newUrl] in place (same position and enabled state),
     * e.g. after the addon was configured. Adds [newUrl] when [oldUrl] is not installed.
     */
    suspend fun replaceAddonUrl(oldUrl: String, newUrl: String) {
        val installed = getInstalledAddons().first()
        val old = installed.firstOrNull { it.baseUrl.equals(oldUrl, ignoreCase = true) }
        if (old == null) {
            addAddon(newUrl)
            return
        }
        setAddonOrder(installed.map { if (it === old) newUrl else it.baseUrl })
        if (!old.enabled) setAddonEnabled(newUrl, false)
    }

    /**
     * The profile an addon change made now is made for (the active profile), captured when an
     * install is confirmed so the write can be held to it. Null when the repository isn't
     * profile-scoped; the `...For` calls below then act like their plain versions.
     */
    fun addonChangeProfileId(): Int? = null

    /**
     * [addAddon] for the profile [profileId] that confirmed it. False, with nothing written, when
     * that profile isn't the active one any more or can't change its addons (it uses the main
     * profile's), so an install can't land on a profile that was switched to meanwhile.
     */
    suspend fun addAddonFor(profileId: Int, url: String): Boolean {
        addAddon(url)
        return true
    }

    /** [replaceAddonUrl] held to [profileId], refused the same way as [addAddonFor]. */
    suspend fun replaceAddonUrlFor(profileId: Int, oldUrl: String, newUrl: String): Boolean {
        replaceAddonUrl(oldUrl, newUrl)
        return true
    }
}
