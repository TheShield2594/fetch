package com.fetch.desktop.ui

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
import com.fetch.desktop.AppState
import com.fetch.desktop.Screen
import com.fetch.desktop.TrackingService
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
    val sources = listOf(LibrarySource.Local) +
        state.remoteLists.keys.map { LibrarySource.Watchlist(it) } + serverSources
    if (source !in sources) source = LibrarySource.Local
    val picker: @Composable () -> Unit = {
        if (sources.size > 1) {
            FilterDropdown(
                label = "Source",
                selected = source,
                options = sources.map { DropdownOption(it, it.label) },
                onSelect = {
                    source = it
                    query = LibraryQuery(sort = query.sort)
                }
            )
        }
    }

    (source as? LibrarySource.Server)?.let { server ->
        ServerLibraryGrid(state, server, picker)
        return
    }

    val remote = (source as? LibrarySource.Watchlist)?.let { state.remoteLists[it.service] }
    val items = remote ?: state.library.toList()
    val watched = state.progress.watched.toSet()
    val view = LibraryView(items, watched, query)
    val gridState = rememberLazyGridState()

    if (sources.size == 1 && state.library.isEmpty()) {
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
                }
            }
        }
        return
    }

    PosterGrid(
        items = view.results.map { it.toPreview() }.filter(state::allowed),
        state = gridState,
        onOpen = { state.navigate(Screen.Detail(it)) },
        showLabels = state.settings.showPosterLabels,
        modifier = Modifier.fillMaxSize(),
        header = {
            fullWidthItem("header") {
                Column(verticalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                    PageHeader("Library", subtitle = "${source.label} · ${items.size} saved")
                    picker()
                    LibraryFilters(view, query, onChange = { query = it })
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

    data object Local : LibrarySource {
        override val label = "Local"
    }

    data class Watchlist(val service: TrackingService) : LibrarySource {
        override val label: String get() = service.watchlistName
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
private fun LibraryFilters(view: LibraryView, query: LibraryQuery, onChange: (LibraryQuery) -> Unit) {
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
            options = LibrarySort.entries.map { DropdownOption(it, it.label) },
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
