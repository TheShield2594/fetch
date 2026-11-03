package com.fetch.tv.data.website

import com.fetch.tv.domain.model.LibraryEntryInput
import com.fetch.tv.domain.model.WatchProgress
import java.time.Instant
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// What the TV and desktop apps send the website, and the edits they make to the snapshot they
// hold straight after a write, which the next read confirms.

/** The website's longest position; a duration past it isn't sent. */
private const val MAX_PLAYBACK_SECONDS = 12L * 60 * 60

/** A resume point to `PUT /playback`, in whole seconds when the player knows them. */
fun fetchWebsitePlaybackBody(progress: WatchProgress): JsonObject? {
    if (!progress.contentId.startsWith("tt")) return null
    val isEpisode = progress.season != null && progress.episode != null
    if (!isEpisode && !progress.contentType.equals("movie", ignoreCase = true)) return null
    val durationSeconds = progress.duration / 1000L
    val positionSeconds = progress.position / 1000L
    return buildJsonObject {
        put("type", if (isEpisode) "episode" else "movie")
        put("imdbId", progress.contentId)
        if (isEpisode) {
            put("seriesImdbId", progress.contentId)
            put("season", progress.season)
            put("episode", progress.episode)
        }
        if (durationSeconds in 1..MAX_PLAYBACK_SECONDS && positionSeconds in 0..durationSeconds) {
            put("positionSeconds", positionSeconds)
            put("durationSeconds", durationSeconds)
        } else {
            put("progress", (progress.progressPercentage * 100f * 10f).roundToInt() / 10.0)
        }
    }
}

/** A resume point held here before the website has given it an id; never sent back to it. */
const val LOCAL_PLAYBACK_ID_PREFIX = "local:"

fun isLocalPlaybackId(id: String): Boolean = id.startsWith(LOCAL_PLAYBACK_ID_PREFIX)

fun FetchWebsiteTrackingSnapshot.withPlayback(entry: WebsitePlayback): FetchWebsiteTrackingSnapshot {
    val others = playback.filterNot { it.imdbId == entry.imdbId && it.season == entry.season && it.episode == entry.episode }
    val previous = playback.firstOrNull { it.imdbId == entry.imdbId && it.season == entry.season && it.episode == entry.episode }
    val merged = previous?.let { entry.copy(id = it.id, name = entry.name ?: it.name, poster = entry.poster ?: it.poster) } ?: entry
    return copy(playback = listOf(merged) + others)
}

fun FetchWebsiteTrackingSnapshot.withListMembership(
    addTo: Collection<String>,
    removeFrom: Collection<String>,
    type: String,
    imdbId: String,
    input: LibraryEntryInput
): FetchWebsiteTrackingSnapshot {
    val added = WebsiteListItem(
        type = type,
        imdbId = imdbId,
        addedAt = Instant.now().toString(),
        title = input.title,
        metadata = WebsiteListItemMetadata(name = input.title, poster = input.poster, year = input.year)
    )
    val updated = listItems.toMutableMap()
    addTo.forEach { listId ->
        val items = updated[listId].orEmpty()
        if (items.none { it.type == type && it.imdbId == imdbId }) updated[listId] = listOf(added) + items
    }
    removeFrom.forEach { listId ->
        updated[listId] = updated[listId].orEmpty().filterNot { it.type == type && it.imdbId == imdbId }
    }
    return copy(listItems = updated)
}
