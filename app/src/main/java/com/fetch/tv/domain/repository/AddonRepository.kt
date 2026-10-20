package com.fetch.tv.domain.repository

import com.fetch.tv.core.network.NetworkResult
import com.fetch.tv.domain.model.Addon
import kotlinx.coroutines.flow.Flow

interface AddonRepository {
    fun getInstalledAddons(): Flow<List<Addon>>
    suspend fun fetchAddon(baseUrl: String): NetworkResult<Addon>
    suspend fun addAddon(url: String)
    suspend fun removeAddon(url: String)
    suspend fun setAddonOrder(urls: List<String>)
    suspend fun setAddonEnabled(url: String, enabled: Boolean)
}
