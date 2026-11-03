package com.fetch.desktop.skip

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** The parts of an episode the TV app can skip, as IntroDB names them. */
@Serializable
enum class SegmentType(val label: String) {
    INTRO("Intro"),
    RECAP("Recap"),
    OUTRO("Credits")
}

data class Segment(
    val type: SegmentType,
    val startSec: Double,
    val endSec: Double,
    /** What the Skip button and the on-screen note call it, when not [type]'s label (SponsorBlock's "sponsor"). */
    val label: String? = null,
    /** Decides auto-skip for this segment alone (SponsorBlock's per-category modes) instead of by [type]. */
    val autoSkip: Boolean? = null
) {
    val displayLabel: String get() = label ?: type.label.lowercase()

    /** Whether it's skipped automatically when [enabled] are the auto-skipped types. */
    fun skipsAutomatically(enabled: Set<SegmentType>): Boolean = autoSkip ?: (type in enabled)
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
    private data class Times(val start_sec: Double? = null, val end_sec: Double? = null)

    @Serializable
    private data class Response(val intro: Times? = null, val recap: Times? = null, val outro: Times? = null)

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** The known segments; empty when IntroDB has none or can't be reached. */
    suspend fun segments(imdbId: String, season: Int?, episode: Int?): List<Segment> = withContext(Dispatchers.IO) {
        val url = (baseUrl().trimEnd('/') + "/segments").toHttpUrl().newBuilder()
            .addQueryParameter("imdb_id", imdbId)
            .apply {
                if (season != null && episode != null) {
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
                listOfNotNull(
                    body.intro?.toSegment(SegmentType.INTRO),
                    body.recap?.toSegment(SegmentType.RECAP),
                    body.outro?.toSegment(SegmentType.OUTRO)
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun Times.toSegment(type: SegmentType): Segment? {
        val start = start_sec ?: return null
        val end = end_sec ?: return null
        return Segment(type, start, end).takeIf { end > start }
    }

    companion object {
        const val DEFAULT_URL = "https://api.introdb.app/"
    }
}

/**
 * Decides when to jump: once playback is inside a segment the user auto-skips, seek to its end.
 * Each segment is skipped once, so seeking back into an intro lets you watch it.
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
