package com.fetch.tv.ui.screens.player

import android.util.Log
import android.widget.Toast
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.SeekParameters
import com.fetch.tv.R
import com.fetch.tv.core.player.Chapter
import com.fetch.tv.core.player.ChapterRules
import com.fetch.tv.core.player.MatroskaChapterReader
import com.fetch.tv.data.repository.SkipInterval
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where the playing stream's chapters come from. The file's own chapters (mpv's chapter-list, or
 * the Matroska Chapters element for ExoPlayer) win over the media server's, which cover what the
 * file does not show, such as a transcoded stream.
 */
internal class PlayerChapterState {
    private val mpvStale = AtomicBoolean(true)

    /** Engine and URL the chapters below belong to. */
    var sourceKey: String? = null
    var containerChapters: List<Chapter> = emptyList()
    var serverChapters: List<Chapter> = emptyList()
    var loadJob: Job? = null

    /** Inputs the chapter skip fallback was last worked out from. */
    var fallbackKey: Any? = null

    /** Safe from any thread: mpv reports chapter-list changes off the main thread. */
    fun markMpvChaptersStale() = mpvStale.set(true)

    fun consumeMpvChaptersStale(): Boolean = mpvStale.getAndSet(false)
}

private const val SERVER_CHAPTERS_TIMEOUT_MS = 10_000L
private const val MKV_CHAPTERS_TIMEOUT_MS = 20_000L

/** Progress-tick entry point: notices a new stream or engine, re-reads mpv's list when it changed. */
internal fun PlayerRuntimeController.refreshChaptersOnTick() {
    val state = chapterState
    val url = currentStreamUrl.takeIf { it.isNotBlank() }
    val usingMpv = isUsingMpvEngine()
    val key = url?.let { (if (usingMpv) "mpv|" else "exo|") + it }
    if (key != state.sourceKey) {
        state.sourceKey = key
        state.loadJob?.cancel()
        state.loadJob = null
        state.containerChapters = emptyList()
        state.serverChapters = emptyList()
        state.markMpvChaptersStale()
        publishChapters()
        if (url != null && key != null) loadChapters(url, usingMpv, key)
    }
    if (usingMpv) {
        val view = mpvView
        if (view != null && state.consumeMpvChaptersStale()) {
            state.containerChapters = runCatching { view.readChapters() }.getOrDefault(emptyList())
            publishChapters()
        }
    }
    refreshChapterSkipFallback()
}

private fun PlayerRuntimeController.publishChapters() {
    val state = chapterState
    val chapters = ChapterRules.normalize(state.containerChapters.ifEmpty { state.serverChapters })
    _uiState.update { if (it.chapters == chapters) it else it.copy(chapters = chapters) }
}

private fun PlayerRuntimeController.loadChapters(url: String, usingMpv: Boolean, key: String) {
    val state = chapterState
    val headers = currentHeaders
    val matroska = !usingMpv && isMatroskaHttpStream(url)
    val server = serverPlayback.isServerSource(url)
    if (!server && !matroska) return
    state.loadJob = scope.launch {
        val serverChapters = if (server) {
            try {
                withTimeoutOrNull(SERVER_CHAPTERS_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) { serverPlayback.chapters(url) }
                }.orEmpty()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(PlayerRuntimeController.TAG, "Server chapters failed: ${error.message}")
                emptyList()
            }
        } else {
            emptyList()
        }
        if (state.sourceKey != key) return@launch
        if (serverChapters.isNotEmpty()) {
            state.serverChapters = serverChapters
            publishChapters()
            return@launch
        }
        if (!matroska) return@launch
        val fileChapters = try {
            withTimeoutOrNull(MKV_CHAPTERS_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { MatroskaChapterReader.read(HttpRangeReader(url, headers)) }
            }.orEmpty()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(PlayerRuntimeController.TAG, "MKV chapters failed: ${error.message}")
            emptyList()
        }
        if (state.sourceKey != key) return@launch
        state.containerChapters = fileChapters
        publishChapters()
    }
}

private fun PlayerRuntimeController.isMatroskaHttpStream(url: String): Boolean {
    val scheme = url.substringBefore("://", "").lowercase()
    if (scheme != "http" && scheme != "https") return false
    if (currentStreamMimeType == MimeTypes.VIDEO_MATROSKA) return true
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    val name = currentFilename?.lowercase().orEmpty()
    return path.endsWith(".mkv") || name.endsWith(".mkv")
}

