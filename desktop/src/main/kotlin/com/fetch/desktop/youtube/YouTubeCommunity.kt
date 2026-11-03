package com.fetch.desktop.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.security.MessageDigest

// The community services the TV app's YouTube player uses, ported from app/.../data/youtube/:
// SponsorBlock, DeArrow and Return YouTube Dislike.

private val communityJson = Json { ignoreUnknownKeys = true }

/**
 * The parts of a video SponsorBlock users marked (sponsors, self-promotion, subscribe reminders…),
 * so playback can skip them. It asks by the first characters of the video id's hash, not the id,
 * so SponsorBlock doesn't learn which video is being watched; the matching video is picked here.
 */
class SponsorBlockClient(private val http: OkHttpClient) {
    companion object {
        const val API_URL = "https://sponsor.ajay.app/api/skipSegments"
        const val HASH_PREFIX_LENGTH = 4

        /** The first characters of the video id's SHA-256, which SponsorBlock and DeArrow are asked by. */
        fun hashPrefixOf(videoId: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(videoId.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }.take(HASH_PREFIX_LENGTH)
        }
    }

    /** The segments of [videoId] in [categories] (SponsorBlock's names); empty when there are none or it can't be read. */
    suspend fun segments(videoId: String, categories: Collection<String>): List<YouTubeSkipSegment> = withContext(Dispatchers.IO) {
        if (categories.isEmpty()) return@withContext emptyList()
        runCatching {
            val encodedCategories = URLEncoder.encode(categories.joinToString(",", "[", "]") { "\"$it\"" }, "UTF-8")
            val actionTypes = URLEncoder.encode("[\"skip\"]", "UTF-8")
            val request = Request.Builder()
                .url("$API_URL/${hashPrefixOf(videoId)}?categories=$encodedCategories&actionTypes=$actionTypes")
                .get()
                .build()
            http.newCall(request).execute().use { response ->
                // 404 means no video with this prefix has segments.
                if (response.code == 404) return@runCatching emptyList()
                if (!response.isSuccessful) error("SponsorBlock returned ${response.code}")
                parseSegments(communityJson.parseToJsonElement(response.body?.string().orEmpty()), videoId)
            }
        }.getOrDefault(emptyList())
    }

    internal fun parseSegments(root: JsonElement, videoId: String): List<YouTubeSkipSegment> {
        val video = (root as? JsonArray)?.firstOrNull {
            ((it as? JsonObject)?.get("videoID") as? JsonPrimitive)?.contentOrNull == videoId
        } as? JsonObject ?: return emptyList()
        return (video["segments"] as? JsonArray).orEmpty().mapNotNull { element ->
            val segment = element as? JsonObject ?: return@mapNotNull null
            val actionType = (segment["actionType"] as? JsonPrimitive)?.contentOrNull
            if (actionType != null && actionType != "skip") return@mapNotNull null
            val bounds = segment["segment"] as? JsonArray ?: return@mapNotNull null
            val start = (bounds.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val end = (bounds.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            if (end <= start) return@mapNotNull null
            YouTubeSkipSegment(
                startMs = (start * 1000).toLong(),
                endMs = (end * 1000).toLong(),
                category = (segment["category"] as? JsonPrimitive)?.contentOrNull ?: "sponsor"
            )
        }.sortedBy { it.startMs }
    }
}

/**
 * The titles and thumbnails DeArrow users wrote for videos, in place of clickbait. Asked the same
 * private way as SponsorBlock, by the hash prefix.
 */
class DeArrowClient(private val http: OkHttpClient) {
    companion object {
        const val BRANDING_URL = "https://sponsor.ajay.app/api/branding"
        const val THUMBNAIL_URL = "https://dearrow-thumb.ajay.app/api/v1/getThumbnail"
        const val MAX_PARALLEL = 6
        const val CACHE_SIZE = 500
    }

    private val noBranding = YouTubeBranding(null, null)
    private val cache = object : LinkedHashMap<String, YouTubeBranding>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, YouTubeBranding>?) = size > CACHE_SIZE
    }

