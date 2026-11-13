package com.fetch.tv.core.player

import com.fetch.tv.core.logging.hostOnlyUrlForLog
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import com.fetch.tv.ui.screens.player.PlayerSubtitleUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Downloads subtitle files from remote URLs to local cache and provides
 * content:// URIs via FileProvider so external players can read them.
 */
@Singleton
class SubtitleFileCache @Inject constructor(
    @ApplicationContext private val context: Context,
    // Callers pass addon-provided subtitle URLs, so use the permissive addon client.
    // Keep it that way: anything routed through here inherits permissive TLS.
    @param:Named("addonPermissive") private val okHttpClient: OkHttpClient
) {
    private val cacheDir: File
        get() = File(context.cacheDir, SUBTITLE_CACHE_DIR).also { it.mkdirs() }

    /**
     * Downloads subtitle files and returns updated [SubtitleInput] list with
     * content:// URIs instead of HTTP URLs.
     *
     * The list is first ordered and capped by [prioritize] (preferred language first, at most
     * [MAX_FORWARDED_SUBTITLES]). Downloads run in parallel, each bounded by
     * [PER_FILE_TIMEOUT_MS]; whatever finished within [OVERALL_TIMEOUT_MS] is returned, so one
     * slow host no longer drops every subtitle. Order is preserved, so the first entry is the
     * one external players enable by default.
     *
     * Subtitles that fail to download are silently skipped (not included in result).
     * Returns null if no subtitles could be downloaded.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun cacheSubtitles(
        subtitles: List<SubtitleInput>,
        preferredLanguages: List<String> = emptyList()
    ): List<SubtitleInput>? {
        val selected = prioritize(subtitles, preferredLanguages)
        if (selected.isEmpty()) return null

        // Clean old cached subtitles before downloading new ones
        clearCache()

        val cached = coroutineScope {
            // Bounds peak memory: at most this many bodies (each <= MAX_SUBTITLE_BYTES) are
            // held and decoded at once.
            val permits = Semaphore(MAX_CONCURRENT_DOWNLOADS)
            val downloads = selected.mapIndexed { index, input ->
                async(Dispatchers.IO) {
                    permits.withPermit { cacheOne(index, input) }
                }
            }
            val finished = withTimeoutOrNull(OVERALL_TIMEOUT_MS) { downloads.awaitAll() }
            finished ?: run {
                // Keep the subtitles that made it; drop the stragglers.
                val partial = downloads.map { if (it.isCompleted && !it.isCancelled) it.getCompleted() else null }
                downloads.forEach { it.cancel() }
                Log.w(TAG, "Subtitle caching timed out; keeping ${partial.count { it != null }}/${downloads.size}")
                partial
            }
        }.filterNotNull()

        return cached.ifEmpty { null }
    }

    private fun cacheOne(index: Int, input: SubtitleInput): SubtitleInput? = try {
        val localUri = downloadToCache(index, input)
        if (localUri != null) {
            input.copy(url = localUri.toString())
        } else {
            Log.w(TAG, "Failed to download subtitle: ${input.name}")
            null
        }
    } catch (e: Exception) {
        Log.w(TAG, "Error caching subtitle: ${input.name}", e)
        null
    }

    /**
     * Downloads a single subtitle file and returns a content:// URI.
     */
    private fun downloadToCache(index: Int, input: SubtitleInput): Uri? {
        val extension = guessExtension(input.url)
        // Index keeps names unique: same-language subtitles from one addon share lang + name.
        val filename = cacheFilename(index, input.lang, input.url, extension)
        val file = File(cacheDir, filename)

        val request = Request.Builder()
            .url(input.url)
            .build()

        return try {
            val call = okHttpClient.newCall(request)
            // Bounds the whole call, including the body read, so a stalled host cannot hold up
            // the batch.
            call.timeout().timeout(PER_FILE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "HTTP ${response.code} downloading subtitle: ${input.url.hostOnlyUrlForLog()}")
                    return null
                }

                val body = response.body ?: return null
                // Reject oversized bodies before buffering them: subtitles are small, and an
                // unbounded read across parallel downloads could exhaust the heap.
                if (body.contentLength() > MAX_SUBTITLE_BYTES) {
                    Log.w(TAG, "Skipping oversized subtitle (${body.contentLength()} bytes): ${input.url.hostOnlyUrlForLog()}")
                    return null
                }
                val source = body.source()
                if (source.request(MAX_SUBTITLE_BYTES + 1)) {
                    Log.w(TAG, "Skipping oversized subtitle (> $MAX_SUBTITLE_BYTES bytes): ${input.url.hostOnlyUrlForLog()}")
                    return null
                }
                val bodyBytes = source.buffer.readByteArray()
                val normalizedText = SubtitleCharsetDetector.decode(bodyBytes, languageHint = input.lang)
                val sanitizedText = com.fetch.tv.ui.screens.player.SubtitleMojibakeSanitizer.sanitize(normalizedText).toString()
                file.writeText(sanitizedText, Charsets.UTF_8)
            }

            // Return content:// URI via FileProvider
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to download subtitle file: ${input.url.hostOnlyUrlForLog()}", e)
            file.delete()
            null
        }
    }

    /**
     * Removes all cached subtitle files.
     */
    fun clearCache() {
        try {
            cacheDir.listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear subtitle cache", e)
        }
    }

    private fun guessExtension(url: String): String {
        val path = url.substringBefore('?').substringBefore('#').trimEnd('/')
        return when {
            path.endsWith(".vtt", ignoreCase = true) -> "vtt"
            path.endsWith(".ass", ignoreCase = true) -> "ass"
            path.endsWith(".ssa", ignoreCase = true) -> "ssa"
            path.endsWith(".ttml", ignoreCase = true) -> "ttml"
            path.endsWith(".dfxp", ignoreCase = true) -> "dfxp"
            else -> "srt"
        }
    }

    companion object {
        private const val TAG = "SubtitleFileCache"
        private const val SUBTITLE_CACHE_DIR = "subtitles"
        internal const val MAX_FORWARDED_SUBTITLES = 20
        private const val PER_FILE_TIMEOUT_MS = 8_000L
        private const val MAX_SUBTITLE_BYTES = 4L * 1024 * 1024
        private const val MAX_CONCURRENT_DOWNLOADS = 8
        private const val OVERALL_TIMEOUT_MS = 10_000L

        /**
         * Orders [subtitles] for an external player and caps the list at [max]:
         * - With preferred languages, only subtitles matching one of them are kept (all of
         *   them if none match), ranked by preference order so the preferred one comes first.
         * - The sort is stable, so addon order is kept within a language.
         */
        internal fun prioritize(
            subtitles: List<SubtitleInput>,
            preferredLanguages: List<String>,
            max: Int = MAX_FORWARDED_SUBTITLES
        ): List<SubtitleInput> {
            val prefs = preferredLanguages
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() && it != "none" }
                .distinct()
            if (prefs.isEmpty()) return subtitles.take(max)

            fun rank(sub: SubtitleInput): Int =
                prefs.indexOfFirst { PlayerSubtitleUtils.matchesLanguageCode(sub.lang, it) }
                    .let { if (it < 0) prefs.size else it }

            val matching = subtitles.filter { rank(it) < prefs.size }
            return matching.ifEmpty { subtitles }
                .sortedBy { rank(it) }
                .take(max)
        }

        /** Unique, filesystem-safe cache name: index + language + a hash of the URL. */
        internal fun cacheFilename(index: Int, lang: String, url: String, extension: String): String {
            val safeLang = lang.replace(Regex("[^a-zA-Z0-9\\-]"), "_").take(16).ifEmpty { "und" }
            val urlHash = Integer.toHexString(url.hashCode())
            return "%03d_%s_%s.%s".format(index, safeLang, urlHash, extension)
        }
    }
}
