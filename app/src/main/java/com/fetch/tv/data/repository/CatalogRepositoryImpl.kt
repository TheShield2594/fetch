package com.fetch.tv.data.repository

import android.content.Context
import android.util.Log
import com.fetch.tv.core.network.NetworkResult
import com.fetch.tv.core.network.safeApiCall
import com.fetch.tv.core.poster.withCustomPosterUrls
import com.fetch.tv.data.mapper.toDomainOrNull
import com.fetch.tv.data.mediaserver.ServerCatalog
import com.fetch.tv.data.mediaserver.messageRes
import com.fetch.tv.data.mediaserver.serverFailure
import com.fetch.tv.data.local.LayoutPreferenceDataStore
import com.fetch.tv.data.remote.api.AddonApi
import com.fetch.tv.domain.model.CatalogRow
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.FilterRowSource
import com.fetch.tv.domain.model.filterRowIdOf
import com.fetch.tv.data.local.FilterRowsDataStore
import com.fetch.tv.R
import com.fetch.tv.domain.repository.CatalogRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CatalogRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: AddonApi,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val serverCatalog: ServerCatalog,
    private val filterRowsDataStore: FilterRowsDataStore
) : CatalogRepository {
    companion object {
        private const val TAG = "CatalogRepository"
        private const val CACHED_CATALOG_CACHE_CONTROL = "only-if-cached, max-stale=604800"
    }

    override fun getCatalog(
        addonBaseUrl: String,
        addonId: String,
        addonName: String,
        catalogId: String,
        catalogName: String,
        type: String,
        skip: Int,
        skipStep: Int,
        extraArgs: Map<String, String>,
        supportsSkip: Boolean,
        posterScreen: com.fetch.tv.core.poster.CustomPosterScreen,
        cachedFirst: Boolean
    ): Flow<NetworkResult<CatalogRow>> = flow {
        emit(NetworkResult.Loading)

        if (ServerCatalog.isServerAddon(addonBaseUrl)) {
            val row = try {
                serverCatalog.catalog(addonBaseUrl, addonId, addonName, catalogId, catalogName, type, skip, extraArgs)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                emit(NetworkResult.Error(context.getString(error.serverFailure().messageRes())))
                return@flow
            }
            emit(NetworkResult.Success(row))
            return@flow
        }

        // A filter row over an addon catalog asks that catalog for its genre, and keeps its own
        // id on the row so paging comes back here.
        val filterSource = filterRowIdOf(catalogId)?.let { rowId ->
            val row = filterRowsDataStore.row(rowId)
            val source = row?.source as? FilterRowSource.AddonCatalog
            if (row == null || source == null) {
                emit(NetworkResult.Error(context.getString(R.string.filter_row_source_missing)))
                return@flow
            }
            source to row.filters.genre
        }
        val requestCatalogId = filterSource?.first?.catalogId ?: catalogId
        val requestExtraArgs = filterSource?.second?.let { genre -> extraArgs + ("genre" to genre) } ?: extraArgs
        val url = buildCatalogUrl(addonBaseUrl, filterSource?.first?.type ?: type, requestCatalogId, skip, requestExtraArgs)
        Log.d(
            TAG,
            "Fetching catalog addonId=$addonId addonName=$addonName type=$type catalogId=$catalogId skip=$skip skipStep=$skipStep supportsSkip=$supportsSkip url=$url"
        )

        suspend fun toCatalogRow(response: com.fetch.tv.data.remote.dto.CatalogResponseDto): CatalogRow {
            val rawItemCount = response.metas.size
            val posterPattern = layoutPreferenceDataStore.customPosterUrlPattern
                .let { flow -> flow.first() }
            val enabledScreens = layoutPreferenceDataStore.customPosterEnabledScreens.first()

            val items = response.metas
                .mapNotNull { it?.toDomainOrNull(type, addonBaseUrl) }
                .distinctBy { it.id }
                .withCustomPosterUrls(
                    com.fetch.tv.core.poster.patternForScreen(posterPattern, posterScreen, enabledScreens)
                )
            return CatalogRow(
                addonId = addonId,
                addonName = addonName,
                addonBaseUrl = addonBaseUrl,
                catalogId = catalogId,
                catalogName = catalogName,
                type = ContentType.fromString(type),
                rawType = type,
                items = items,
                isLoading = false,
                hasMore = supportsSkip && rawItemCount > 0,
                currentPage = if (skipStep > 0) skip / skipStep else 0,
                supportsSkip = supportsSkip,
                skipStep = skipStep,
                nextSkip = if (supportsSkip && rawItemCount > 0) skip + rawItemCount else skip,
                extraArgs = extraArgs
            )
        }

        // Show the last copy straight from disk so the row isn't empty while the network answers.
        // Only addons whose responses are cacheable land in the HTTP cache; the rest skip this.
        val cachedRow = if (cachedFirst) {
            val cached = safeApiCall(context) { api.getCachedCatalog(url, CACHED_CATALOG_CACHE_CONTROL) }
            (cached as? NetworkResult.Success)?.data
                ?.takeIf { it.metas.isNotEmpty() }
                ?.let { toCatalogRow(it) }
                ?.also { row ->
                    Log.d(TAG, "Catalog cache hit addonId=$addonId type=$type catalogId=$catalogId items=${row.items.size}")
                    emit(NetworkResult.Success(row))
                }
        } else {
            null
        }

        when (val result = safeApiCall(context) { api.getCatalog(url) }) {
            is NetworkResult.Success -> {
                val catalogRow = toCatalogRow(result.data)
                Log.d(
                    TAG,
                    "Catalog fetch success addonId=$addonId type=$type catalogId=$catalogId items=${catalogRow.items.size}"
                )
                // A response still fresh in the cache comes back identical; don't make Home redo the row.
                if (catalogRow != cachedRow) {
                    emit(NetworkResult.Success(catalogRow))
                }
            }
            is NetworkResult.Error -> {
                Log.w(
                    TAG,
                    "Catalog fetch failed addonId=$addonId type=$type catalogId=$catalogId code=${result.code} message=${result.message} url=$url"
                )
                // Offline or failing: the cached row already on screen is better than an error.
                if (cachedRow == null) {
                    emit(result)
                }
            }
            NetworkResult.Loading -> { /* Already emitted */ }
        }
    }

    private fun buildCatalogUrl(
        baseUrl: String,
        type: String,
        catalogId: String,
        skip: Int,
        extraArgs: Map<String, String>
    ): String {
        val trimmedBase = baseUrl.trimEnd('/')
        val queryStart = trimmedBase.indexOf('?')
        val basePath = if (queryStart >= 0) trimmedBase.substring(0, queryStart).trimEnd('/') else trimmedBase
        val baseQuery = if (queryStart >= 0) trimmedBase.substring(queryStart) else ""

        val catalogPath = if (extraArgs.isEmpty()) {
            if (skip > 0) {
                "$basePath/catalog/$type/$catalogId/skip=$skip.json"
            } else {
                "$basePath/catalog/$type/$catalogId.json"
            }
        } else {
            val allArgs = LinkedHashMap<String, String>()
            allArgs.putAll(extraArgs)

            if (!allArgs.containsKey("skip") && skip > 0) {
                allArgs["skip"] = skip.toString()
            }

            val encodedArgs = allArgs.entries.joinToString("&") { (key, value) ->
                "${encodeArg(key)}=${encodeArg(value)}"
            }

            "$basePath/catalog/$type/$catalogId/$encodedArgs.json"
        }

        return catalogPath + baseQuery
    }

    private fun encodeArg(value: String): String {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}
