package com.fetch.tv.ui.screens.player

import android.widget.Toast
import com.fetch.tv.R
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Title a viewer-recorded skip marker belongs to; [season]/[episode] are null for movies. */
internal data class SkipMarkerTarget(val imdbId: String, val season: Int?, val episode: Int?)

private val IMDB_ID = Regex("tt[0-9]+")

internal fun PlayerRuntimeController.skipMarkerTarget(): SkipMarkerTarget? {
    val ids = listOfNotNull(currentVideoId, contentId).filter { it.isNotBlank() }
    val imdbId = ids.firstNotNullOfOrNull { id -> id.substringBefore(':').takeIf { it.matches(IMDB_ID) } }
        ?: contentType?.let { type ->
            ids.firstNotNullOfOrNull { id -> metaRepository.getCachedMeta(type, id.substringBefore(':'))?.imdbId }
        }?.takeIf { it.matches(IMDB_ID) }
        ?: return null
    if (contentType.equals("movie", ignoreCase = true)) return SkipMarkerTarget(imdbId, null, null)
    val season = currentSeason ?: return null
    val episode = currentEpisode ?: return null
    return SkipMarkerTarget(imdbId, season, episode)
}

/** Shows the "mark segment" control only when an IntroDB key is set and the title has an IMDb id. */
internal fun PlayerRuntimeController.refreshSkipMarkerSubmission() {
    scope.launch {
        val target = skipMarkerTarget()
        val available = target != null && skipIntroRepository.canSubmitMarkers()
        val type = if (target != null && target.season == null) "outro" else "intro"
        _uiState.update {
            if (it.markerSubmissionAvailable == available && it.markerSegmentType == type) it
            else it.copy(markerSubmissionAvailable = available, markerSegmentType = type, markerStartSec = null)
        }
    }
}

/** First press records the segment start at the current position; the second submits it. */
internal fun PlayerRuntimeController.markSkipSegmentBoundary() {
    val state = _uiState.value
    if (!state.markerSubmissionAvailable) return
    val positionSec = (currentPlaybackPositionMs() ?: return) / 1000.0
    val start = state.markerStartSec
    if (start == null || positionSec <= start) {
        _uiState.update { it.copy(markerStartSec = positionSec) }
        Toast.makeText(context, context.getString(R.string.skip_marker_start_recorded), Toast.LENGTH_SHORT).show()
        return
    }
    val target = skipMarkerTarget() ?: return
    _uiState.update { it.copy(markerStartSec = null) }
    scope.launch {
        val ok = skipIntroRepository.submitMarker(
            imdbId = target.imdbId,
            season = target.season,
            episode = target.episode,
            type = state.markerSegmentType,
            startSec = start,
            endSec = positionSec
        )
        Toast.makeText(
            context,
            context.getString(if (ok) R.string.skip_marker_submitted else R.string.skip_marker_submit_failed),
            Toast.LENGTH_SHORT
        ).show()
    }
}

/** Reports the segment on screen as wrong to its provider, then hides the button for it. */
internal fun PlayerRuntimeController.reportActiveSkipMarker() {
    val interval = _uiState.value.activeSkipInterval ?: return
    if (!com.fetch.tv.data.repository.canReportSkipInterval(interval)) return
    skippedIntervalKey = interval.autoSkipKey()
    _uiState.update { it.copy(activeSkipInterval = null, skipIntervalDismissed = true) }
    scope.launch {
        val ok = skipIntroRepository.reportWrongMarker(interval)
        Toast.makeText(
            context,
            context.getString(if (ok) R.string.skip_marker_reported else R.string.skip_marker_report_failed),
            Toast.LENGTH_SHORT
        ).show()
    }
}
