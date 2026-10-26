package com.fetch.tv.data.website

import android.content.Context
import com.fetch.tv.R
import com.fetch.tv.core.network.NetworkResult
import com.fetch.tv.domain.model.CatalogRow
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.FetchListCollectionSource
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.PosterShape
import com.fetch.tv.domain.model.TmdbCollectionMediaType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fills a collection row from one of the active profile's lists on the Fetch website, the
 * source that lets a list made on the website become a row without Trakt. The whole list is
 * one page; lists are personal-sized.
 */
@Singleton
class FetchListSourceResolver @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val client: FetchWebsiteClient,
    private val sync: FetchWebsiteSyncService
) {
    fun resolve(source: FetchListCollectionSource): Flow<NetworkResult<CatalogRow>> = flow {
        emit(NetworkResult.Loading)
        val profile = sync.activeWebsiteProfile()
        if (profile == null) {
            emit(NetworkResult.Error(appContext.getString(R.string.website_list_source_not_signed_in)))
            return@flow
        }
        val types = when (source.mediaType) {
            TmdbCollectionMediaType.MOVIE -> listOf("movie")
            TmdbCollectionMediaType.TV -> listOf("series")
            null -> listOf("movie", "series")
        }
        try {
            val items = types.flatMap { type ->
                client.listMetas(profile.id, sync.profileToken(profile.id), source.listId, type).map { it.toPreview() }
            }
            emit(NetworkResult.Success(source.toRow(items)))
        } catch (error: CancellationException) {
            throw error
        } catch (error: FetchWebsiteException) {
            emit(
                NetworkResult.Error(
                    if (error.status == 404) appContext.getString(R.string.website_list_source_missing) else error.message.orEmpty(),
                    error.status
                )
            )
        } catch (error: Exception) {
            emit(NetworkResult.Error(error.message ?: appContext.getString(R.string.website_list_source_failed)))
        }
    }

    private fun FetchListCollectionSource.toRow(items: List<MetaPreview>): CatalogRow {
        val rawType = if (mediaType == TmdbCollectionMediaType.TV) "series" else "movie"
        return CatalogRow(
            addonId = "fetch",
            addonName = appContext.getString(R.string.website_name),
            addonBaseUrl = "",
            catalogId = "fetch_$listId",
            catalogName = title,
            type = ContentType.fromString(rawType),
            rawType = rawType,
            items = items,
            isLoading = false,
            hasMore = false,
            currentPage = 1,
            supportsSkip = false
        )
    }

    private fun WebsiteMeta.toPreview(): MetaPreview {
        val rawType = if (type == "series") "series" else "movie"
        return MetaPreview(
            id = id,
            type = ContentType.fromString(rawType),
            rawType = rawType,
            name = name,
            poster = poster,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = description,
            releaseInfo = year?.toString(),
            imdbRating = rating?.toFloat(),
            genres = genres,
            imdbId = id.takeIf { it.startsWith("tt") }
        )
    }
}
