package com.fetch.desktop.data

import com.fetch.tv.domain.model.Video
import com.fetch.tv.domain.model.WatchProgress

/** What Fetch knows it is playing, so a position from mpv can be saved against it. */
data class PlaybackTarget(
    val contentId: String,
    val contentType: String,
    val name: String,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val video: Video?,
    /** The show's episodes, to find the next one when this one is finished. */
    val episodes: List<Video> = emptyList()
) {
    val videoId: String get() = video?.id ?: contentId
}

/**
 * The TV app's Continue Watching rules, on the desktop's saved positions:
 * - a title counts as started at 2% and finished at 90% ([WatchProgress] thresholds);
 * - one entry per title, so a show shows only its latest episode;
 * - finishing an episode turns the entry into "Next Up" for the following episode (season 0,
 *   specials, never counts), finishing a movie or the last episode removes it.
 */
object WatchHistory {

    fun record(file: ProgressFile, target: PlaybackTarget, positionMs: Long, durationMs: Long, now: Long): ProgressFile {
        if (durationMs <= 0) return file
        val fraction = positionMs.toFloat() / durationMs
        val others = file.entries.filter { it.contentId != target.contentId }
        val existing = file.entries.firstOrNull { it.contentId == target.contentId }

        if (fraction >= WatchProgress.COMPLETED_THRESHOLD) {
            val watched = (file.watched + target.videoId).distinct()
            val next = target.video?.let { nextEpisode(target.episodes, it, watched.toSet()) }
            val entries = if (next == null) others else others + entryFor(target, next, 0, 0, now).copy(upNext = true)
            return ProgressFile(entries = entries, watched = watched)
        }
        // Opening a stream and closing it straight away doesn't start a title.
        if (fraction < WatchProgress.STARTED_THRESHOLD && existing?.videoId != target.videoId) return file
        val entry = entryFor(target, target.video, positionMs, durationMs, now)
        return file.copy(entries = others + entry)
    }

    private fun entryFor(target: PlaybackTarget, video: Video?, positionMs: Long, durationMs: Long, now: Long) = ProgressEntry(
        contentId = target.contentId,
        contentType = target.contentType,
        name = target.name,
        poster = target.poster,
        backdrop = target.backdrop,
        logo = target.logo,
        videoId = video?.id ?: target.contentId,
        season = video?.season,
        episode = video?.episode,
        episodeTitle = video?.title?.takeIf { it.isNotBlank() },
        positionMs = positionMs,
        durationMs = durationMs,
        lastWatched = now
    )

    /** The first episode after [current], in season and episode order, not already watched. */
    fun nextEpisode(episodes: List<Video>, current: Video, watched: Set<String> = emptySet()): Video? {
        val ordered = episodes
            .filter { (it.season ?: 0) > 0 && it.episode != null }
            .sortedWith(compareBy({ it.season }, { it.episode }))
        val index = ordered.indexOfFirst { it.id == current.id }
        if (index < 0) return null
        return ordered.drop(index + 1).firstOrNull { it.id !in watched }
    }

    /** Continue Watching, most recent first. */
    fun continueWatching(file: ProgressFile): List<ProgressEntry> = file.entries
        .filter { !it.hidden && (it.upNext || it.positionMs > 0) }
        .sortedByDescending { it.lastWatched }

    fun hide(file: ProgressFile, contentId: String): ProgressFile =
        file.copy(entries = file.entries.map { if (it.contentId == contentId) it.copy(hidden = true) else it })

    /** Forgets where a title was stopped, so it plays from the start. */
    fun clearPosition(file: ProgressFile, contentId: String): ProgressFile =
        file.copy(entries = file.entries.filter { it.contentId != contentId })

    /** Seconds to resume [videoId] from, or null to start at the beginning. */
    fun resumeSeconds(file: ProgressFile, videoId: String): Long? {
        val entry = file.entries.firstOrNull { it.videoId == videoId && !it.upNext } ?: return null
        if (entry.durationMs <= 0 || entry.positionMs.toFloat() / entry.durationMs >= WatchProgress.COMPLETED_THRESHOLD) return null
        return (entry.positionMs / 1000).takeIf { it > 0 }
    }
}

/** "38m left" / "1h 5m left", as on the TV cards. */
fun remainingLabel(entry: ProgressEntry): String? {
    if (entry.upNext || entry.durationMs <= 0) return null
    val minutes = ((entry.durationMs - entry.positionMs).coerceAtLeast(0) / 60_000).coerceAtLeast(1)
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m left" else "${minutes}m left"
}

fun episodeTag(season: Int?, episode: Int?): String? =
    if (season != null && episode != null) "S$season E$episode" else null
