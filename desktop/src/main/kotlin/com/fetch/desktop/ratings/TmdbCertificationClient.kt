package com.fetch.desktop.ratings

import com.fetch.tv.core.parental.ContentCertifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** A title's age rating as TMDB lists it, e.g. `PG-13` in `US`. */
@Serializable
data class Certification(val rating: String, val country: String)

/**
 * Looks up a title's certification on TMDB by IMDb id, the way the TV app's
 * `TmdbCertificationSource` + `ContentCertifications.pickLevel` do:
 * `find/{imdb}` → first movie/tv result → movie `release_dates` or tv `content_ratings` →
 * per-country values → first country (in [ContentCertifications.PREFERRED_COUNTRIES] order, then
 * the rest in TMDB's order) with a value that maps to a level; within it, the strictest.
 */
class TmdbCertificationClient(
    private val http: OkHttpClient,
    private val apiKey: () -> String?,
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
) {
    /** False when no TMDB credential is configured; lookups then return null without a request. */
    val isConfigured: Boolean get() = !apiKey().isNullOrBlank()

    /**
     * The certification for [imdbId] (`tt…`; a `tt…:season:episode` id uses its series), where
     * [type] is `movie` or `series` (`tv`/`show` also mean series). Null when there is no key,
     * the id isn't an IMDb id, TMDB doesn't know the title (404 or no find result), or it lists no
     * usable rating. Throws [IOException] on network errors and other non-success responses, so
     * callers can tell a failure (retry later) from a known-unrated title.
     */
    suspend fun certification(imdbId: String, type: String): Certification? {
        val key = apiKey()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val imdb = imdbId.trim().substringBefore(':').takeIf { it.startsWith("tt") } ?: return null
        val tv = isSeries(type)
        return withContext(Dispatchers.IO) {
            val find = get(key, listOf("find", imdb), "external_source" to "imdb_id")
                ?.let { json.decodeFromString(FindResponse.serializer(), it) }
                ?: return@withContext null
            val tmdbId = (if (tv) find.tvResults else find.movieResults).firstOrNull()?.id
                ?: return@withContext null
            val byCountry: Map<String, List<String>> = if (tv) {
                val body = get(key, listOf("tv", tmdbId.toString(), "content_ratings")) ?: return@withContext null
                json.decodeFromString(ContentRatingsResponse.serializer(), body).results
                    .mapNotNull { item -> item.country?.let { it to listOfNotNull(item.rating) } }
                    .toMap()
            } else {
                val body = get(key, listOf("movie", tmdbId.toString(), "release_dates")) ?: return@withContext null
                json.decodeFromString(ReleaseDatesResponse.serializer(), body).results
                    .mapNotNull { country ->
                        country.country?.let { code -> code to country.releaseDates.mapNotNull { it.certification } }
                    }
                    .toMap()
            }
            pick(byCountry)
        }
    }

    /** GETs a TMDB path; null on 404, throws on other failures. */
    private fun get(key: String, segments: List<String>, vararg query: Pair<String, String>): String? {
        val url = baseUrl.newBuilder().apply {
            segments.forEach(::addPathSegment)
            query.forEach { (name, value) -> addQueryParameter(name, value) }
            if (!TmdbAuth.isBearerToken(key)) addQueryParameter("api_key", key)
        }.build()
        val request = Request.Builder().url(url).header("Accept", "application/json").apply {
            if (TmdbAuth.isBearerToken(key)) header("Authorization", "Bearer $key")
        }.build()
        http.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw IOException("TMDB ${segments.first()} returned HTTP ${response.code}")
            return response.body?.string() ?: throw IOException("TMDB ${segments.first()} returned no body")
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.themoviedb.org/3/"

        internal val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

        internal fun isSeries(type: String): Boolean =
            when (type.trim().lowercase()) {
                "series", "tv", "show", "tvshow" -> true
                else -> false
            }

        /**
         * [ContentCertifications.pickLevel], keeping the label: preferred countries first, then the
         * rest in order; the first country with a value that maps to a level wins, and within it
         * the strictest value (a title can carry several, e.g. theatrical and unrated cut).
         */
        fun pick(byCountry: Map<String, List<String>>): Certification? {
            val normalized = LinkedHashMap<String, List<String>>()
            byCountry.forEach { (country, values) -> normalized[country.uppercase()] = values }
            val preferred = ContentCertifications.PREFERRED_COUNTRIES
            val ordered = preferred.mapNotNull { country -> normalized[country]?.let { country to it } } +
                normalized.filterKeys { it !in preferred }.toList()
            for ((country, values) in ordered) {
                val best = values
                    .mapNotNull { value -> ContentCertifications.levelOf(value, country)?.let { value.trim() to it } }
                    .maxByOrNull { it.second.rank }
                if (best != null) return Certification(best.first, country)
            }
            return null
        }
    }
}

@Serializable
private data class FindResponse(
    @SerialName("movie_results") val movieResults: List<FindResult> = emptyList(),
    @SerialName("tv_results") val tvResults: List<FindResult> = emptyList(),
)

@Serializable
private data class FindResult(val id: Int)

@Serializable
private data class ReleaseDatesResponse(val results: List<ReleaseDatesCountry> = emptyList())

@Serializable
private data class ReleaseDatesCountry(
    @SerialName("iso_3166_1") val country: String? = null,
    @SerialName("release_dates") val releaseDates: List<ReleaseDate> = emptyList(),
)

@Serializable
private data class ReleaseDate(val certification: String? = null)

@Serializable
private data class ContentRatingsResponse(val results: List<ContentRating> = emptyList())

@Serializable
private data class ContentRating(
    @SerialName("iso_3166_1") val country: String? = null,
    val rating: String? = null,
)
