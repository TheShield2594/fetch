package com.fetch.tv.data.youtube

import android.util.Log
import com.fetch.tv.core.network.IPv4FirstDns
import com.fetch.tv.core.network.NoDelaySocketFactory
import com.fetch.tv.core.network.SharedHttpClient
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** The app's [ReturnYouTubeDislikeClient] (shared with the desktop), on the app's HTTP client. */
@Singleton
class ReturnYouTubeDislikeService @Inject constructor() {
    private val client by lazy { ReturnYouTubeDislikeClient(communityHttpClient()) { Log.w("ReturnYouTubeDislike", it) } }

    suspend fun votes(videoId: String): YouTubeVoteCounts? = client.votes(videoId)
}

/** The client SponsorBlock, DeArrow, Return YouTube Dislike and captions are asked on. */
internal fun communityHttpClient(readTimeoutSeconds: Long = 10): OkHttpClient =
    SharedHttpClient.root.newBuilder()
        .dns(IPv4FirstDns())
        .socketFactory(NoDelaySocketFactory)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
        .build()
