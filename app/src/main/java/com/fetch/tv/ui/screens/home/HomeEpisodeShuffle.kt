package com.fetch.tv.ui.screens.home

import androidx.lifecycle.viewModelScope
import com.fetch.tv.core.player.PlayerSessionTracker
import com.fetch.tv.data.local.EpisodeShuffleProfile
import com.fetch.tv.domain.model.ContinueWatchingSortMode
import com.fetch.tv.domain.model.EpisodeShuffle
import com.fetch.tv.domain.model.ShuffleSurface
import com.fetch.tv.domain.model.Video
import com.fetch.tv.domain.model.WatchProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal data class HomeShuffleRefresh(val visit: Long = System.nanoTime(), val metadata: Int = 0)

/** Whether any show on this profile shuffles on Home; with none, [applyHomeShuffle] changes nothing. */
internal fun EpisodeShuffleProfile.hasHomeShuffle(): Boolean = available && shows.values.any { it.enabled }

/**
 * Runs [active] while some show shuffles and the player is closed, else stands still: with
 * shuffle off it emits [inactive] once and collects nothing, and while the player is open it
 * collects nothing and the last value stays (#824).
 */
@OptIn(ExperimentalCoroutinesApi::class)
private fun <T> HomeViewModel.whileHomeShuffleActive(inactive: T, active: () -> Flow<T>): Flow<T> =
    episodeShuffleStore.profiles
        .map { it.hasHomeShuffle() }
        .distinctUntilChanged()
        .flatMapLatest { on ->
            if (on) active().pausedWhilePlayerOpen(PlayerSessionTracker.isSessionOpen) else flowOf(inactive)
        }

internal fun HomeViewModel.createShuffleHomeState(): StateFlow<HomeUiState> {
    val finished = finishedShuffleSeeds().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    viewModelScope.launch {
        finished.collectLatest { seeds -> seeds.forEach { resolveCwMeta(it) } }
    }
    // Regrouping every watched item on each progress save is skipped with shuffle off and while
    // the player is open, and runs on Default otherwise (#824).
    val watchedEpisodes = whileHomeShuffleActive(emptyMap()) {
        combine(sharedWatchedItems, sharedAllProgress, ::groupWatchedEpisodesForShuffle)
            .flowOn(Dispatchers.Default)
    }.distinctUntilChanged()
    return combine(
        _uiState,
        episodeShuffleStore.profiles,
        watchedEpisodes,
        shuffleHomeRefresh,
        finished
    ) { state, profile, watched, refresh, seeds ->
        val cards = seeds.mapNotNull { seed -> cachedCwMeta(seed.contentType, seed.contentId)?.let(seed::toShuffleCard) }
        applyHomeShuffle(state, profile, watched, episodeShuffle, refresh.visit, continueWatchingSortMode, cards) { id, type ->
            cachedCwMeta(type, id)?.videos?.map { video ->
                Video(video.id, video.title.orEmpty(), video.released, video.thumbnail,
                    season = video.season, episode = video.episode, overview = video.overview, available = video.available)
            }
        }
    }.combine(parentalControls.filterRevision) { state, _ ->
        filterContinueWatchingForProfile(state, parentalControls)
    }.combine(_pendingCwRemoval) { state, pending ->
        hidePendingContinueWatchingRemoval(state, pending)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeUiState())
}

private fun HomeViewModel.finishedShuffleSeeds() = whileHomeShuffleActive(emptyList()) { combine(
    sharedNextUpSeeds,
    traktSettingsDataStore.continueWatchingDaysCap,
    traktSettingsDataStore.nextUpDismissals,
    episodeShuffleStore.profiles
) { seeds, daysCap, dismissed, profile ->
    val cutoff = watchProgressRepository.activeProviderContinueWatchingCutoffEpochMs(daysCap, System.currentTimeMillis())
    seeds.filter { seed ->
        profile.settings(seed.contentId, seed.contentType).enabled &&
            (seed.season ?: 0) > 0 && seed.episode != null &&
            (cutoff == null || seed.lastWatched >= cutoff) &&
            // A shuffle seed never resolves to an offered episode, so a dismissal holds
            // until the user watches past it.
            !dismissed.hides(seed.contentId, seed.season, seed.episode) &&
            !watchProgressRepository.isDroppedShow(seed.contentId) &&
            shouldUseAsCompletedSeed(seed)
    }.sortedByDescending { it.lastWatched }.distinctBy { it.contentId }
} }.distinctUntilChanged()

