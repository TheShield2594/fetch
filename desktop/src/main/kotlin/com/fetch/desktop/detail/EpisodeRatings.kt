package com.fetch.desktop.detail

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-episode IMDb ratings for the ratings grid, as the TV's `ImdbEpisodeRatingsRepository`: the
 * IMDb service by IMDb id first, then SeriesGraph by TMDB id, both answering
 * `api/shows/{id}/season-ratings`. Their addresses come with the build
 * (`IMDB_TAPFRAME_API_BASE_URL`, `IMDB_RATINGS_API_BASE_URL`); without them there's no grid.
 * Answers are kept 30 minutes.
 */
class EpisodeRatingsClient(
    private val http: OkHttpClient,
    private val imdbBaseUrl: String? = EpisodeRatingsEndpoints.imdb(),
    private val seriesGraphBaseUrl: String? = EpisodeRatingsEndpoints.seriesGraph(),
    private val now: () -> Long = System::currentTimeMillis
) {
    private data class Entry(val ratings: Map<Pair<Int, Int>, Double>, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    val available: Boolean get() = !imdbBaseUrl.isNullOrBlank() || !seriesGraphBaseUrl.isNullOrBlank()

    /** Ratings by (season, episode); empty when neither service knows the show. */
    suspend fun ratings(imdbId: String?, tmdbId: Int?): Map<Pair<Int, Int>, Double> {
        val imdb = imdbId?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) }?.substringBefore(':')
        val tmdb = tmdbId?.takeIf { it > 0 }
        if (imdb == null && tmdb == null) return emptyMap()
        val key = if (imdb != null) "imdb:$imdb" else "tmdb:$tmdb"
        cache[key]?.let { if (it.expiresAt > now()) return it.ratings else cache.remove(key) }
        var result = emptyMap<Pair<Int, Int>, Double>()
        if (imdb != null && !imdbBaseUrl.isNullOrBlank()) result = fetch(imdbBaseUrl, imdb)
        if (result.isEmpty() && tmdb != null && !seriesGraphBaseUrl.isNullOrBlank()) result = fetch(seriesGraphBaseUrl, tmdb.toString())
        cache[key] = Entry(result, now() + TTL_MS)
        return result
    }

    private suspend fun fetch(base: String, id: String): Map<Pair<Int, Int>, Double> = try {
        val url = base.trimEnd('/') + "/api/shows/$id/season-ratings"
        val body = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(url).get().build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
        }
        if (body == null) emptyMap() else parse(body)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyMap()
    }

    companion object {
        const val TTL_MS = 30L * 60L * 1000L
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        /** The services' answer: a list of seasons, each with its episodes' `vote_average`. */
        fun parse(body: String): Map<Pair<Int, Int>, Double> =
            runCatching { json.decodeFromString<List<SeasonDto>>(body) }.getOrDefault(emptyList())
                .flatMap { it.episodes.orEmpty() }
                .mapNotNull { e ->
                    val s = e.season ?: return@mapNotNull null
                    val n = e.episode ?: return@mapNotNull null
                    val v = e.voteAverage ?: return@mapNotNull null
                    (s to n) to v
                }.toMap()
    }

    @Serializable
    private data class EpisodeDto(
        @SerialName("season_number") val season: Int? = null,
        @SerialName("episode_number") val episode: Int? = null,
        @SerialName("vote_average") val voteAverage: Double? = null
    )

    @Serializable
    private data class SeasonDto(val episodes: List<EpisodeDto>? = null)
}

/** The ratings services' addresses from the build, else the environment. */
object EpisodeRatingsEndpoints {
    private val props by lazy {
        Properties().apply { runCatching { EpisodeRatingsEndpoints::class.java.getResourceAsStream("/fetch-desktop.properties")?.use(::load) } }
    }

    private fun value(prop: String, env: String): String? =
        (props.getProperty(prop)?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("$") } ?: System.getenv(env)?.trim())?.takeIf { it.startsWith("http") }

    fun imdb(): String? = value("imdbTapframeApiBaseUrl", "IMDB_TAPFRAME_API_BASE_URL")
    fun seriesGraph(): String? = value("imdbRatingsApiBaseUrl", "IMDB_RATINGS_API_BASE_URL")
}

/** A rating chip's colour, as the TV grid's `episodeRatingChipColor` (ARGB). */
fun episodeRatingColor(value: Double): Long = when {
    value >= 9.7 -> 0xFF1DA1F3
    value >= 9.0 -> 0xFF186A3B
    value >= 8.0 -> 0xFF28B463
    value >= 7.5 -> 0xFFF4D03F
    value >= 7.0 -> 0xFFF39C12
    value >= 6.0 -> 0xFFE85445
    else -> 0xFF633974
}

/** Dark text on the light chips, white on the dark ones, as on TV. */
fun episodeRatingTextColor(value: Double): Long = when {
    value >= 9.7 -> 0xFF1D1D1F
    value >= 9.0 -> 0xFFFFFFFF
    value >= 6.0 -> 0xFF1D1D1F
    else -> 0xFFFFFFFF
}
