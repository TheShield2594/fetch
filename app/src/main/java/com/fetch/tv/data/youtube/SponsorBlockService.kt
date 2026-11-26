package com.fetch.tv.data.youtube

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/** The app's [SponsorBlockClient] (shared with the desktop), on the app's HTTP client. */
@Singleton
class SponsorBlockService @Inject constructor() {
    private val client by lazy { SponsorBlockClient(communityHttpClient()) { Log.w("SponsorBlockService", it) } }

    /** The segments of [videoId] in [categories] (SponsorBlock's names); empty when there are none or it can't be read. */
    suspend fun segments(
        videoId: String,
        categories: Collection<String> = SponsorBlockClient.DEFAULT_CATEGORIES
    ): List<YouTubeSkipSegment> = client.segments(videoId, categories)
}
