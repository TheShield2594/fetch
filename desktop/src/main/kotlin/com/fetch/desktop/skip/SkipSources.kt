package com.fetch.desktop.skip

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap

private val skipJson = Json { ignoreUnknownKeys = true }

private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
private fun JsonElement?.arr(): JsonArray? = this as? JsonArray
private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

/**
 * The anime-specific types AniSkip and Anime-Skip use, as the skip system's: openings are intros,
 * endings are credits ("mixed" ones play over the episode and are skipped the same).
 */
internal fun skipSegmentType(type: String): SegmentType? = when (type.trim().lowercase()) {
    "intro", "op", "opening", "mixed-op" -> SegmentType.INTRO
    "recap" -> SegmentType.RECAP
    "outro", "ed", "ending", "mixed-ed", "credits" -> SegmentType.OUTRO
    "movie-credits" -> SegmentType.MOVIE_CREDITS
    "post-credits" -> SegmentType.POST_CREDITS
    else -> null
}

/**
 * The TV's best-of merge: each of opening, ending and recap comes from the first provider in
 * [providerResults] (IntroDB, then Anime-Skip, then AniSkip) that has it, so a partial answer from
 * one never hides a complete one from another. Movie credits and post-credits aren't merged.
 */
fun mergeByPriority(vararg providerResults: List<Segment>): List<Segment> {
    val chosen = LinkedHashMap<SegmentType, Segment>()
    providerResults.forEach { result ->
        result.forEach { segment ->
            if (segment.type in setOf(SegmentType.INTRO, SegmentType.OUTRO, SegmentType.RECAP)) chosen.putIfAbsent(segment.type, segment)
        }
    }
    return chosen.values.toList()
}

/** AniSkip (api.aniskip.com), crowd-sourced anime openings and endings by MyAnimeList id. */
class AniSkipClient(private val http: OkHttpClient, private val baseUrl: String = DEFAULT_URL) {

