package com.fetch.tv.ui.screens.detail

import com.fetch.tv.domain.model.WatchProgress

internal fun resolveNextToWatchLatestProgress(
    latestProgress: WatchProgress?,
    progressEntries: Collection<WatchProgress>,
    isResumable: (WatchProgress) -> Boolean
): WatchProgress? {
    val resumeOverride = progressEntries
        .filter(isResumable)
        // The most recently watched resumable episode; on a tie, the furthest one. The comparator
        // is ascending because maxWithOrNull returns the greatest element under it.
        .maxWithOrNull(
            compareBy<WatchProgress> { it.lastWatched }
                .thenBy { it.season ?: 0 }
                .thenBy { it.episode ?: 0 }
        )
    return when {
        resumeOverride != null && (
            latestProgress == null ||
                !isResumable(latestProgress) ||
                resumeOverride.lastWatched >= latestProgress.lastWatched
            ) -> resumeOverride
        else -> latestProgress
    }
}
