package com.fetch.tv.data.local

import android.content.Context
import com.fetch.tv.R
import com.fetch.tv.core.network.NetworkResult
import com.fetch.tv.domain.model.CatalogRow
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.LocalListCollectionSource
import com.fetch.tv.domain.model.TmdbCollectionMediaType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fills a collection row from one of the active profile's local lists, so a list made on the
 * TV with no account can sit on Home like any other custom row. Newest additions first.
 */
@Singleton
class LocalListSourceResolver @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val store: LocalListsDataStore
) {
    fun resolve(source: LocalListCollectionSource): Flow<NetworkResult<CatalogRow>> = flow {
        emit(NetworkResult.Loading)
        val list = store.getLists().firstOrNull { it.id == source.listId }
        if (list == null) {
            emit(NetworkResult.Error(appContext.getString(R.string.local_list_source_missing), 404))
            return@flow
        }
        val items = list.items
            .filter { item -> source.mediaType == null || item.isMediaType(source.mediaType) }
            .sortedByDescending { it.addedAt }
            .map { it.toMetaPreview() }
        val rawType = if (source.mediaType == TmdbCollectionMediaType.TV) "series" else "movie"
        emit(
            NetworkResult.Success(
                CatalogRow(
                    addonId = "local",
                    addonName = appContext.getString(R.string.trakt_library_source_fetch),
                    addonBaseUrl = "",
                    catalogId = "local_${source.listId}",
                    catalogName = source.title,
                    type = ContentType.fromString(rawType),
                    rawType = rawType,
                    items = items,
                    isLoading = false,
                    hasMore = false,
                    currentPage = 1,
                    supportsSkip = false
                )
            )
        )
    }

    private fun com.fetch.tv.domain.model.SavedLibraryItem.isMediaType(mediaType: TmdbCollectionMediaType): Boolean {
        val normalized = type.trim().lowercase()
        return when (mediaType) {
            TmdbCollectionMediaType.MOVIE -> normalized == "movie"
            TmdbCollectionMediaType.TV -> normalized in setOf("series", "tv", "show", "anime")
        }
    }
}
