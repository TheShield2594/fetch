package com.fetch.tv.ui.screens.library

import android.view.KeyEvent as AndroidKeyEvent
import com.fetch.tv.ui.components.FetchButtonStyle
import com.fetch.tv.ui.components.FetchButton
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fetch.tv.ui.theme.cardCornerRadius
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.focusable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.domain.model.LibraryListTab
import com.fetch.tv.domain.model.LibrarySourceMode
import com.fetch.tv.domain.model.PosterShape
import com.fetch.tv.ui.components.EmptyScreenState
import com.fetch.tv.ui.components.GridContentCard
import com.fetch.tv.ui.screens.detail.requestFocusAfterFrames
import com.fetch.tv.ui.screens.home.HeroBackdropState
import kotlinx.coroutines.launch
import com.fetch.tv.ui.components.PosterCardDefaults
import com.fetch.tv.domain.model.localizedTitle
import com.fetch.tv.ui.components.PosterGridSkeleton
import com.fetch.tv.ui.components.StateAction
import com.fetch.tv.ui.components.FetchDialog
import com.fetch.tv.ui.theme.FetchStatusTones
import com.fetch.tv.ui.theme.FetchTheme
import com.fetch.tv.ui.util.dpadRepeatThrottle
import com.fetch.tv.ui.util.localizedContentType
import com.fetch.tv.ui.util.localizedGenreLabel
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import com.fetch.tv.R


