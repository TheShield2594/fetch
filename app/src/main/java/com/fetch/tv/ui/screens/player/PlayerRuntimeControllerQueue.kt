package com.fetch.tv.ui.screens.player

import com.fetch.tv.data.local.QueueItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The queue without the video that is playing now; its head is the "Up next in queue" card. */
internal fun upcomingQueueItems(queue: List<QueueItem>, currentVideoId: String?): List<QueueItem> =
    queue.filterNot { it.videoId == currentVideoId }

internal fun PlayerRuntimeController.observeEpisodeQueue() {
    scope.launch {
        episodeQueueStore.items.collect { queue ->
            _uiState.update { it.copy(queueItems = queue) }
        }
    }
}

/** Next-episode autoplay seeds the queue with the episode it would play. */
internal fun PlayerRuntimeController.seedQueueWithNextEpisode(next: NextEpisodeInfo) {
    if (!next.hasAired || next.isOtherType) return
    val seriesId = contentId?.takeIf { it.isNotBlank() } ?: return
    val type = contentType?.takeIf { it.isNotBlank() } ?: return
    if (type.equals("cloud", ignoreCase = true)) return
    val item = QueueItem(
        contentId = seriesId,
        contentType = type,
        videoId = next.videoId,
        name = contentName?.takeIf { it.isNotBlank() } ?: title,
        season = next.season,
        episode = next.episode,
        episodeTitle = next.title.takeIf { it.isNotBlank() },
        poster = next.thumbnail ?: poster,
        backdrop = backdrop,
        logo = logo
    )
    scope.launch { runCatching { episodeQueueStore.seedFromAutoplay(item) } }
}

/** The video that just started is no longer waiting in the queue. */
internal fun PlayerRuntimeController.markCurrentVideoStartedInQueue() {
    val videoId = currentVideoId ?: return
    if (_uiState.value.queueItems.none { it.videoId == videoId }) return
    scope.launch { runCatching { episodeQueueStore.markStarted(videoId) } }
}

internal fun PlayerRuntimeController.showQueuePanel() {
    _uiState.update {
        it.copy(
            showQueuePanel = true,
            showControls = true,
            showSourcesPanel = false,
            showEpisodesPanel = false,
            showAudioOverlay = false,
            showSubtitleOverlay = false
        )
    }
}

internal fun PlayerRuntimeController.dismissQueuePanel() {
    _uiState.update { it.copy(showQueuePanel = false) }
    scheduleHideControls()
}

/**
 * The next episode of this series plays in place, like the Next episode button. Anything else
 * leaves the player through [PlayerUiState.pendingQueuePlayback], which the screen hands to
 * navigation so the item gets its own stream search.
 */
internal fun PlayerRuntimeController.playQueueItem(item: QueueItem) {
    val state = _uiState.value
    val next = state.nextEpisode
    _uiState.update { it.copy(showQueuePanel = false) }
    if (next != null && next.hasAired && next.videoId == item.videoId && item.contentId == contentId) {
        playNextEpisode(userInitiated = true)
        return
    }
    _uiState.update { it.copy(pendingQueuePlayback = item) }
}

internal fun PlayerRuntimeController.removeQueueItem(videoId: String) {
    scope.launch { runCatching { episodeQueueStore.remove(videoId) } }
}

/**
 * Undo for [removeQueueItem]: adds [item] back (at the end) and moves it up to [index], where it
 * sat before. The store only appends and moves one place at a time, so this walks it back.
 */
internal fun PlayerRuntimeController.restoreQueueItem(item: QueueItem, index: Int) {
    scope.launch {
        runCatching {
            episodeQueueStore.add(item)
            val restored = episodeQueueStore.items.first()
            val current = restored.indexOfFirst { it.videoId == item.videoId }
            if (current < 0) return@runCatching
            repeat((current - index.coerceAtLeast(0)).coerceAtLeast(0)) {
                episodeQueueStore.move(item.videoId, up = true)
            }
        }
    }
}

internal fun PlayerRuntimeController.moveQueueItem(videoId: String, up: Boolean) {
    scope.launch { runCatching { episodeQueueStore.move(videoId, up) } }
}
