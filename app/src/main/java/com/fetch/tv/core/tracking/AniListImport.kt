package com.fetch.tv.core.tracking

import com.fetch.tv.data.anilist.AniListListEntry
import com.fetch.tv.data.anilist.AniListStatus
import com.fetch.tv.domain.model.WatchedItem

/**
 * An AniList account's anime as watched marks. AniList only knows how far into each entry you
 * are, so an entry at progress 7 marks its episodes 1 to 7. Each entry is its own title with
 * flat episode numbers, keyed by its MyAnimeList id where it has one (what anime catalogs use)
 * and its AniList id otherwise. AniList has no resume points.
 */
fun buildAniListSnapshot(entries: List<AniListListEntry>): TrackingImportSnapshot {
    val watched = buildList {
        entries.forEach { entry ->
            if (entry.status == AniListStatus.PLANNING) return@forEach
            val contentId = entry.malId?.let { "mal:$it" } ?: "anilist:${entry.mediaId}"
            val updatedAt = entry.updatedAt * 1_000L
            if (entry.isMovie) {
                if (entry.progress >= 1 || entry.status == AniListStatus.COMPLETED) {
                    add(
                        WatchedItem(
                            contentId = contentId,
                            contentType = "movie",
                            title = entry.title,
                            watchedAt = updatedAt,
                            poster = entry.coverUrl
                        )
                    )
                }
                return@forEach
            }
            val last = entry.progress.coerceAtMost(MAX_EPISODES)
            for (episode in 1..last) {
                add(
                    WatchedItem(
                        contentId = contentId,
                        contentType = "series",
                        title = entry.title,
                        season = null,
                        episode = episode,
                        // Only the furthest episode's time is known: it is when the entry last changed.
                        watchedAt = if (episode == last) updatedAt else 0L,
                        poster = entry.coverUrl
                    )
                )
            }
        }
    }
    return TrackingImportSnapshot(watched = watched)
}

/** Long-running shows reach a few thousand episodes; anything past this is bad data. */
private const val MAX_EPISODES = 5_000