@Composable
private fun localizedTypeLabel(key: String): String = when (key.lowercase()) {
    LibraryTypeTab.ALL_KEY -> stringResource(R.string.library_type_all)
    else -> localizedContentType(key)
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel = hiltViewModel(),
    serversViewModel: LibraryServersViewModel = hiltViewModel(),
    showBuiltInHeader: Boolean = true,
    onNavigateToDetail: (String, String, String?) -> Unit,
    onNavigateToCatalogSeeAll: (String, String, String) -> Unit = { _, _, _ -> },
    onNavigateToSearch: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    // Where "Connect Trakt" / "Connect Simkl" lands: the account page, not the Settings root.
    onNavigateToTrackingSettings: () -> Unit = onNavigateToSettings,
    // The website's stats page and calendar, which the TV reads from it.
    onNavigateToStats: () -> Unit = {},
    onNavigateToCalendar: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val watchedMovieIds by viewModel.watchedMovieIds.collectAsStateWithLifecycle()
    val watchedSeriesIds by viewModel.watchedSeriesIds.collectAsStateWithLifecycle()
    var showDeleteConfirm by remember(uiState.showManageDialog) { mutableStateOf(false) }
    var expandedPicker by remember { mutableStateOf<String?>(null) }
    var selectedTab by rememberSaveable { mutableStateOf(LibraryTab.SAVED) }
    val servers by serversViewModel.servers.collectAsStateWithLifecycle()
    val serverShelves by serversViewModel.shelves.collectAsStateWithLifecycle()
    val hasServers = servers.enabledConnections.isNotEmpty()
    val tabs = LibraryTab.entries.filter { it != LibraryTab.SERVERS || hasServers }
    val tab = selectedTab.takeIf { it in tabs } ?: LibraryTab.SAVED
    // The activity tabs are collected only while shown (the flows stop 5 s after the last
    // collector), so the Saved grid doesn't keep the full watch history decoded behind it.
    val historyMonths = if (tab == LibraryTab.HISTORY) {
        viewModel.historyMonths.collectAsStateWithLifecycle().value
    } else {
        null
    }
    val inProgressItems = if (tab == LibraryTab.IN_PROGRESS) {
        viewModel.inProgressItems.collectAsStateWithLifecycle().value
    } else {
        null
    }
    val activityKeys = remember(tab, historyMonths, inProgressItems) {
        when (tab) {
            LibraryTab.HISTORY -> historyMonths.orEmpty().flatMap { month -> month.items.map(::historyFocusKey) }
            LibraryTab.IN_PROGRESS -> inProgressItems.orEmpty().map(::inProgressFocusKey)
            else -> emptyList()
        }
    }
    val activityFocusRequesters = remember(activityKeys) { activityKeys.associateWith { FocusRequester() } }
    val primaryFocusRequester = remember { FocusRequester() }
    val selectorFocusRequester = remember { FocusRequester() }
    val gridState = rememberLazyGridState()
    var pendingPrimaryFocus by remember { mutableStateOf(true) }
    var serverFocusTarget by remember {
        mutableStateOf(serversViewModel.focusedShelfKey?.let { key -> serversViewModel.focusedIndexes[key]?.let { key to it } })
    }
    var lastFocusedPosterKey by rememberSaveable { mutableStateOf<String?>(null) }
    val visibleItemKeys = remember(uiState.visibleItems) {
        uiState.visibleItems.map { "${it.type}:${it.id}" }
    }
    val visibleItemIndexByKey = remember(visibleItemKeys) {
        visibleItemKeys.withIndex().associate { (index, key) -> key to index }
    }
    val posterFocusRequesters = remember(visibleItemKeys) {
        visibleItemKeys.associateWith { FocusRequester() }
    }
    val layoutDirection = LocalLayoutDirection.current
    val firstVisiblePosterKey = visibleItemKeys.firstOrNull()
    val firstVisibleCardKey = visibleItemKeys.let { keys ->
        if (layoutDirection == LayoutDirection.Rtl) {
            keys.lastOrNull()  // Last in logical order = rightmost in RTL
        } else {
            keys.firstOrNull() // First in logical order = leftmost in LTR
        }
    }
    val posterCardStyle = PosterCardDefaults.Style.copy(
        width = uiState.posterCardWidthDp.dp,
        height = (uiState.posterCardWidthDp * 3 / 2).dp,
        cornerRadius = cardCornerRadius(uiState.posterCardCornerRadiusDp),
        showRestingBorder = true
    )

    LaunchedEffect(tab, servers.revision) {
        if (tab == LibraryTab.SERVERS) serversViewModel.load()
    }

    // A new tab starts at its top. Not on the first composition: coming back from a title keeps
    // the grid where it was.
    var shownTab by remember { mutableStateOf(tab) }
    LaunchedEffect(tab) {
        if (tab != shownTab) {
            shownTab = tab
            runCatching { gridState.scrollToItem(0) }
        }
    }

    LaunchedEffect(uiState.isLoading) {
        if (uiState.isLoading) {
            pendingPrimaryFocus = true
        }
    }

    LaunchedEffect(uiState.isLoading, uiState.sourceMode, uiState.listTabs.size) {
        if (!uiState.isLoading && pendingPrimaryFocus && tab == LibraryTab.SERVERS && serverFocusTarget != null) {
            pendingPrimaryFocus = false
        }
        if (!uiState.isLoading && pendingPrimaryFocus) {
            val restoreKey = lastFocusedPosterKey
            // Only the Saved grid restores here; the activity tabs restore through focusRestorer
            // once their items have loaded.
            val restoreIndex = restoreKey?.takeIf { tab == LibraryTab.SAVED }?.let { visibleItemIndexByKey[it] }
            val restoreRequester = restoreKey?.let { posterFocusRequesters[it] }

            // requestFocus() reports false while the target is not laid out yet, so each try
            // waits for the next frame rather than a fixed delay.
            var focused = false
            if (restoreIndex != null && restoreRequester != null) {
                if (gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0) {
                    runCatching { gridState.scrollToItem(restoreIndex) }
                }
                focused = restoreRequester.requestFocusAfterFrames(frames = 0)
            }

            if (!focused) {
                primaryFocusRequester.requestFocusAfterFrames(frames = 0)
            }
            pendingPrimaryFocus = false
        }
    }

    // Back from deep in the grid returns to the top and the selectors row first, as on Home and
    // Search; a second Back (or Back from the top) leaves the screen.
    val backScope = rememberCoroutineScope()
    val gridScrolledAway by remember {
        androidx.compose.runtime.derivedStateOf { gridState.firstVisibleItemIndex > 0 }
    }
    androidx.activity.compose.BackHandler(
        enabled = tab != LibraryTab.SERVERS && expandedPicker == null && gridScrolledAway
    ) {
        backScope.launch {
            runCatching { gridState.scrollToItem(0) }
            // Give the selectors row (Saved) or the tab row a frame to compose before focusing it.
            val target = if (tab == LibraryTab.SAVED) selectorFocusRequester else primaryFocusRequester
            target.requestFocusAfterFrames(frames = 0)
        }
    }

    LaunchedEffect(uiState.sortSelectionVersion, firstVisiblePosterKey) {
        if (uiState.sortSelectionVersion <= 0L) return@LaunchedEffect
        val targetKey = firstVisiblePosterKey ?: return@LaunchedEffect
        runCatching { gridState.scrollToItem(0) }
        // The poster's requester is registered when it composes, so look it up each frame, and
        // stop once the user has focused a poster of their own.
        val focusedBefore = lastFocusedPosterKey
        repeat(6) {
            if (lastFocusedPosterKey != focusedBefore) return@LaunchedEffect
            val focused = posterFocusRequesters[targetKey]
                ?.let { requester -> runCatching { requester.requestFocus() }.getOrDefault(false) }
                ?: false
            if (focused) return@LaunchedEffect
            withFrameNanos { }
        }
    }

    if (uiState.isLoading) {
        val loadingFocusRequester = remember { FocusRequester() }
        LaunchedEffect(uiState.isLoading) {
            loadingFocusRequester.requestFocus()
        }
        val syncingText = stringResource(R.string.library_syncing_library)

        Box(modifier = Modifier.fillMaxSize()) {
            // Holds focus until the grid is ready. It's named, so TalkBack doesn't land on an
            // unlabelled element.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(loadingFocusRequester)
                    .focusable()
                    .semantics { contentDescription = syncingText }
            )
            Column(
                modifier = Modifier.padding(start = FetchTheme.spacing.screen.horizontal, top = FetchTheme.spacing.screen.vertical),
                verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.lg)
            ) {
                Text(
                    text = syncingText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = FetchTheme.colors.TextSecondary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
                PosterGridSkeleton(cardWidth = posterCardStyle.width, cardHeight = posterCardStyle.height)
            }
        }
        return
    }


    val globalLandscape = com.fetch.tv.ui.components.LocalLandscapePosterMode.current

    LazyVerticalGrid(
        columns = GridCells.Adaptive(
            minSize = if (globalLandscape) posterCardStyle.height else posterCardStyle.width
        ),
        state = gridState,
        modifier = Modifier
            .fillMaxSize()
            .background(FetchTheme.colors.Background)
            .focusRestorer {
                val lastKey = lastFocusedPosterKey
                when {
                    lastKey != null && lastKey in activityFocusRequesters -> activityFocusRequesters[lastKey]
                    lastKey != null && lastKey in posterFocusRequesters -> posterFocusRequesters[lastKey]
                    tab == LibraryTab.HISTORY || tab == LibraryTab.IN_PROGRESS ->
                        activityKeys.firstOrNull()?.let(activityFocusRequesters::get)
                    else -> posterFocusRequesters[firstVisibleCardKey]
                } ?: FocusRequester()
            }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                // Observed, not consumed: clears an error banner that has been up long enough to read.
                if (native.action == AndroidKeyEvent.ACTION_DOWN && uiState.transientMessageDismissible) {
                    viewModel.onDismissReadError()
                }
                false
            }
            .dpadRepeatThrottle(moveFocusOnRepeat = false),
        contentPadding = PaddingValues(start = FetchTheme.spacing.screen.horizontal, end = FetchTheme.spacing.screen.horizontal, top = FetchTheme.spacing.screen.vertical, bottom = FetchTheme.spacing.xxl),
        horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.lg)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.library_title),
                    style = FetchTheme.textStyles.sectionTitle,
                    color = if (showBuiltInHeader) FetchTheme.colors.TextPrimary else Color.Transparent
                )
                // Source names are stored in natural case and capitalized here, once, for the locale.
                val sourceName = when {
                    tab == LibraryTab.SERVERS -> stringResource(R.string.library_source_servers)
                    tab == LibraryTab.HISTORY -> stringResource(R.string.library_tab_history)
                    tab == LibraryTab.IN_PROGRESS -> stringResource(R.string.library_tab_in_progress)
                    uiState.sourceMode == LibrarySourceMode.TRAKT -> stringResource(R.string.trakt_name)
                    uiState.sourceMode == LibrarySourceMode.SIMKL -> stringResource(R.string.simkl_name)
                    uiState.sourceMode == LibrarySourceMode.MDBLIST -> stringResource(R.string.mdblist_name)
                    uiState.sourceMode == LibrarySourceMode.FETCH_WEBSITE -> stringResource(R.string.website_name)
                    else -> stringResource(R.string.library_source_local)
                }
                Text(
                    text = sourceName.uppercase(LocalConfiguration.current.locales[0]),
                    style = FetchTheme.textStyles.eyebrow,
                    color = if (showBuiltInHeader) FetchTheme.colors.TextTertiary else Color.Transparent
                )
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            LibraryTabRow(
                tabs = tabs,
                selectedTab = tab,
                primaryFocusRequester = primaryFocusRequester,
                onSelected = { picked ->
                    expandedPicker = null
                    when (picked) {
                        LibraryTab.UPCOMING -> onNavigateToCalendar()
                        LibraryTab.STATS -> onNavigateToStats()
                        else -> selectedTab = picked
                    }
                }
            )
        }

        if (tab == LibraryTab.SAVED) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                LibrarySelectorsRow(
                    sourceMode = uiState.sourceMode,
                    listTabs = uiState.listTabs,
                    typeTabs = uiState.availableTypeTabs,
                    sortOptions = uiState.availableSortOptions,
                    genres = uiState.availableGenres,
                    years = uiState.availableYears,
                    selectedListKey = uiState.selectedListKey,
                    selectedTypeTab = uiState.selectedTypeTab,
                    selectedSortOption = uiState.selectedSortOption,
                    selectedGenre = uiState.selectedGenre,
                    selectedYear = uiState.selectedYear,
                    selectedWatchedFilter = uiState.selectedWatchedFilter,
                    anythingReady = uiState.readyBestMatchKeys.isNotEmpty(),
                    primaryFocusRequester = selectorFocusRequester,
                    upFocusRequester = primaryFocusRequester,
                    expandedPicker = expandedPicker,
                    onExpandedChange = { picker, shouldExpand ->
                        expandedPicker = if (shouldExpand) picker else null
                    },
                    onSelectList = { key ->
                        viewModel.onSelectListTab(key)
                        expandedPicker = null
                    },
                    onSelectType = { type ->
                        viewModel.onSelectTypeTab(type)
                        expandedPicker = null
                    },
                    onSelectSort = { sort ->
                        viewModel.onSelectSortOption(sort)
                        expandedPicker = null
                    },
                    onSelectGenre = { key ->
                        viewModel.onSelectGenre(key)
                        expandedPicker = null
                    },
                    onSelectYear = { key ->
                        viewModel.onSelectYear(key)
                        expandedPicker = null
                    },
                    onSelectWatchedFilter = { filter ->
                        viewModel.onSelectWatchedFilter(filter)
                        expandedPicker = null
                    }
                )
            }

            if (uiState.listManagement != null && uiState.isTrackingAuthenticated) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LibraryActionsRow(
                        pending = uiState.pendingOperation,
                        isSyncing = uiState.isSyncing,
                        showManageLists = uiState.listManagement != null,
                        showRefresh = uiState.sourceMode != LibrarySourceMode.LOCAL,
                        onManageLists = viewModel::onOpenManageLists,
                        onRefresh = viewModel::onRefresh
                    )
                }
            }

            if (uiState.visibleItems.isEmpty() && uiState.filtersHideAllItems) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyScreenState(
                        title = stringResource(R.string.library_empty_filtered_title),
                        subtitle = stringResource(R.string.library_empty_filtered_subtitle),
                        icon = Icons.Default.BookmarkBorder,
                        height = 300.dp,
                        modifier = Modifier.padding(bottom = FetchTheme.spacing.xxl),
                        action = StateAction(stringResource(R.string.library_action_clear_filters)) {
                            expandedPicker = null
                            viewModel.onClearFilters()
                        }
                    )
                }
            } else if (uiState.visibleItems.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    val typeKey = uiState.selectedTypeTab?.key?.lowercase()
                    val selectedTypeLabel = when (typeKey) {
                        "movie" -> stringResource(R.string.library_empty_type_movies)
                        "series", "tv" -> stringResource(R.string.library_empty_type_series)
                        null, LibraryTypeTab.ALL_KEY -> stringResource(R.string.library_empty_type_items)
                        else -> localizedTypeLabel(typeKey.orEmpty()).lowercase()
                    }
                    val title = when {
                        uiState.sourceMode == LibrarySourceMode.TRAKT && !uiState.isTrackingAuthenticated -> stringResource(R.string.library_empty_trakt_not_auth_title)
                        uiState.sourceMode == LibrarySourceMode.SIMKL && !uiState.isTrackingAuthenticated -> stringResource(R.string.library_empty_simkl_not_auth_title)
                        uiState.sourceMode == LibrarySourceMode.TRAKT || uiState.sourceMode == LibrarySourceMode.MDBLIST ||
                            uiState.sourceMode == LibrarySourceMode.FETCH_WEBSITE -> stringResource(R.string.library_empty_trakt_title, selectedTypeLabel)
                        uiState.sourceMode == LibrarySourceMode.SIMKL -> stringResource(R.string.library_empty_simkl_title, selectedTypeLabel)
                        else -> stringResource(R.string.library_empty_local_title, selectedTypeLabel)
                    }
                    val subtitle = when {
                        uiState.sourceMode == LibrarySourceMode.TRAKT && !uiState.isTrackingAuthenticated -> stringResource(R.string.library_empty_trakt_not_auth_subtitle)
                        uiState.sourceMode == LibrarySourceMode.SIMKL && !uiState.isTrackingAuthenticated -> stringResource(R.string.library_empty_simkl_not_auth_subtitle)
                        uiState.sourceMode == LibrarySourceMode.TRAKT || uiState.sourceMode == LibrarySourceMode.MDBLIST ||
                            uiState.sourceMode == LibrarySourceMode.FETCH_WEBSITE -> stringResource(R.string.library_empty_trakt_subtitle)
                        uiState.sourceMode == LibrarySourceMode.SIMKL -> stringResource(R.string.library_empty_simkl_subtitle)
                        else -> stringResource(R.string.library_empty_local_subtitle)
                    }
                    val signedOut = !uiState.isTrackingAuthenticated &&
                        (uiState.sourceMode == LibrarySourceMode.TRAKT || uiState.sourceMode == LibrarySourceMode.SIMKL)
                    EmptyScreenState(
                        title = title,
                        subtitle = subtitle,
                        icon = Icons.Default.BookmarkBorder,
                        height = 300.dp,
                        modifier = Modifier.padding(bottom = FetchTheme.spacing.xxl),
                        action = if (signedOut) {
                            val providerName = stringResource(
                                if (uiState.sourceMode == LibrarySourceMode.SIMKL) R.string.simkl_name else R.string.trakt_name
                            )
                            StateAction(stringResource(R.string.library_action_connect_provider, providerName), onNavigateToTrackingSettings)
                        } else {
                            StateAction(stringResource(R.string.library_action_search_titles), onNavigateToSearch)
                        }
                    )
                }
            }

            items(uiState.visibleItems, key = { "${it.type}:${it.id}" }) { item ->
                val focusKey = "${item.type}:${item.id}"
                val isSeries = item.type.equals("series", ignoreCase = true) || item.type.equals("tv", ignoreCase = true)
                val previewForLongPress = remember(item) {
                    item.toMetaPreview().copy(posterShape = PosterShape.POSTER)
                }
                GridContentCard(
                    item = previewForLongPress,
                    posterCardStyle = posterCardStyle,
                    isWatched = if (isSeries) item.id in watchedSeriesIds else item.id in watchedMovieIds,
                    focusRequester = posterFocusRequesters[focusKey],
                    showLabel = true,
                    onFocused = {
                        lastFocusedPosterKey = focusKey
                        viewModel.prefetchMetaOnFocus(item.id, item.type)
                    },
                    onClick = {
                        lastFocusedPosterKey = focusKey
                        val backdrop = viewModel.getCachedBackdrop(item.id, item.type)
                        HeroBackdropState.update(backdrop)
                        onNavigateToDetail(item.id, item.type, item.addonBaseUrl)
                    },
                    onLongPress = {
                        lastFocusedPosterKey = focusKey
                        viewModel.posterOptions.show(previewForLongPress, item.addonBaseUrl)
                    }
                )
            }
        } else if (tab == LibraryTab.IN_PROGRESS || tab == LibraryTab.HISTORY) {
            val actions = LibraryActivityCardActions(
                focusRequesterFor = activityFocusRequesters::get,
                onFocused = { key, item ->
                    lastFocusedPosterKey = key
                    viewModel.prefetchMetaOnFocus(item.id, item.type)
                },
                onClick = { key, item ->
                    lastFocusedPosterKey = key
                    HeroBackdropState.update(viewModel.getCachedBackdrop(item.id, item.type))
                    onNavigateToDetail(item.id, item.type, item.addonBaseUrl)
                },
                onLongPress = { key, preview, item ->
                    lastFocusedPosterKey = key
                    viewModel.posterOptions.show(preview, item.addonBaseUrl)
                },
                isWatched = { item ->
                    if (item.isSeries) item.id in watchedSeriesIds else item.id in watchedMovieIds
                }
            )
            if (tab == LibraryTab.HISTORY) {
                libraryHistoryContent(historyMonths, posterCardStyle, actions, onNavigateToSearch)
            } else {
                libraryInProgressContent(inProgressItems, posterCardStyle, actions, onNavigateToSearch)
            }
        } else {
            libraryServerContent(
                shelves = serverShelves,
                onNavigateToSettings = onNavigateToSettings,
                onRetry = serversViewModel::reload,
                posterCardStyle = posterCardStyle,
                rowInset = FetchTheme.spacing.xxxl,
                focusTarget = serverFocusTarget,
                focusedIndexes = serversViewModel.focusedIndexes,
                onItemFocused = { shelfKey, index ->
                    val target = serverFocusTarget
                    if (target == null || target == shelfKey to index) {
                        serverFocusTarget = null
                        serversViewModel.onItemFocused(shelfKey, index)
                    }
                },
                isWatched = { item ->
                    val isSeries = item.apiType.equals("series", ignoreCase = true) || item.apiType.equals("tv", ignoreCase = true)
                    if (isSeries) item.id in watchedSeriesIds else item.id in watchedMovieIds
                },
                onItemClick = { itemId, itemType, addonBaseUrl -> onNavigateToDetail(itemId, itemType, addonBaseUrl) },
                onSeeAll = { row -> onNavigateToCatalogSeeAll(row.catalogId, row.addonId, row.apiType) },
                onItemLongPress = { item, addonBaseUrl -> viewModel.posterOptions.show(item, addonBaseUrl) }
            )
        }

        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(modifier = Modifier.height(FetchTheme.spacing.sm)) }
    }

    if (uiState.showManageDialog && uiState.listManagement != null) {
        ManageListsDialog(
            capabilities = requireNotNull(uiState.listManagement),
            tabs = uiState.listTabs,
            selectedKey = uiState.manageSelectedListKey,
            errorMessage = uiState.errorMessage,
            pending = uiState.pendingOperation,
            onSelect = viewModel::onSelectManageList,
            onCreate = viewModel::onStartCreateList,
            onEdit = viewModel::onStartEditList,
            onMoveUp = viewModel::onMoveSelectedListUp,
            onMoveDown = viewModel::onMoveSelectedListDown,
            onDelete = { showDeleteConfirm = true },
            onDismiss = viewModel::onCloseManageLists
        )
    }

    if (showDeleteConfirm && uiState.showManageDialog) {
        ConfirmDeleteDialog(
            listTitle = uiState.listTabs.firstOrNull { it.key == uiState.manageSelectedListKey }?.localizedTitle(),
            sourceMode = uiState.sourceMode,
            pending = uiState.pendingOperation,
            onConfirm = {
                showDeleteConfirm = false
                viewModel.onDeleteSelectedList()
            },
            onCancel = { showDeleteConfirm = false }
        )
    }

    val listEditor = uiState.listEditorState
    if (listEditor != null && uiState.showManageDialog && uiState.listManagement != null) {
        ListEditorDialog(
            capabilities = requireNotNull(uiState.listManagement),
            state = listEditor,
            pending = uiState.pendingOperation,
            onNameChanged = viewModel::onUpdateEditorName,
            onDescriptionChanged = viewModel::onUpdateEditorDescription,
            onPrivacyChanged = viewModel::onUpdateEditorPrivacy,
            onSave = viewModel::onSubmitEditor,
            onCancel = viewModel::onCancelEditor
        )
    }

    val transientMessage = uiState.transientMessage
    if (!transientMessage.isNullOrBlank()) {
        Box(
            modifier = Modifier
                .fillMaxSize(),
            contentAlignment = androidx.compose.ui.Alignment.TopCenter
        ) {
            Text(
                text = transientMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = if (uiState.transientMessageIsError) FetchStatusTones.errorText else FetchTheme.colors.TextPrimary,
                modifier = Modifier
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .padding(top = FetchTheme.spacing.xl)
                    .background(FetchTheme.colors.BackgroundElevated, RoundedCornerShape(FetchTheme.radii.sm))
                    .padding(horizontal = FetchTheme.spacing.lg, vertical = FetchTheme.spacing.md)
            )
        }
    }

    val posterOptionsState by viewModel.posterOptions.state.collectAsStateWithLifecycle()
    com.fetch.tv.ui.components.posteroptions.PosterOptionsHost(
        state = posterOptionsState,
        controller = viewModel.posterOptions,
        onNavigateToDetail = { id, type, addonBaseUrl ->
            onNavigateToDetail(id, type, addonBaseUrl.takeIf { it.isNotBlank() })
        }
    )
}

