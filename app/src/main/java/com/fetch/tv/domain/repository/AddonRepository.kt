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
}
