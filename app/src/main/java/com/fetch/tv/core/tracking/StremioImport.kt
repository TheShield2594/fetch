package com.fetch.tv.core.tracking

import com.fetch.tv.domain.model.Video
import com.fetch.tv.domain.model.WatchProgress
import com.fetch.tv.domain.model.WatchedItem
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.OffsetDateTime
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import okio.ByteString.Companion.decodeBase64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reading a Stremio account's library into an import snapshot.
 *
 * Stremio keeps one library item per title, whether or not it is still in the library: the
 * `state` holds the resume point (`timeOffset` of `duration`, for `video_id`), how often it was
 * watched, and for series a compressed bit per episode (`watched`). The episode bits only mean
 * something against the series' episode list in the order Stremio sorts it, so series need
 * their episodes from an addon; see [decodeStremioWatchedVideoIds].
 */
data class StremioLibraryItem(
    val id: String,
    val type: String,
    val name: String,
    val poster: String?,
    val removed: Boolean,
    val temp: Boolean,
    /** Epoch ms, or 0 when Stremio has no time. */
    val lastWatched: Long,
    val timeOffset: Long,
    val duration: Long,
    val timesWatched: Int,
    val flaggedWatched: Int,
    val videoId: String?,
    val watched: String?
) {
    val isMovie: Boolean get() = type.equals("movie", ignoreCase = true)

    /** Types Fetch treats as episodic. Channels, TV and "other" items have no watch state to bring over. */
    val isSeries: Boolean get() = type.lowercase() in SERIES_TYPES

    private companion object {
        val SERIES_TYPES = setOf("series", "anime")
    }
}

/** Parses the `result` of `datastoreGet` on the `libraryItem` collection. Malformed items are dropped. */
fun parseStremioLibrary(body: String): List<StremioLibraryItem> {
    val root = JSONObject(body)
    root.optJSONObject("error")?.let { error ->
        throw StremioApiException(error.optInt("code"), error.optString("message"))
    }
    val items = root.optJSONArray("result") ?: JSONArray()
    return buildList {
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val id = item.optString("_id").trim()
            if (id.isEmpty()) continue
            val state = item.optJSONObject("state") ?: JSONObject()
            add(
                StremioLibraryItem(
                    id = id,
                    type = item.optString("type"),
                    name = item.optString("name"),
                    poster = item.optNullableString("poster"),
                    removed = item.optBoolean("removed"),
                    temp = item.optBoolean("temp"),
                    lastWatched = parseStremioTime(state.optNullableString("lastWatched")),
                    timeOffset = state.optLong("timeOffset").coerceAtLeast(0L),
                    duration = state.optLong("duration").coerceAtLeast(0L),
                    timesWatched = state.optInt("timesWatched"),
                    flaggedWatched = state.optInt("flaggedWatched"),
                    videoId = state.optNullableString("video_id"),
                    watched = state.optNullableString("watched")
                )
            )
        }
    }
}

class StremioApiException(val code: Int, message: String) : Exception(message)

/**
 * The ids of the videos a Stremio `watched` field marks as watched, given the series' videos.
 *
 * The field is `<anchor video id>:<anchor length>:<base64 zlib bitfield>`, where bit `i` (least
 * significant bit first) is the i-th video in Stremio's order (season, then episode, then release
 * date; see [stremioVideoOrder]) and the anchor is the last watched video at index
 * `anchor length - 1`. Episodes added or removed since then shift the list, so the bits are
 * re-aligned on the anchor, exactly as Stremio does. An anchor missing from [videos] means the
 * bits can't be placed, and nothing is returned.
 */
fun decodeStremioWatchedVideoIds(watched: String, videos: List<Video>): Set<String>? {
    val field = parseStremioWatchedField(watched) ?: return null
    val ordered = stremioVideoOrder(videos).map(Video::id)
    val anchorIndex = ordered.indexOf(field.anchorVideo)
    if (anchorIndex < 0) return null
    val offset = field.anchorLength - anchorIndex - 1
    return buildSet {
        ordered.forEachIndexed { index, id ->
            val previous = index + offset
            if (previous >= 0 && field.isSet(previous)) add(id)
        }
    }
}

