package com.fetch.tv.ui.screens

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fetch.tv.core.hidden.TitleVisibilityFilter
import com.fetch.tv.core.parental.ParentalControls
import com.fetch.tv.core.network.NetworkResult
import com.fetch.tv.core.util.runSuspendCatching
import com.fetch.tv.data.mediaserver.ServerCatalog
import com.fetch.tv.domain.model.Addon
import com.fetch.tv.domain.model.CatalogDescriptor
import com.fetch.tv.domain.model.CatalogRow
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.mergeCatalogPage
import com.fetch.tv.domain.model.nextCatalogSkip
import com.fetch.tv.domain.model.skipStep
import com.fetch.tv.domain.model.supportsExtra
import com.fetch.tv.domain.repository.AddonRepository
import com.fetch.tv.domain.repository.CatalogRepository
import com.fetch.tv.ui.screens.search.DiscoverFilters
import com.fetch.tv.ui.screens.search.DiscoverSort
import com.fetch.tv.ui.screens.search.DiscoverYearRange
import com.fetch.tv.ui.screens.search.applyDiscoverFilters
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The See all header's filters (#651): a genre, when the catalog declares one, plus Discover's
 * year, rating and sort, and a hide-watched toggle.
 *
 * Year, rating, sort and hide-watched run on the titles already loaded. A genre is a different
 * query, so choosing one loads the catalog again with that `genre` extra into [SeeAllFilterState.genreRow],
 * which pages on its own and is filtered for the profile like the row it stands in for.
 */
@Immutable
data class SeeAllFilterState(
    /** The catalog's declared genre options; empty means no genre chip. */
    val genres: List<String> = emptyList(),
    val genre: String? = null,
    val filters: DiscoverFilters = DiscoverFilters(),
    val hideWatched: Boolean = false,
    /** The catalog loaded with [genre]; null while no genre is chosen. */
    val genreRow: CatalogRow? = null,
    /** The first genre page failed. */
    val genreFailed: Boolean = false
) {
    val isFiltering: Boolean
        get() = genre != null || filters.narrowsResults || hideWatched
}

