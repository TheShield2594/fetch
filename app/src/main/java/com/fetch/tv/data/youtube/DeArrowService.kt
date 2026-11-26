package com.fetch.tv.data.youtube

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/** The app's [DeArrowClient] (shared with the desktop), on the app's HTTP client. */
@Singleton
class DeArrowService @Inject constructor() {
    private val client by lazy { DeArrowClient(communityHttpClient()) { Log.w("DeArrowService", it) } }

    /** Branding for each of [videoIds] that has any; the rest are left out. */
    suspend fun branding(videoIds: Collection<String>): Map<String, YouTubeBranding> = client.branding(videoIds)
}
