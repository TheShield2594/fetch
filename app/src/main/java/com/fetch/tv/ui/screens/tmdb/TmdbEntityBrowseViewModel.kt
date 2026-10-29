package com.fetch.tv.ui.screens.tmdb

import com.fetch.tv.domain.model.THEME_CARD_CORNER_RADIUS
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fetch.tv.R
import com.fetch.tv.core.poster.withCustomPosterUrls
import com.fetch.tv.core.tmdb.TmdbEntityBrowseData
import com.fetch.tv.core.tmdb.TmdbEntityKind
import com.fetch.tv.core.tmdb.TmdbEntityRailType
import com.fetch.tv.core.tmdb.TmdbEntityMediaType
import com.fetch.tv.core.tmdb.TmdbMetadataService
import com.fetch.tv.data.local.TmdbSettingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.net.URLDecoder
import javax.inject.Inject

@HiltViewModel
class TmdbEntityBrowseViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tmdbMetadataService: TmdbMetadataService,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val watchProgressRepository: com.fetch.tv.domain.repository.WatchProgressRepository,
    private val watchedSeriesStateHolder: com.fetch.tv.data.local.WatchedSeriesStateHolder,
    private val layoutPreferenceDataStore: com.fetch.tv.data.local.LayoutPreferenceDataStore,
    private val parentalControls: com.fetch.tv.core.parental.ParentalControls,
    val posterOptions: com.fetch.tv.ui.components.posteroptions.PosterOptionsController,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val inFlightRailLoads = mutableSetOf<String>()

    val entityKind: TmdbEntityKind = TmdbEntityKind.fromRouteValue(
        savedStateHandle.get<String>("entityKind").orEmpty()
    )
    val entityId: Int = savedStateHandle.get<Int>("entityId") ?: 0
    val entityName: String = savedStateHandle.get<String>("entityName").orEmpty().let { raw ->
        runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
    }
    val sourceType: String = savedStateHandle.get<String>("sourceType").orEmpty()

    private val _watchedMovieIds = MutableStateFlow<Set<String>>(emptySet())
    val watchedMovieIds: StateFlow<Set<String>> = _watchedMovieIds.asStateFlow()
    val watchedSeriesIds: StateFlow<Set<String>> = watchedSeriesStateHolder.fullyWatchedSeriesIds

    private val _uiState = MutableStateFlow<TmdbEntityBrowseUiState>(TmdbEntityBrowseUiState.Loading)
    // Titles above the active profile's limit, or hidden from it, are left out.
    val uiState: StateFlow<TmdbEntityBrowseUiState> = com.fetch.tv.core.util.derivedStateFlow(_uiState, parentalControls.filterRevision) { state, _ ->
        state.filteredForProfile(parentalControls)
    }

    private val _posterCardCornerRadiusDp = MutableStateFlow(THEME_CARD_CORNER_RADIUS)
    val posterCardCornerRadiusDp: StateFlow<Int> = _posterCardCornerRadiusDp.asStateFlow()

    init {
        posterOptions.bind(viewModelScope)
        load()
        viewModelScope.launch {
            watchProgressRepository.observeWatchedMovieIds()
                .collect { ids -> _watchedMovieIds.value = ids }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.posterCardCornerRadiusDp
                .collect { _posterCardCornerRadiusDp.value = it }
        }
    }

    fun retry() {
        _uiState.value = TmdbEntityBrowseUiState.Loading
        load()
    }

    fun loadMoreRail(mediaType: TmdbEntityMediaType, railType: TmdbEntityRailType) {
        val railKey = "${mediaType.value}_${railType.value}"
        val currentSuccess = _uiState.value as? TmdbEntityBrowseUiState.Success ?: return
        val targetRail = currentSuccess.data.rails.firstOrNull {
            it.mediaType == mediaType && it.railType == railType
        } ?: return
        if (!targetRail.hasMore || targetRail.isLoading || !inFlightRailLoads.add(railKey)) return

        _uiState.value = TmdbEntityBrowseUiState.Success(
            currentSuccess.data.withUpdatedRail(mediaType, railType) { it.copy(isLoading = true) }
        )

        viewModelScope.launch {
            try {
                val latestData = (_uiState.value as? TmdbEntityBrowseUiState.Success)?.data ?: return@launch
                val latestRail = latestData.rails.firstOrNull {
                    it.mediaType == mediaType && it.railType == railType
                } ?: return@launch
                val language = tmdbSettingsDataStore.settings.first().language
                val nextPage = latestRail.currentPage + 1
                val pageResult = tmdbMetadataService.fetchEntityRailPage(
                    entityKind = entityKind,
                    entityId = entityId,
                    mediaType = mediaType,
                    railType = railType,
                    language = language,
                    page = nextPage
                )
                val mergedItems = (latestRail.items + pageResult.items)
                    .distinctBy { it.id }

                val pattern = layoutPreferenceDataStore.customPosterUrlPattern.first()
                val enabledScreens = layoutPreferenceDataStore.customPosterEnabledScreens.first()
                _uiState.value = TmdbEntityBrowseUiState.Success(
                    latestData.withUpdatedRail(mediaType, railType) {
                        it.copy(
                            items = mergedItems.withCustomPosterUrls(
                                com.fetch.tv.core.poster.patternForScreen(pattern, com.fetch.tv.core.poster.CustomPosterScreen.DETAILS, enabledScreens)
                            ),
                            currentPage = nextPage,
                            hasMore = pageResult.hasMore,
                            isLoading = false
                        )
                    }
                )
            } catch (_: Exception) {
                val fallback = (_uiState.value as? TmdbEntityBrowseUiState.Success)?.data ?: return@launch
                _uiState.value = TmdbEntityBrowseUiState.Success(
                    fallback.withUpdatedRail(mediaType, railType) { it.copy(isLoading = false) }
                )
            } finally {
                inFlightRailLoads.remove(railKey)
            }
        }
    }

    private fun load() {
        viewModelScope.launch {
            try {
                val language = tmdbSettingsDataStore.settings.first().language
                val browseData = tmdbMetadataService.fetchEntityBrowse(
                    entityKind = entityKind,
                    entityId = entityId,
                    sourceType = sourceType,
                    fallbackName = entityName,
                    language = language
                )
                _uiState.value = if (browseData != null) {
                    val pattern = layoutPreferenceDataStore.customPosterUrlPattern.first()
                    val enabledScreens = layoutPreferenceDataStore.customPosterEnabledScreens.first()
                    TmdbEntityBrowseUiState.Success(browseData.withCustomPosterUrls(
                        com.fetch.tv.core.poster.patternForScreen(pattern, com.fetch.tv.core.poster.CustomPosterScreen.DETAILS, enabledScreens)
                    ))
                } else {
                    TmdbEntityBrowseUiState.Error(
                        if (entityName.isNotBlank()) {
                            context.getString(R.string.tmdb_entity_error_load_named, entityName)
                        } else {
                            context.getString(R.string.tmdb_entity_error_load)
                        }
                    )
                }
            } catch (e: Exception) {
                _uiState.value = TmdbEntityBrowseUiState.Error(
                    e.message ?: context.getString(R.string.tmdb_entity_error_load)
                )
            }
        }
    }

    private fun TmdbEntityBrowseData.withUpdatedRail(
        mediaType: TmdbEntityMediaType,
        railType: TmdbEntityRailType,
        transform: (com.fetch.tv.core.tmdb.TmdbEntityRail) -> com.fetch.tv.core.tmdb.TmdbEntityRail
    ): TmdbEntityBrowseData {
        return copy(
            rails = rails.map { rail ->
                if (rail.mediaType == mediaType && rail.railType == railType) {
                    transform(rail)
                } else {
                    rail
                }
            }
        )
    }
}
