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
    // "Local", or a connected account's watchlist, as the TV Library's source picker.
    var source by remember { mutableStateOf<TrackingService?>(null) }
    val remote = source?.let { state.remoteLists[it] }
    if (source != null && remote == null) source = null
    val items = remote ?: state.library.toList()
    val watched = state.progress.watched.toSet()
    val view = LibraryView(items, watched, query)
    val gridState = rememberLazyGridState()
    val sourceLabel = source?.watchlistName ?: "Local"

    if (state.library.isEmpty() && state.remoteLists.isEmpty()) {
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
        items = view.results.map { it.toPreview() },
        state = gridState,
        onOpen = { state.navigate(Screen.Detail(it)) },
        showLabels = state.settings.showPosterLabels,
        modifier = Modifier.fillMaxSize(),
        header = {
            fullWidthItem("header") {
                Column(verticalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                    PageHeader("Library", subtitle = "$sourceLabel · ${items.size} saved")
                    if (state.remoteLists.isNotEmpty()) {
                        FilterDropdown(
                            label = "Source",
                            selected = source,
                            options = listOf(DropdownOption<TrackingService?>(null, "Local")) +
                                state.remoteLists.keys.map { DropdownOption<TrackingService?>(it, it.watchlistName) },
                            onSelect = {
                                source = it
                                query = LibraryQuery(sort = query.sort)
                            }
                        )
                    }
                    LibraryFilters(view, query, onChange = { query = it })
                }
            }
            if (view.results.isEmpty()) {
                fullWidthItem("none") {
                    EmptyState(
                        title = "Nothing matches these filters",
                        body = "Try All in one of the filters above.",
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
