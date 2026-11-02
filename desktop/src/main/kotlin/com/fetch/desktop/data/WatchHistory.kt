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
        .filter { !it.hidden && (it.upNext || it.positionMs > 0 || (it.remotePercent ?: 0f) > 0f) }
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

    /**
     * mpv's --start value to resume [videoId]: seconds from a local position, or a percentage
     * from Trakt or Simkl ("42%"). Null starts at the beginning.
     */
    fun resumeStart(file: ProgressFile, videoId: String): String? {
        resumeSeconds(file, videoId)?.let { return it.toString() }
        val entry = file.entries.firstOrNull { it.videoId == videoId && !it.upNext } ?: return null
        val percent = entry.remotePercent?.takeIf { entry.durationMs <= 0 } ?: return null
        if (percent < WatchProgress.STARTED_THRESHOLD * 100 || percent >= WatchProgress.COMPLETED_THRESHOLD * 100) return null
        return "%.1f%%".format(java.util.Locale.ROOT, percent)
    }

    /**
     * Merges in-progress titles from Trakt or Simkl: a remote one wins when it is newer than the
     * local entry for that title (or there is none). Finished or hidden local entries stay.
     */
    fun mergeRemote(file: ProgressFile, remote: List<ProgressEntry>, watchedIds: Set<String>): ProgressFile {
        var entries = file.entries
        remote.forEach { incoming ->
            val local = entries.firstOrNull { it.contentId == incoming.contentId }
            if (local == null || incoming.lastWatched > local.lastWatched) {
                // Newer activity elsewhere brings a removed title back, as watching it here would.
                entries = entries.filter { it.contentId != incoming.contentId } + incoming
            }
        }
        return ProgressFile(entries = entries, watched = (file.watched + watchedIds).distinct())
    }
}

/** "38m left" / "1h 5m left", as on the TV cards; "40% watched" from Trakt or Simkl. */
fun remainingLabel(entry: ProgressEntry): String? {
    if (entry.upNext) return null
    if (entry.durationMs <= 0) return entry.remotePercent?.let { "${it.toInt()}% watched" }
    val minutes = ((entry.durationMs - entry.positionMs).coerceAtLeast(0) / 60_000).coerceAtLeast(1)
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m left" else "${minutes}m left"
}

fun episodeTag(season: Int?, episode: Int?): String? =
    if (season != null && episode != null) "S$season E$episode" else null
