package com.fetch.tv.ui.screens.library

import com.fetch.tv.ui.components.focusSafeHorizontalScroll
import com.fetch.tv.core.util.runSuspendCatching
import android.view.KeyEvent as AndroidKeyEvent
import com.fetch.tv.ui.components.FetchButtonStyle
import com.fetch.tv.ui.components.FetchButton
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fetch.tv.ui.theme.cardCornerRadius
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.focusable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Sync
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.domain.model.LibraryListTab
import com.fetch.tv.domain.model.LibrarySourceMode
import com.fetch.tv.domain.model.PosterShape
import com.fetch.tv.ui.components.EmptyScreenState
import com.fetch.tv.ui.components.GridContentCard
import com.fetch.tv.ui.components.browseGridItemMotion
import com.fetch.tv.ui.components.browseGridFastScroll
import com.fetch.tv.ui.components.restoreBrowseFocus
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.pluralStringResource
import com.fetch.tv.ui.screens.detail.requestFocusAfterFrames
import com.fetch.tv.ui.screens.home.HeroBackdropState
import kotlinx.coroutines.launch
import com.fetch.tv.ui.components.PosterCardDefaults
import com.fetch.tv.domain.model.localizedTitle
import com.fetch.tv.ui.components.PosterGridSkeleton
import com.fetch.tv.ui.components.FetchFilterChip
import com.fetch.tv.ui.components.FetchFilterOption
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
    onNavigateToDetail: (String, String, String?) -> Unit,
    onNavigateToCatalogSeeAll: (String, String, String) -> Unit = { _, _, _ -> },
    onNavigateToSearch: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    // Where "Connect Trakt" / "Connect Simkl" lands: the account page, not the Settings root.
    onNavigateToTrackingSettings: () -> Unit = onNavigateToSettings,
    // The website's stats page and calendar, which the TV reads from it.
    onNavigateToStats: () -> Unit = {},
    onNavigateToCalendar: () -> Unit = {},
    /** OK on an In progress card: resumes it the way Continue Watching does (#856). */
    onResume: (com.fetch.tv.ui.screens.home.ContinueWatchingItem) -> Unit = { item ->
        val progress = (item as com.fetch.tv.ui.screens.home.ContinueWatchingItem.InProgress).progress
        onNavigateToDetail(progress.contentId, progress.contentType, progress.addonBaseUrl)
    },
    /** Play/Pause on a saved title: starts its Best match without the detail page (#856, #812). */
    onPlayTitle: (id: String, type: String, addonBaseUrl: String?) -> Unit = { _, _, _ -> }
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // The ids the Watched filter reads, so the ticks and the filter can't disagree (#849).
    val watchedMovieIds = uiState.watchedMovieIds
    val watchedSeriesIds = uiState.watchedSeriesIds
    var showDeleteConfirm by remember(uiState.showManageDialog) { mutableStateOf(false) }
    var expandedPicker by remember { mutableStateOf<String?>(null) }
    var selectedTab by rememberSaveable { mutableStateOf(LibraryTab.WATCHLIST) }
    val servers by serversViewModel.servers.collectAsStateWithLifecycle()
    val serverShelves by serversViewModel.shelves.collectAsStateWithLifecycle()
    val hasServers = servers.enabledConnections.isNotEmpty()
    // Upcoming and Stats open their own pages, so they are buttons apart from the tabs (#855).
    val tabs = LibraryTab.entries.filter { !it.opensPage && (it != LibraryTab.SERVERS || hasServers) }
    val tab = selectedTab.takeIf { it in tabs } ?: LibraryTab.WATCHLIST
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
        uiState.visibleItems.map { com.fetch.tv.ui.components.browseItemKey(it.type, it.id) }
    }
    val visibleItemIndexByKey = remember(visibleItemKeys) {
        visibleItemKeys.withIndex().associate { (index, key) -> key to index }
    }
    val posterFocusRequesters = remember(visibleItemKeys) {
        visibleItemKeys.associateWith { FocusRequester() }
    }
    val firstVisiblePosterKey = visibleItemKeys.firstOrNull()

    // Multi-select (#653): OK toggles cards, the bar under the grid acts on them, Back ends it.
    var selection by remember { mutableStateOf(LibrarySelectionState()) }
    fun dispatchSelection(action: LibrarySelectionAction) {
        selection = reduceLibrarySelection(selection, action)
    }
    val selectionKeys = if (tab.listScope != null) visibleItemKeys else activityKeys
    val selectionBarFocusRequester = remember { FocusRequester() }
    var pendingBulkRemoveConfirm by remember { mutableStateOf(false) }
    var showBulkListPicker by remember { mutableStateOf(false) }
    var selectionBarHeightPx by remember { mutableIntStateOf(0) }
    val newEpisodeBadges by viewModel.newEpisodeBadges.collectAsStateWithLifecycle()
    val progressByContentId by viewModel.progressByContentId.collectAsStateWithLifecycle()
    val quickPlayAllowed by viewModel.quickPlayAllowed.collectAsStateWithLifecycle()
    val readyFallbackLabel = stringResource(R.string.library_card_ready)
    fun readyChip(labels: List<String>?): String? = labels?.let { libraryReadyChipText(it, readyFallbackLabel) }
    fun resumeInProgress(item: LibraryActivityItem): Boolean {
        val progress = item.resume ?: return false
        onResume(com.fetch.tv.ui.screens.home.ContinueWatchingItem.InProgress(progress))
        return true
    }
    val newEpisodeLabel = stringResource(R.string.cw_new_episode_short)
    val newSeasonLabel = stringResource(R.string.cw_new_season)
    fun badgeLabel(badge: LibraryNewEpisodeBadge?): Pair<String, Boolean>? = when (badge) {
        null -> null
        LibraryNewEpisodeBadge.NEW_EPISODE -> newEpisodeLabel to false
        LibraryNewEpisodeBadge.NEW_SEASON -> newSeasonLabel to true
    }
    val selectionScope = rememberCoroutineScope()
    // The Select button: starts choosing on the card last focused here (else the first), or ends it.
    fun toggleSelectionMode() {
        if (selection.active) {
            dispatchSelection(LibrarySelectionAction.Exit)
            return
        }
        dispatchSelection(LibrarySelectionAction.Enter)
        val target = lastFocusedPosterKey?.takeIf { it in selectionKeys } ?: selectionKeys.firstOrNull() ?: return
        selectionScope.launch {
            val index = if (tab.listScope != null) visibleItemIndexByKey[target] else null
            // The header sits above the grid (#850), so a card's index is its grid index.
            if (index != null) runSuspendCatching { gridState.scrollToItem(index) }
            (posterFocusRequesters[target] ?: activityFocusRequesters[target])?.requestFocusAfterFrames(frames = 1)
        }
    }
    LaunchedEffect(tab) { dispatchSelection(LibrarySelectionAction.Exit) }
    LaunchedEffect(selectionKeys) { dispatchSelection(LibrarySelectionAction.Retain(selectionKeys.toSet())) }
    // The posters take the size set in Layout, made smaller only as far as needed for two full
    // rows (three of the shorter landscape cards) to show under the header on entry (audit C3,
    // #848); the browse scaffold measures the grid with its header open (#850).
    val density = LocalDensity.current

    // Watchlist and Lists share the grid; the view model shows the tab's lists in it.
    LaunchedEffect(tab) {
        tab.listScope?.let(viewModel::onSelectListScope)
    }

    LaunchedEffect(tab, servers.revision) {
        if (tab == LibraryTab.SERVERS) serversViewModel.load()
    }

    // A new tab starts at its top. Not on the first composition: coming back from a title keeps
    // the grid where it was.
    // The grid's content fades in on a tab change and as the first page replaces the skeleton,
    // keeping the header still (#853). Not under reduced motion or on a low-RAM device.
    val fadeContent = FetchTheme.fullMotion && !FetchTheme.lowRamDevice
    val contentAlpha = remember { androidx.compose.animation.core.Animatable(1f) }
    val contentFade = remember(contentAlpha) { Modifier.graphicsLayer { alpha = contentAlpha.value } }
    suspend fun fadeContentIn() {
        if (!fadeContent) return
        contentAlpha.snapTo(0f)
        contentAlpha.animateTo(1f, com.fetch.tv.ui.theme.FetchMotion.enterTween())
    }
    var shownTab by remember { mutableStateOf(tab) }
    LaunchedEffect(tab) {
        if (tab != shownTab) {
            shownTab = tab
            runCatching { gridState.scrollToItem(0) }
            fadeContentIn()
        }
    }
    // The activity tabs show their skeleton until the first read, then fade the cards in.
    val activityLoading = (tab == LibraryTab.IN_PROGRESS && inProgressItems == null) ||
        (tab == LibraryTab.HISTORY && historyMonths == null)
    var sawActivitySkeleton by remember { mutableStateOf(false) }
    LaunchedEffect(activityLoading) {
        if (activityLoading) sawActivitySkeleton = true else if (sawActivitySkeleton) {
            sawActivitySkeleton = false
            fadeContentIn()
        }
    }
    var sawLoading by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.isLoading) {
        if (uiState.isLoading) sawLoading = true else if (sawLoading) {
            sawLoading = false
            fadeContentIn()
        }
    }

    // A sync no longer takes the screen (#846): the header stays, so focus stays where it is and
    // isn't sent back to the tab when the sync starts or ends.
    val savedGridLoading = uiState.isLoading && tab.listScope != null

    LaunchedEffect(uiState.isLoading, uiState.sourceMode, uiState.listTabs.size) {
        if (uiState.isLoading && pendingPrimaryFocus) {
            // Opening the Library during its first sync: the tabs are up, so start on the
            // selected one; a title to come back to is restored once the grid has it.
            primaryFocusRequester.requestFocusAfterFrames(frames = 0)
            if (lastFocusedPosterKey == null) pendingPrimaryFocus = false
        }
        if (!uiState.isLoading && pendingPrimaryFocus && tab == LibraryTab.SERVERS && serverFocusTarget != null) {
            pendingPrimaryFocus = false
        }
        if (!uiState.isLoading && pendingPrimaryFocus) {
            val restoreKey = lastFocusedPosterKey
            // Only the Saved grid restores here; the activity tabs restore through focusRestorer
            // once their items have loaded.
            val restoreIndex = restoreKey?.takeIf { tab.listScope != null }?.let { visibleItemIndexByKey[it] }
            val restoreRequester = restoreKey?.let { posterFocusRequesters[it] }

            // requestFocus() reports false while the target is not laid out yet, so each try
            // waits for the next frame rather than a fixed delay.
            val focused = restoreIndex != null && restoreRequester != null &&
                gridState.restoreBrowseFocus(restoreIndex, restoreRequester)

            if (!focused) {
                primaryFocusRequester.requestFocusAfterFrames(frames = 0)
            }
            pendingPrimaryFocus = false
        }
    }

    // The browse Back ladder (#850): scrolled, back to the top and the first card; from the top
    // rows, to the tab row; then the Library leaves.
    val backScope = rememberCoroutineScope()
    var gridHasFocus by remember { mutableStateOf(false) }
    com.fetch.tv.ui.components.BrowseBackHandler(
        gridState = gridState,
        gridHasFocus = gridHasFocus,
        enabled = expandedPicker == null,
        onFocusFirstCard = {
            val firstKey = if (tab.listScope != null) firstVisiblePosterKey else activityKeys.firstOrNull()
            val requester = firstKey?.let { posterFocusRequesters[it] ?: activityFocusRequesters[it] }
            requester?.requestFocusAfterFrames(frames = 1) == true
        },
        onFocusHeader = { primaryFocusRequester.requestFocusAfterFrames(frames = 0) }
    )

    // Declared after the scroll-to-top handler, so while choosing titles Back ends selection first.
    androidx.activity.compose.BackHandler(enabled = selection.active) {
        dispatchSelection(LibrarySelectionAction.Exit)
        pendingBulkRemoveConfirm = false
        showBulkListPicker = false
        val anchor = lastFocusedPosterKey?.takeIf { it in selectionKeys }
        backScope.launch {
            val requester = anchor?.let { posterFocusRequesters[it] ?: activityFocusRequesters[it] }
            if (requester?.requestFocusAfterFrames(frames = 1) != true) primaryFocusRequester.requestFocusAfterFrames(frames = 0)
        }
    }

    LaunchedEffect(uiState.sortSelectionVersion, firstVisiblePosterKey) {
        if (uiState.sortSelectionVersion <= 0L) return@LaunchedEffect
        val targetKey = firstVisiblePosterKey ?: return@LaunchedEffect
        runCatching { gridState.scrollToItem(0) }
        // A new sort starts on its first title, by the browse screens' one restore step (#850).
        posterFocusRequesters[targetKey]?.let { requester ->
            gridState.restoreBrowseFocus(0, requester)
        }
    }

    val collapsed = com.fetch.tv.ui.components.rememberBrowseHeaderCollapsed(gridState)
    // Source names are stored in natural case; the activity tabs are named by their tab already,
    // so they show the count alone (#854).
    val sourceName = when {
        tab == LibraryTab.SERVERS -> stringResource(R.string.library_source_servers)
        uiState.sourceMode == LibrarySourceMode.TRAKT -> stringResource(R.string.trakt_name)
        uiState.sourceMode == LibrarySourceMode.SIMKL -> stringResource(R.string.simkl_name)
        uiState.sourceMode == LibrarySourceMode.MDBLIST -> stringResource(R.string.mdblist_name)
        uiState.sourceMode == LibrarySourceMode.FETCH_WEBSITE -> stringResource(R.string.website_name)
        else -> stringResource(R.string.library_source_local)
    }
    val shownCount = when {
        savedGridLoading -> null
        tab.listScope != null -> uiState.visibleItems.size
        tab == LibraryTab.IN_PROGRESS -> inProgressItems?.size
        tab == LibraryTab.HISTORY -> historyMonths?.sumOf { it.items.size }
        else -> null
    }
    val countText = shownCount?.let { pluralStringResource(R.plurals.library_title_count, it, it) }
    val summary = when {
        countText == null -> if (tab == LibraryTab.IN_PROGRESS || tab == LibraryTab.HISTORY) null else sourceName
        tab == LibraryTab.IN_PROGRESS || tab == LibraryTab.HISTORY -> countText
        else -> "$countText · $sourceName"
    }

    // The hero band (#852): the focused title on this tab, else its first, filled in from the
    // cached meta once the focus prefetch lands. Off in Layout, on low-RAM devices and on Servers.
    val heroSetting by viewModel.heroEnabled.collectAsStateWithLifecycle()
    val metaCacheVersion by viewModel.metaCacheVersion.collectAsStateWithLifecycle()
    val heroAllowed = heroSetting && !FetchTheme.lowRamDevice && tab != LibraryTab.SERVERS && !savedGridLoading
    val movieLabel = com.fetch.tv.ui.util.localizedContentType("movie")
    val seriesLabel = com.fetch.tv.ui.util.localizedContentType("series")
    val heroItem = remember(heroAllowed, tab, lastFocusedPosterKey, uiState.visibleItems, inProgressItems, historyMonths) {
        if (!heroAllowed) return@remember null
        val key = lastFocusedPosterKey
        when {
            tab.listScope != null -> uiState.visibleItems
                .let { items -> items.firstOrNull { com.fetch.tv.ui.components.browseItemKey(it.type, it.id) == key } ?: items.firstOrNull() }
                ?.toMetaPreview()
            tab == LibraryTab.IN_PROGRESS -> inProgressItems.orEmpty()
                .let { items -> items.firstOrNull { inProgressFocusKey(it) == key } ?: items.firstOrNull() }
                ?.toMetaPreview(detail = null)
            tab == LibraryTab.HISTORY -> historyMonths.orEmpty().flatMap { it.items }
                .let { items -> items.firstOrNull { historyFocusKey(it) == key } ?: items.firstOrNull() }
                ?.toMetaPreview(detail = null)
            else -> null
        }
    }
    val heroCandidate = remember(heroItem, metaCacheVersion, movieLabel, seriesLabel) {
        heroItem?.let { item ->
            val isSeries = com.fetch.tv.ui.util.isSeriesContentType(item.apiType)
            com.fetch.tv.ui.components.hero.browseHeroPreview(
                item = item,
                cached = viewModel.cachedMeta(item.id, item.apiType),
                typeLabel = if (isSeries) seriesLabel else movieLabel
            )
        }
    }
    val heroTarget = com.fetch.tv.ui.components.hero.rememberBrowseHeroTarget(heroCandidate)
    val showHero = heroAllowed && heroItem != null && heroTarget != null

    com.fetch.tv.ui.components.BrowseScaffold(
        title = stringResource(R.string.library_title),
        collapsed = collapsed,
        layoutKey = tab,
        heroBackdrop = heroTarget?.backdrop,
        heroPoster = heroTarget?.poster,
        hero = if (showHero) {
            @Composable { com.fetch.tv.ui.components.hero.BrowseHeroTitle(heroTarget) }
        } else {
            null
        },
        tabsDescription = stringResource(R.string.library_tabs_description),
        tabs = {
            tabs.forEach { option ->
                com.fetch.tv.ui.components.BrowseTab(
                    label = libraryTabLabel(option),
                    selected = option == tab,
                    selectedFocusRequester = primaryFocusRequester,
                    onClick = {
                        expandedPicker = null
                        selectedTab = option
                    }
                )
            }
        },
        headerActions = {
            // Select, Manage lists and Sync sit at the end of the tab row on every tab, so the
            // filter row holds only filters (#862). History and In progress have no filter row:
            // Down from a tab still lands on the grid in one press.
            if (tab.listScope != null) {
                LibrarySelectButton(
                    active = selection.active,
                    enabled = uiState.visibleItems.isNotEmpty(),
                    onClick = {
                        expandedPicker = null
                        toggleSelectionMode()
                    }
                )
                LibraryActionsRow(
                    pending = uiState.pendingOperation,
                    isSyncing = uiState.isSyncing,
                    // Lists are managed from the Lists tab, not over the watchlist.
                    showManageLists = tab == LibraryTab.LISTS && uiState.listManagement != null && uiState.isTrackingAuthenticated,
                    showRefresh = uiState.sourceMode != LibrarySourceMode.LOCAL && uiState.isTrackingAuthenticated,
                    onManageLists = {
                        expandedPicker = null
                        viewModel.onOpenManageLists()
                    },
                    onRefresh = viewModel::onRefresh
                )
            } else if ((tab == LibraryTab.HISTORY || tab == LibraryTab.IN_PROGRESS) && activityKeys.isNotEmpty()) {
                LibrarySelectButton(
                    active = selection.active,
                    enabled = true,
                    onClick = { toggleSelectionMode() }
                )
            }
            LibraryPageButtons(
                onUpcoming = {
                    expandedPicker = null
                    onNavigateToCalendar()
                },
                onStats = {
                    expandedPicker = null
                    onNavigateToStats()
                }
            )
        },
        filters = if (tab.listScope != null) {
            @Composable {
                LibrarySelectorsRow(
                    sourceMode = uiState.sourceMode,
                    listTabs = uiState.scopedListTabs,
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
        } else {
            null
        },
        summary = summary
    ) { gridViewport ->
    val posterCardStyle = com.fetch.tv.ui.components.rememberBrowseCardStyle(
        preferredWidthDp = uiState.posterCardWidthDp,
        cornerRadiusDp = uiState.posterCardCornerRadiusDp,
        gridViewport = gridViewport
    )

    if (savedGridLoading) {
        val syncingText = stringResource(R.string.library_syncing_library)

        // Only the Watchlist and Lists grids wait for the provider's first sync; the header, the
        // other tabs and the filters stay usable, so nothing here needs to hold focus (#846, #850).
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.padding(start = FetchTheme.spacing.screen.horizontal, top = com.fetch.tv.ui.components.BrowseDefaults.gridTopPadding),
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
        return@BrowseScaffold
    }

    val globalLandscape = com.fetch.tv.ui.components.LocalLandscapePosterMode.current

    LazyVerticalGrid(
        columns = GridCells.Adaptive(
            minSize = if (globalLandscape) posterCardStyle.height else posterCardStyle.width
        ),
        state = gridState,
        modifier = Modifier
            .fillMaxSize()
            .onFocusChanged { gridHasFocus = it.hasFocus }
            .focusRestorer {
                val lastKey = lastFocusedPosterKey
                when {
                    lastKey != null && lastKey in activityFocusRequesters -> activityFocusRequesters[lastKey]
                    lastKey != null && lastKey in posterFocusRequesters -> posterFocusRequesters[lastKey]
                    // Nothing to come back to: the grid's first card. The header sits outside the
                    // grid now (#850), so falling back to the tab row would bounce focus out again.
                    else -> null
                } ?: FocusRequester.Default
            }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                // Observed, not consumed: clears an error banner that has been up long enough to read.
                if (native.action == AndroidKeyEvent.ACTION_DOWN && uiState.transientMessageDismissible) {
                    viewModel.onDismissReadError()
                }
                // While choosing titles, Menu jumps to the actions bar from anywhere in the grid.
                if (selection.active && native.keyCode == AndroidKeyEvent.KEYCODE_MENU) {
                    if (native.action == AndroidKeyEvent.ACTION_DOWN) {
                        runCatching { selectionBarFocusRequester.requestFocus() }
                    }
                    return@onPreviewKeyEvent true
                }
                false
            }
            // Held Up / Down: the browse screens' one fast scroll, landing in the same column (#850).
            .browseGridFastScroll(
                gridState = gridState,
                focusedKey = { lastFocusedPosterKey },
                requesterFor = { key -> (key as? String)?.let { posterFocusRequesters[it] ?: activityFocusRequesters[it] } }
            ),
        // The last row scrolls clear of the selection bar.
        contentPadding = com.fetch.tv.ui.components.BrowseDefaults.gridPadding(
            extraBottom = if (selection.active) with(density) { selectionBarHeightPx.toDp() } else Dp.Hairline
        ),
        horizontalArrangement = Arrangement.spacedBy(com.fetch.tv.ui.components.BrowseDefaults.columnGap),
        verticalArrangement = Arrangement.spacedBy(com.fetch.tv.ui.components.BrowseDefaults.rowGap)
    ) {
        if (tab.listScope != null) {

            if (uiState.visibleItems.isEmpty() && uiState.refreshFailed) {
                // A failed load isn't an empty library: say so and offer Retry, as Discover does (#850).
                item(span = { GridItemSpan(maxLineSpan) }) {
                    com.fetch.tv.ui.components.ErrorState(
                        message = stringResource(R.string.library_error_refresh_failed),
                        onRetry = viewModel::onRefresh,
                        modifier = Modifier.height(LibraryEmptyStateHeight)
                    )
                }
            } else if (uiState.visibleItems.isEmpty() && uiState.filtersHideAllItems) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyScreenState(
                        title = stringResource(R.string.library_empty_filtered_title),
                        subtitle = stringResource(R.string.library_empty_filtered_subtitle),
                        icon = Icons.Default.BookmarkBorder,
                        height = LibraryEmptyStateHeight,
                        modifier = Modifier.padding(bottom = FetchTheme.spacing.xxl),
                        action = StateAction(stringResource(R.string.library_action_clear_filters)) {
                            expandedPicker = null
                            viewModel.onClearFilters()
                        }
                    )
                }
            } else if (uiState.visibleItems.isEmpty() && tab == LibraryTab.LISTS && uiState.scopedListTabs.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    val canManage = uiState.listManagement != null && uiState.isTrackingAuthenticated
                    EmptyScreenState(
                        title = stringResource(R.string.library_lists_empty_title),
                        subtitle = stringResource(R.string.library_lists_empty_subtitle),
                        icon = Icons.Default.BookmarkBorder,
                        height = LibraryEmptyStateHeight,
                        modifier = Modifier.padding(bottom = FetchTheme.spacing.xxl),
                        action = if (canManage) {
                            StateAction(stringResource(R.string.library_manage_lists), viewModel::onOpenManageLists)
                        } else {
                            StateAction(stringResource(R.string.library_action_search_titles), onNavigateToSearch)
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
                        height = LibraryEmptyStateHeight,
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

            items(uiState.visibleItems, key = { com.fetch.tv.ui.components.browseItemKey(it.type, it.id) }) { item ->
                val focusKey = com.fetch.tv.ui.components.browseItemKey(item.type, item.id)
                val isSeries = com.fetch.tv.ui.util.isSeriesContentType(item.type)
                val previewForLongPress = remember(item) {
                    item.toMetaPreview().copy(posterShape = PosterShape.POSTER)
                }
                val selected = if (selection.active) selection.isSelected(focusKey) else null
                val badge = badgeLabel(newEpisodeBadges.badgeFor(item.id, item.imdbId).takeIf { isSeries })
                GridContentCard(
                    item = previewForLongPress,
                    modifier = browseGridItemMotion().then(contentFade),
                    progress = progressByContentId[item.id] ?: item.imdbId?.let { progressByContentId[it] },
                    readyLabel = readyChip(libraryEntryReadyLabels(item, uiState.readyBestMatchLabels)),
                    onPlay = if (selection.active || !quickPlayAllowed) null else ({
                        lastFocusedPosterKey = focusKey
                        HeroBackdropState.update(viewModel.getCachedBackdrop(item.id, item.type))
                        onPlayTitle(item.id, item.type, item.addonBaseUrl)
                    }),
                    posterCardStyle = posterCardStyle,
                    isWatched = item.isWatchedIn(watchedMovieIds, watchedSeriesIds),
                    focusRequester = posterFocusRequesters[focusKey],
                    showLabel = true,
                    selected = selected,
                    newEpisodeBadge = badge?.first,
                    newEpisodeBadgeIsSeason = badge?.second == true,
                    onFocused = {
                        lastFocusedPosterKey = focusKey
                        viewModel.prefetchMetaOnFocus(item.id, item.type)
                    },
                    onClick = {
                        lastFocusedPosterKey = focusKey
                        if (selection.active) {
                            dispatchSelection(LibrarySelectionAction.Toggle(focusKey))
                            return@GridContentCard
                        }
                        val backdrop = viewModel.getCachedBackdrop(item.id, item.type)
                        HeroBackdropState.update(backdrop)
                        onNavigateToDetail(item.id, item.type, item.addonBaseUrl)
                    },
                    onLongPress = if (selection.active) null else ({
                        lastFocusedPosterKey = focusKey
                        viewModel.posterOptions.show(previewForLongPress, item.addonBaseUrl)
                    })
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
                    if (selection.active) {
                        dispatchSelection(LibrarySelectionAction.Toggle(key))
                        return@LibraryActivityCardActions
                    }
                    // In progress resumes at once, as Continue Watching does; the options menu
                    // keeps Go to details (#856).
                    if (tab == LibraryTab.IN_PROGRESS && resumeInProgress(item)) return@LibraryActivityCardActions
                    HeroBackdropState.update(viewModel.getCachedBackdrop(item.id, item.type))
                    onNavigateToDetail(item.id, item.type, item.addonBaseUrl)
                },
                onLongPress = { key, preview, item ->
                    lastFocusedPosterKey = key
                    viewModel.posterOptions.show(preview, item.addonBaseUrl)
                },
                isWatched = { item ->
                    com.fetch.tv.ui.util.isTitleWatched(item.id, item.type, watchedMovieIds, watchedSeriesIds)
                },
                selectedFor = { key -> if (selection.active) selection.isSelected(key) else null },
                newEpisodeBadgeFor = { item -> badgeLabel(newEpisodeBadges.badgeFor(item.id).takeIf { item.isSeries }) },
                progressFor = { item ->
                    // History cards show a bar only for a title started again since.
                    if (tab == LibraryTab.IN_PROGRESS) item.progressPercent?.let { it / 100f } else progressByContentId[item.id]
                },
                readyLabelFor = { item -> readyChip(libraryActivityReadyLabels(item, uiState.readyBestMatchLabels)) },
                onPlay = if (!quickPlayAllowed) null else ({ key, item ->
                    lastFocusedPosterKey = key
                    if (!(tab == LibraryTab.IN_PROGRESS && resumeInProgress(item))) {
                        HeroBackdropState.update(viewModel.getCachedBackdrop(item.id, item.type))
                        onPlayTitle(item.id, item.type, item.addonBaseUrl)
                    }
                }),
                cardModifier = contentFade
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
                    com.fetch.tv.ui.util.isTitleWatched(item.id, item.apiType, watchedMovieIds, watchedSeriesIds)
                },
                onItemClick = { itemId, itemType, addonBaseUrl ->
                    HeroBackdropState.update(viewModel.getCachedBackdrop(itemId, itemType))
                    onNavigateToDetail(itemId, itemType, addonBaseUrl)
                },
                onSeeAll = { row -> onNavigateToCatalogSeeAll(row.catalogId, row.addonId, row.apiType) },
                onItemLongPress = { item, addonBaseUrl -> viewModel.posterOptions.show(item, addonBaseUrl) }
            )
        }

        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(modifier = Modifier.height(FetchTheme.spacing.sm)) }
    }
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

    if (selection.active) {
        LibrarySelectionOverlay(
            tab = tab,
            selection = selection,
            uiState = uiState,
            selectionKeys = selectionKeys,
            historyMonths = historyMonths,
            inProgressItems = inProgressItems,
            anchorKey = lastFocusedPosterKey,
            barFocusRequester = selectionBarFocusRequester,
            requesterFor = { key -> posterFocusRequesters[key] ?: activityFocusRequesters[key] },
            fallbackFocusRequester = primaryFocusRequester,
            showRemoveConfirm = pendingBulkRemoveConfirm,
            onShowRemoveConfirm = { pendingBulkRemoveConfirm = it },
            showListPicker = showBulkListPicker,
            onShowListPicker = { showBulkListPicker = it },
            onBarHeightChanged = { selectionBarHeightPx = it },
            onExit = { dispatchSelection(LibrarySelectionAction.Exit) },
            onSelectAll = { dispatchSelection(LibrarySelectionAction.SelectAll(selectionKeys)) },
            onClear = { dispatchSelection(LibrarySelectionAction.Clear) },
            exitFocusScope = selectionScope,
            onBulkAction = viewModel::onBulkAction
        )
    }

    val transientMessage = uiState.transientMessage
    if (!transientMessage.isNullOrBlank()) {
        // At the bottom, clear of the tab row and filters it used to cover, and above the
        // selection bar while that is open (#849).
        val selectionBarHeight = if (selection.active) with(density) { selectionBarHeightPx.toDp() } else Dp.Hairline
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = selectionBarHeight),
            contentAlignment = androidx.compose.ui.Alignment.BottomCenter
        ) {
            Text(
                text = transientMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = if (uiState.transientMessageIsError) FetchStatusTones.errorText else FetchTheme.colors.TextPrimary,
                modifier = Modifier
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .padding(bottom = FetchTheme.spacing.xl)
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
            // Go to details from the options menu hands over the backdrop too (#847).
            HeroBackdropState.update(viewModel.getCachedBackdrop(id, type))
            onNavigateToDetail(id, type, addonBaseUrl.takeIf { it.isNotBlank() })
        },
        extraActions = { target ->
            // "Play" beside Go to details, the menu's way to the Play/Pause shortcut (#856).
            if (quickPlayAllowed) {
                val addonBaseUrl = posterOptionsState.addonBaseUrl
                LibraryMenuButton(
                    text = stringResource(R.string.hero_play),
                    onClick = {
                        viewModel.posterOptions.dismiss()
                        val progress = inProgressItems?.firstOrNull { it.id == target.id }
                        if (tab == LibraryTab.IN_PROGRESS && progress != null && resumeInProgress(progress)) return@LibraryMenuButton
                        HeroBackdropState.update(viewModel.getCachedBackdrop(target.id, target.apiType))
                        onPlayTitle(target.id, target.apiType, addonBaseUrl.takeIf { it.isNotBlank() })
                    }
                )
            }
        }
    )
}

