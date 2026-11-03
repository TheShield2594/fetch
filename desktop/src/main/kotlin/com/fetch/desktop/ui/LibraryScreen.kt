package com.fetch.desktop.ui

import com.fetch.desktop.poster.withCustomPosterUrls
import com.fetch.desktop.poster.posterPatternFor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import com.fetch.desktop.ListProvider
import com.fetch.desktop.AppState
import com.fetch.desktop.Screen
import com.fetch.desktop.servers.ServerSort
import com.fetch.tv.domain.model.MetaPreview
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import com.fetch.desktop.data.LibraryQuery
import com.fetch.desktop.data.LibrarySort
import com.fetch.desktop.data.LibraryView
import com.fetch.desktop.data.WatchedFilter
import com.fetch.desktop.typeLabel

/**
 * The local Library, as the TV app shows it without a Trakt or Simkl account: titles saved with
 * "Add to library", filtered by type, genre, year and watched, and sorted.
 */
@Composable
fun LibraryScreen(state: AppState) {
    var query by remember { mutableStateOf(LibraryQuery()) }
    // "Local", a connected account's watchlist, or a media server library, as the TV Library's
    // source picker ("Saved" and "Servers").
    var source by remember { mutableStateOf<LibrarySource>(LibrarySource.Local) }
    val serverSources = state.servers.enabled.flatMap { account ->
        account.libraries.filter { it.selected }.map { LibrarySource.Server(account.id, it.id, "${it.name} · ${account.name}") }
    }
    val sources = state.lists.providers.map { LibrarySource.Lists(it) } + serverSources
    if (source !in sources) source = LibrarySource.Local
    // The provider's list tab; the local Library shows tabs only once you've made a list, as on TV.
    var listKey by remember { mutableStateOf<String?>(null) }
    var managing by remember { mutableStateOf(false) }
    val picker: @Composable () -> Unit = {
        if (sources.size > 1) {
            FilterDropdown(
                label = "Source",
                selected = source,
                options = sources.map { DropdownOption(it, it.label) },
                onSelect = {
                    source = it
                    listKey = null
                    // A provider starts in its own order, local lists by when added, as on TV.
                    query = LibraryQuery(sort = (it as? LibrarySource.Lists)?.provider?.let { p -> com.fetch.desktop.library.LibraryProviderOrder.sortOptions(p).first() } ?: query.sort)
                }
            )
        }
    }

    (source as? LibrarySource.Server)?.let { server ->
        ServerLibraryGrid(state, server, picker)
        return
    }

    val provider = (source as? LibrarySource.Lists)?.provider ?: ListProvider.LOCAL
    val providerLists = state.lists.lists(provider)
    val currentList = providerLists.firstOrNull { it.key == listKey } ?: providerLists.firstOrNull()
    val items = currentList?.items.orEmpty()
    val watched = state.progress.watched.toSet()
    // Each provider's sort options, its own order first (Library provider order, as on TV).
    val sortOptions = com.fetch.desktop.library.LibraryProviderOrder.sortOptions(provider)
    val effectiveQuery = query.copy(sort = com.fetch.desktop.library.LibraryProviderOrder.sortFor(provider, query.sort))
    val view = LibraryView(items, watched, effectiveQuery)
    val gridState = rememberLazyGridState()

    if (managing) ListsManagerDialog(state, provider, onDismiss = { managing = false })

    if (sources.size == 1 && state.library.isEmpty() && state.lists.local.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(top = 40.dp, start = PageGutter, end = PageGutter)) {
            PageHeader("Library", subtitle = "Local")
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("No items yet", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Start saving your favorites to see them here. Use Add to library on any title.",
                        color = FetchPalette.TextSecondary,
                        textAlign = TextAlign.Center
                    )
                    SecondaryButton("Search for titles", onClick = { state.openSection(Screen.Search) }, icon = FetchIcons.Search)
                    SecondaryButton("New list", onClick = { managing = true }, icon = FetchIcons.Add)
                }
            }
        }
        return
    }

    PosterGrid(
        items = view.results.map { it.toPreview() }.filter(state::allowed).withCustomPosterUrls(state.settings.layout.posterPatternFor(com.fetch.desktop.poster.CustomPosterScreen.LIBRARY)),
        state = gridState,
        onOpen = { state.navigate(Screen.Detail(it)) },
        showLabels = state.settings.showPosterLabels,
        modifier = Modifier.fillMaxSize(),
        header = {
            fullWidthItem("header") {
                Column(verticalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                    PageHeader("Library", subtitle = "${source.label} · ${currentList?.title ?: ""} · ${items.size} titles")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        picker()
                        if (provider != ListProvider.SIMKL) SecondaryButton("Manage lists", onClick = { managing = true }, icon = FetchIcons.List)
                        if (provider != ListProvider.LOCAL && state.lists.refreshing) {
                            CircularProgressIndicator(Modifier.size(20.dp), color = FetchPalette.Accent, strokeWidth = 2.dp)
                        }
                    }
                    if (providerLists.size > 1) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(providerLists, key = { it.key }) { list ->
                                val selected = list.key == currentList?.key
                                Text(
                                    "${list.title} ${list.items.size}",
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (selected) Color.Black else Color.White,
                                    modifier = Modifier.clip(RoundedCornerShape(50))
                                        .background(if (selected) Color.White else Color.White.copy(alpha = 0.08f))
                                        .clickable { listKey = list.key; query = LibraryQuery(sort = query.sort) }
                                        .pointerHoverIcon(PointerIcon.Hand)
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                    LibraryFilters(view, effectiveQuery, onChange = { query = it }, sortOptions = sortOptions)
                }
            }
            if (view.results.isEmpty()) {
                fullWidthItem("none") {
                    EmptyState(
                        title = if (items.isEmpty()) "Nothing saved here yet" else "Nothing matches these filters",
                        body = if (items.isEmpty()) "Use Add to library on any title." else "Try All in one of the filters above.",
                        modifier = Modifier.fillMaxWidth().padding(top = 60.dp)
                    )
                }
            }
        }
    )
}

