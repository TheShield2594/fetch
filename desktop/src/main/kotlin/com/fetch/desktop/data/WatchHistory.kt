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

    fun record(
        file: ProgressFile,
        target: PlaybackTarget,
        positionMs: Long,
        durationMs: Long,
        now: Long,
        fromFurthest: Boolean = true
    ): ProgressFile {
        if (durationMs <= 0) return file
        val fraction = positionMs.toFloat() / durationMs
        val others = file.entries.filter { it.contentId != target.contentId }
        val existing = file.entries.firstOrNull { it.contentId == target.contentId }

        if (WatchProgress.isFinishedAt(positionMs, durationMs)) {
            val watched = (file.watched + target.videoId).distinct()
            return moveOn(file.copy(watched = watched), target, target.video, now, fromFurthest)
                .copy(finished = finished(file, target, now))
        }
        // Opening a stream and closing it straight away doesn't start a title.
        if (fraction < WatchProgress.STARTED_THRESHOLD && existing?.videoId != target.videoId) return file
        val entry = entryFor(target, target.video, positionMs, durationMs, now)
        return file.copy(entries = others + entry, caughtUp = file.caughtUp.filter { it.contentId != target.contentId })
    }

    /**
     * After [from] (an episode, or the movie when null) is finished: the show's entry becomes Next
     * Up for the episode after the furthest one watched (or after [from] when [fromFurthest] is
     * off, for rewatches), with its air date, as on TV. With nothing after it the show is caught
     * up: it leaves Continue Watching and is checked for new episodes ([refreshShow]).
     */
    private fun moveOn(file: ProgressFile, target: PlaybackTarget, from: Video?, now: Long, fromFurthest: Boolean): ProgressFile {
        val others = file.entries.filter { it.contentId != target.contentId }
        val caught = file.caughtUp.filter { it.contentId != target.contentId }
        if (from == null) return file.copy(entries = others, caughtUp = caught)
        val watched = file.watched.toSet()
        val base = (if (fromFurthest) furthestWatched(target.episodes, watched) else null) ?: from
        val next = nextEpisode(target.episodes, base, watched)
        return if (next != null) {
            file.copy(entries = others + upNextEntry(target, next, now), caughtUp = caught)
        } else {
            file.copy(entries = others, caughtUp = caught + caughtUpShow(target, base.id, now))
        }
    }

    private fun upNextEntry(target: PlaybackTarget, next: Video, now: Long) =
        entryFor(target, next, 0, 0, now).copy(upNext = true, airDate = next.released?.takeIf { it.isNotBlank() })

    private fun caughtUpShow(target: PlaybackTarget, lastVideoId: String, now: Long) = CaughtUpShow(
        contentId = target.contentId,
        contentType = target.contentType,
        name = target.name,
        poster = target.poster,
        backdrop = target.backdrop,
        logo = target.logo,
        lastVideoId = lastVideoId,
        at = now
    )

    /** The furthest real episode (season and episode order, no specials) in [watched]. */
    fun furthestWatched(episodes: List<Video>, watched: Set<String>): Video? =
        episodes.filter { (it.season ?: 0) > 0 && it.episode != null && it.id in watched }
            .maxWithOrNull(compareBy({ it.season }, { it.episode }))

    /**
     * Checks a show against a fresh episode list ([target]'s), as the TV re-checks Continue
     * Watching: a caught-up show whose next episode is now listed comes back as Next Up (with
     * its air date, so it shows as upcoming until then), and a Next Up's date is kept current.
     * A show part-way through an episode is left alone.
     */
    fun refreshShow(file: ProgressFile, target: PlaybackTarget, now: Long): ProgressFile {
        val existing = file.entries.firstOrNull { it.contentId == target.contentId }
        if (existing != null && !existing.upNext) return file
        val caught = file.caughtUp.firstOrNull { it.contentId == target.contentId }
        if (existing == null && caught == null) return file
        val watched = file.watched.toSet()
        val base = furthestWatched(target.episodes, watched) ?: return file
        val next = nextEpisode(target.episodes, base, watched) ?: return file
        val fresh = upNextEntry(target, next, existing?.lastWatched ?: caught?.at ?: now)
            .copy(hidden = existing?.hidden ?: false, source = existing?.source)
        if (existing == fresh) return file
        return file.copy(
            entries = file.entries.filter { it.contentId != target.contentId } + fresh,
            caughtUp = file.caughtUp.filter { it.contentId != target.contentId }
        )
    }

    /** Whether [entry] is a Next Up whose episode hasn't aired by [today]. */
    fun isUpcoming(entry: ProgressEntry, today: java.time.LocalDate): Boolean =
        entry.upNext && airDateOf(entry)?.isAfter(today) == true

    fun airDateOf(entry: ProgressEntry): java.time.LocalDate? =
        entry.airDate?.take(10)?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }

    /**
     * Continue Watching and the Upcoming row, by Layout's settings, as the TV splits them
     * (`splitUpcomingItems`): unaired Next Up only with [showUnaired]; Default sorts everything
     * by recency, Streaming style puts upcoming ones last, soonest first, and Separate Upcoming
     * row moves them to the second list.
     */
    fun continueWatchingRows(
        file: ProgressFile,
        today: java.time.LocalDate,
        showUnaired: Boolean,
        sort: com.fetch.tv.domain.model.ContinueWatchingSortMode
    ): Pair<List<ProgressEntry>, List<ProgressEntry>> {
        val all = continueWatching(file).filter { showUnaired || !isUpcoming(it, today) }
        if (sort == com.fetch.tv.domain.model.ContinueWatchingSortMode.DEFAULT) return all to emptyList()
        val (upcoming, aired) = all.partition { isUpcoming(it, today) }
        val soonest = upcoming.sortedBy { airDateOf(it) }
        return if (sort == com.fetch.tv.domain.model.ContinueWatchingSortMode.SPLIT_UPCOMING) aired to soonest else (aired + soonest) to emptyList()
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

    /**
     * Marks [videoIds] of [target]'s title watched or unwatched by hand, as the TV's "Mark as
     * watched" does. Watching a movie removes it from Continue Watching; when the episode in
     * Continue Watching is marked watched, the entry moves on to the next unwatched one (Next Up),
     * or goes when there is none.
     */
    fun setWatched(file: ProgressFile, target: PlaybackTarget, videoIds: Collection<String>, watched: Boolean, now: Long): ProgressFile {
        if (videoIds.isEmpty()) return file
        val ids = videoIds.toSet()
        if (!watched) return file.copy(watched = file.watched.filter { it !in ids })
        val updated = file.copy(watched = (file.watched + ids).distinct(), finished = finished(file, target, now))
        val existing = file.entries.firstOrNull { it.contentId == target.contentId }
        return when {
            existing == null || existing.videoId !in ids -> updated
            target.episodes.isEmpty() -> updated.copy(entries = file.entries.filter { it.contentId != target.contentId })
            else -> moveOn(updated, target, target.episodes.firstOrNull { it.id == existing.videoId }, now, fromFurthest = true)
        }
    }

    /** [file]'s finished titles with [target]'s show or movie first. */
    private fun finished(file: ProgressFile, target: PlaybackTarget, now: Long): List<FinishedTitle> =
        (listOf(FinishedTitle(target.contentId, target.contentType, target.name, now)) + file.finished.filter { it.contentId != target.contentId })
            .take(MAX_FINISHED)

    private const val MAX_FINISHED = 30

    /** The aired episodes before [episode] in season and episode order (specials excluded). */
    fun episodesBefore(episodes: List<Video>, episode: Video): List<Video> =
        episodes.filter { (it.season ?: 0) > 0 && it.episode != null }
            .sortedWith(compareBy({ it.season }, { it.episode }))
            .takeWhile { it.id != episode.id }

    /**
     * "Remove from Continue Watching": the title's entry stays, hidden and stamped with the
     * removal time [now], so only activity after the removal (here, or on Trakt, Simkl or the
     * Fetch website) brings it back.
     */
    fun hide(file: ProgressFile, contentId: String, now: Long): ProgressFile =
        file.copy(entries = file.entries.map { if (it.contentId == contentId) it.copy(hidden = true, lastWatched = maxOf(it.lastWatched, now)) else it })

    /** Forgets where a title was stopped, so it plays from the start. */
    fun clearPosition(file: ProgressFile, contentId: String): ProgressFile =
        file.copy(entries = file.entries.filter { it.contentId != contentId })

    /** Seconds to resume [videoId] from, or null to start at the beginning. */
    fun resumeSeconds(file: ProgressFile, videoId: String): Long? {
        val entry = file.entries.firstOrNull { it.videoId == videoId && !it.upNext } ?: return null
        if (entry.durationMs <= 0 || WatchProgress.isFinishedAt(entry.positionMs, entry.durationMs)) return null
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
        return file.copy(entries = entries, watched = (file.watched + watchedIds).distinct())
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
