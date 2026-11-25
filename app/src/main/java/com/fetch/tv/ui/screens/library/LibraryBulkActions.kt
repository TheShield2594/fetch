package com.fetch.tv.ui.screens.library

import android.util.Log
import com.fetch.tv.core.network.NetworkResult
import com.fetch.tv.data.local.WatchedSeriesStateHolder
import com.fetch.tv.data.repository.parseContentIds
import com.fetch.tv.domain.model.LibraryEntry
import com.fetch.tv.domain.model.LibraryEntryInput
import com.fetch.tv.domain.model.LibraryListTab
import com.fetch.tv.domain.model.ListMembershipChanges
import com.fetch.tv.domain.model.WatchProgress
import com.fetch.tv.domain.model.watchableEpisodes
import com.fetch.tv.domain.repository.LibraryRepository
import com.fetch.tv.domain.repository.MetaRepository
import com.fetch.tv.domain.repository.WatchProgressRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** One title a bulk action works on, from the Saved grid or an activity tab. */
data class LibraryBulkTarget(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val imdbId: String? = null,
    val input: LibraryEntryInput
) {
    val isSeries: Boolean
        get() = type.equals("series", ignoreCase = true) || type.equals("tv", ignoreCase = true) ||
            type.equals("anime", ignoreCase = true)
}

internal fun LibraryEntry.toBulkTarget(): LibraryBulkTarget = LibraryBulkTarget(
    id = id,
    type = type,
    name = name,
    poster = poster,
    imdbId = imdbId,
    input = LibraryEntryInput(
        itemId = id,
        itemType = type,
        title = name,
        year = releaseInfo?.let { Regex("""\b(19|20)\d{2}\b""").find(it)?.value?.toIntOrNull() },
        traktId = traktId,
        simklId = simklId,
        imdbId = imdbId,
        tmdbId = tmdbId,
        poster = rawPosterUrl ?: poster,
        posterShape = posterShape,
        background = background,
        logo = logo,
        description = description,
        releaseInfo = releaseInfo,
        imdbRating = imdbRating,
        genres = genres,
        addonBaseUrl = addonBaseUrl
    )
)

internal fun LibraryActivityItem.toBulkTarget(): LibraryBulkTarget {
    val ids = parseContentIds(id)
    return LibraryBulkTarget(
        id = id,
        type = type,
        name = title,
        poster = poster,
        imdbId = ids.imdb,
        input = LibraryEntryInput(
            itemId = id,
            itemType = type,
            title = title,
            traktId = ids.trakt,
            simklId = ids.simkl,
            imdbId = ids.imdb,
            tmdbId = ids.tmdb,
            poster = poster,
            addonBaseUrl = addonBaseUrl
        )
    )
}

/** How a bulk action went: titles done, failed, and left alone (a list that can't hold them, or a removal the service wants confirmed one by one). */
data class LibraryBulkResult(val done: Int = 0, val failed: Int = 0, val skipped: Int = 0) {
    operator fun plus(other: LibraryBulkResult) =
        LibraryBulkResult(done + other.done, failed + other.failed, skipped + other.skipped)
}

/**
 * The Library's bulk actions (#653). No service takes a batch of titles, so each one loops over
 * the single-title writes the poster menu uses, one title at a time.
 */
