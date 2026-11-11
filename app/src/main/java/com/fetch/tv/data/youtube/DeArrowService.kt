package com.fetch.tv.data.youtube

import android.util.Log
import android.util.LruCache
import com.fetch.tv.core.network.IPv4FirstDns
import com.fetch.tv.core.network.NoDelaySocketFactory
import com.fetch.tv.core.network.SharedHttpClient
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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DeArrowService"

/**
 * Reads the titles and thumbnails DeArrow users wrote for videos, in place of clickbait. Made by
 * SponsorBlock's author, and asked the same private way: by the first characters of the video id's
 * hash, so DeArrow doesn't learn which video is shown.
 */
@Singleton
class DeArrowService @Inject constructor() {
    internal companion object {
        const val BRANDING_URL = "https://sponsor.ajay.app/api/branding"
        const val THUMBNAIL_URL = "https://dearrow-thumb.ajay.app/api/v1/getThumbnail"
        const val MAX_PARALLEL = 6
        const val CACHE_SIZE = 500
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val cache = LruCache<String, YouTubeBranding>(CACHE_SIZE)
    private val noBranding = YouTubeBranding(null, null)

    private val httpClient by lazy {
        SharedHttpClient.root.newBuilder()
            .dns(IPv4FirstDns())
            .socketFactory(NoDelaySocketFactory)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** Branding for each of [videoIds] that has any; the rest are left out. */
    suspend fun branding(videoIds: Collection<String>): Map<String, YouTubeBranding> = withContext(Dispatchers.IO) {
        val limit = Semaphore(MAX_PARALLEL)
        coroutineScope {
            videoIds.distinct().map { id ->
                async { id to (cache.get(id) ?: limit.withPermit { fetch(id) }) }
            }.awaitAll()
        }.filter { (_, branding) -> branding != noBranding }.toMap()
    }

    private fun fetch(videoId: String): YouTubeBranding {
        val branding = runCatching {
            val request = Request.Builder()
                .url("$BRANDING_URL/${SponsorBlockService.hashPrefixOf(videoId)}")
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (response.code == 404) return@runCatching noBranding
                if (!response.isSuccessful) error("DeArrow returned ${response.code}")
                parseBranding(json.parseToJsonElement(response.body?.string().orEmpty()), videoId) ?: noBranding
            }
        }.onFailure { Log.w(TAG, "No DeArrow branding for $videoId: ${it.message}") }
            // A failure isn't cached, so the video is asked again next time.
            .getOrElse { return noBranding }
        cache.put(videoId, branding)
        return branding
    }

    /**
     * The video's entry from a branding response: the top title and thumbnail that aren't the
     * originals and that voters haven't sunk (a locked one always counts), as DeArrow's own
     * extension picks them.
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