@HiltViewModel
class CatalogSeeAllFiltersViewModel @Inject constructor(
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
    private val titleVisibilityFilter: TitleVisibilityFilter,
    private val parentalControls: ParentalControls
) : ViewModel() {

    private val _state = MutableStateFlow(SeeAllFilterState())
    val state: StateFlow<SeeAllFilterState> = _state.asStateFlow()

    private var boundKey: String? = null
    private var source: GenreSource? = null
    /** Unfiltered genre pages as they came, so a profile change can't hide titles for good. */
    private var rawGenreRow: CatalogRow? = null
    private var genreJob: Job? = null
    private var hideSuggestionsOnly = false

    init {
        // "Not interested", hide-watched and the profile limit can change while the genre grid is up.
        viewModelScope.launch {
            titleVisibilityFilter.visibility.collect { publishGenreRow() }
        }
    }

    private data class GenreSource(
        val addon: Addon,
        val catalog: CatalogDescriptor,
        val baseExtraArgs: Map<String, String>
    )

    /**
     * Looks the catalog up among the installed addons to learn its genres. A media server's
     * catalog is the user's library and has no genre extra.
     */
    fun bind(addonId: String, type: String, catalogId: String, fromSearch: Boolean) {
        val key = "$addonId|$type|$catalogId"
        if (boundKey == key) return
        boundKey = key
        hideSuggestionsOnly = fromSearch
        if (ServerCatalog.isServerAddonId(addonId)) return
        viewModelScope.launch {
            val addons = runSuspendCatching { addonRepository.getInstalledAddons().first() }.getOrNull()
                ?: return@launch
            val addon = addons.firstOrNull { it.id == addonId && it.enabled } ?: return@launch
            val catalog = addon.catalogs.firstOrNull { it.id == catalogId && it.apiType == type } ?: return@launch
            val genres = seeAllGenreOptions(catalog)
            if (genres.isEmpty()) return@launch
            source = GenreSource(addon, catalog, baseExtraArgs = emptyMap())
            _state.update { it.copy(genres = genres) }
        }
    }

    /** Chooses a genre (null for every genre). [row] is the catalog as See all shows it now. */
    fun selectGenre(genre: String?, row: CatalogRow?) {
        if (genre == _state.value.genre) return
        genreJob?.cancel()
        rawGenreRow = null
        if (genre == null) {
            _state.update { it.copy(genre = null, genreRow = null, genreFailed = false) }
            return
        }
        val src = source ?: return
        // Keep any other extras the row was loaded with (a required one, say); the genre replaces its own.
        val extras = row?.extraArgs.orEmpty().filterKeys { !it.equals("genre", ignoreCase = true) } + ("genre" to genre)
        source = src.copy(baseExtraArgs = extras)
        _state.update { it.copy(genre = genre, genreRow = null, genreFailed = false) }
        genreJob = viewModelScope.launch {
            val page = fetchGenrePage(src.copy(baseExtraArgs = extras), skip = 0)
            if (page == null) {
                _state.update { it.copy(genreFailed = true) }
                return@launch
            }
            rawGenreRow = page.copy(isLoading = false)
            publishGenreRow()
        }
    }

    fun loadMoreGenre() {
        val src = source ?: return
        val current = rawGenreRow ?: return
        if (current.isLoading || !current.hasMore || genreJob?.isActive == true) return
        rawGenreRow = current.copy(isLoading = true)
        publishGenreRow()
        genreJob = viewModelScope.launch {
            val page = fetchGenrePage(src, skip = current.nextCatalogSkip())
            val latest = rawGenreRow ?: return@launch
            rawGenreRow = if (page == null) {
                latest.copy(isLoading = false)
            } else {
                latest.mergeCatalogPage(page).copy(isLoading = false)
            }
            publishGenreRow()
        }
    }

    fun retryGenre(row: CatalogRow?) {
        val genre = _state.value.genre ?: return
        _state.update { it.copy(genre = null) }
        selectGenre(genre, row)
    }

    fun selectYearRange(range: DiscoverYearRange?) =
        _state.update { it.copy(filters = it.filters.copy(yearRange = range)) }

    fun selectMinRating(rating: Int?) =
        _state.update { it.copy(filters = it.filters.copy(minRating = rating)) }

    fun selectSort(sort: DiscoverSort) =
        _state.update { it.copy(filters = it.filters.copy(sort = sort)) }

    fun toggleHideWatched() = _state.update { it.copy(hideWatched = !it.hideWatched) }

    fun clearFilters(row: CatalogRow?) {
        selectGenre(null, row)
        _state.update { it.copy(filters = DiscoverFilters(), hideWatched = false) }
    }

    private fun publishGenreRow() {
        val raw = rawGenreRow ?: return
        val visibility = titleVisibilityFilter.visibility.value
        val allowed = parentalControls.filterRow(raw)
        val visible = if (hideSuggestionsOnly) {
            visibility.filterSuggestionRow(allowed)
        } else {
            visibility.filterCatalogRow(allowed)
        }
        _state.update { it.copy(genreRow = visible, genreFailed = false) }
    }

    private suspend fun fetchGenrePage(src: GenreSource, skip: Int): CatalogRow? = try {
        val result = catalogRepository.getCatalog(
            addonBaseUrl = src.addon.baseUrl,
            addonId = src.addon.id,
            addonName = src.addon.displayName,
            catalogId = src.catalog.id,
            catalogName = src.catalog.name,
            type = src.catalog.apiType,
            skip = skip,
            skipStep = src.catalog.skipStep(),
            extraArgs = src.baseExtraArgs,
            supportsSkip = src.catalog.supportsExtra("skip")
        ).first { it !is NetworkResult.Loading }
        (result as? NetworkResult.Success)?.data
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }
}

/** The genres a catalog declares for its `genre` extra, blank and repeated spellings dropped. */
internal fun seeAllGenreOptions(catalog: CatalogDescriptor): List<String> =
    catalog.extra
        .firstOrNull { it.name.equals("genre", ignoreCase = true) }
        ?.options
        .orEmpty()
        .filter { it.isNotBlank() }
        .distinctBy { it.trim().lowercase(java.util.Locale.ROOT) }

/**
 * What the See all grid shows: the year, rating and sort filters, then hide-watched by [isWatched].
 * A row with no filter set comes back untouched.
 */
internal fun seeAllVisibleItems(
    items: List<MetaPreview>,
    filters: DiscoverFilters,
    hideWatched: Boolean,
    isWatched: (MetaPreview) -> Boolean
): List<MetaPreview> {
    val filtered = applyDiscoverFilters(items, filters)
    if (!hideWatched) return filtered
    val kept = filtered.filterNot(isWatched)
    return if (kept.size == filtered.size) filtered else kept
}