/** Small Range reads over the playback client; a read past 0 that the server answers with 200 counts as failed. */
private class HttpRangeReader(
    private val url: String,
    private val headers: Map<String, String>
) : MatroskaChapterReader.RangeReader {

    override fun read(offset: Long, length: Int): ByteArray? {
        if (length <= 0) return null
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("Range", "bytes=$offset-${offset + length - 1}")
            .apply {
                if (headers.none { it.key.equals("User-Agent", ignoreCase = true) }) {
                    header("User-Agent", PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
                }
                headers.forEach { (name, value) ->
                    if (value.isNotBlank() && !name.equals("Range", ignoreCase = true)) header(name, value)
                }
            }
            .build()
        return client.newCall(request).execute().use { response ->
            val usable = response.code == 206 || (response.code == 200 && offset == 0L)
            if (!usable) return@use null
            val body = response.body ?: return@use null
            body.byteStream().use { input ->
                val out = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(out, read, length - read)
                    if (n <= 0) break
                    read += n
                }
                if (read == length) out else out.copyOf(read)
            }
        }
    }

    private companion object {
        val client by lazy {
            PlayerPlaybackNetworking.playbackHttpClient.newBuilder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .callTimeout(15, TimeUnit.SECONDS)
                .build()
        }
    }
}

/**
 * Chapter-named skip segments, for when IntroDB, AniSkip and Anime-Skip have nothing: the
 * lowest-priority source. Empty until the title's length is known.
 */
internal fun PlayerRuntimeController.chapterSkipFallback(): List<SkipInterval> {
    val durationMs = currentPlaybackDurationMs()
    val chapters = _uiState.value.chapters
    if (durationMs <= 0L || chapters.isEmpty()) return emptyList()
    return ChapterRules.skipIntervals(
        chapters = ChapterRules.normalize(chapters, durationMs),
        durationMs = durationMs,
        isMovie = contentType.equals("movie", ignoreCase = true)
    )
}

/** Takes the skip services' segments, or the chapter fallback when they have none. */
internal fun PlayerRuntimeController.applyProviderSkipIntervals(intervals: List<SkipInterval>) {
    skipIntervals = intervals.ifEmpty { if (skipIntroEnabled) chapterSkipFallback() else emptyList() }
}

/**
 * Puts the chapter fallback in place when the chapters, the known length or the skip setting
 * change, unless the skip services' segments are already loaded.
 */
internal fun PlayerRuntimeController.refreshChapterSkipFallback() {
    val state = chapterState
    val key = listOf(_uiState.value.chapters, currentPlaybackDurationMs() > 0L, skipIntroEnabled, contentType)
    if (key == state.fallbackKey) return
    state.fallbackKey = key
    if (skipIntervals.any { it.provider != ChapterRules.SKIP_PROVIDER }) return
    val fallback = if (skipIntroEnabled) chapterSkipFallback() else emptyList()
    if (fallback != skipIntervals) skipIntervals = fallback
}

/**
 * Seeks to the previous or next chapter. False when there is none to go to, so the caller can
 * fall back to its usual action.
 */
internal fun PlayerRuntimeController.seekToAdjacentChapter(next: Boolean): Boolean {
    val chapters = _uiState.value.chapters
    if (chapters.isEmpty() || isLiveSeekBlocked()) return false
    val position = pendingPreviewSeekPosition ?: currentPlaybackPositionMs() ?: return false
    val target = if (next) {
        ChapterRules.nextTargetMs(chapters, position)
    } else {
        ChapterRules.previousTargetMs(chapters, position)
    } ?: return false
    pendingPreviewSeekPosition = null
    seekPlaybackTo(target, SeekParameters.EXACT)
    updatePlaybackTimeline(currentPosition = target)
    updateSeekFeedback(originMs = position, targetMs = target, committed = true)
    scheduleProgressSyncAfterSeek()
    if (_uiState.value.showControls) showControlsTemporarily() else showSeekOverlayTemporarily()
    val index = ChapterRules.indexAt(chapters, target)
    chapters.getOrNull(index)?.let { chapter ->
        val label = chapter.title?.let { context.getString(R.string.player_chapter_named, index + 1, it) }
            ?: context.getString(R.string.player_chapter_number, index + 1)
        Toast.makeText(context, label, Toast.LENGTH_SHORT).show()
    }
    return true
}
