package com.fetch.tv.core.recommendations

import android.content.Context
import android.util.Log
import com.fetch.tv.BuildConfig
import com.fetch.tv.LocaleCache
import com.fetch.tv.R
import com.fetch.tv.core.tmdb.TmdbService
import com.fetch.tv.core.util.withAppLocale
import com.fetch.tv.data.remote.api.TmdbApi
import com.fetch.tv.data.remote.api.TmdbCollectionPart
import com.fetch.tv.data.remote.api.TmdbCollectionResponse
import com.fetch.tv.data.remote.api.TmdbCreditsResponse
import com.fetch.tv.data.remote.api.TmdbDetailsResponse
import com.fetch.tv.data.remote.api.TmdbRecommendationResult
import com.fetch.tv.domain.model.CatalogRow
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.PosterShape
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

const val BECAUSE_YOU_WATCHED_ADDON_ID = "fetch_because_you_watched"

private const val TAG = "BecauseYouWatched"
private const val MAX_CANDIDATES = 20
private const val MAX_ITEMS = 15
private const val MIN_ITEMS = 3
private const val CANDIDATE_REQUEST_CONCURRENCY = 4
private val MOVIE_WRITER_JOBS = setOf("screenplay", "writer")

/**
 * Builds "Because you watched {title}" rows from TMDB recommendations.
 *
 * Every card carries a reason computed from TMDB credits or collections. A candidate that shares
 * nothing with the seed is dropped.
 */