/** A Library section's name in the header's pill tabs (#850). */
@Composable
private fun libraryTabLabel(tab: LibraryTab): String = when (tab) {
    LibraryTab.WATCHLIST -> stringResource(R.string.library_tab_watchlist)
    LibraryTab.LISTS -> stringResource(R.string.library_tab_lists)
    LibraryTab.IN_PROGRESS -> stringResource(R.string.library_tab_in_progress)
    LibraryTab.HISTORY -> stringResource(R.string.library_tab_history)
    LibraryTab.SERVERS -> stringResource(R.string.library_source_servers)
    LibraryTab.UPCOMING -> stringResource(R.string.library_tab_upcoming)
    LibraryTab.STATS -> stringResource(R.string.library_open_stats)
}

/**
 * Upcoming and Stats at the end of the header (#855): icon buttons apart from the tabs, since
 * they leave the Library. Each shows its name while focused, and always names itself to TalkBack.
 */
@Composable
private fun LibraryPageButtons(
    onUpcoming: () -> Unit,
    onStats: () -> Unit
) {
    val groupDescription = stringResource(R.string.library_pages_description)
    Row(
        modifier = Modifier.semantics { contentDescription = groupDescription },
        horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        LibraryPageButton(Icons.Default.CalendarMonth, stringResource(R.string.library_tab_upcoming), onUpcoming)
        LibraryPageButton(Icons.Default.BarChart, stringResource(R.string.library_open_stats), onStats)
    }
}

