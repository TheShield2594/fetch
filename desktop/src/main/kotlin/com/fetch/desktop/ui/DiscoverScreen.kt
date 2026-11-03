package com.fetch.desktop.ui

import com.fetch.desktop.i18n.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import com.fetch.desktop.AppState
import com.fetch.desktop.DiscoverState
import com.fetch.desktop.Screen
import com.fetch.desktop.typeLabel
import com.fetch.tv.domain.model.MetaPreview
import kotlinx.coroutines.flow.distinctUntilChanged

/** Discover on its own page (the "Show in Side Panel" location). */
@Composable
fun DiscoverScreen(state: AppState) {
    val gridState = rememberLazyGridState()
    DiscoverGrid(state, gridState, Modifier.fillMaxSize()) {
        fullWidthItem("title") { PageHeader("Discover") }
    }
}

/**
 * The Discover grid: type, catalog and genre pickers over the catalog's titles, loading the next
 * page as you scroll near the end. [header] goes above the pickers (Search puts its field there).
 */
@Composable
fun DiscoverGrid(
    state: AppState,
    gridState: LazyGridState,
    modifier: Modifier = Modifier,
    /** Another catalog browser, such as a row's "See all"; it shows only its genre picker. */
    discover: DiscoverState = state.discover,
    header: LazyGridScope.() -> Unit = {}
) {
    val singleCatalog = discover !== state.discover
    val open: (MetaPreview) -> Unit = { state.navigate(Screen.Detail(it)) }

    // Infinite scroll: ask for the next page when the last visible card is within 6 of the end.
    LaunchedEffect(gridState, discover) {
        snapshotFlow {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= info.totalItemsCount - 6
        }.distinctUntilChanged().collect { nearEnd ->
            if (nearEnd && discover.items.isNotEmpty()) discover.loadMore()
        }
    }

    PosterGrid(
        items = discover.items.filter(state::allowed),
        state = gridState,
        onOpen = open,
        showLabels = state.settings.showPosterLabels,
        modifier = modifier,
        header = {
            header()
            fullWidthItem("discover-pickers") { DiscoverPickers(discover, state.settings.showCatalogType, catalogPickers = !singleCatalog) }
            when {
                state.addonsLoading && !singleCatalog -> Unit
                discover.catalogs.isEmpty() && !singleCatalog -> fullWidthItem("no-catalog") {
                    EmptyState(
                        title = "Select a catalog",
                        body = "Install an addon with catalogs to browse them here.",
                        modifier = Modifier.fillMaxWidth().padding(top = 60.dp)
                    )
                }
                discover.items.isEmpty() && discover.loading -> fullWidthItem("loading") {
                    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = FetchPalette.Accent)
                    }
                }
                discover.items.isEmpty() -> fullWidthItem("empty") {
                    EmptyState(
                        title = discover.error?.let { "Couldn't load this catalog" } ?: "No content found",
                        body = discover.error ?: "Try a different genre or catalog.",
                        modifier = Modifier.fillMaxWidth().padding(top = 60.dp)
                    )
                }
            }
        },
        footer = {
            if (discover.items.isNotEmpty()) {
                fullWidthItem("more") {
                    Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                        when {
                            discover.loading -> CircularProgressIndicator(color = FetchPalette.Accent, modifier = Modifier.size(28.dp))
                            !discover.endReached -> SecondaryButton("Load more", onClick = discover::loadMore)
                            else -> Text(tr("That's everything in this catalog."), color = FetchPalette.TextMuted)
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun DiscoverPickers(discover: DiscoverState, showType: Boolean, catalogPickers: Boolean = true) {
    val selected = discover.selected
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (catalogPickers) FilterDropdown(
                label = "Type",
                selected = discover.type,
                options = discover.types.map { DropdownOption<String?>(it, typeLabel(it)) },
                onSelect = { it?.let(discover::selectType) }
            )
            if (catalogPickers) FilterDropdown(
                label = "Catalog",
                selected = selected?.key,
                options = discover.catalogsOfType.map {
                    DropdownOption<String?>(it.key, "${it.catalog.name} · ${it.addon.displayName}")
                },
                onSelect = { key -> discover.catalogsOfType.firstOrNull { it.key == key }?.let(discover::select) }
            )
            if (selected != null && selected.genres.isNotEmpty()) {
                FilterDropdown(
                    label = "Genre",
                    selected = discover.genre,
                    options = (if (selected.genreRequired) emptyList() else listOf(DropdownOption<String?>(null, "All genres"))) +
                        selected.genres.map { DropdownOption<String?>(it, it) },
                    onSelect = discover::selectGenre
                )
            }
        }
        if (selected != null) {
            Text(
                listOfNotNull(
                    selected.addon.displayName,
                    typeLabel(selected.catalog.apiType).takeIf { showType },
                    discover.genre
                ).joinToString(" • "),
                color = FetchPalette.TextMuted
            )
        }
    }
}

/** A Home row's whole catalog as a grid that loads more as you scroll (TV: See all). */
@Composable
fun SeeAllScreen(state: AppState, screen: Screen.SeeAll) {
    val grid = rememberLazyGridState()
    DiscoverGrid(state, grid, Modifier.fillMaxSize(), discover = state.seeAll) {
        fullWidthItem("see-all-title") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (state.canGoBack) CircleIconButton(FetchIcons.Back, "Back", onClick = state::back)
                Text(screen.title, style = MaterialTheme.typography.headlineSmall, color = Color.White)
            }
        }
    }
}
