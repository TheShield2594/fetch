package com.fetch.tv.ui.screens.search

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fetch.tv.ui.theme.cardCornerRadius
import com.fetch.tv.ui.theme.FetchTheme
import com.fetch.tv.ui.screens.home.HeroBackdropState

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import com.fetch.tv.R
import com.fetch.tv.domain.model.DiscoverLocation
import com.fetch.tv.ui.components.EmptyScreenState
import com.fetch.tv.ui.components.PosterCardDefaults
import com.fetch.tv.ui.components.PosterCardStyle
import kotlin.math.roundToInt

@Composable
fun DiscoverScreen(
    viewModel: SearchViewModel = hiltViewModel(),
    showBuiltInHeader: Boolean = true,
    onNavigateToDetail: (String, String, String) -> Unit,
    /** Play/Pause on a card: starts the title (id, type, addon) without its detail page (#857). */
    onPlayTitle: (String, String, String) -> Unit = { _, _, _ -> }
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val watchedMovieIds by viewModel.watchedMovieIds.collectAsStateWithLifecycle()
    val watchedSeriesIds by viewModel.watchedSeriesIds.collectAsStateWithLifecycle()
    val progressByContentId by viewModel.progressByContentId.collectAsStateWithLifecycle()
    val libraryIds by viewModel.libraryIds.collectAsStateWithLifecycle()
    val quickPlayAllowed by viewModel.quickPlayAllowed.collectAsStateWithLifecycle()
    val heroSetting by viewModel.heroEnabled.collectAsStateWithLifecycle()
    val metaCacheVersion by viewModel.metaCacheVersion.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val discoverFirstItemFocusRequester = remember { FocusRequester() }
    // The focused title's key, not its index, so it survives re-sorts and new pages (#844).
    var discoverFocusedItemKey by rememberSaveable { mutableStateOf<String?>(null) }
    var restoreDiscoverFocus by rememberSaveable { mutableStateOf(false) }
    var pendingDiscoverRestoreOnResume by rememberSaveable { mutableStateOf(false) }
    // Not saved: each time Discover is entered (from the rail too) it takes focus, unless it is
    // coming back from a title and restores the focused poster instead.
    var initialFocusHandled by remember {
        mutableStateOf(restoreDiscoverFocus || pendingDiscoverRestoreOnResume)
    }

    LaunchedEffect(uiState.discoverLocation) {
        if (uiState.discoverLocation != DiscoverLocation.OFF) {
            // Coming back from a title keeps the grid as it was, so the focused card can be
            // restored; other entries refetch stale results (#845).
            viewModel.ensureDiscoverLoaded(allowRefresh = !(restoreDiscoverFocus || pendingDiscoverRestoreOnResume))
        }
    }

    val latestPendingDiscoverRestore by rememberUpdatedState(pendingDiscoverRestoreOnResume)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && latestPendingDiscoverRestore) {
                restoreDiscoverFocus = true
                pendingDiscoverRestoreOnResume = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FetchTheme.colors.Background)
    ) {
        if (uiState.discoverLocation == DiscoverLocation.OFF) {
            EmptyScreenState(
                title = stringResource(R.string.discover_disabled_title),
                subtitle = stringResource(R.string.discover_disabled_subtitle),
                icon = Icons.Default.Search
            )
        } else {
            DiscoverSection(
                uiState = uiState,
                watchedMovieIds = watchedMovieIds,
                watchedSeriesIds = watchedSeriesIds,
                progressByContentId = progressByContentId,
                heroEnabled = heroSetting,
                heroMetaVersion = metaCacheVersion,
                cachedMeta = viewModel::cachedMeta,
                focusResults = false,
                showBuiltInHeader = showBuiltInHeader,
                firstItemFocusRequester = discoverFirstItemFocusRequester,
                focusedItemKey = discoverFocusedItemKey,
                shouldRestoreFocusedItem = restoreDiscoverFocus,
                restorePending = restoreDiscoverFocus || pendingDiscoverRestoreOnResume,
                requestInitialFocus = !initialFocusHandled,
                onInitialFocusHandled = { initialFocusHandled = true },
                onRestoreFocusedItemHandled = { restoreDiscoverFocus = false },
                onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                    pendingDiscoverRestoreOnResume = true
                    onNavigateToDetail(itemId, itemType, addonBaseUrl)
                },
                onDiscoverItemFocused = { item ->
                    discoverFocusedItemKey = discoverItemKey(item)
                    viewModel.prefetchMetaOnFocus(item.id, item.rawType)
                },
                onSelectType = {
                    discoverFocusedItemKey = null
                    viewModel.onEvent(SearchEvent.SelectDiscoverType(it))
                },
                onSelectGenre = {
                    discoverFocusedItemKey = null
                    viewModel.onEvent(SearchEvent.SelectDiscoverGenre(it))
                },
                onSelectYearRange = {
                    discoverFocusedItemKey = null
                    viewModel.onEvent(SearchEvent.SelectDiscoverYearRange(it))
                },
                onSelectMinRating = {
                    discoverFocusedItemKey = null
                    viewModel.onEvent(SearchEvent.SelectDiscoverMinRating(it))
                },
                onSelectSort = {
                    discoverFocusedItemKey = null
                    viewModel.onEvent(SearchEvent.SelectDiscoverSort(it))
                },
                libraryIds = libraryIds,
                onItemPlay = if (!quickPlayAllowed) null else ({ item ->
                    discoverFocusedItemKey = discoverItemKey(item)
                    pendingDiscoverRestoreOnResume = true
                    HeroBackdropState.update(item.backdropUrl)
                    onPlayTitle(item.id, item.apiType, item.sourceAddonBaseUrl.orEmpty())
                }),
                onToggleHideWatched = {
                    discoverFocusedItemKey = null
                    viewModel.onEvent(SearchEvent.ToggleDiscoverHideWatched)
                },
                onToggleIncludeUnrated = {
                    discoverFocusedItemKey = null
                    viewModel.onEvent(SearchEvent.ToggleDiscoverIncludeUnrated)
                },
                onResetFilters = {
                    discoverFocusedItemKey = null
                    viewModel.onEvent(SearchEvent.ResetDiscoverFilters)
                },
                // The empty grid's Clear filters resets the genre and sort too, since the genre is
                // often what emptied it (#849): the same as the Reset chip.
                onClearFilters = {
                    discoverFocusedItemKey = null
                    viewModel.onEvent(SearchEvent.ResetDiscoverFilters)
                },
                onLoadMore = { viewModel.onEvent(SearchEvent.LoadNextDiscoverResults) },
                onRetry = { viewModel.onEvent(SearchEvent.RetryDiscover) },
                onItemLongPress = { item, addonBaseUrl ->
                    viewModel.posterOptions.show(item, addonBaseUrl)
                }
            )
        }

        val posterOptionsState by viewModel.posterOptions.state.collectAsStateWithLifecycle()
        com.fetch.tv.ui.components.posteroptions.PosterOptionsHost(
            state = posterOptionsState,
            controller = viewModel.posterOptions,
            showNotInterested = true,
            // Saving is what Discover's menu is mostly for: first and focused, so Menu then OK
            // adds a title to (or takes it off) the library, with the menu's confirmation (#857).
            libraryActionFirst = true,
            onNavigateToDetail = { id, type, addonBaseUrl ->
                pendingDiscoverRestoreOnResume = true
                val clickedItem = uiState.discoverResults.firstOrNull { it.id == id }
                HeroBackdropState.update(clickedItem?.backdropUrl)
                onNavigateToDetail(id, type, addonBaseUrl)
            }
        )
    }
}
