package com.fetch.tv.ui.screens.home

import androidx.lifecycle.viewModelScope
import com.fetch.tv.core.recommendations.BecauseYouWatchedSeed
import com.fetch.tv.core.recommendations.latestCompletedSeeds
import com.fetch.tv.domain.model.CatalogRow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One row per seed: the last completed title, then the one before it. */
private const val MAX_BECAUSE_YOU_WATCHED_ROWS = 2
private const val BECAUSE_YOU_WATCHED_DEBOUNCE_MS = 1_000L

private data class BecauseYouWatchedInput(
    val profileId: Int,
    val enabled: Boolean,
    val language: String,
    val localeTag: String,
    val seeds: List<BecauseYouWatchedSeed>
)

/**
 * Keeps the "Because you watched" rows in step with the active profile's history.
 *
 * Uses TMDB's recommendations, so it follows the TMDB "More like this" setting.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
internal fun HomeViewModel.observeBecauseYouWatchedPipeline() {
    if (!becauseYouWatchedService.isAvailable) return
    viewModelScope.launch {
        val seeds = combine(
            watchProgressRepository.allProgress,
            watchProgressRepository.watchedItems
        ) { progress, watched ->
            latestCompletedSeeds(progress, watched, MAX_BECAUSE_YOU_WATCHED_ROWS)
        }.distinctUntilChanged()

        val settings = tmdbSettingsDataStore.settings
            .map { (it.enabled && it.useMoreLikeThis) to it.language }
            .distinctUntilChanged()

        val rows = combine(
            profileManager.activeProfileId,
            seeds,
            settings,
            _currentLocaleTag
        ) { profileId, seedList, (enabled, language), localeTag ->
            BecauseYouWatchedInput(profileId, enabled, language, localeTag, seedList)
        }
            .distinctUntilChanged()
            .debounce(BECAUSE_YOU_WATCHED_DEBOUNCE_MS)
            .mapLatest { input ->
                val builtRows = if (!input.enabled || input.seeds.isEmpty()) {
                    emptyList()
                } else {
                    coroutineScope {
                        input.seeds
                            .map { seed -> async { becauseYouWatchedService.buildRow(seed, input.language) } }
                            .awaitAll()
                            .filterNotNull()
                    }
                }
                input.profileId to builtRows
            }

        combine(rows, parentalControls.filterRevision) { (profileId, builtRows), _ ->
            // Rows built for a profile that is no longer active must not reach the new one.
            if (profileId != profileManager.activeProfileId.value) {
                emptyList()
            } else {
                builtRows.map(parentalControls::filterRow).filter { it.items.isNotEmpty() }
            }
        }.collect { filteredRows: List<CatalogRow> ->
            _uiState.update { state ->
                if (state.becauseYouWatchedRows == filteredRows) state
                else state.copy(becauseYouWatchedRows = filteredRows)
            }
        }
    }
}