internal data class StremioWatchedField(
    val anchorVideo: String,
    val anchorLength: Int,
    val bits: ByteArray
) {
    fun isSet(index: Int): Boolean {
        val byte = index / 8
        if (index < 0 || byte >= bits.size) return false
        return (bits[byte].toInt() shr (index % 8)) and 1 != 0
    }
}

/** Splits from the right: the anchor video id has colons of its own (`tt0944947:1:2`). */
internal fun parseStremioWatchedField(value: String): StremioWatchedField? {
    val lastColon = value.lastIndexOf(':')
    if (lastColon <= 0) return null
    val lengthColon = value.lastIndexOf(':', lastColon - 1)
    if (lengthColon <= 0) return null
    val anchorVideo = value.substring(0, lengthColon)
    val anchorLength = value.substring(lengthColon + 1, lastColon).toIntOrNull() ?: return null
    if (anchorLength <= 0 || anchorVideo == "undefined") return null
    val compressed = value.substring(lastColon + 1).decodeBase64()?.toByteArray() ?: return null
    val bits = inflateCapped(compressed) ?: return null
    return StremioWatchedField(anchorVideo, anchorLength, bits)
}

private fun inflateCapped(compressed: ByteArray): ByteArray? = runCatching {
    val output = ByteArrayOutputStream()
    InflaterInputStream(compressed.inputStream(), Inflater()).use { input ->
        val buffer = ByteArray(4096)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            // One bit per episode: anything near this size is not a real series.
            if (output.size() > MAX_BITFIELD_BYTES) return null
        }
    }
    output.toByteArray()
}.getOrNull()

private const val MAX_BITFIELD_BYTES = 64 * 1024

/**
 * Stremio's episode order for the watched bitfield: season, then episode, then release date,
 * each missing value first. A video counts as having a season and episode only when it has
 * both. The sort is stable, so ties keep the addon's order.
 */
fun stremioVideoOrder(videos: List<Video>): List<Video> = videos.sortedWith(
    compareBy<Video>(
        { if (it.season != null && it.episode != null) it.season!!.toLong() else Long.MIN_VALUE },
        { if (it.season != null && it.episode != null) it.episode!!.toLong() else Long.MIN_VALUE },
        { parseStremioTime(it.released).takeIf { time -> time != 0L } ?: Long.MIN_VALUE }
    )
)

/**
 * Builds the import snapshot. [videosFor] returns a series' episodes from an addon, or null when
 * none could be fetched; it is only asked about series with watched episodes or a resume point.
 */
suspend fun buildStremioSnapshot(
    items: List<StremioLibraryItem>,
    videosFor: suspend (StremioLibraryItem) -> List<Video>?
): TrackingImportSnapshot {
    val watched = mutableListOf<WatchedItem>()
    val inProgress = mutableListOf<WatchProgress>()
    var unreadable = 0
    items.forEach { item ->
        when {
            item.isMovie -> {
                if (item.timesWatched > 0 || item.flaggedWatched > 0) {
                    watched += WatchedItem(
                        contentId = item.id,
                        contentType = "movie",
                        title = item.name,
                        // Watched before and being played again: lastWatched is this playback, not
                        // when it was finished, which stays unknown so the resume point survives.
                        watchedAt = if (item.isInContinueWatching()) 0L else item.lastWatched,
                        poster = item.poster
                    )
                }
                if (item.isInContinueWatching()) {
                    inProgress += item.toProgress(videoId = item.videoId ?: item.id, season = null, episode = null, episodeTitle = null)
                }
            }
            item.isSeries -> {
                val needsEpisodes = !item.watched.isNullOrBlank() || item.isInContinueWatching()
                if (!needsEpisodes) return@forEach
                val videos = videosFor(item).orEmpty()
                val byId = videos.associateBy(Video::id)

                item.watched?.takeIf(String::isNotBlank)?.let { field ->
                    val ids = decodeStremioWatchedVideoIds(field, videos)
                    if (ids == null) {
                        unreadable++
                        // The anchor is the last episode watched, whatever the list: still true on its own.
                        parseStremioWatchedField(field)?.anchorVideo
                            ?.let { anchor -> episodeOf(anchor, item.id, byId) }
                            ?.let { (season, episode) -> watched += item.episodeMark(season, episode, item.lastWatchedFor(field)) }
                    } else {
                        var unmapped = false
                        ids.forEach { id ->
                            val position = episodeOf(id, item.id, byId)
                            if (position == null) {
                                unmapped = true
                                return@forEach
                            }
                            watched += item.episodeMark(position.first, position.second, item.watchedTimeOf(id))
                        }
                        // Some watched episodes had no season or episode number to file them under.
                        if (unmapped) unreadable++
                    }
                }

                if (item.isInContinueWatching()) {
                    val videoId = item.videoId
                    val position = videoId?.let { episodeOf(it, item.id, byId) }
                    if (videoId != null && position != null) {
                        inProgress += item.toProgress(
                            videoId = videoId,
                            season = position.first,
                            episode = position.second,
                            episodeTitle = byId[videoId]?.title?.takeIf(String::isNotBlank)
                        )
                    }
                }
            }
        }
    }
    return TrackingImportSnapshot(watched = watched, inProgress = inProgress, unreadable = unreadable)
}