    suspend fun segments(malId: String, episode: Int): List<Segment> = withContext(Dispatchers.IO) {
        val url = (baseUrl.trimEnd('/') + "/skip-times/$malId/$episode").toHttpUrl().newBuilder().apply {
            listOf("op", "ed", "recap", "mixed-op", "mixed-ed").forEach { addQueryParameter("types[]", it) }
            addQueryParameter("episodeLength", "0")
        }.build()
        try {
            http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val body = skipJson.parseToJsonElement(response.body?.string().orEmpty()).obj() ?: return@use emptyList()
                if ((body["found"] as? JsonPrimitive)?.booleanOrNull != true) return@use emptyList()
                body["results"].arr().orEmpty().mapNotNull { element ->
                    val result = element.obj() ?: return@mapNotNull null
                    val interval = result["interval"].obj() ?: return@mapNotNull null
                    val start = (interval["startTime"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                    val end = (interval["endTime"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                    val type = skipSegmentType(result["skipType"].str() ?: return@mapNotNull null) ?: return@mapNotNull null
                    if (end <= start || start < 0) return@mapNotNull null
                    Segment(type, start, end, provider = PROVIDER, sourceId = result["skipId"].str())
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Reports the segment AniSkip gave [skipId] as wrong (a downvote, the TV's report). */
    suspend fun reportWrong(skipId: String): Boolean = withContext(Dispatchers.IO) {
        if (skipId.isBlank()) return@withContext false
        val body = buildJsonObject { put("voteType", "downvote") }.toString().toRequestBody("application/json".toMediaType())
        runCatching {
            val url = (baseUrl.trimEnd('/') + "/skip-times").toHttpUrl().newBuilder().addPathSegment(skipId).addPathSegment("votes").build()
            http.newCall(Request.Builder().url(url).header("Accept", "application/json").post(body).build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    companion object {
        const val DEFAULT_URL = "https://api.aniskip.com/v2/"
        const val PROVIDER = "aniskip"
    }
}

/**
 * Anime-Skip (anime-skip.com), timestamps by AniList id through its GraphQL API. It needs your own
 * client id (Settings › Skip Segments), as on the TV.
 */
class AnimeSkipClient(private val http: OkHttpClient, private val baseUrl: String = DEFAULT_URL) {
    private val showIds = ConcurrentHashMap<String, String>()

    private fun query(clientId: String, query: String): JsonObject? {
        val body = buildJsonObject {
            put("query", query)
            put("variables", JsonObject(emptyMap()))
        }.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(baseUrl.trimEnd('/') + "/graphql")
            .header("X-Client-ID", clientId).header("Accept", "application/json").post(body).build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null
            else skipJson.parseToJsonElement(response.body?.string().orEmpty()).obj()?.get("data").obj()
        }
    }

    /** Whether Anime-Skip accepts [clientId] (the TV's check before saving it). */
    suspend fun validate(clientId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { query(clientId.trim(), "{ findShowsByExternalId(service: ANILIST, serviceId: \"1\") { id } }") != null }.getOrDefault(false)
    }

    suspend fun segments(clientId: String, anilistId: String, episode: Int, season: Int? = null): List<Segment> = withContext(Dispatchers.IO) {
        try {
            val shows = showIdsFor(clientId, anilistId)
            for (showId in shows) {
                val data = query(clientId, "{ findEpisodesByShowId(showId: \"$showId\") { season number timestamps { at type { name } } } }") ?: continue
                val target = data["findEpisodesByShowId"].arr().orEmpty().mapNotNull { it.obj() }.firstOrNull { ep ->
                    ep["number"].str()?.toIntOrNull() == episode && (season == null || ep["season"].str()?.toIntOrNull() == season)
                } ?: continue
                val result = timestampsToSegments(target["timestamps"].arr().orEmpty().mapNotNull { ts ->
                    val o = ts.obj() ?: return@mapNotNull null
                    val at = (o["at"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                    val name = o["type"].obj()?.get("name").str() ?: return@mapNotNull null
                    at to name
                })
                if (result.isNotEmpty()) return@withContext result
            }
            emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun showIdsFor(clientId: String, anilistId: String): List<String> {
        showIds[anilistId]?.let { return if (it == NO_ID) emptyList() else listOf(it) }
        val ids = runCatching {
            query(clientId, "{ findShowsByExternalId(service: ANILIST, serviceId: \"$anilistId\") { id } }")
                ?.get("findShowsByExternalId").arr().orEmpty().mapNotNull { it.obj()?.get("id").str() }
        }.getOrNull().orEmpty()
        when (ids.size) {
            0 -> showIds[anilistId] = NO_ID
            1 -> showIds[anilistId] = ids[0]
        }
        return ids
    }

    companion object {
        const val DEFAULT_URL = "https://api.anime-skip.com/"
        const val PROVIDER = "animeskip"
        private const val NO_ID = "__none__"

        /**
         * Anime-Skip marks where each part starts; a part runs to the next mark (or the end). Only
         * intros, credits and recaps are kept, named as AniSkip names them.
         */
        fun timestampsToSegments(timestamps: List<Pair<Double, String>>): List<Segment> {
            val sorted = timestamps.sortedBy { it.first }
            return sorted.mapIndexedNotNull { i, (at, name) ->
                val end = sorted.getOrNull(i + 1)?.first ?: Segment.OPEN_END
                val type = when (name.lowercase()) {
                    "intro", "new intro", "mixed intro" -> SegmentType.INTRO
                    "credits", "new credits", "mixed credits" -> SegmentType.OUTRO
                    "recap" -> SegmentType.RECAP
                    else -> return@mapIndexedNotNull null
                }
                Segment(type, at, end, provider = PROVIDER).takeIf { end > at }
            }
        }
    }
}

/**
 * Simkl's id mapping, as the TV's SimklIdResolver uses it: from an IMDb, MyAnimeList or Kitsu id
 * to the anime ids (MAL for AniSkip, AniList for Anime-Skip), per season when Simkl splits a show
 * into one anime entry per season, and the TVDB numbering of each anime episode. Needs the build's
 * Simkl client id; without one it resolves nothing.
 */
class SimklIdResolver(
    private val http: OkHttpClient,
    private val clientId: String,
    private val appName: String = "fetch",
    private val appVersion: String = "desktop",
    private val baseUrl: String = "https://api.simkl.com"
) {
    data class Ids(
        val simklId: Long,
        val type: String,
        val mal: String? = null,
        val anilist: String? = null,
        val kitsu: String? = null,
        val imdb: String? = null,
        val tvdbSeason: Int? = null
    )

    data class EpisodeMapping(val animeEpisode: Int, val tvdbSeason: Int, val tvdbEpisode: Int)

    private val idsCache = ConcurrentHashMap<String, Ids>()
    private val episodeCache = ConcurrentHashMap<Long, List<EpisodeMapping>>()
    private val seasonCache = ConcurrentHashMap<Long, List<Pair<Long, Int>>>()
    private val noRedirects by lazy { http.newBuilder().followRedirects(false).followSslRedirects(false).build() }

    private fun common() = "client_id=$clientId&app-name=$appName&app-version=$appVersion"

    private fun get(url: String): String? = http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
        if (response.isSuccessful) response.body?.string() else null
    }

    suspend fun resolveIds(source: String, id: String): Ids? = withContext(Dispatchers.IO) {
        if (clientId.isBlank()) return@withContext null
        idsCache["$source:$id"]?.let { return@withContext it }
        runCatching {
            val location = noRedirects.newCall(Request.Builder().url("$baseUrl/redirect?to=simkl&$source=$id&${common()}").get().build())
                .execute().use { it.header("Location") } ?: return@runCatching null
            val (type, simklId) = parseRedirectLocation(location) ?: return@runCatching null
            details(simklId, type)
        }.getOrNull()?.also { idsCache["$source:$id"] = it }
    }

    private fun details(simklId: Long, type: String): Ids? {
        idsCache["simkl:$simklId"]?.let { return it }
        val body = get("$baseUrl/$type/$simklId?extended=full&${common()}") ?: return null
        val details = skipJson.parseToJsonElement(body).obj() ?: return null
        val ids = details["ids"].obj()
        return Ids(
            simklId = simklId,
            type = type,
            mal = ids?.get("mal").str(),
            anilist = ids?.get("anilist").str(),
            kitsu = ids?.get("kitsu").str(),
            imdb = ids?.get("imdb").str(),
            tvdbSeason = (details["season"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
        ).also { idsCache["simkl:$simklId"] = it }
    }

    suspend fun episodeMapping(simklId: Long, type: String): List<EpisodeMapping> = withContext(Dispatchers.IO) {
        if (clientId.isBlank()) return@withContext emptyList()
        episodeCache[simklId]?.let { return@withContext it }
        runCatching {
            val body = get("$baseUrl/$type/episodes/$simklId?${common()}") ?: return@runCatching null
            skipJson.parseToJsonElement(body).arr().orEmpty().mapNotNull { element ->
                val ep = element.obj() ?: return@mapNotNull null
                val number = (ep["episode"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                val tvdb = ep["tvdb"].obj() ?: return@mapNotNull null
                val s = (tvdb["season"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                val e = (tvdb["episode"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                EpisodeMapping(number, s, e).takeIf { number > 0 && s > 0 && e > 0 }
            }
        }.getOrNull()?.also { episodeCache[simklId] = it }.orEmpty()
    }

    /** The TVDB season and episode of [source]:[id]'s anime [episode]. */
    suspend fun episodeTvdb(source: String, id: String, episode: Int): Pair<Int, Int>? {
        val ids = resolveIds(source, id) ?: return null
        return episodeMapping(ids.simklId, ids.type).firstOrNull { it.animeEpisode == episode }?.let { it.tvdbSeason to it.tvdbEpisode }
    }

    /** The anime entry for TVDB [season] of [imdbId]: Simkl keeps one per season for many shows. */
    suspend fun resolveForImdbEpisode(imdbId: String, season: Int): Ids? {
        val base = resolveIds("imdb", imdbId) ?: return null
        if (base.type != "anime" || base.tvdbSeason == season) return base
        val sibling = withContext(Dispatchers.IO) {
            runCatching {
                val seasons = seasonCache[base.simklId] ?: run {
                    val body = get("$baseUrl/${base.type}/${base.simklId}?extended=full_anime_seasons&${common()}") ?: return@runCatching null
                    skipJson.parseToJsonElement(body).obj()?.get("mapped_tvdb_seasons").arr().orEmpty().mapNotNull { e ->
                        val o = e.obj() ?: return@mapNotNull null
                        val sid = (o["simkl_id"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: return@mapNotNull null
                        val tv = (o["tvdb_season"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 } ?: return@mapNotNull null
                        sid to tv
                    }.also { seasonCache[base.simklId] = it }
                }
                seasons.firstOrNull { it.second == season }?.first?.takeIf { it != base.simklId }?.let { details(it, base.type) }
            }.getOrNull()
        }
        return sibling ?: base
    }

    companion object {
        /** "https://simkl.com/anime/39687/..." → ("anime", 39687). */
        fun parseRedirectLocation(location: String): Pair<String, Long>? {
            val parts = location.substringBefore('?').split('/')
            val index = parts.indexOfLast { it == "anime" || it == "tv" || it == "movies" }
            if (index < 0) return null
            val id = parts.getOrNull(index + 1)?.toLongOrNull() ?: return null
            return parts[index] to id
        }
    }
}

/**
 * Where the player's skippable parts come from, as on TV (SkipIntroRepository): IntroDB for
 * anything with an IMDb id, and for anime also Anime-Skip (when you've set it up) and AniSkip,
 * merged best-of. Movies use IntroDB only.
 */
class SkipSources(
    private val introDb: IntroDbClient,
    private val aniSkip: AniSkipClient,
    private val animeSkip: AnimeSkipClient,
    private val simkl: SimklIdResolver,
    /** Anime-Skip's client id when it's enabled; null otherwise. */
    private val animeSkipClientId: () -> String?
) {
    private val cache = ConcurrentHashMap<String, List<Segment>>()
    /** Bumped per title when a submitted marker clears its cache, so a lookup already running doesn't store old times. */
    private val invalidations = ConcurrentHashMap<String, Int>()

    /**
     * Segments for [contentId] (`tt…`, `kitsu:…`, `mal:…`); [season]/[episode] from the episode, or
     * null for a movie. [videoId] (`kitsu:123:5`) gives the episode number when [episode] is null.
     */
    suspend fun segments(contentId: String, videoId: String?, season: Int?, episode: Int?, isMovie: Boolean): List<Segment> {
        val key = "$contentId|$videoId|$season|$episode|$isMovie"
        cache[key]?.let { return it }
        val version = invalidations[contentId] ?: 0
        val parts = contentId.split(':')
        val scheme = parts[0]
        val result = when {
            isMovie -> movie(contentId, videoId)
            contentId.startsWith("tt") && season != null && episode != null -> imdbEpisode(contentId.substringBefore(':'), season, episode)
            (scheme == "kitsu" || scheme == "mal") && parts.size >= 2 -> {
                val number = episode ?: videoId?.split(':')?.getOrNull(2)?.toIntOrNull() ?: return emptyList()
                animeEpisode(scheme, parts[1], number)
            }
            else -> emptyList()
        }
        return result.also { if ((invalidations[contentId] ?: 0) == version) cache[key] = it }
    }

    private suspend fun movie(contentId: String, videoId: String?): List<Segment> {
        val imdb = movieImdbId(contentId, videoId) ?: return emptyList()
        return introDb.segments(imdb, null, null)
    }

    private suspend fun movieImdbId(contentId: String, videoId: String?): String? {
        val ids = listOfNotNull(contentId, videoId).distinct()
        return ids.firstNotNullOfOrNull { id -> id.substringBefore(':').takeIf(SkipMarkerRules::isImdbId) }
            ?: ids.firstNotNullOfOrNull { id ->
                val p = id.split(':')
                if (p.size >= 2 && (p[0] == "mal" || p[0] == "kitsu")) simkl.resolveIds(p[0], p[1])?.imdb?.takeIf(SkipMarkerRules::isImdbId) else null
            }
    }

    /**
     * Where a marker recorded on [contentId] goes on IntroDB, found the way [segments] looks it up
     * (anime through Simkl's TVDB numbering); null when the title has no IMDb id.
     */
    suspend fun markerTarget(contentId: String, videoId: String?, season: Int?, episode: Int?, isMovie: Boolean): MarkerTarget? {
        if (isMovie) return movieImdbId(contentId, videoId)?.let { MarkerTarget(it, null, null) }
        val parts = contentId.split(':')
        val imdb = parts[0].takeIf(SkipMarkerRules::isImdbId)
        if (imdb != null) return if (season != null && episode != null) MarkerTarget(imdb, season, episode) else null
        if ((parts[0] == "kitsu" || parts[0] == "mal") && parts.size >= 2) {
            val number = episode ?: videoId?.split(':')?.getOrNull(2)?.toIntOrNull() ?: return null
            val ids = simkl.resolveIds(parts[0], parts[1]) ?: return null
            val showImdb = ids.imdb?.takeIf(SkipMarkerRules::isImdbId) ?: return null
            val (s, e) = simkl.episodeTvdb(parts[0], parts[1], number) ?: return null
            return MarkerTarget(showImdb, s, e)
        }
        return null
    }

    /** Sends a recorded marker to IntroDB; on success the title's segments are looked up afresh next time. */
    suspend fun submitMarker(apiKey: String, contentId: String, target: MarkerTarget, type: SegmentType, startSec: Double, endSec: Double): Boolean {
        val ok = introDb.submit(apiKey, target, type, startSec, endSec)
        if (ok) {
            invalidations.merge(contentId, 1, Int::plus)
            cache.keys.removeAll { it.startsWith("$contentId|") }
        }
        return ok
    }

    /** Reports [segment] as wrong to its provider (AniSkip only); false when it can't be reported. */
    suspend fun reportWrong(segment: Segment): Boolean {
        if (!SkipMarkerRules.canReport(segment)) return false
        return aniSkip.reportWrong(segment.sourceId!!)
    }

    private suspend fun imdbEpisode(imdb: String, season: Int, episode: Int): List<Segment> = coroutineScope {
        val intro = async { introDb.segments(imdb, season, episode) }
        val ids = simkl.resolveForImdbEpisode(imdb, season)
        // The TVDB episode number as the anime entry's own episode number.
        val animeEpisode = ids?.let { simkl.episodeMapping(it.simklId, it.type).firstOrNull { m -> m.tvdbSeason == season && m.tvdbEpisode == episode }?.animeEpisode } ?: episode
        val ani = async { ids?.mal?.let { aniSkip.segments(it, animeEpisode) }.orEmpty() }
        val animeSkipped = async { animeSkipFor(ids?.anilist, animeEpisode) }
        mergeByPriority(intro.await(), animeSkipped.await(), ani.await())
    }

    private suspend fun animeEpisode(source: String, id: String, episode: Int): List<Segment> = coroutineScope {
        val ids = simkl.resolveIds(source, id)
        val mal = if (source == "mal") id else ids?.mal
        val ani = async { mal?.let { aniSkip.segments(it, episode) }.orEmpty() }
        val animeSkipped = async { animeSkipFor(ids?.anilist, episode) }
        val intro = async {
            val imdb = ids?.imdb ?: return@async emptyList()
            val (s, e) = simkl.episodeTvdb(source, id, episode) ?: return@async emptyList()
            introDb.segments(imdb, s, e)
        }
        mergeByPriority(intro.await(), animeSkipped.await(), ani.await())
    }

    private suspend fun animeSkipFor(anilist: String?, episode: Int): List<Segment> {
        val clientId = animeSkipClientId()?.takeIf { it.isNotBlank() } ?: return emptyList()
        return anilist?.let { animeSkip.segments(clientId, it, episode) }.orEmpty()
    }
}

/**
 * The app's skip sources: IntroDB at the server Settings › Skip Segments names, AniSkip, Simkl's
 * id mapping with the build's Simkl client id, and Anime-Skip when it's on and has a client id.
 */
fun defaultSkipSources(player: () -> com.fetch.desktop.data.PlayerSettings): SkipSources {
    val http = OkHttpClient.Builder()
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    val credentials = com.fetch.desktop.tracking.TrackingCredentials.load()
    return SkipSources(
        introDb = IntroDbClient(http) { player().introDbUrl?.takeIf { it.isNotBlank() } ?: IntroDbClient.DEFAULT_URL },
        aniSkip = AniSkipClient(http),
        animeSkip = AnimeSkipClient(http),
        simkl = SimklIdResolver(http, credentials.simklClientId, credentials.simklAppName, com.fetch.desktop.AppInfo.version),
        animeSkipClientId = { player().extras.takeIf { it.animeSkipEnabled }?.animeSkipClientId?.trim() }
    )
}
