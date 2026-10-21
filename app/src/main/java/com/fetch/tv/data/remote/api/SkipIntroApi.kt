package com.fetch.tv.data.remote.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

// --- IntroDB API ---

interface IntroDbApi {
    /** [url] is the full `segments` endpoint on the configured server. */
    @GET
    suspend fun getSegments(
        @Url url: String,
        @Query("imdb_id") imdbId: String,
        @Query("season") season: Int? = null,
        @Query("episode") episode: Int? = null,
        @Query("is_movie") isMovie: Boolean? = null
    ): Response<IntroDbSegmentsResponse>

    /** [url] is the full `submit` endpoint on the configured server. */
    @POST
    suspend fun submit(
        @Url url: String,
        @Header("X-API-Key") apiKey: String,
        @Body body: IntroDbSubmission
    ): Response<Unit>
}

@JsonClass(generateAdapter = true)
data class IntroDbSubmission(
    @Json(name = "segment_type") val segmentType: String,
    @Json(name = "imdb_id") val imdbId: String,
    @Json(name = "is_movie") val isMovie: Boolean? = null,
    @Json(name = "season") val season: Int? = null,
    @Json(name = "episode") val episode: Int? = null,
    @Json(name = "start_sec") val startSec: Double,
    @Json(name = "end_sec") val endSec: Double
)

@JsonClass(generateAdapter = true)
data class IntroDbSegmentsResponse(
    @Json(name = "imdb_id") val imdbId: String? = null,
    @Json(name = "media_type") val mediaType: String? = null,
    @Json(name = "is_movie") val isMovie: Boolean? = null,
    @Json(name = "season") val season: Int? = null,
    @Json(name = "episode") val episode: Int? = null,
    @Json(name = "intro") val intro: IntroDbSegment? = null,
    @Json(name = "recap") val recap: IntroDbSegment? = null,
    @Json(name = "outro") val outro: IntroDbSegment? = null,
    @Json(name = "post_credits") val postCredits: IntroDbSegment? = null
)

@JsonClass(generateAdapter = true)
data class IntroDbSegment(
    @Json(name = "start_sec") val startSec: Double? = null,
    @Json(name = "end_sec") val endSec: Double? = null,
    @Json(name = "start_ms") val startMs: Long? = null,
    @Json(name = "end_ms") val endMs: Long? = null,
    @Json(name = "confidence") val confidence: Double? = null,
    @Json(name = "submission_count") val submissionCount: Int? = null,
    @Json(name = "updated_at") val updatedAt: String? = null
)

// --- AniSkip API ---

interface AniSkipApi {
    @GET("skip-times/{malId}/{episode}")
    suspend fun getSkipTimes(
        @Path("malId") malId: String,
        @Path("episode") episode: Int,
        @Query("types[]") types: List<String>,
        @Query("episodeLength") episodeLength: Int = 0
    ): Response<AniSkipResponse>

    /** Up- or down-votes one submitted segment; a downvote is AniSkip's "wrong marker" report. */
    @POST("skip-times/{skipId}/votes")
    suspend fun vote(
        @Path("skipId") skipId: String,
        @Body body: AniSkipVoteRequest
    ): Response<Unit>
}

@JsonClass(generateAdapter = true)
data class AniSkipVoteRequest(
    @Json(name = "voteType") val voteType: String
)

@JsonClass(generateAdapter = true)
data class AniSkipResponse(
    @Json(name = "found") val found: Boolean = false,
    @Json(name = "results") val results: List<AniSkipResult>? = null
)

@JsonClass(generateAdapter = true)
data class AniSkipResult(
    @Json(name = "interval") val interval: AniSkipInterval,
    @Json(name = "skipType") val skipType: String,
    @Json(name = "skipId") val skipId: String? = null
)

@JsonClass(generateAdapter = true)
data class AniSkipInterval(
    @Json(name = "startTime") val startTime: Double,
    @Json(name = "endTime") val endTime: Double
)

// --- Anime-Skip API (GraphQL) ---

interface AnimeSkipApi {
    @POST("graphql")
    suspend fun query(
        @Header("X-Client-ID") clientId: String,
        @Body body: AnimeSkipRequest
    ): Response<AnimeSkipResponse>
}

@JsonClass(generateAdapter = true)
data class AnimeSkipRequest(
    @Json(name = "query") val query: String,
    @Json(name = "variables") val variables: Map<String, String> = emptyMap()
)

@JsonClass(generateAdapter = true)
data class AnimeSkipResponse(
    @Json(name = "data") val data: AnimeSkipData? = null
)

@JsonClass(generateAdapter = true)
data class AnimeSkipData(
    @Json(name = "findShowsByExternalId") val findShowsByExternalId: List<AnimeSkipShow>? = null,
    @Json(name = "findEpisodesByShowId") val findEpisodesByShowId: List<AnimeSkipEpisode>? = null
)

@JsonClass(generateAdapter = true)
data class AnimeSkipShow(
    @Json(name = "id") val id: String
)

@JsonClass(generateAdapter = true)
data class AnimeSkipEpisode(
    @Json(name = "season") val season: String? = null,
    @Json(name = "number") val number: String? = null,
    @Json(name = "timestamps") val timestamps: List<AnimeSkipTimestamp>? = null
)

@JsonClass(generateAdapter = true)
data class AnimeSkipTimestamp(
    @Json(name = "at") val at: Double,
    @Json(name = "type") val type: AnimeSkipTimestampType
)

@JsonClass(generateAdapter = true)
data class AnimeSkipTimestampType(
    @Json(name = "name") val name: String
)
