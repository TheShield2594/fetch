package com.fetch.tv.core.hidden

import androidx.compose.runtime.Immutable
import com.fetch.tv.data.local.HiddenTitle
import com.fetch.tv.data.local.HiddenTitlesState
import com.fetch.tv.data.local.HiddenTitlesStore
import com.fetch.tv.data.local.WatchedSeriesStateHolder
import com.fetch.tv.data.local.isSeriesKind
import com.fetch.tv.domain.model.CatalogRow
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.repository.WatchProgressRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * Which titles the active profile's suggestions leave out (#650):
 *
 * - titles marked "Not interested" ([hiddenTitles]) go from Home rows, See all, Search and More
 *   like this ([filterSuggestions] / [filterCatalogRow]);
 * - with [hideWatched], watched movies and fully watched shows also go from Home catalog rows and
 *   their See all ([filterCatalogRow]), by the same watched lookup the poster badges use.
 *
 * Continue Watching, the library and the profile's own lists are never filtered here.
 */
@Immutable
data class TitleVisibility(
    val hiddenTitles: List<HiddenTitle> = emptyList(),
    val hideWatched: Boolean = false,
    val watchedMovieIds: Set<String> = emptySet(),
    val watchedSeriesIds: Set<String> = emptySet()
) {
    private val hiddenById: Map<String, List<HiddenTitle>> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        buildMap<String, MutableList<HiddenTitle>> {
            hiddenTitles.forEach { title -> title.ids.forEach { id -> getOrPut(id) { mutableListOf() } += title } }
        }
    }

    /** True when nothing would be left out, so callers can return their input untouched. */
    val isEmpty: Boolean get() = hiddenTitles.isEmpty() && !filtersWatched

    private val filtersWatched: Boolean
        get() = hideWatched && (watchedMovieIds.isNotEmpty() || watchedSeriesIds.isNotEmpty())

    fun isNotInterested(item: MetaPreview): Boolean {
        if (hiddenTitles.isEmpty()) return false
        return candidateIds(item).any { id ->
            hiddenById[id]?.any { it.matches(item.id, item.imdbId, item.apiType) } == true
        }
    }

    fun isWatched(item: MetaPreview): Boolean {
        val watched = if (isSeriesKind(item.apiType)) {
            watchedSeriesIds
        } else if (item.apiType.equals("movie", ignoreCase = true)) {
            watchedMovieIds
        } else {
            return false
        }
        return candidateIds(item).any { it in watched }
    }

    /** Leaves out "Not interested" titles: Search, Discover and More like this. */
    fun filterSuggestions(items: List<MetaPreview>): List<MetaPreview> {
        if (hiddenTitles.isEmpty()) return items
        val kept = items.filterNot(::isNotInterested)
        return if (kept.size == items.size) items else kept
    }

    /** Leaves out "Not interested" titles and, with [hideWatched], watched ones: Home catalog rows and See all. */
    fun filterCatalogItems(items: List<MetaPreview>): List<MetaPreview> {
        if (isEmpty) return items
        val dropWatched = filtersWatched
        val kept = items.filterNot { item -> isNotInterested(item) || (dropWatched && isWatched(item)) }
        return if (kept.size == items.size) items else kept
    }

    fun filterCatalogRow(row: CatalogRow): CatalogRow {
        val kept = filterCatalogItems(row.items)
        return if (kept === row.items) row else row.copy(items = kept)
    }

    /** Leaves out "Not interested" titles only: rows that suggest but aren't a Home catalog (Search). */
    fun filterSuggestionRow(row: CatalogRow): CatalogRow {
        val kept = filterSuggestions(row.items)
        return if (kept === row.items) row else row.copy(items = kept)
    }

    private fun candidateIds(item: MetaPreview): List<String> =
        listOfNotNull(item.id, item.imdbId?.takeIf { it.isNotBlank() && it != item.id })

    companion object {
        val None = TitleVisibility()

        fun of(state: HiddenTitlesState, watchedMovieIds: Set<String>, watchedSeriesIds: Set<String>): TitleVisibility =
            TitleVisibility(
                hiddenTitles = state.titles,
                hideWatched = state.hideWatched,
                // Only kept while they're used, so a change to them doesn't refilter for nothing.
                watchedMovieIds = if (state.hideWatched) watchedMovieIds else emptySet(),
                watchedSeriesIds = if (state.hideWatched) watchedSeriesIds else emptySet()
            )
    }
}

/** The active profile's [TitleVisibility], kept current for every screen that filters by it. */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class TitleVisibilityFilter @Inject constructor(
    hiddenTitlesStore: HiddenTitlesStore,
    watchProgressRepository: WatchProgressRepository,
    watchedSeriesStateHolder: WatchedSeriesStateHolder
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val visibility: StateFlow<TitleVisibility> = hiddenTitlesStore.state
        .flatMapLatest { state ->
            if (!state.hideWatched) {
                flowOf(TitleVisibility.of(state, emptySet(), emptySet()))
            } else {
                combine(
                    watchProgressRepository.observeWatchedMovieIds(),
                    watchedSeriesStateHolder.fullyWatchedSeriesIds
                ) { movies, series -> TitleVisibility.of(state, movies, series) }
            }
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, TitleVisibility.None)
}