@Singleton
class BecauseYouWatchedService internal constructor(
    private val appContext: Context,
    private val tmdbApi: TmdbApi,
    private val tmdbService: TmdbService,
    private val apiKey: String,
    private val ioDispatcher: CoroutineDispatcher
) {
    @Inject
    constructor(
        @ApplicationContext appContext: Context,
        tmdbApi: TmdbApi,
        tmdbService: TmdbService
    ) : this(appContext, tmdbApi, tmdbService, BuildConfig.TMDB_API_KEY, Dispatchers.IO)

    private val rowCache = ConcurrentHashMap<String, CachedRow>()
    private val creditsCache = ConcurrentHashMap<String, TitleCredits>()

    private data class CachedRow(val row: CatalogRow?)

    private data class ReasonedPreview(val preview: MetaPreview, val reason: BecauseYouWatchedReason)

    val isAvailable: Boolean
        get() = apiKey.isNotBlank()

    suspend fun buildRow(seed: BecauseYouWatchedSeed, language: String): CatalogRow? = withContext(ioDispatcher) {
        if (!isAvailable) return@withContext null
        val isSeries = isSeriesType(seed.contentType)
        val tmdbType = if (isSeries) "tv" else "movie"
        val cacheKey = "${seed.contentId.lowercase()}:$tmdbType:$language:${LocaleCache.localeTag}"
        rowCache[cacheKey]?.let { return@withContext it.row }

        try {
            val tmdbId = tmdbService.ensureTmdbId(seed.contentId, tmdbType)?.toIntOrNull()
                ?: return@withContext null
            val result = resolveRow(seed, tmdbId, isSeries, language)
            if (result.complete) {
                rowCache[cacheKey] = CachedRow(result.row)
            }
            result.row
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to build row for ${seed.contentId}: ${e.message}")
            null
        }
    }

    private data class RowResult(val row: CatalogRow?, val complete: Boolean)

    private suspend fun resolveRow(
        seed: BecauseYouWatchedSeed,
        tmdbId: Int,
        isSeries: Boolean,
        language: String
    ): RowResult {
        val seedDetails = fetchDetails(tmdbId, isSeries, language) ?: return RowResult(null, complete = false)
        val seedCredits = fetchCredits(tmdbId, isSeries, seedDetails) ?: return RowResult(null, complete = false)

        val complete = AtomicBoolean(true)
        val collectionId = if (isSeries) null else seedDetails.belongsToCollection?.id
        val collection = collectionId?.let { fetchCollection(it, language) }
        if (collectionId != null && collection == null) complete.set(false)
        val collectionName = collection?.name?.takeIf { it.isNotBlank() }
        val collectionParts = collection?.parts.orEmpty()
        val collectionPartIds = collectionParts.mapTo(HashSet()) { it.id }

        val recommendationsResponse = if (isSeries) {
            tmdbApi.getTvRecommendations(tmdbId, apiKey, language)
        } else {
            tmdbApi.getMovieRecommendations(tmdbId, apiKey, language)
        }
        val recommendationsBody = recommendationsResponse.body()
        if (!recommendationsResponse.isSuccessful || recommendationsBody == null) {
            return RowResult(null, complete = false)
        }
        val candidates = recommendationsBody.results.orEmpty()
            .filter { it.id > 0 && it.id != tmdbId && it.matchesType(isSeries) }
            .distinctBy { it.id }
            .take(MAX_CANDIDATES)

        val today = LocalDate.now().toString()
        val collectionItems = if (collectionName == null) {
            emptyList()
        } else {
            collectionParts
                .filter { part -> part.id != tmdbId && part.releaseDate.orEmpty().let { it.isNotBlank() && it <= today } }
                .sortedBy { it.releaseDate }
                .mapNotNull { part ->
                    part.toPreview()?.let {
                        ReasonedPreview(it, BecauseYouWatchedReason.SameCollection(collectionName))
                    }
                }
        }

        val semaphore = Semaphore(CANDIDATE_REQUEST_CONCURRENCY)
        val recommendationItems = coroutineScope {
            candidates
                .filter { it.id !in collectionPartIds }
                .map { candidate ->
                    async {
                        semaphore.withPermit {
                            val details = if (isSeries) {
                                fetchDetails(candidate.id, isSeries = true, language = language)
                            } else {
                                null
                            }
                            val credits = if (isSeries && details == null) {
                                null
                            } else {
                                fetchCredits(candidate.id, isSeries, details)
                            }
                            if (credits == null) {
                                complete.set(false)
                                return@withPermit null
                            }
                            val reason = becauseYouWatchedReason(
                                seed = seedCredits,
                                candidate = credits,
                                isSeries = isSeries
                            ) ?: return@withPermit null
                            candidate.toPreview(isSeries)?.let { ReasonedPreview(it, reason) }
                        }
                    }
                }
                .awaitAll()
                .filterNotNull()
        }

        val items = (collectionItems + recommendationItems)
            .distinctBy { it.preview.id }
            .take(MAX_ITEMS)
            .map { it.preview.copy(recommendationReason = formatReason(it.reason)) }
        if (items.size < MIN_ITEMS) return RowResult(null, complete.get())

        val rawType = if (isSeries) "series" else "movie"
        val row = CatalogRow(
            addonId = BECAUSE_YOU_WATCHED_ADDON_ID,
            addonName = "TMDB",
            addonBaseUrl = "",
            catalogId = "because_you_watched_${rawType}_$tmdbId",
            catalogName = localizedContext().getString(R.string.because_you_watched_row_title, seed.title),
            type = if (isSeries) ContentType.SERIES else ContentType.MOVIE,
            rawType = rawType,
            items = items,
            isLoading = false,
            hasMore = false,
            supportsSkip = false
        )
        return RowResult(row, complete.get())
    }

    private suspend fun fetchDetails(tmdbId: Int, isSeries: Boolean, language: String): TmdbDetailsResponse? {
        return request("details ${if (isSeries) "tv" else "movie"} $tmdbId") {
            if (isSeries) tmdbApi.getTvDetails(tmdbId, apiKey, language) else tmdbApi.getMovieDetails(tmdbId, apiKey, language)
        }
    }

    private suspend fun fetchCollection(collectionId: Int, language: String): TmdbCollectionResponse? {
        return request("collection $collectionId") {
            tmdbApi.getCollectionDetails(collectionId, apiKey, language)
        }
    }

    /** Credits for a title. A show's creators come from [tvDetails], so pass it for series. */
    private suspend fun fetchCredits(tmdbId: Int, isSeries: Boolean, tvDetails: TmdbDetailsResponse?): TitleCredits? {
        val key = "${if (isSeries) "tv" else "movie"}:$tmdbId"
        creditsCache[key]?.let { return it }
        val response = request("credits $key") {
            if (isSeries) tmdbApi.getTvCredits(tmdbId, apiKey) else tmdbApi.getMovieCredits(tmdbId, apiKey)
        } ?: return null
        val credits = response.toTitleCredits(isSeries, tvDetails)
        creditsCache[key] = credits
        return credits
    }

    private suspend fun <T : Any> request(label: String, call: suspend () -> retrofit2.Response<T>): T? {
        return try {
            val response = call()
            val body = response.body()
            if (!response.isSuccessful || body == null) {
                Log.w(TAG, "TMDB $label returned HTTP ${response.code()}")
                null
            } else {
                body
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "TMDB $label failed: ${e.message}")
            null
        }
    }

    private fun formatReason(reason: BecauseYouWatchedReason): String {
        val context = localizedContext()
        return when (reason) {
            is BecauseYouWatchedReason.SameCollection ->
                context.getString(R.string.because_you_watched_reason_collection, reason.collectionName)
            is BecauseYouWatchedReason.SameDirector ->
                context.getString(R.string.because_you_watched_reason_director, reason.name)
            is BecauseYouWatchedReason.SameCreator ->
                context.getString(R.string.because_you_watched_reason_creator, reason.name)
            is BecauseYouWatchedReason.SameWriter ->
                context.getString(R.string.because_you_watched_reason_writer, reason.name)
            is BecauseYouWatchedReason.SameLeadCast ->
                context.getString(R.string.because_you_watched_reason_cast, reason.name)
        }
    }

    private fun localizedContext(): Context = appContext.withAppLocale()
}