/** The watched time for the anchor episode: only known when it is also the last video played. */
private fun StremioLibraryItem.lastWatchedFor(field: String): Long =
    parseStremioWatchedField(field)?.anchorVideo?.let(::watchedTimeOf) ?: 0L

/**
 * When episode [videoId] was finished: [StremioLibraryItem.lastWatched] when it is the last video
 * played and not being played again now (a resume point means lastWatched is that playback);
 * otherwise unknown.
 */
private fun StremioLibraryItem.watchedTimeOf(videoId: String): Long =
    if (videoId == this.videoId && !isInContinueWatching()) lastWatched else 0L

/** Stremio's own rule for Continue Watching. */
private fun StremioLibraryItem.isInContinueWatching(): Boolean =
    (!removed || temp) && timeOffset > 0L && duration > 0L

/**
 * Season and episode of a video: from the addon's episode list, or from a `<series id>:<season>:<episode>`
 * video id when the list doesn't have it.
 */
internal fun episodeOf(videoId: String, seriesId: String, videos: Map<String, Video>): Pair<Int, Int>? {
    videos[videoId]?.let { video ->
        val season = video.season
        val episode = video.episode
        if (season != null && episode != null) return season to episode
    }
    if (!videoId.startsWith("$seriesId:")) return null
    val parts = videoId.removePrefix("$seriesId:").split(':')
    if (parts.size != 2) return null
    val season = parts[0].toIntOrNull() ?: return null
    val episode = parts[1].toIntOrNull() ?: return null
    return season to episode
}

private fun StremioLibraryItem.episodeMark(season: Int, episode: Int, watchedAt: Long) = WatchedItem(
    contentId = id,
    contentType = "series",
    title = name,
    season = season,
    episode = episode,
    watchedAt = watchedAt,
    poster = poster
)

private fun StremioLibraryItem.toProgress(
    videoId: String,
    season: Int?,
    episode: Int?,
    episodeTitle: String?
) = WatchProgress(
    contentId = id,
    contentType = if (isMovie) "movie" else "series",
    name = name,
    poster = poster,
    backdrop = null,
    logo = null,
    videoId = videoId,
    season = season,
    episode = episode,
    episodeTitle = episodeTitle,
    position = timeOffset.coerceAtMost(duration),
    duration = duration,
    lastWatched = lastWatched
)

/** Epoch ms of an ISO-8601 time, or 0 when absent or unreadable. */
internal fun parseStremioTime(value: String?): Long {
    val text = value?.trim().orEmpty()
    if (text.isEmpty()) return 0L
    return runCatching { Instant.parse(text).toEpochMilli() }
        .recoverCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }
        .getOrDefault(0L)
}

private fun JSONObject.optNullableString(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf(String::isNotBlank)
