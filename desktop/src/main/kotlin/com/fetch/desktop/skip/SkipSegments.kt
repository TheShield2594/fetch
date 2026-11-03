package com.fetch.desktop.skip

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The parts of a title the TV app can skip: an episode's intro (anime openings too), recap and
 * credits (endings), and a movie's end credits and post-credits scene (IntroDB's `post_credits`).
 */
@Serializable
enum class SegmentType(val label: String) {
    INTRO("Intro"),
    RECAP("Recap"),
    OUTRO("Credits"),
    /** A movie's end credits; skipping them never skips a post-credits scene. */
    MOVIE_CREDITS("Credits"),
    /** A scene after a movie's credits: never skipped, it's what skipping the credits jumps to. */
    POST_CREDITS("Post-credits scene");

    companion object {
        /** The types Settings › Skip Segments can skip automatically (the TV's AutoSkipSegmentType). */
        val autoSkippable: List<SegmentType> = listOf(INTRO, RECAP, OUTRO, MOVIE_CREDITS)
    }
}

data class Segment(
    val type: SegmentType,
    val startSec: Double,
    val endSec: Double,
    /** What the Skip button and the on-screen note call it, when not [type]'s label (SponsorBlock's "sponsor"). */
    val label: String? = null,
    /** Decides auto-skip for this segment alone (SponsorBlock's per-category modes) instead of by [type]. */
    val autoSkip: Boolean? = null,
    /** Where the times came from: "introdb", "aniskip" or "animeskip". */
    val provider: String = "introdb",
    /** Where skipping it lands when not at its end: the start of a post-credits scene after a gap. */
    val skipToSec: Double? = null,
    /** The provider's id for it (AniSkip's skipId). */
    val sourceId: String? = null
) {
    val displayLabel: String get() = label ?: type.label.lowercase()

    /** Where skipping it seeks to. An open-ended Anime-Skip segment (to the end) is capped; mpv stops at the end. */
    val targetSec: Double get() = (skipToSec ?: endSec).coerceAtMost(MAX_SEEK_SEC)

    /** Whether it's skipped automatically when [enabled] are the auto-skipped types. A post-credits scene never is. */
    fun skipsAutomatically(enabled: Set<SegmentType>): Boolean =
        type != SegmentType.POST_CREDITS && (autoSkip ?: (type in enabled))

    companion object {
        /** An Anime-Skip segment that runs to the end of the episode has no known end. */
        const val OPEN_END = Double.MAX_VALUE
        private const val MAX_SEEK_SEC = 10_000_000.0
    }
}

/**
 * IntroDB (https://introdb.app), the TV app's source of intro, recap and credits times for
 * anything with an IMDb id.
 */