/**
 * The Library's sections in one row. Upcoming and Stats open their pages; the others switch the
 * grid below. The selected tab takes the screen's first focus.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LibraryTabRow(
    tabs: List<LibraryTab>,
    selectedTab: LibraryTab,
    primaryFocusRequester: FocusRequester,
    onSelected: (LibraryTab) -> Unit
) {
    val rowDescription = stringResource(R.string.library_tabs_description)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = rowDescription },
        horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        tabs.forEach { tab ->
            val isSelected = tab == selectedTab
            FetchButton(
                onClick = { onSelected(tab) },
                modifier = Modifier
                    .then(if (isSelected) Modifier.focusRequester(primaryFocusRequester) else Modifier)
                    .semantics { selected = isSelected },
                selected = isSelected
            ) {
                val icon = when (tab) {
                    LibraryTab.UPCOMING -> Icons.Default.CalendarMonth
                    LibraryTab.STATS -> Icons.Default.BarChart
                    else -> null
                }
                if (icon != null) {
                    Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(FetchTheme.spacing.sm))
                }
                Text(
                    text = when (tab) {
                        LibraryTab.SAVED -> stringResource(R.string.library_source_saved)
                        LibraryTab.IN_PROGRESS -> stringResource(R.string.library_tab_in_progress)
                        LibraryTab.HISTORY -> stringResource(R.string.library_tab_history)
                        LibraryTab.SERVERS -> stringResource(R.string.library_source_servers)
                        LibraryTab.UPCOMING -> stringResource(R.string.library_tab_upcoming)
                        LibraryTab.STATS -> stringResource(R.string.library_open_stats)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LibrarySelectorsRow(
    sourceMode: LibrarySourceMode,
    listTabs: List<LibraryListTab>,
    typeTabs: List<LibraryTypeTab>,
    sortOptions: List<LibrarySortOption>,
    genres: List<FilterOption>,
    years: List<FilterOption>,
    selectedListKey: String?,
    selectedTypeTab: LibraryTypeTab?,
    selectedSortOption: LibrarySortOption,
    selectedGenre: String?,
    selectedYear: String?,
    selectedWatchedFilter: LibraryWatchedFilter,
    anythingReady: Boolean,
    primaryFocusRequester: FocusRequester,
    upFocusRequester: FocusRequester,
    expandedPicker: String?,
    onExpandedChange: (String, Boolean) -> Unit,
    onSelectList: (String) -> Unit,
    onSelectType: (LibraryTypeTab) -> Unit,
    onSelectSort: (LibrarySortOption) -> Unit,
    onSelectGenre: (String?) -> Unit,
    onSelectYear: (String?) -> Unit,
    onSelectWatchedFilter: (LibraryWatchedFilter) -> Unit
) {
    val selectedListLabel = listTabs.firstOrNull { it.key == selectedListKey }?.localizedTitle()
        ?: stringResource(R.string.action_select)
    val selectedTypeLabel = selectedTypeTab?.let {
        if (it.key == LibraryTypeTab.ALL_KEY) stringResource(R.string.library_type_all) else localizedTypeLabel(it.key)
    } ?: stringResource(R.string.library_type_all)
    val selectedSortLabel = stringResource(selectedSortOption.labelResId)
    val allLabel = stringResource(R.string.library_type_all)
    val selectedGenreLabel = selectedGenre?.let { localizedGenreLabel(it) } ?: allLabel
    val selectedYearLabel = selectedYear ?: allLabel

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)
        ) {
            if (listTabs.isNotEmpty()) {
                LibraryDropdownPicker(
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(primaryFocusRequester),
                    upFocusRequester = upFocusRequester,
                    title = stringResource(R.string.library_filter_list),
                    value = selectedListLabel,
                    selectedValue = selectedListKey,
                    expanded = expandedPicker == "list",
                    options = listTabs.map { LibraryOption(it.localizedTitle(), it.key) },
                    onExpandedChange = { onExpandedChange("list", it) },
                    onSelect = { onSelectList(it.value) }
                )
            }

            LibraryDropdownPicker(
                modifier = if (listTabs.isNotEmpty()) {
                    Modifier.weight(1f)
                } else {
                    Modifier
                        .weight(1f)
                        .focusRequester(primaryFocusRequester)
                },
                upFocusRequester = upFocusRequester,
                title = stringResource(R.string.library_filter_type),
                value = selectedTypeLabel,
                selectedValue = selectedTypeTab?.key,
                expanded = expandedPicker == "type",
                options = typeTabs.map {
                    val label = if (it.key == LibraryTypeTab.ALL_KEY) it.label else {
                        val count = it.label.substringAfterLast("(", "").removeSuffix(")").trim().toIntOrNull()
                        val localizedName = localizedTypeLabel(it.key)
                        if (count != null) stringResource(R.string.library_filter_option_count, localizedName, count) else localizedName
                    }
                    LibraryOption(label, it.key)
                },
                onExpandedChange = { onExpandedChange("type", it) },
                onSelect = { option ->
                    typeTabs.firstOrNull { it.key == option.value }?.let(onSelectType)
                }
            )

            if (sortOptions.isNotEmpty()) {
                LibraryDropdownPicker(
                    modifier = Modifier.weight(1f),
                    upFocusRequester = upFocusRequester,
                    title = stringResource(R.string.library_filter_sort),
                    value = selectedSortLabel,
                    selectedValue = selectedSortOption.key,
                    expanded = expandedPicker == "sort",
                    options = sortOptions.map { LibraryOption(stringResource(it.labelResId), it.key) },
                    onExpandedChange = { onExpandedChange("sort", it) },
                    onSelect = { option ->
                        sortOptions.firstOrNull { it.key == option.value }?.let(onSelectSort)
                    }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)
        ) {
            if (genres.isNotEmpty()) {
                val genreAllOption = LibraryOption(allLabel, "__all__")
                LibraryDropdownPicker(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.library_filter_genre),
                    value = selectedGenreLabel,
                    selectedValue = selectedGenre ?: "__all__",
                    expanded = expandedPicker == "genre",
                    options = listOf(genreAllOption) + genres.map {
                        LibraryOption(stringResource(R.string.library_filter_option_count, localizedGenreLabel(it.label), it.count), it.key)
                    },
                    onExpandedChange = { onExpandedChange("genre", it) },
                    onSelect = { option ->
                        onSelectGenre(if (option.value == "__all__") null else option.value)
                    }
                )
            }

            if (years.isNotEmpty()) {
                val yearAllOption = LibraryOption(allLabel, "__all__")
                LibraryDropdownPicker(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.library_filter_year),
                    value = selectedYearLabel,
                    selectedValue = selectedYear ?: "__all__",
                    expanded = expandedPicker == "year",
                    options = listOf(yearAllOption) + years.map {
                        LibraryOption(stringResource(R.string.library_filter_option_count, it.label, it.count), it.key)
                    },
                    onExpandedChange = { onExpandedChange("year", it) },
                    onSelect = { option ->
                        onSelectYear(if (option.value == "__all__") null else option.value)
                    }
                )
            }

            val watchedFilterLabel = stringResource(selectedWatchedFilter.labelResId)
            LibraryDropdownPicker(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.library_filter_watched),
                value = watchedFilterLabel,
                selectedValue = selectedWatchedFilter.key,
                expanded = expandedPicker == "watched",
                options = libraryWatchedFilterOptions(anythingReady, selectedWatchedFilter).map {
                    LibraryOption(stringResource(it.labelResId), it.key)
                },
                onExpandedChange = { onExpandedChange("watched", it) },
                onSelect = { option ->
                    LibraryWatchedFilter.entries.firstOrNull { it.key == option.value }
                        ?.let(onSelectWatchedFilter)
                }
            )
        }
    }
}

/**
 * A filter that opens its choices in the app's single-choice dialog, the TV picker Settings uses,
 * rather than a touch dropdown. Closing it puts focus back on the filter.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LibraryDropdownPicker(
    modifier: Modifier = Modifier,
    upFocusRequester: FocusRequester? = null,
    title: String,
    value: String,
    selectedValue: String?,
    expanded: Boolean,
    options: List<LibraryOption>,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (LibraryOption) -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val anchorFocusRequester = remember { FocusRequester() }
    var wasExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        if (expanded) {
            wasExpanded = true
        } else if (wasExpanded) {
            wasExpanded = false
            anchorFocusRequester.requestFocusAfterFrames()
        }
    }

    Box(modifier = modifier) {
        Card(
            onClick = { onExpandedChange(!expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (upFocusRequester != null) {
                        Modifier.focusProperties { up = upFocusRequester }
                    } else {
                        Modifier
                    }
                )
                .focusRequester(anchorFocusRequester)
                .onFocusChanged { isFocused = it.isFocused },
            shape = CardDefaults.shape(shape = RoundedCornerShape(FetchTheme.radii.md)),
            colors = CardDefaults.colors(
                containerColor = FetchTheme.colors.BackgroundCard,
                focusedContainerColor = FetchTheme.colors.FocusBackground
            ),
            border = CardDefaults.border(
                border = androidx.tv.material3.Border(
                    border = BorderStroke(FetchTheme.spacing.hairline, FetchTheme.colors.Border),
                    shape = RoundedCornerShape(FetchTheme.radii.md)
                ),
                focusedBorder = androidx.tv.material3.Border(
                    border = FetchTheme.focusRing.border(),
                    shape = RoundedCornerShape(FetchTheme.radii.md)
                )
            ),
            scale = CardDefaults.scale(
                focusedScale = 1.0f,
                pressedScale = 1.0f
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FetchTheme.spacing.md, vertical = FetchTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xxs)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = FetchTheme.colors.TextTertiary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.titleMedium,
                        color = FetchTheme.colors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) stringResource(R.string.cd_collapse, title) else stringResource(R.string.cd_expand, title),
                        tint = if (isFocused) FetchTheme.colors.FocusRing else FetchTheme.colors.TextSecondary
                    )
                }
            }
        }
    }

    if (expanded) {
        com.fetch.tv.ui.screens.settings.SettingsSingleChoiceDialog(
            title = title,
            options = options.map { com.fetch.tv.ui.screens.settings.SettingsPickerOption(it.value, it.label) },
            selectedValue = selectedValue.orEmpty(),
            onOptionSelected = { chosen -> options.firstOrNull { it.value == chosen }?.let(onSelect) },
            onDismiss = { onExpandedChange(false) }
        )
    }
}

private data class LibraryOption(
    val label: String,
    val value: String
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LibraryActionsRow(
    pending: Boolean,
    isSyncing: Boolean,
    showManageLists: Boolean,
    showRefresh: Boolean,
    onManageLists: () -> Unit,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)
    ) {
        if (showManageLists) {
            FetchButton(
                onClick = onManageLists,
                enabled = !pending && !isSyncing
            ) {
                Text(stringResource(R.string.library_manage_lists))
            }
        }
        if (showRefresh) {
            // Stays enabled while syncing so it keeps focus; the "Syncing…" label is the state cue
            // and a press during a sync does nothing.
            FetchButton(
                onClick = { if (!pending && !isSyncing) onRefresh() }
            ) {
                Text(if (isSyncing) stringResource(R.string.library_syncing_btn) else stringResource(R.string.library_sync_btn))
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ConfirmDeleteDialog(
    listTitle: String?,
    sourceMode: LibrarySourceMode,
    pending: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val cancelFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { cancelFocusRequester.requestFocus() }
    }
    // Name the service the list is really deleted from; this used to say Trakt for every provider.
    val providerName = when (sourceMode) {
        LibrarySourceMode.TRAKT -> stringResource(R.string.trakt_name)
        LibrarySourceMode.SIMKL -> stringResource(R.string.simkl_name)
        LibrarySourceMode.MDBLIST -> stringResource(R.string.mdblist_name)
        LibrarySourceMode.FETCH_WEBSITE -> stringResource(R.string.website_name)
        LibrarySourceMode.LOCAL -> null
    }
    FetchDialog(
        onDismiss = onCancel,
        title = if (listTitle != null) {
            stringResource(R.string.library_delete_title_named, listTitle)
        } else {
            stringResource(R.string.library_delete_title)
        },
        subtitle = if (providerName != null) {
            stringResource(R.string.library_delete_subtitle_provider, providerName)
        } else {
            stringResource(R.string.library_delete_subtitle_local)
        },
        width = 420.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)
        ) {
            // Cancel first and focused, so one stray OK press can't delete the list.
            FetchButton(
                onClick = onCancel,
                enabled = !pending,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(cancelFocusRequester)
            ) {
                Text(stringResource(R.string.action_cancel))
            }
            FetchButton(
                onClick = onConfirm,
                enabled = !pending,
                modifier = Modifier.weight(1f),
                style = FetchButtonStyle.Destructive
            ) {
                Text(stringResource(R.string.library_list_delete))
            }
        }
    }
}