    private fun cached(id: String): YouTubeBranding? = synchronized(cache) { cache[id] }

    /** Branding for each of [videoIds] that has any; the rest are left out. */
    suspend fun branding(videoIds: Collection<String>): Map<String, YouTubeBranding> = withContext(Dispatchers.IO) {
        val limit = Semaphore(MAX_PARALLEL)
        coroutineScope {
            videoIds.distinct().map { id -> async { id to (cached(id) ?: limit.withPermit { fetch(id) }) } }.awaitAll()
        }.filter { (_, branding) -> branding != noBranding }.toMap()
    }

    private fun fetch(videoId: String): YouTubeBranding {
        val branding = runCatching {
            val request = Request.Builder().url("$BRANDING_URL/${SponsorBlockClient.hashPrefixOf(videoId)}").get().build()
            http.newCall(request).execute().use { response ->
                if (response.code == 404) return@runCatching noBranding
                if (!response.isSuccessful) error("DeArrow returned ${response.code}")
                parseBranding(communityJson.parseToJsonElement(response.body?.string().orEmpty()), videoId) ?: noBranding
            }
        }
            // A failure isn't cached, so the video is asked again next time.
            .getOrElse { return noBranding }
        synchronized(cache) { cache[videoId] = branding }
        return branding
    }

    /**
     * The video's entry: the top title and thumbnail that aren't the originals and that voters
     * haven't sunk (a locked one always counts), as DeArrow's own extension picks them.
     */
    internal fun parseBranding(root: JsonElement, videoId: String): YouTubeBranding? {
        val video = (root as? JsonObject)?.get(videoId) as? JsonObject ?: return null
        fun JsonObject.trusted(): Boolean {
            val original = (this["original"] as? JsonPrimitive)?.booleanOrNull == true
            val locked = (this["locked"] as? JsonPrimitive)?.booleanOrNull == true
            val votes = (this["votes"] as? JsonPrimitive)?.intOrNull ?: 0
            return !original && (locked || votes >= 0)
        }
        val title = (video["titles"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .firstOrNull { it.trusted() }
            ?.let { (it["title"] as? JsonPrimitive)?.contentOrNull }
            // A leading ">" asks for the title as written, without DeArrow's case formatting.
            ?.removePrefix(">")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val time = (video["thumbnails"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .firstOrNull { it.trusted() }
            ?.let { (it["timestamp"] as? JsonPrimitive)?.doubleOrNull }
        val thumbnail = time?.let {
            THUMBNAIL_URL.toHttpUrl().newBuilder()
                .addQueryParameter("videoID", videoId)
                .addQueryParameter("time", it.toString())
                .build()
                .toString()
        }
        if (title == null && thumbnail == null) return null
        return YouTubeBranding(title, thumbnail)
    }
}

/**
 * Like and dislike counts from Return YouTube Dislike, which estimates the dislikes YouTube hides.
 * Its API takes the video id; it's only asked when the profile turned it on.
 */
class ReturnYouTubeDislikeClient(private val http: OkHttpClient) {
    companion object {
        const val VOTES_URL = "https://returnyoutubedislikeapi.com/votes"
    }

    suspend fun votes(videoId: String): YouTubeVoteCounts? = withContext(Dispatchers.IO) {
        runCatching {
            val url = VOTES_URL.toHttpUrl().newBuilder().addQueryParameter("videoId", videoId).build()
            http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) error("Return YouTube Dislike returned ${response.code}")
                parseVotes(communityJson.parseToJsonElement(response.body?.string().orEmpty()))
            }
        }.getOrNull()
    }

    internal fun parseVotes(root: JsonElement): YouTubeVoteCounts? {
        val obj = root as? JsonObject ?: return null
        val likes = (obj["likes"] as? JsonPrimitive)?.longOrNull ?: return null
        val dislikes = (obj["dislikes"] as? JsonPrimitive)?.longOrNull ?: return null
        return YouTubeVoteCounts(likes = likes, dislikes = dislikes)
    }
}
