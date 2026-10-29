package com.fetch.tv.ui.screens.library

import com.fetch.tv.ui.theme.FetchRadii
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.focusable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.fetch.tv.domain.model.LibraryListTab
import com.fetch.tv.domain.model.LibrarySourceMode
import com.fetch.tv.domain.model.PosterShape
import com.fetch.tv.ui.components.EmptyScreenState
import com.fetch.tv.ui.components.GridContentCard
import com.fetch.tv.ui.screens.detail.requestFocusAfterFrames
import com.fetch.tv.ui.screens.home.HeroBackdropState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.fetch.tv.ui.components.PosterCardDefaults
import com.fetch.tv.domain.model.localizedTitle
import com.fetch.tv.ui.components.PosterGridSkeleton
import com.fetch.tv.ui.components.StateAction
import com.fetch.tv.ui.components.FetchDialog
import com.fetch.tv.ui.theme.FetchTheme
import com.fetch.tv.ui.util.localizedContentType
import com.fetch.tv.ui.util.localizedGenreLabel
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalConfiguration
import com.fetch.tv.R
import com.fetch.tv.ui.theme.FetchStatusTones

private const val KEY_REPEAT_THROTTLE_MS = 80L

private enum class LibraryViewMode {
    Saved,
    Servers
}

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
    onNavigateToSettings: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val watchedMovieIds by viewModel.watchedMovieIds.collectAsState()
    val watchedSeriesIds by viewModel.watchedSeriesIds.collectAsState()
    var showDeleteConfirm by remember(uiState.showManageDialog) { mutableStateOf(false) }
    var expandedPicker by remember { mutableStateOf<String?>(null) }
    var selectedViewMode by rememberSaveable { mutableStateOf(LibraryViewMode.Saved) }
    val servers by serversViewModel.servers.collectAsState()
    val serverShelves by serversViewModel.shelves.collectAsState()
    val hasServers = servers.enabledConnections.isNotEmpty()
    val viewModes = LibraryViewMode.entries.filter { it != LibraryViewMode.Servers || hasServers }
    val viewMode = selectedViewMode.takeIf { it in viewModes } ?: LibraryViewMode.Saved
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
        cornerRadius = uiState.posterCardCornerRadiusDp.dp,
        showRestingBorder = true
    )

    LaunchedEffect(viewMode, servers.revision) {
        if (viewMode == LibraryViewMode.Servers) serversViewModel.load()
    }

    LaunchedEffect(uiState.isLoading) {
        if (uiState.isLoading) {
            pendingPrimaryFocus = true
        }
    }

    LaunchedEffect(uiState.isLoading, uiState.sourceMode, uiState.listTabs.size) {
        if (!uiState.isLoading && pendingPrimaryFocus && viewMode == LibraryViewMode.Servers && serverFocusTarget != null) {
            pendingPrimaryFocus = false
        }
        if (!uiState.isLoading && pendingPrimaryFocus) {
            val restoreKey = lastFocusedPosterKey
            val restoreIndex = restoreKey?.let { visibleItemIndexByKey[it] }
            val restoreRequester = restoreKey?.let { posterFocusRequesters[it] }

            var focused = false
            if (restoreIndex != null && restoreRequester != null) {
                if (gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0) {
                    runCatching { gridState.scrollToItem(restoreIndex) }
                }
                focused = runCatching { restoreRequester.requestFocus() }.isSuccess
                if (!focused) {
                    delay(16)
                    focused = runCatching { restoreRequester.requestFocus() }.isSuccess
                }
            }

            if (!focused) {
                focused = runCatching { primaryFocusRequester.requestFocus() }.isSuccess
            }
            if (!focused) {
                delay(16)
                runCatching { primaryFocusRequester.requestFocus() }
            }
            pendingPrimaryFocus = false
        }
    }

    LaunchedEffect(uiState.sortSelectionVersion, firstVisiblePosterKey) {
        if (uiState.sortSelectionVersion <= 0L) return@LaunchedEffect
        val targetKey = firstVisiblePosterKey ?: return@LaunchedEffect
        runCatching { gridState.scrollToItem(0) }
        var focused = false
        repeat(6) {
            focused = posterFocusRequesters[targetKey]
                ?.let { requester -> runCatching { requester.requestFocus() }.isSuccess }
                ?: false
            if (focused) return@LaunchedEffect
            delay(24)
        }
    }

    if (uiState.isLoading) {
        val loadingFocusRequester = remember { FocusRequester() }
        LaunchedEffect(uiState.isLoading) {
            loadingFocusRequester.requestFocus()
        }

        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(loadingFocusRequester)
                    .focusable()
            )
            Column(
                modifier = Modifier.padding(start = FetchTheme.spacing.xxxl, top = FetchTheme.spacing.xl),
                verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.lg)
            ) {
                Text(
                    text = stringResource(R.string.library_syncing_library),
                    style = MaterialTheme.typography.bodyMedium,
                    color = FetchTheme.colors.TextSecondary
                )
                PosterGridSkeleton(cardWidth = posterCardStyle.width, cardHeight = posterCardStyle.height)
            }
        }
        return
    }

    val lastKeyRepeatTime = remember { longArrayOf(0L) }

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
                (if (lastKey != null && lastKey in posterFocusRequesters) {
                    posterFocusRequesters[lastKey]
                } else {
                    posterFocusRequesters[firstVisibleCardKey]
                }) ?: FocusRequester()
            }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount > 0) {
                    val now = System.currentTimeMillis()
                    if (now - lastKeyRepeatTime[0] < KEY_REPEAT_THROTTLE_MS) {
                        return@onPreviewKeyEvent true
                    }
                    lastKeyRepeatTime[0] = now
                }
                false
            },
        contentPadding = PaddingValues(start = FetchTheme.spacing.xxxl, end = FetchTheme.spacing.xxxl, top = FetchTheme.spacing.xl, bottom = FetchTheme.spacing.xxl),
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
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (showBuiltInHeader) FetchTheme.colors.TextPrimary else Color.Transparent,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = when {
                        viewMode == LibraryViewMode.Servers -> stringResource(R.string.library_source_servers).uppercase(LocalConfiguration.current.locales[0])
                        uiState.sourceMode == LibrarySourceMode.TRAKT -> "TRAKT"
                        uiState.sourceMode == LibrarySourceMode.SIMKL -> "SIMKL"
                        uiState.sourceMode == LibrarySourceMode.MDBLIST -> "MDBLIST"
                        uiState.sourceMode == LibrarySourceMode.FETCH_WEBSITE -> stringResource(R.string.website_name).uppercase(LocalConfiguration.current.locales[0])
                        uiState.isFetchAccount -> "FETCH"
                        else -> stringResource(R.string.library_source_local)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (showBuiltInHeader) FetchTheme.colors.TextTertiary else Color.Transparent,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 2.sp
                )
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            LibraryViewModeRow(
                modes = viewModes,
                selectedMode = viewMode,
                primaryFocusRequester = primaryFocusRequester,
                onSelected = { mode ->
                    selectedViewMode = mode
                    expandedPicker = null
                }
            )
        }

        if (viewMode == LibraryViewMode.Saved) {
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

            if (uiState.visibleItems.isEmpty()) {
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
                            StateAction(stringResource(R.string.nav_settings), onNavigateToSettings)
                        } else {
                            StateAction(stringResource(R.string.nav_search), onNavigateToSearch)
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
        } else {
            libraryServerContent(
                shelves = serverShelves,
                onNavigateToSettings = onNavigateToSettings,
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
                color = FetchTheme.colors.TextPrimary,
                modifier = Modifier
                    .padding(top = FetchTheme.spacing.xl)
                    .background(FetchTheme.colors.BackgroundElevated, RoundedCornerShape(FetchRadii.tokens.sm))
                    .padding(horizontal = FetchTheme.spacing.lg, vertical = FetchTheme.spacing.md)
            )
        }
    }

    val posterOptionsState by viewModel.posterOptions.state.collectAsState()
    com.fetch.tv.ui.components.posteroptions.PosterOptionsHost(
        state = posterOptionsState,
        controller = viewModel.posterOptions,
        onNavigateToDetail = { id, type, addonBaseUrl ->
            onNavigateToDetail(id, type, addonBaseUrl.takeIf { it.isNotBlank() })
        }
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LibraryViewModeRow(
    modes: List<LibraryViewMode>,
    selectedMode: LibraryViewMode,
    primaryFocusRequester: FocusRequester,
    onSelected: (LibraryViewMode) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)) {
            modes.forEach { mode ->
                val selected = mode == selectedMode
                Button(
                    onClick = { onSelected(mode) },
                    modifier = Modifier
                        .then(if (selected) Modifier.focusRequester(primaryFocusRequester) else Modifier),
                    colors = ButtonDefaults.colors(
                        containerColor = if (selected) FetchTheme.colors.FocusBackground else FetchTheme.colors.BackgroundCard,
                        contentColor = FetchTheme.colors.TextPrimary
                    )
                ) {
                    Text(
                        text = when (mode) {
                            LibraryViewMode.Saved -> stringResource(R.string.library_source_saved)
                            LibraryViewMode.Servers -> stringResource(R.string.library_source_servers)
                        }
                    )
                }
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
        verticalArrangement = Arrangement.spacedBy(10.dp)
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
                        val countPart = it.label.substringAfterLast("(", "").removeSuffix(")")
                        val localizedName = localizedTypeLabel(it.key)
                        if (countPart.isNotBlank()) "$localizedName ($countPart)" else localizedName
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
                        LibraryOption("${localizedGenreLabel(it.label)} (${it.count})", it.key)
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
                        LibraryOption("${it.label} (${it.count})", it.key)
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
                options = LibraryWatchedFilter.entries.map {
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

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
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
    var anchorSize by remember { mutableStateOf(IntSize.Zero) }
    // Seed focused option with the current selection so reopen highlights the right row
    // even before focus lands (fixes #2848 / incomplete #2507 race on TV).
    var focusedOptionValue by remember(expanded) {
        mutableStateOf(if (expanded) selectedValue else null)
    }
    val selectedItemFocusRequester = remember { FocusRequester() }
    val selectedBringIntoViewRequester = remember { BringIntoViewRequester() }

    // Popup content attaches focus targets a few frames after expand. A fixed 50ms
    // delay was flaky on TV and left focus on the first item for non-top selections.
    LaunchedEffect(expanded, selectedValue) {
        if (!expanded || selectedValue == null) return@LaunchedEffect
        var focused = selectedItemFocusRequester.requestFocusAfterFrames(frames = 3)
        var attempt = 0
        while (!focused && attempt < 6) {
            delay(32)
            focused = runCatching { selectedItemFocusRequester.requestFocus() }.getOrDefault(false)
            attempt++
        }
        if (!focused) return@LaunchedEffect
        runCatching { selectedBringIntoViewRequester.bringIntoView() }
        // Material DropdownMenu may still move initial focus to the first item after
        // the popup settles; re-assert once so long lists keep the real selection.
        delay(48)
        if (runCatching { selectedItemFocusRequester.requestFocus() }.getOrDefault(false)) {
            runCatching { selectedBringIntoViewRequester.bringIntoView() }
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
                .onSizeChanged { anchorSize = it }
                .onFocusChanged { isFocused = it.isFocused },
            shape = CardDefaults.shape(shape = RoundedCornerShape(FetchRadii.tokens.md)),
            colors = CardDefaults.colors(
                containerColor = FetchTheme.colors.BackgroundCard,
                focusedContainerColor = FetchTheme.colors.FocusBackground
            ),
            border = CardDefaults.border(
                border = androidx.tv.material3.Border(
                    border = BorderStroke(FetchTheme.spacing.hairline, FetchTheme.colors.Border),
                    shape = RoundedCornerShape(FetchRadii.tokens.md)
                ),
                focusedBorder = androidx.tv.material3.Border(
                    border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs),
                    shape = RoundedCornerShape(FetchRadii.tokens.md)
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
                    style = MaterialTheme.typography.labelSmall,
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

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                focusedOptionValue = null
                onExpandedChange(false)
            },
            modifier = Modifier
                .width(with(LocalDensity.current) { anchorSize.width.toDp() })
                .heightIn(max = 320.dp),
            shape = RoundedCornerShape(FetchRadii.tokens.md),
            containerColor = FetchTheme.colors.BackgroundCard,
            tonalElevation = FetchTheme.spacing.none,
            shadowElevation = FetchTheme.spacing.sm,
            border = BorderStroke(FetchTheme.spacing.hairline, FetchTheme.colors.Border)
        ) {
            options.forEach { option ->
                val isSelected = option.value == selectedValue
                val isOptionFocused = option.value == focusedOptionValue
                val itemTextColor = when {
                    isOptionFocused -> FetchTheme.colors.OnSecondary
                    isSelected -> FetchTheme.colors.TextPrimary
                    else -> FetchTheme.colors.TextPrimary
                }
                val itemBackgroundColor = when {
                    isOptionFocused -> FetchTheme.colors.Secondary
                    isSelected -> FetchTheme.colors.FocusBackground
                    else -> Color.Transparent
                }

                DropdownMenuItem(
                    modifier = Modifier
                        .then(
                            if (isSelected) {
                                Modifier
                                    .focusRequester(selectedItemFocusRequester)
                                    .bringIntoViewRequester(selectedBringIntoViewRequester)
                            } else {
                                Modifier
                            }
                        )
                        .padding(horizontal = FetchTheme.spacing.sm, vertical = FetchTheme.spacing.xxs)
                        .background(
                            color = itemBackgroundColor,
                            shape = RoundedCornerShape(FetchRadii.tokens.sm)
                        )
                        .onFocusChanged { state ->
                            val hasFocus = state.isFocused || state.hasFocus
                            focusedOptionValue = when {
                                hasFocus -> option.value
                                focusedOptionValue == option.value -> null
                                else -> focusedOptionValue
                            }
                        },
                    text = {
                        Text(
                            text = option.label,
                            color = itemTextColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    onClick = { onSelect(option) },
                    colors = MenuDefaults.itemColors(
                        textColor = itemTextColor,
                        disabledTextColor = FetchTheme.colors.TextDisabled
                    )
                )
            }
        }
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
            Button(
                onClick = onManageLists,
                enabled = !pending && !isSyncing,
                colors = ButtonDefaults.colors(
                    containerColor = FetchTheme.colors.BackgroundCard,
                    contentColor = FetchTheme.colors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.library_manage_lists))
            }
        }
        if (showRefresh) {
            Button(
                onClick = onRefresh,
                enabled = !pending && !isSyncing,
                colors = ButtonDefaults.colors(
                    containerColor = FetchTheme.colors.BackgroundCard,
                    contentColor = FetchTheme.colors.TextPrimary
                )
            ) {
                Text(if (isSyncing) stringResource(R.string.library_syncing_btn) else stringResource(R.string.library_sync_btn))
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ConfirmDeleteDialog(
    pending: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    FetchDialog(
        onDismiss = onCancel,
        title = stringResource(R.string.library_delete_title),
        subtitle = stringResource(R.string.library_delete_subtitle),
        width = 420.dp
    ) {
        Button(
            onClick = onConfirm,
            enabled = !pending,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.colors(
                containerColor = FetchStatusTones.errorContainer,
                contentColor = FetchTheme.colors.TextPrimary
            )
        ) {
            Text(stringResource(R.string.library_list_delete))
        }
    }
}
