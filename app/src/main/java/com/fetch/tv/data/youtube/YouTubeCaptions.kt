package com.fetch.tv.data.youtube

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "YouTubeCaptions"

/**
 * Lists a video's caption tracks and downloads the chosen one, with the rules in [YouTubeCaptions]
 * (shared with the desktop) on the app's HTTP client.
 */
@Singleton
class YouTubeCaptionService @Inject constructor() {
    private val httpClient by lazy { communityHttpClient(readTimeoutSeconds = 15) }

    /** See [YouTubeCaptions.tracks]. */
    fun tracks(captionsBlocks: List<JsonElement?>, translateInto: List<String>): List<YouTubeCaptionTrack> =
        YouTubeCaptions.tracks(captionsBlocks, translateInto)

    /** The cues of [track], from the first of its links that gives any. */
    suspend fun cues(track: YouTubeCaptionTrack): List<YouTubeCaptionCue> = withContext(Dispatchers.IO) {
        YouTubeCaptions.cues(track, download = ::download)
    }

    private fun download(url: String): String? = runCatching {
        httpClient.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) error("Captions returned ${response.code}")
            response.body?.string().orEmpty()
        }
    }.onFailure { Log.w(TAG, "Caption link failed: ${it.message}") }.getOrNull()
}

/** See [YouTubeCaptions.pickDefault]. */
internal fun pickDefaultCaptionTrack(tracks: List<YouTubeCaptionTrack>, languages: List<String>): YouTubeCaptionTrack? =
    YouTubeCaptions.pickDefault(tracks, languages)