private fun HomeViewModel.cachedCwMeta(type: String, id: String) =
    cwMetaCache["$type:$id"] ?: cwMetaCache["series:$id"] ?: cwMetaCache["tv:$id"]

private fun WatchProgress.toShuffleCard(meta: CwMetaSummary) = ContinueWatchingItem.NextUp(NextUpInfo(
    contentId = contentId, contentType = contentType, name = name.ifBlank { meta.name },
    poster = poster ?: meta.poster, backdrop = backdrop ?: meta.backdropUrl, logo = logo ?: meta.logo,
    videoId = videoId, season = season ?: 1, episode = episode ?: 1, episodeTitle = null, thumbnail = null,
    lastWatched = lastWatched, sortTimestamp = lastWatched, seedSeason = season, seedEpisode = episode
))

internal fun applyHomeShuffle(
    state: HomeUiState,
    profile: EpisodeShuffleProfile,
    watched: Map<String, Set<Pair<Int, Int>>>,
    shuffle: EpisodeShuffle,
    visit: Long,
    sortMode: ContinueWatchingSortMode,
    finished: List<ContinueWatchingItem.NextUp> = emptyList(),
    videos: (String, String) -> List<Video>?
): HomeUiState {
    if (!profile.hasHomeShuffle()) return state
    val native = state.continueWatchingItems + state.upcomingItems
    val items = native + finished.filter { card ->
        profile.settings(card.info.contentId, card.info.contentType).enabled &&
            native.none { it.contentId() == card.info.contentId }
    }
    val resumable = items.filterIsInstance<ContinueWatchingItem.InProgress>()
        .filter { profile.settings(it.progress.contentId, it.progress.contentType).enabled }
        .groupBy { it.progress.contentId }
        .mapValues { (_, episodes) -> episodes.maxBy { it.progress.lastWatched } }
    val projected = items.mapNotNull { item ->
        when (item) {
            is ContinueWatchingItem.InProgress -> {
                val latest = resumable[item.progress.contentId]
                if (latest != null && latest != item) null else item.copy(shufflePlayback = latest != null)
            }
            is ContinueWatchingItem.NextUp -> {
                val info = item.info
                if (info.contentId in resumable) return@mapNotNull null
                val settings = profile.settings(info.contentId, info.contentType)
                if (!settings.enabled) item else {
                    val catalogue = videos(info.contentId, info.contentType) ?: return@mapNotNull null
                    val selected = shuffle.select(
                        profile.profileId, info.contentId, catalogue, settings.includeWatched,
                        watched[info.contentId].orEmpty(), surface = ShuffleSurface.HOME, visit = visit
                    ) ?: return@mapNotNull null
                    item.copy(shufflePlayback = true, info = info.copy(
                        videoId = selected.id, season = selected.season!!, episode = selected.episode!!,
                        episodeTitle = selected.title, episodeDescription = selected.overview,
                        thumbnail = selected.thumbnail, released = selected.released,
                        hasAired = true, airDateLabel = null, releaseTimestamp = null,
                        isReleaseAlert = false, isNewSeasonRelease = false
                    ))
                }
            }
        }
    }
    val unique = projected.distinctBy { it.shuffleFocusKey ?: continueWatchingItemKey(it) }
    val (current, upcoming) = splitUpcomingItems(unique, sortMode)
    return state.copy(continueWatchingItems = current, upcomingItems = upcoming)
}
