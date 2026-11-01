package com.fetch.tv.data.youtube

import android.util.Log
import com.fetch.tv.core.network.IPv4FirstDns
import com.fetch.tv.core.network.NoDelaySocketFactory
import com.fetch.tv.core.network.SharedHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.Request
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SponsorBlockService"

/**
 * Reads the parts of a video that SponsorBlock users marked as sponsors, self-promotion or
 * subscribe reminders, so playback can skip them, as SmartTube does (block/SponsorBlockService).
 *
 * It asks by the first characters of the video id's hash, not by the id, so SponsorBlock doesn't
 * learn which video is being watched; the matching video is picked out here.
 */
@Singleton
class SponsorBlockService @Inject constructor() {
    internal companion object {
        const val API_URL = "https://sponsor.ajay.app/api/skipSegments"
        /** What was asked before categories could be chosen; still the default. */
        val CATEGORIES = SponsorBlockCategory.entries.filter { it.defaultMode != SponsorBlockMode.OFF }.map { it.apiName }
        const val HASH_PREFIX_LENGTH = 4

        /** The first characters of the video id's SHA-256, which SponsorBlock and DeArrow are asked by. */
        fun hashPrefixOf(videoId: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(videoId.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }.take(HASH_PREFIX_LENGTH)
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient by lazy {
        SharedHttpClient.root.newBuilder()
            .dns(IPv4FirstDns())
            .socketFactory(NoDelaySocketFactory)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** The segments of [videoId] in [categories] (SponsorBlock's names); empty when there are none or it can't be read. */
    suspend fun segments(videoId: String, categories: Collection<String> = CATEGORIES): List<YouTubeSkipSegment> = withContext(Dispatchers.IO) {
        if (categories.isEmpty()) return@withContext emptyList()
        runCatching {
            val encodedCategories = URLEncoder.encode(categories.joinToString(",", "[", "]") { "\"$it\"" }, "UTF-8")
            val actionTypes = URLEncoder.encode("[\"skip\"]", "UTF-8")
            val request = Request.Builder()
                .url("$API_URL/${hashPrefix(videoId)}?categories=$encodedCategories&actionTypes=$actionTypes")
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                // 404 means no video with this prefix has segments.
                if (response.code == 404) return@runCatching emptyList()
                if (!response.isSuccessful) error("SponsorBlock returned ${response.code}")
                parseSegments(json.parseToJsonElement(response.body?.string().orEmpty()), videoId)
            }
        }.onFailure { Log.w(TAG, "No SponsorBlock segments for $videoId: ${it.message}") }
            .getOrDefault(emptyList())
    }

    internal fun hashPrefix(videoId: String): String = hashPrefixOf(videoId)

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
