package com.fetch.tv.data.youtube

import android.util.Log
import com.fetch.tv.core.network.IPv4FirstDns
import com.fetch.tv.core.network.NoDelaySocketFactory
import com.fetch.tv.core.network.SharedHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ReturnYouTubeDislike"

/**
 * Like and dislike counts from Return YouTube Dislike, which estimates the dislikes YouTube hides
 * from its archive and its users' votes. Its API takes the video id; it's only asked when the
 * profile turned it on.
 */
@Singleton
class ReturnYouTubeDislikeService @Inject constructor() {
    internal companion object {
        const val VOTES_URL = "https://returnyoutubedislikeapi.com/votes"
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

    suspend fun votes(videoId: String): YouTubeVoteCounts? = withContext(Dispatchers.IO) {
        runCatching {
            val url = VOTES_URL.toHttpUrl().newBuilder().addQueryParameter("videoId", videoId).build()
            httpClient.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) error("Return YouTube Dislike returned ${response.code}")
                parseVotes(json.parseToJsonElement(response.body?.string().orEmpty()))
            }
        }.onFailure { Log.w(TAG, "No vote counts for $videoId: ${it.message}") }.getOrNull()
    }

    internal fun parseVotes(root: JsonElement): YouTubeVoteCounts? {
        val obj = root as? JsonObject ?: return null
        val likes = (obj["likes"] as? JsonPrimitive)?.longOrNull ?: return null
        val dislikes = (obj["dislikes"] as? JsonPrimitive)?.longOrNull ?: return null
        return YouTubeVoteCounts(likes = likes, dislikes = dislikes)
    }
}
