package com.fetch.tv.ui.screens.player

import com.fetch.tv.core.streams.specLineLabels
import com.fetch.tv.core.streams.ranking.StreamTitleParser
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Called once the current stream shows its first frame. Takes the video off the episode queue,
 * puts the stream's spec line
 * ("4K · DV · Atmos · Cached") on the player overlay and remembers it as this video's
 * Best match, so the Home hero can show it without asking any addon.
 */
internal fun PlayerRuntimeController.onCurrentStreamFirstFrame() {
    markCurrentVideoStartedInQueue()
    val state = _uiState.value
    val text = listOfNotNull(
        state.currentStreamName,
        currentStreamDescription,
        currentFilename
    ).filter { it.isNotBlank() }.joinToString("\n")
    val descriptor = StreamTitleParser.parse(text)
    val labels = descriptor.specLineLabels()
    val isCached = descriptor.isDebridCached
    if (state.currentSourceSpecLabels != labels || state.currentSourceIsCached != isCached) {
        _uiState.update { it.copy(currentSourceSpecLabels = labels, currentSourceIsCached = isCached) }
    }
    val type = contentType ?: return
    val videoId = currentVideoId ?: return
    scope.launch {
        runCatching { bestMatchCacheDataStore.save(type, videoId, labels, isCached) }
    }
}