@Composable
private fun LibraryPageButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    LibraryCompactButton(icon = icon, label = label, onClick = onClick)
}

/**
 * A header action as an icon that shows its name while focused, so the header's tabs fit beside
 * the actions instead of scrolling under them. [showLabel] keeps the name in view when it carries
 * state ("Syncing…", "Done"). Always names itself to TalkBack.
 */
@Composable
internal fun LibraryCompactButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    showLabel: Boolean = false
) {
    var focused by remember { mutableStateOf(false) }
    FetchButton(
        onClick = onClick,
        enabled = enabled,
        selected = selected,
        // The icon already says what the button does; a check in front of it would be noise.
        showSelectedCheck = false,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = label }
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
        androidx.compose.animation.AnimatedVisibility(visible = focused || showLabel) {
            Row {
                Spacer(modifier = Modifier.width(FetchTheme.spacing.sm))
                Text(label, maxLines = 1)
            }
        }
    }
}

/** An extra entry in the Library's options menu, full width like the menu's own (#856). */
@Composable
private fun LibraryMenuButton(text: String, onClick: () -> Unit) {
    FetchButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text)
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

    val genreYearSet = listOf(selectedGenre, selectedYear).count { it != null }
    val hasMoreFilters = genres.isNotEmpty() || years.isNotEmpty()
    // Genre and Year sit behind "More filters" (#862), revealed next to it. They open revealed
    // while either is set, so a set filter is never out of sight.
    var moreFiltersOpen by rememberSaveable { mutableStateOf(genreYearSet > 0) }

    // One row (audit C3): List, Type, Sort and Show always in view, then More filters with Genre
    // and Year after it once opened. It scrolls sideways with focus when it's wider than the screen.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusSafeHorizontalScroll(fadeEdges = true),
        horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        if (listTabs.isNotEmpty()) {
            LibraryDropdownPicker(
                modifier = Modifier.focusRequester(primaryFocusRequester),
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
            modifier = if (listTabs.isNotEmpty()) Modifier else Modifier.focusRequester(primaryFocusRequester),
            upFocusRequester = upFocusRequester,
            title = stringResource(R.string.library_filter_type),
            value = selectedTypeLabel,
            selectedValue = selectedTypeTab?.key,
            expanded = expandedPicker == "type",
            active = selectedTypeTab != null && selectedTypeTab.key != LibraryTypeTab.ALL_KEY,
            options = typeTabs.map { LibraryOption(localizedTypeLabel(it.key), it.key, it.count) },
            onExpandedChange = { onExpandedChange("type", it) },
            onSelect = { option ->
                typeTabs.firstOrNull { it.key == option.value }?.let(onSelectType)
            }
        )

        if (sortOptions.isNotEmpty()) {
            LibraryDropdownPicker(
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

        val watchedFilterLabel = stringResource(selectedWatchedFilter.labelResId)
        LibraryDropdownPicker(
            upFocusRequester = upFocusRequester,
            title = stringResource(R.string.library_filter_watched),
            value = watchedFilterLabel,
            selectedValue = selectedWatchedFilter.key,
            active = selectedWatchedFilter != LibraryWatchedFilter.ALL,
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

        if (hasMoreFilters) {
            com.fetch.tv.ui.components.FetchChip(
                label = libraryMoreFiltersLabel(
                    base = stringResource(R.string.library_more_filters),
                    setCount = genreYearSet
                ),
                // Lit while Genre or Year is set, so a filter behind the chip still shows (#862).
                selected = genreYearSet > 0,
                modifier = Modifier.focusProperties { up = upFocusRequester },
                onClick = {
                    if (expandedPicker == "genre" || expandedPicker == "year") {
                        onExpandedChange(expandedPicker, false)
                    }
                    moreFiltersOpen = !moreFiltersOpen
                }
            )
        }

        if (hasMoreFilters && moreFiltersOpen) {
            if (genres.isNotEmpty()) {
                val genreAllOption = LibraryOption(allLabel, LibraryTypeTab.ALL_KEY)
                LibraryDropdownPicker(
                    upFocusRequester = upFocusRequester,
                    title = stringResource(R.string.library_filter_genre),
                    value = selectedGenreLabel,
                    selectedValue = selectedGenre ?: LibraryTypeTab.ALL_KEY,
                    expanded = expandedPicker == "genre",
                    active = selectedGenre != null,
                    options = listOf(genreAllOption) + genres.map {
                        LibraryOption(localizedGenreLabel(it.label), it.key, it.count)
                    },
                    onExpandedChange = { onExpandedChange("genre", it) },
                    onSelect = { option ->
                        onSelectGenre(if (option.value == LibraryTypeTab.ALL_KEY) null else option.value)
                    }
                )
            }

            if (years.isNotEmpty()) {
                val yearAllOption = LibraryOption(allLabel, LibraryTypeTab.ALL_KEY)
                LibraryDropdownPicker(
                    upFocusRequester = upFocusRequester,
                    title = stringResource(R.string.library_filter_year),
                    value = selectedYearLabel,
                    selectedValue = selectedYear ?: LibraryTypeTab.ALL_KEY,
                    expanded = expandedPicker == "year",
                    active = selectedYear != null,
                    options = listOf(yearAllOption) + years.map {
                        LibraryOption(it.label, it.key, it.count)
                    },
                    onExpandedChange = { onExpandedChange("year", it) },
                    onSelect = { option ->
                        onSelectYear(if (option.value == LibraryTypeTab.ALL_KEY) null else option.value)
                    }
                )
            }
        }
    }
}

/** "More filters", or "More filters · 2" while Genre and Year are both set (#862). */
internal fun libraryMoreFiltersLabel(base: String, setCount: Int): String =
    if (setCount > 0) "$base · $setCount" else base

/**
 * A Library filter: the shared [FetchFilterChip] (#851), whose choices open in a panel under it,
 * as on Discover and See all. Closing it puts focus back on the filter.
 */
@Composable
private fun LibraryDropdownPicker(
    modifier: Modifier = Modifier,
    upFocusRequester: FocusRequester? = null,
    title: String,
    value: String,
    selectedValue: String?,
    active: Boolean = false,
    expanded: Boolean,
    options: List<LibraryOption>,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (LibraryOption) -> Unit
) {
    FetchFilterChip(
        title = title,
        value = value,
        active = active,
        expanded = expanded,
        options = options.map { option ->
            FetchFilterOption(
                key = option.value,
                label = option.label,
                selected = option.value == selectedValue,
                count = option.count
            )
        },
        onExpandedChange = onExpandedChange,
        onSelect = { chosen -> options.firstOrNull { it.value == chosen.key }?.let(onSelect) },
        modifier = modifier,
        upFocusRequester = upFocusRequester
    )
}

private data class LibraryOption(
    val label: String,
    val value: String,
    /** How many titles the choice keeps, shown after it in the panel. */
    val count: Int? = null
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
    Row(horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)) {
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
            LibraryCompactButton(
                icon = Icons.Default.Sync,
                label = if (isSyncing) stringResource(R.string.library_syncing_btn) else stringResource(R.string.library_sync_btn),
                onClick = { if (!pending && !isSyncing) onRefresh() },
                showLabel = isSyncing
            )
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