class IntroDbClient(
    private val http: OkHttpClient,
    private val baseUrl: () -> String = { DEFAULT_URL }
) {
    constructor(http: OkHttpClient, baseUrl: String) : this(http, { baseUrl })

    @Serializable
    private data class Times(
        val start_sec: Double? = null,
        val end_sec: Double? = null,
        val start_ms: Long? = null,
        val end_ms: Long? = null
    )

    @Serializable
    private data class Response(
        val intro: Times? = null,
        val recap: Times? = null,
        val outro: Times? = null,
        val post_credits: Times? = null
    )

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /**
     * The known segments; empty when IntroDB has none or can't be reached. An episode has intro,
     * recap and credits; a movie ([season] or [episode] null) its credits and post-credits scene.
     */
    suspend fun segments(imdbId: String, season: Int?, episode: Int?): List<Segment> = withContext(Dispatchers.IO) {
        val isEpisode = season != null && episode != null
        val url = (baseUrl().trimEnd('/') + "/segments").toHttpUrl().newBuilder()
            .addQueryParameter("imdb_id", imdbId)
            .apply {
                if (isEpisode) {
                    addQueryParameter("season", season.toString())
                    addQueryParameter("episode", episode.toString())
                } else {
                    addQueryParameter("is_movie", "true")
                }
            }
            .build()
        runCatching {
            http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val body = json.decodeFromString(Response.serializer(), response.body?.string().orEmpty())
                if (isEpisode) {
                    listOfNotNull(
                        body.intro?.toSegment(SegmentType.INTRO),
                        body.recap?.toSegment(SegmentType.RECAP),
                        body.outro?.toSegment(SegmentType.OUTRO)
                    )
                } else {
                    movieSegments(body.outro?.toSegment(SegmentType.MOVIE_CREDITS), body.post_credits?.toSegment(SegmentType.POST_CREDITS))
                }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Sends a marker you recorded to IntroDB (its `submit` endpoint, with your API key), as the
     * TV's SkipIntroRepository.submitMarker. False when IntroDB refused it or wasn't reached.
     */
    suspend fun submit(apiKey: String, target: MarkerTarget, type: SegmentType, startSec: Double, endSec: Double): Boolean = withContext(Dispatchers.IO) {
        val key = apiKey.trim()
        if (key.isBlank() || !SkipMarkerRules.isImdbId(target.imdbId) || startSec < 0 || endSec <= startSec) return@withContext false
        val segmentType = SkipMarkerRules.submissionType(type, target.isMovie) ?: return@withContext false
        val body = buildJsonObject {
            put("segment_type", segmentType)
            put("imdb_id", target.imdbId)
            if (target.isMovie) {
                put("is_movie", true)
            } else {
                put("season", target.season)
                put("episode", target.episode)
            }
            put("start_sec", startSec)
            put("end_sec", endSec)
        }.toString().toRequestBody("application/json".toMediaType())
        runCatching {
            val request = Request.Builder().url(baseUrl().trimEnd('/') + "/submit")
                .header("X-API-Key", key).header("Accept", "application/json").post(body).build()
            http.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    private fun Times.toSegment(type: SegmentType): Segment? {
        val start = start_sec ?: start_ms?.let { it / 1000.0 } ?: return null
        val end = end_sec ?: end_ms?.let { it / 1000.0 } ?: return null
        if (!start.isFinite() || !end.isFinite() || start < 0) return null
        return Segment(type, start, end).takeIf { end > start }
    }

    companion object {
        const val DEFAULT_URL = "https://api.introdb.app/"

        /**
         * A movie's credits and post-credits scene, as the TV reads them: skipping the credits must
         * not also skip the scene, so credits overlapping it end where it starts, and credits that
         * end before it jump to its start.
         */
        fun movieSegments(credits: Segment?, scene: Segment?): List<Segment> {
            if (credits == null || scene == null) return listOfNotNull(credits, scene)
            val safe = when {
                scene.startSec < credits.endSec && scene.endSec > credits.startSec ->
                    credits.copy(endSec = scene.startSec).takeIf { it.endSec > it.startSec }
                scene.startSec >= credits.endSec -> credits.copy(skipToSec = scene.startSec)
                else -> credits
            }
            return listOfNotNull(safe, scene)
        }
    }
}

/**
 * Decides when to jump: once playback is inside a segment the user auto-skips, seek to its end
 * (or the post-credits scene after it). Each segment is skipped once, so seeking back into an
 * intro lets you watch it.
 */
class SkipPlanner(segments: List<Segment>, enabled: Set<SegmentType>) {
    private val pending = segments.filter { it.skipsAutomatically(enabled) }.sortedBy { it.startSec }.toMutableList()

    val isEmpty: Boolean get() = pending.isEmpty()

    /** The segment to skip at [positionSec], if any; it won't be offered again. */
    fun at(positionSec: Double): Segment? {
        // A second's grace at the end: landing right on the end shouldn't loop.
        val hit = pending.firstOrNull { positionSec >= it.startSec && positionSec < it.endSec - 1.0 } ?: return null
        pending.remove(hit)
        return hit
    }
}
