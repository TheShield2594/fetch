package com.fetch.tv.ui.screens.detail

import android.util.Log
import com.fetch.tv.core.streams.BestMatchPreview
import com.fetch.tv.core.streams.BestMatchPreviewer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Decides when the detail page asks stream addons what Best match would play. Each search tells
 * every stream addon which title is open and can cost debrid requests, so a page that is only
 * passed through never searches: a pick saved in the last day is shown at once, and otherwise
 * the search waits for a long stay on the page or for Play to keep focus for a moment.
 */
internal class BestMatchPreviewLookup(
    private val scope: CoroutineScope,
    private val previewer: BestMatchPreviewer,
    private val dwellMs: Long = DWELL_MS,
    private val playHoldMs: Long = PLAY_HOLD_MS
) {
    private data class Target(
        val type: String,
        val videoId: String,
        val season: Int?,
        val episode: Int?,
        val contentId: String?
    )

    private val _preview = MutableStateFlow<BestMatchPreview?>(null)
    /** What Best match will play from the Play button, once known. */
    val preview: StateFlow<BestMatchPreview?> = _preview.asStateFlow()

    private var target: Target? = null
    private var cachedPick: Deferred<BestMatchPreview?>? = null
    private var dwellJob: Job? = null
    private var holdJob: Job? = null
    private var searchJob: Job? = null
    private var playFocused = false

    /** The video Play would start changed (or the page opened): start over for it. */
    fun show(type: String, videoId: String, season: Int?, episode: Int?, contentId: String?) {
        val next = Target(type, videoId, season, episode, contentId)
        if (next == target) return
        target = next
        cancelAll()
        if (_preview.value?.videoId != videoId) _preview.value = null
        val cached = scope.async {
            runCatching { previewer.cached(type, videoId, contentId) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
                ?.also { _preview.value = it }
        }
        cachedPick = cached
        dwellJob = scope.launch {
            delay(dwellMs)
            searchUnlessCached(next)
        }
        if (playFocused) startHold(next)
    }

    /** Play holding focus for [playHoldMs] is a sign the viewer means to play, so search then. */
    fun onPlayFocusChanged(focused: Boolean) {
        if (focused == playFocused) return
        playFocused = focused
        holdJob?.cancel()
        holdJob = null
        if (focused) target?.let(::startHold)
    }

    /**
     * The page left the screen. A search that has not started is dropped, so a page kept on the
     * back stack never searches; one already running finishes, since pressing Play joins it.
     */
    fun pause() {
        playFocused = false
        holdJob?.cancel()
        holdJob = null
        val waiting = dwellJob?.isActive == true
        dwellJob?.cancel()
        dwellJob = null
        // Coming back starts the wait again; a search or a saved pick stays as it is.
        if (waiting && searchJob == null) target = null
    }

    private fun startHold(forTarget: Target) {
        holdJob = scope.launch {
            delay(playHoldMs)
            searchUnlessCached(forTarget)
        }
    }

    private suspend fun searchUnlessCached(forTarget: Target) {
        if (cachedPick?.await() != null) return
        // Whichever of the dwell and the hold ends first searches; the other finds it started.
        if (forTarget != target || searchJob != null) return
        searchJob = scope.launch {
            try {
                previewer.preview(
                    type = forTarget.type,
                    videoId = forTarget.videoId,
                    season = forTarget.season,
                    episode = forTarget.episode,
                    contentId = forTarget.contentId
                ).collect { _preview.value = it }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Best match preview failed for ${forTarget.videoId}", error)
            }
        }
    }

    private fun cancelAll() {
        cachedPick?.cancel()
        cachedPick = null
        dwellJob?.cancel()
        dwellJob = null
        holdJob?.cancel()
        holdJob = null
        searchJob?.cancel()
        searchJob = null
    }

    companion object {
        private const val TAG = "BestMatchPreviewLookup"

        /** How long the page stays open before searching anyway, with focus away from Play. */
        const val DWELL_MS = 8_000L

        /** How long Play keeps focus before searching. */
        const val PLAY_HOLD_MS = 3_000L
    }
}
