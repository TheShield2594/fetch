package com.fetch.tv.domain.repository

import com.fetch.tv.core.network.NetworkResult
import com.fetch.tv.domain.model.CatalogRow
import kotlinx.coroutines.flow.Flow

interface CatalogRepository {
    fun getCatalog(
        addonBaseUrl: String,
        addonId: String,
        addonName: String,
        catalogId: String,
        catalogName: String,
        type: String,
        skip: Int = 0,
        skipStep: Int = 100,
        extraArgs: Map<String, String> = emptyMap(),
        supportsSkip: Boolean = false,
        posterScreen: com.fetch.tv.core.poster.CustomPosterScreen = com.fetch.tv.core.poster.CustomPosterScreen.HOME,
        /** Emit the HTTP-cached copy first, however stale, then the network result. */
        cachedFirst: Boolean = false
    ): Flow<NetworkResult<CatalogRow>>
}