class LibraryBulkActions @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val watchProgressRepository: WatchProgressRepository,
    private val metaRepository: MetaRepository,
    private val watchedSeriesStateHolder: WatchedSeriesStateHolder
) {
    /**
     * Puts [targets] on [addTo] and/or takes them off [removeFrom]. A removal the service asks to
     * confirm (it would also drop ratings or history) is skipped: that needs the one-title menu.
     */
    suspend fun changeMembership(
        targets: List<LibraryBulkTarget>,
        tabs: List<LibraryListTab>,
        addTo: String?,
        removeFrom: String?,
        isCurrent: () -> Boolean = { true }
    ): LibraryBulkResult = forEachTarget(targets, isCurrent) { target ->
        val snapshot = libraryRepository.getMembershipSnapshot(target.input).listMembership
        val desired = libraryBulkMembership(snapshot, tabs, target.type, addTo, removeFrom)
            ?: return@forEachTarget Outcome.SKIPPED
        if (desired == snapshot) return@forEachTarget Outcome.DONE
        val result = libraryRepository.applyMembershipChanges(target.input, ListMembershipChanges(desired))
        if (result.requiresRemovalConfirmation) Outcome.SKIPPED else Outcome.DONE
    }

    /** Marks movies watched, and every aired episode of a series. */
    suspend fun markWatched(
        targets: List<LibraryBulkTarget>,
        isCurrent: () -> Boolean = { true }
    ): LibraryBulkResult = forEachTarget(targets, isCurrent) { target ->
        if (target.isSeries) {
            val episodes = fetchEpisodes(target).watchableEpisodes()
            // Without episodes the only mark is a show-level one, which trackers read as all watched.
            if (episodes.isEmpty()) return@forEachTarget Outcome.FAILED
            // The episode fetch is the long wait; don't write once another profile is active.
            if (!isCurrent()) return@forEachTarget Outcome.SKIPPED
            val badgeIds = setOfNotNull(target.id, target.imdbId)
            val before = watchedSeriesStateHolder.fullyWatchedSeriesIds.value
            watchedSeriesStateHolder.update(before + badgeIds)
            try {
                watchProgressRepository.markAsCompletedBatch(episodes.map { video ->
                    completedProgress(target, video.id, video.season, video.episode, video.title)
                })
            } catch (error: Exception) {
                watchedSeriesStateHolder.update(watchedSeriesStateHolder.fullyWatchedSeriesIds.value - (badgeIds - before))
                throw error
            }
        } else {
            val ids = setOfNotNull(target.id, target.imdbId)
            watchProgressRepository.applyOptimisticWatchedMovie(ids, add = true)
            try {
                watchProgressRepository.markAsCompleted(completedProgress(target, target.id, null, null, null))
            } catch (error: Exception) {
                watchProgressRepository.revertOptimisticWatchedMovie(ids, add = true)
                throw error
            }
        }
        Outcome.DONE
    }

    /** Takes titles off History: a movie's watch, or every watched episode of a show. */
    suspend fun removeFromHistory(
        targets: List<LibraryBulkTarget>,
        isCurrent: () -> Boolean = { true }
    ): LibraryBulkResult {
        val watched = watchProgressRepository.watchedItems.first().groupBy { it.contentId }
        return forEachTarget(targets, isCurrent) { target ->
            val episodes = watched[target.id].orEmpty()
                .filter { it.season != null && it.episode != null }
                .map { Triple(it.season!!, it.episode!!, null as String?) }
            if (target.isSeries && episodes.isNotEmpty()) {
                watchProgressRepository.removeFromHistoryBatch(target.id, target.imdbId, episodes)
                watchedSeriesStateHolder.update(watchedSeriesStateHolder.fullyWatchedSeriesIds.value - setOfNotNull(target.id, target.imdbId))
            } else {
                watchProgressRepository.removeFromHistory(target.id, videoId = target.imdbId)
            }
            Outcome.DONE
        }
    }

    /** Takes titles off In progress (their saved playback positions). */
    suspend fun removeProgress(
        targets: List<LibraryBulkTarget>,
        isCurrent: () -> Boolean = { true }
    ): LibraryBulkResult = forEachTarget(targets, isCurrent) { target ->
        watchProgressRepository.removeProgress(target.id)
        Outcome.DONE
    }

    private enum class Outcome { DONE, FAILED, SKIPPED }

    /**
     * Runs [action] on each target in turn. Stops once [isCurrent] turns false (the profile that
     * started the action is no longer the active one), since the writes go to the active profile.
     */
    private suspend fun forEachTarget(
        targets: List<LibraryBulkTarget>,
        isCurrent: () -> Boolean,
        action: suspend (LibraryBulkTarget) -> Outcome
    ): LibraryBulkResult {
        var result = LibraryBulkResult()
        for (target in targets) {
            if (!isCurrent()) break
            val outcome = try {
                action(target)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Bulk action failed for ${target.id}: ${error.message}")
                Outcome.FAILED
            }
            result += when (outcome) {
                Outcome.DONE -> LibraryBulkResult(done = 1)
                Outcome.FAILED -> LibraryBulkResult(failed = 1)
                Outcome.SKIPPED -> LibraryBulkResult(skipped = 1)
            }
        }
        return result
    }

    private suspend fun fetchEpisodes(target: LibraryBulkTarget): List<com.fetch.tv.domain.model.Video> {
        val type = if (target.isSeries) "series" else target.type
        val result = metaRepository.getMetaFromPrimaryAddon(type, target.id)
            .first { it !is NetworkResult.Loading }
        return (result as? NetworkResult.Success)?.data?.videos.orEmpty()
    }

    private fun completedProgress(
        target: LibraryBulkTarget,
        videoId: String,
        season: Int?,
        episode: Int?,
        episodeTitle: String?
    ): WatchProgress = WatchProgress(
        contentId = target.id,
        contentType = target.type,
        name = target.name,
        poster = target.poster,
        backdrop = target.input.background,
        logo = target.input.logo,
        videoId = videoId,
        season = season,
        episode = episode,
        episodeTitle = episodeTitle,
        position = 1L,
        duration = 1L,
        lastWatched = System.currentTimeMillis(),
        progressPercent = 100f
    )

    private companion object {
        const val TAG = "LibraryBulkActions"
    }
}
