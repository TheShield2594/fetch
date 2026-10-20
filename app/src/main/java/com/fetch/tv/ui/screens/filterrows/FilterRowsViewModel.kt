package com.fetch.tv.ui.screens.filterrows

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fetch.tv.data.local.FilterRowsDataStore
import com.fetch.tv.domain.model.FilterRow
import com.fetch.tv.domain.model.FilterRowFilters
import com.fetch.tv.domain.model.FilterRowSort
import com.fetch.tv.domain.model.FilterRowSource
import com.fetch.tv.domain.model.WatchedFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FilterRowListItem(
    val row: FilterRow,
    /** Null when the server library or addon catalog the row reads from is gone. */
    val sourceLabel: String?
)

data class FilterRowsUiState(
    val isLoading: Boolean = true,
    val rows: List<FilterRowListItem> = emptyList()
)

@HiltViewModel
class FilterRowsViewModel @Inject constructor(
    private val dataStore: FilterRowsDataStore,
    private val sources: FilterRowSources
) : ViewModel() {
    private val _uiState = MutableStateFlow(FilterRowsUiState())
    val uiState: StateFlow<FilterRowsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            dataStore.rows.collectLatest { rows ->
                val labels = sources.options().associate { it.key to it.label }
                _uiState.value = FilterRowsUiState(
                    isLoading = false,
                    rows = rows.map { FilterRowListItem(it, labels[FilterRowSources.key(it.source)]) }
                )
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch { dataStore.remove(id) }
    }
}

data class FilterRowEditorUiState(
    val isLoading: Boolean = true,
    val isNew: Boolean = true,
    val title: String = "",
    val sources: List<FilterRowSourceOption> = emptyList(),
    val selectedSourceKey: String? = null,
    val genres: List<String> = emptyList(),
    val genresLoading: Boolean = false,
    val filters: FilterRowFilters = FilterRowFilters()
) {
    val selectedSource: FilterRowSourceOption?
        get() = sources.firstOrNull { it.key == selectedSourceKey }

    val canSave: Boolean
        get() = selectedSource != null
}

@HiltViewModel
class FilterRowEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val dataStore: FilterRowsDataStore,
    private val sources: FilterRowSources
) : ViewModel() {
    private val rowId: String? = savedStateHandle.get<String>("rowId")?.takeIf { it.isNotBlank() }
    private val _uiState = MutableStateFlow(FilterRowEditorUiState())
    val uiState: StateFlow<FilterRowEditorUiState> = _uiState.asStateFlow()
    private var genresJob: Job? = null

    init {
        viewModelScope.launch {
            val options = sources.options()
            val existing = rowId?.let { dataStore.row(it) }
            val selected = existing?.let { FilterRowSources.key(it.source) } ?: options.firstOrNull()?.key
            _uiState.value = FilterRowEditorUiState(
                isLoading = false,
                isNew = existing == null,
                title = existing?.title.orEmpty(),
                sources = options,
                selectedSourceKey = selected,
                filters = existing?.filters ?: FilterRowFilters()
            )
            loadGenres()
        }
    }

    fun setTitle(title: String) = _uiState.update { it.copy(title = title) }

    fun selectSource(key: String) {
        val option = _uiState.value.sources.firstOrNull { it.key == key } ?: return
        _uiState.update { state ->
            state.copy(
                selectedSourceKey = key,
                // Addon catalogs only filter by genre, and genres differ between sources.
                filters = if (option.isServer) state.filters.copy(genre = null) else FilterRowFilters()
            )
        }
        loadGenres()
    }

    fun setGenre(genre: String?) = updateFilters { it.copy(genre = genre) }

    fun setYearFrom(year: Int?) = updateFilters { it.copy(yearFrom = year) }

    fun setYearTo(year: Int?) = updateFilters { it.copy(yearTo = year) }

    fun setWatched(watched: WatchedFilter) = updateFilters { it.copy(watched = watched) }

    fun setMinRating(rating: Double?) = updateFilters { it.copy(minRating = rating) }

    fun setSort(sort: FilterRowSort) = updateFilters { it.copy(sort = sort) }

    fun setDescending(descending: Boolean) = updateFilters { it.copy(descending = descending) }

    fun save(defaultTitle: String, onDone: () -> Unit) {
        val state = _uiState.value
        val source = state.selectedSource?.source ?: return
        val row = FilterRow(
            id = rowId ?: UUID.randomUUID().toString(),
            title = state.title.trim().ifEmpty { defaultTitle },
            source = source,
            filters = state.filters
        )
        viewModelScope.launch {
            dataStore.save(row)
            onDone()
        }
    }

    fun delete(onDone: () -> Unit) {
        val id = rowId ?: return onDone()
        viewModelScope.launch {
            dataStore.remove(id)
            onDone()
        }
    }

    private fun updateFilters(transform: (FilterRowFilters) -> FilterRowFilters) =
        _uiState.update { it.copy(filters = transform(it.filters)) }

    private fun loadGenres() {
        genresJob?.cancel()
        val option = _uiState.value.selectedSource ?: return
        val addonGenres = option.addonGenres
        if (addonGenres != null) {
            _uiState.update { it.copy(genres = addonGenres, genresLoading = false) }
            return
        }
        val source = option.source as? FilterRowSource.ServerLibrary ?: return
        _uiState.update { it.copy(genres = emptyList(), genresLoading = true) }
        genresJob = viewModelScope.launch {
            val genres = try {
                sources.serverGenres(source)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emptyList()
            }
            _uiState.update { it.copy(genres = genres, genresLoading = false) }
        }
    }
}
