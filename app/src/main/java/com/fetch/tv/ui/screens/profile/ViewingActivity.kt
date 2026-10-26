package com.fetch.tv.ui.screens.profile

import com.fetch.tv.domain.model.WatchProgress
import com.fetch.tv.domain.model.WatchedItem

/** One line of a profile's viewing activity: a movie or an episode, and when it was last watched. */
data class ViewingActivityEntry(
    val contentId: String,
    val contentType: String,
    val title: String,
    val season: Int?,
    val episode: Int?,
    val episodeTitle: String?,
    val watchedAt: Long,
    /** Null when it was finished (or marked watched) rather than stopped part way. */
    val percentWatched: Int?
)

internal const val VIEWING_ACTIVITY_LIMIT = 300

/**
 * A profile's viewing activity, newest first, from what it finished ([watched]) and what it
 * stopped part way through ([progress]). A title or episode in both appears once, as whichever
 * happened last. Entries without a time are left out, as they say nothing about when.
 */
internal fun viewingActivity(
    watched: List<WatchedItem>,
    progress: Collection<WatchProgress>,
    limit: Int = VIEWING_ACTIVITY_LIMIT
): List<ViewingActivityEntry> {
    val entries = HashMap<Triple<String, Int?, Int?>, ViewingActivityEntry>()
    fun offer(entry: ViewingActivityEntry) {
        if (entry.watchedAt <= 0L) return
        val key = Triple(entry.contentId, entry.season, entry.episode)
        val existing = entries[key]
        // A finished entry has no episode title, so it keeps the one its progress entry had.
        entries[key] = when {
            existing == null -> entry
            entry.watchedAt > existing.watchedAt ->
                entry.copy(episodeTitle = entry.episodeTitle ?: existing.episodeTitle)
            else -> existing.copy(episodeTitle = existing.episodeTitle ?: entry.episodeTitle)
        }
    }
    watched.forEach { item ->
        offer(
            ViewingActivityEntry(
                contentId = item.contentId,
                contentType = item.contentType,
                title = item.title,
                season = item.season,
                episode = item.episode,
                episodeTitle = null,
                watchedAt = item.watchedAt,
                percentWatched = null
            )
        )
    }
    progress.forEach { item ->
        offer(
            ViewingActivityEntry(
                contentId = item.contentId,
                contentType = item.contentType,
                title = item.name,
                season = item.season,
                episode = item.episode,
                episodeTitle = item.episodeTitle,
                watchedAt = item.lastWatched,
                percentWatched = if (item.isCompleted()) null else (item.progressPercentage * 100).toInt()
            )
        )
    }
    return entries.values
        .sortedByDescending { it.watchedAt }
        .take(limit)
}