private fun isSeriesType(type: String): Boolean = when (type.trim().lowercase()) {
    "series", "tv", "show" -> true
    else -> false
}

private fun TmdbRecommendationResult.matchesType(isSeries: Boolean): Boolean {
    return when (mediaType?.trim()?.lowercase()) {
        "tv" -> isSeries
        "movie" -> !isSeries
        else -> true
    }
}

internal fun TmdbCreditsResponse.toTitleCredits(isSeries: Boolean, tvDetails: TmdbDetailsResponse?): TitleCredits {
    val directors = if (isSeries) {
        tvDetails?.createdBy.orEmpty().mapNotNull { person ->
            val id = person.id ?: return@mapNotNull null
            person.name?.takeIf { it.isNotBlank() }?.let { CreditPerson(id, it) }
        }
    } else {
        crew.orEmpty()
            .filter { it.job.equals("Director", ignoreCase = true) }
            .mapNotNull { person -> person.id?.let { id -> person.name?.takeIf { it.isNotBlank() }?.let { CreditPerson(id, it) } } }
    }
    val writers = if (isSeries) {
        emptyList()
    } else {
        crew.orEmpty()
            .filter { it.job?.lowercase() in MOVIE_WRITER_JOBS }
            .mapNotNull { person -> person.id?.let { id -> person.name?.takeIf { it.isNotBlank() }?.let { CreditPerson(id, it) } } }
    }
    val cast = cast.orEmpty()
        .mapNotNull { person -> person.id?.let { id -> person.name?.takeIf { it.isNotBlank() }?.let { CreditPerson(id, it) } } }
    return TitleCredits(
        directors = directors.distinctBy { it.id },
        writers = writers.distinctBy { it.id },
        cast = cast.distinctBy { it.id }
    )
}

private fun TmdbRecommendationResult.toPreview(isSeries: Boolean): MetaPreview? {
    val title = title?.takeIf { it.isNotBlank() }
        ?: name?.takeIf { it.isNotBlank() }
        ?: originalTitle?.takeIf { it.isNotBlank() }
        ?: originalName?.takeIf { it.isNotBlank() }
        ?: return null
    val date = (if (isSeries) firstAirDate else releaseDate)?.takeIf { it.isNotBlank() }
    return MetaPreview(
        id = "tmdb:$id",
        type = if (isSeries) ContentType.SERIES else ContentType.MOVIE,
        rawType = if (isSeries) "series" else "movie",
        name = title,
        poster = tmdbImageUrl(posterPath, "w500") ?: tmdbImageUrl(backdropPath, "w780"),
        posterShape = PosterShape.POSTER,
        background = tmdbImageUrl(backdropPath, "w1280"),
        logo = null,
        description = overview?.takeIf { it.isNotBlank() },
        releaseInfo = date?.take(4),
        released = date,
        imdbRating = voteAverage?.toFloat(),
        voteCount = voteCount,
        genres = emptyList()
    )
}

private fun TmdbCollectionPart.toPreview(): MetaPreview? {
    val title = title?.takeIf { it.isNotBlank() }
        ?: originalTitle?.takeIf { it.isNotBlank() }
        ?: return null
    return MetaPreview(
        id = "tmdb:$id",
        type = ContentType.MOVIE,
        rawType = "movie",
        name = title,
        poster = tmdbImageUrl(posterPath, "w500") ?: tmdbImageUrl(backdropPath, "w780"),
        posterShape = PosterShape.POSTER,
        background = tmdbImageUrl(backdropPath, "w1280"),
        logo = null,
        description = overview?.takeIf { it.isNotBlank() },
        releaseInfo = releaseDate?.take(4),
        released = releaseDate?.takeIf { it.isNotBlank() },
        imdbRating = voteAverage?.toFloat(),
        voteCount = voteCount,
        genres = emptyList()
    )
}

private fun tmdbImageUrl(path: String?, size: String): String? {
    val clean = path?.takeIf { it.isNotBlank() } ?: return null
    return "https://image.tmdb.org/t/p/$size$clean"
}
