package com.fetch.tv.core.tracking

import com.fetch.tv.data.anime.AnimeListEntry
import com.fetch.tv.data.anime.AnimeListStatus
import com.fetch.tv.domain.model.WatchedItem

/**
 * An AniList or MyAnimeList account's anime as watched marks. Both only know how far into each
 * entry you are, so an entry at progress 7 marks its episodes 1 to 7. Each entry is its own
 * title with flat episode numbers, keyed by its MyAnimeList id where it has one (what anime
 * catalogs use) and its AniList id otherwise. Neither has resume points.
 */
fun buildAnimeListSnapshot(entries: List<AnimeListEntry>): TrackingImportSnapshot {
    val watched = buildList {
        entries.forEach { entry ->
            if (entry.status == AnimeListStatus.PLANNING) return@forEach
            val contentId = entry.malId?.let { "mal:$it" }
                ?: entry.aniListId?.let { "anilist:$it" }
                ?: return@forEach
            val updatedAt = entry.updatedAt * 1_000L
            if (entry.isMovie) {
                if (entry.progress >= 1 || entry.status == AnimeListStatus.COMPLETED) {
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