/** Where the Library's titles come from. */
sealed interface LibrarySource {
    val label: String

    companion object {
        val Local: LibrarySource = Lists(ListProvider.LOCAL)
    }

    /** A provider's lists: Local (the library and lists of your own), Trakt or Simkl. */
    data class Lists(val provider: ListProvider) : LibrarySource {
        override val label: String get() = provider.label
    }

    data class Server(val accountId: String, val libId: String, override val label: String) : LibrarySource
}

/** A media server library, a page at a time, sorted as the server sorts it. */
@Composable
private fun ServerLibraryGrid(state: AppState, source: LibrarySource.Server, picker: @Composable () -> Unit) {
    var sort by remember(source) { mutableStateOf(ServerSort.ADDED) }
    val items = remember(source, sort) { mutableStateListOf<MetaPreview>() }
    var total by remember(source, sort) { mutableStateOf<Int?>(null) }
    var loading by remember(source, sort) { mutableStateOf(false) }
    var error by remember(source, sort) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()
    val loadMore: () -> Unit = load@{
        if (loading || (total != null && items.size >= total!!)) return@load
        loading = true
        scope.launch {
            try {
                val page = state.servers.browse(source.accountId, source.libId, items.size, 60, sort)
                if (page == null) {
                    total = items.size
                } else {
                    items.addAll(page.items)
                    total = page.total
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Couldn't reach the server"
                total = items.size
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(source, sort) { loadMore() }
    LaunchedEffect(gridState, source, sort) {
        snapshotFlow {
            val info = gridState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 6
        }.distinctUntilChanged().collect { nearEnd -> if (nearEnd && items.isNotEmpty()) loadMore() }
    }

    PosterGrid(
        items = items.filter(state::allowed),
        state = gridState,
        onOpen = { state.navigate(Screen.Detail(it)) },
        showLabels = state.settings.showPosterLabels,
        modifier = Modifier.fillMaxSize(),
        header = {
            fullWidthItem("header") {
                Column(verticalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                    PageHeader("Library", subtitle = source.label + (total?.let { " · $it titles" } ?: ""))
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        picker()
                        FilterDropdown(
                            label = "Sort",
                            selected = sort,
                            options = ServerSort.entries.map { DropdownOption(it, it.label) },
                            onSelect = { sort = it }
                        )
                    }
                }
            }
            when {
                items.isEmpty() && loading -> fullWidthItem("loading") {
                    Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = FetchPalette.Accent)
                    }
                }
                items.isEmpty() -> fullWidthItem("empty") {
                    EmptyState(
                        title = error?.let { "Couldn't load this library" } ?: "This library is empty",
                        body = error ?: "Nothing to show yet.",
                        modifier = Modifier.fillMaxWidth().padding(top = 60.dp)
                    )
                }
            }
        }
    )
}

@Composable
private fun LibraryFilters(view: LibraryView, query: LibraryQuery, onChange: (LibraryQuery) -> Unit, sortOptions: List<LibrarySort> = LibrarySort.entries) {
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        FilterDropdown(
            label = "Type",
            selected = query.type,
            options = listOf(DropdownOption<String?>(null, "All (${view.allTypesCount})")) +
                view.types.map { DropdownOption(it.value, "${typeLabel(it.value)} (${it.count})") },
            onSelect = { onChange(query.copy(type = it)) }
        )
        FilterDropdown(
            label = "Sort",
            selected = query.sort,
            options = sortOptions.map { DropdownOption(it, it.label) },
            onSelect = { onChange(query.copy(sort = it)) }
        )
        if (view.genres.isNotEmpty()) {
            FilterDropdown(
                label = "Genre",
                selected = query.genre,
                options = listOf(DropdownOption<String?>(null, "All")) + view.genres.map { DropdownOption(it.value, "${it.value} (${it.count})") },
                onSelect = { onChange(query.copy(genre = it)) }
            )
        }
        if (view.years.isNotEmpty()) {
            FilterDropdown(
                label = "Year",
                selected = query.year,
                options = listOf(DropdownOption<String?>(null, "All")) + view.years.map { DropdownOption(it.value, "${it.value} (${it.count})") },
                onSelect = { onChange(query.copy(year = it)) }
            )
        }
        FilterDropdown(
            label = "Watched",
            selected = query.watched,
            options = WatchedFilter.entries.map { DropdownOption(it, it.label) },
            onSelect = { onChange(query.copy(watched = it)) }
        )
    }
}
