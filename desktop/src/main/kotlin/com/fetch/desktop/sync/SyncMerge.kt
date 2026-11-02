package com.fetch.desktop.sync

import com.fetch.desktop.data.LibraryEntry
import com.fetch.desktop.data.ProgressEntry
import kotlinx.serialization.Serializable

/** What this profile's library held after its last sync, so deletes can be told from additions. */
@Serializable
data class SyncBase(val libraryIds: List<String> = emptyList())

data class LibraryMerge(
    val local: List<LibraryEntry>,
    val push: List<LibraryEntry>,
    val deleteRemote: List<LibraryEntry>,
    val base: SyncBase
)

/**
 * Two-way merges for the sync server. The library is a three-way merge against [SyncBase]: a
 * title gone from one side since the last sync was deleted there, so it goes from the other side
 * too; anything new on either side is copied across.
 */
object SyncMerge {

    fun library(local: List<LibraryEntry>, remote: List<RemoteLibraryItem>, base: SyncBase): LibraryMerge {
        val baseIds = base.libraryIds.toSet()
        val localIds = local.mapTo(HashSet()) { it.id }
        val remoteById = remote.associateBy { it.id }
        val deletedHere = baseIds - localIds
        val deletedThere = baseIds - remoteById.keys
        val kept = local.filter { it.id !in deletedThere }
        val added = remote.filter { it.id !in localIds && it.id !in deletedHere }.map { it.toEntry() }
        val merged = kept + added
        return LibraryMerge(
            local = merged,
            push = kept.filter { it.id !in remoteById },
            deleteRemote = deletedHere.mapNotNull { id -> remoteById[id]?.toEntry() },
            base = SyncBase(merged.map { it.id })
        )
    }

    /** Watched videos both ways: Cinemeta ids, "tt1" for a movie, "tt1:2:5" for an episode. */
    fun watchedToPush(localIds: Collection<String>, remote: List<RemoteWatchedItem>, now: Long): List<RemoteWatchedItem> {
        val remoteIds = remote.mapTo(HashSet()) { it.videoId() }
        return localIds.filter { it !in remoteIds }.mapNotNull { watchedItem(it, now) }
    }

    fun RemoteWatchedItem.videoId(): String =
        if (season != null && episode != null) "$contentId:$season:$episode" else contentId

    fun watchedItem(videoId: String, now: Long): RemoteWatchedItem? {
        val parts = videoId.split(':')
        val id = parts.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val season = parts.getOrNull(1)?.toIntOrNull()
        val episode = parts.getOrNull(2)?.toIntOrNull()
        val isEpisode = season != null && episode != null
        return RemoteWatchedItem(id, if (isEpisode) "series" else "movie", season = season, episode = episode, watchedAtEpochMs = now)
    }

    /** Local positions worth sharing: real positions from this app, not other services' guesses. */
    fun progressToPush(local: List<ProgressEntry>): List<RemoteProgress> = local
        .filter { it.source == null && !it.upNext && it.positionMs > 0 && it.durationMs > 0 }
        .map { RemoteProgress(it.contentId, it.contentType, it.videoId, it.season, it.episode, it.positionMs, it.durationMs, it.lastWatched) }

    /** Remote positions as Continue Watching entries; names come from [known] or are filled in later. */
    fun progressEntries(remote: List<RemoteProgress>, known: Map<String, ProgressEntry>): List<ProgressEntry> =
        remote.filter { it.positionMs > 0 && it.durationMs > 0 }
            .groupBy { it.contentId }
            .map { (_, items) -> items.maxBy { it.lastWatchedEpochMs } }
            .map { p ->
                val seen = known[p.contentId]
                val imdb = p.contentId.takeIf { it.startsWith("tt") }
                ProgressEntry(
                    contentId = p.contentId,
                    contentType = p.contentType,
                    name = seen?.name ?: p.contentId,
                    poster = seen?.poster ?: imdb?.let { "https://images.metahub.space/poster/small/$it/img" },
                    backdrop = seen?.backdrop ?: imdb?.let { "https://images.metahub.space/background/medium/$it/img" },
                    logo = seen?.logo ?: imdb?.let { "https://images.metahub.space/logo/medium/$it/img" },
                    videoId = p.videoId,
                    season = p.season,
                    episode = p.episode,
                    episodeTitle = seen?.takeIf { it.videoId == p.videoId }?.episodeTitle,
                    positionMs = p.positionMs,
                    durationMs = p.durationMs,
                    lastWatched = p.lastWatchedEpochMs
                )
            }

    private fun RemoteLibraryItem.toEntry() = LibraryEntry(
        id = id,
        type = type,
        name = name,
        poster = poster,
        posterShape = posterShape.lowercase(),
        background = background,
        description = description,
        releaseInfo = releaseInfo,
        imdbRating = imdbRating,
        genres = genres,
        addonBaseUrl = addonBaseUrl,
        addedAt = addedAtEpochMs
    )

    fun LibraryEntry.toRemote() = RemoteLibraryItem(
        id = id,
        type = type,
        name = name,
        poster = poster,
        posterShape = posterShape.uppercase(),
        background = background,
        description = description,
        releaseInfo = releaseInfo,
        imdbRating = imdbRating,
        genres = genres,
        addonBaseUrl = addonBaseUrl,
        addedAtEpochMs = addedAt
    )
}
