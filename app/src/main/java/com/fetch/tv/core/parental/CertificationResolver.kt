package com.fetch.tv.core.parental

import android.util.Log
import com.fetch.tv.core.tmdb.TmdbService
import com.fetch.tv.data.remote.api.TmdbApi
import com.fetch.tv.domain.model.ContentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CertificationResolver"
private const val MAX_CONCURRENT_LOOKUPS = 4
private const val MAX_CACHE_ENTRIES = 5_000
private const val FAILURE_RETRY_MS = 5 * 60_000L

/** What is known about a title when deciding whether a profile may see it. */
data class CertificationQuery(
    val contentId: String,
    val type: ContentType,
    val imdbId: String? = null,
    /** Rating the source already provided (addon, Trakt, media server), if any. */
    val knownCertification: String? = null
) {
    /** Base id used for caching: series episode ids share the series' rating. */
    val cacheKey: String
        get() = "${type.name}:${(imdbId ?: contentId).substringBefore(':').trim()}"
}

/** Looks up certifications by country for a title; null when the title can't be found. */
interface CertificationSource {
    suspend fun certificationsByCountry(query: CertificationQuery): Map<String, List<String>>?
}

class TmdbCertificationSource @Inject constructor(
    private val tmdbApi: TmdbApi,
    private val tmdbService: TmdbService
) : CertificationSource {
    override suspend fun certificationsByCountry(query: CertificationQuery): Map<String, List<String>>? {
        if (tmdbService.apiKey().isBlank()) return null
        val mediaType = if (query.type == ContentType.MOVIE) "movie" else "tv"
        val tmdbId = tmdbService.ensureTmdbId(query.contentId, mediaType, query.imdbId)?.toIntOrNull()
            ?: return null
        return if (mediaType == "movie") {
            tmdbApi.getMovieReleaseDates(tmdbId, tmdbService.apiKey()).body()?.results.orEmpty()
                .mapNotNull { country ->
                    val code = country.iso31661 ?: return@mapNotNull null
                    code to country.releaseDates.orEmpty().mapNotNull { it.certification }
                }
                .toMap()
        } else {
            tmdbApi.getTvContentRatings(tmdbId, tmdbService.apiKey()).body()?.results.orEmpty()
                .mapNotNull { item ->
                    val code = item.iso31661 ?: return@mapNotNull null
                    code to listOfNotNull(item.rating)
                }
                .toMap()
        }
    }
}

/**
 * Resolves certification levels for titles and caches them. Lookups run in the background;
 * [levelIfKnown] answers synchronously so list pipelines can filter without suspending, and
 * [revision] changes whenever new results land so those pipelines can recompute.
 */
@Singleton
class CertificationResolver internal constructor(
    private val source: CertificationSource,
    private val scope: CoroutineScope,
    private val clock: () -> Long
) {
    @Inject
    constructor(source: TmdbCertificationSource) :
        this(source, CoroutineScope(SupervisorJob() + Dispatchers.IO), System::currentTimeMillis)

    /** Cached result; [level] null means the title is unrated or could not be found. */
    data class Resolved(val level: CertificationLevel?)

    private val cache = ConcurrentHashMap<String, Resolved>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val semaphore = Semaphore(MAX_CONCURRENT_LOOKUPS)

    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    /**
     * Returns the cached result, or null when it isn't known yet. A rating supplied by the
     * source counts as known; otherwise an unknown title is queued for lookup.
     */
    fun levelIfKnown(query: CertificationQuery): Resolved? {
        ContentCertifications.levelOf(query.knownCertification)?.let { return Resolved(it) }
        cache[query.cacheKey]?.let { return it }
        enqueue(query)
        return null
    }

    suspend fun resolve(query: CertificationQuery): CertificationLevel? {
        ContentCertifications.levelOf(query.knownCertification)?.let { return it }
        cache[query.cacheKey]?.let { return it.level }
        return lookup(query).level
    }

    private fun enqueue(query: CertificationQuery) {
        val key = query.cacheKey
        // Failed lookups wait before retrying, so a pipeline that recomputes on every
        // revision can't turn one failure into a request loop.
        failedAt[key]?.let { if (clock() - it < FAILURE_RETRY_MS) return }
        if (!inFlight.add(key)) return
        scope.launch {
            try {
                lookup(query)
            } finally {
                inFlight.remove(key)
            }
        }
    }

    private suspend fun lookup(query: CertificationQuery): Resolved {
        val resolved = semaphore.withPermit {
            cache[query.cacheKey] ?: try {
                Resolved(source.certificationsByCountry(query)?.let(ContentCertifications::pickLevel))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Certification lookup failed for ${query.cacheKey}", error)
                // Not cached; retried after FAILURE_RETRY_MS, treated as unrated meanwhile.
                failedAt[query.cacheKey] = clock()
                return@withPermit null
            }.also { store(query.cacheKey, it) }
        }
        if (resolved == null) return Resolved(null)
        failedAt.remove(query.cacheKey)
        _revision.update { it + 1 }
        return resolved
    }

    private fun store(key: String, value: Resolved) {
        if (cache.size >= MAX_CACHE_ENTRIES) cache.clear()
        cache[key] = value
    }
}
