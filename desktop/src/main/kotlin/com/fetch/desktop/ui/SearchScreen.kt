package com.fetch.desktop.ui

import com.fetch.desktop.poster.withCustomPosterUrls
import com.fetch.desktop.poster.posterPatternFor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.clickable
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.fetch.desktop.AppState
import com.fetch.desktop.Screen
import com.fetch.desktop.typeLabel

/**
 * Search across every addon catalog that takes a search, as on the TV app. Results come in one
 * row per catalog while each addon answers.
 */
@Composable
fun SearchScreen(state: AppState) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val query = state.searchQuery.trim()

    // The field stays put above whatever is below it, so typing never loses focus when the
    // page switches between Discover and results.
    Column(Modifier.fillMaxSize()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(start = PageGutter, end = PageGutter, top = 40.dp)
        ) {
            SearchField(
                value = state.searchQuery,
                onValueChange = state::search,
                onSubmit = { state.rememberSearch() },
                modifier = Modifier.focusRequester(focus)
            )
            // Recent searches, until you type, as on TV.
            val recent = state.settings.recentSearches
            if (query.isEmpty() && recent.isNotEmpty()) {
                RecentSearches(
                    recent,
                    onPick = { state.search(it) },
                    onRemove = state::removeRecentSearch,
                    onClear = state::clearRecentSearches
                )
            }
            if (!state.addonsLoading && !state.hasSearchableCatalogs) {
                Text(
                    "None of your addons can search. Install one with a search catalog, such as Cinemeta, in Settings › Content & Discovery › Addons.",
                    color = FetchPalette.TextMuted
                )
            }
        }
        if (query.length < 2 && !state.settings.discoverInSidebar) {
            // Discover's default place on the TV app: inside Search, until you type.
            DiscoverGrid(state, gridState, Modifier.fillMaxSize()) {
                fullWidthItem("discover-title") {
                    Text("Discover", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                }
            }
            return@Column
        }
        ScrollbarBox(listState, Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(top = 36.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(36.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                when {
                    query.length < 2 -> item(key = "hint") {
                        EmptyState(
                            title = "Find a movie or show",
                            body = "Type a title. Every addon that can search is asked at once.",
                            modifier = Modifier.fillMaxWidth().padding(top = 80.dp)
                        )
                    }
                    state.searchRows.isEmpty() -> item(key = "waiting") {
                        Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = FetchPalette.Accent, modifier = Modifier.size(28.dp))
                        }
                    }
                    !state.searching && state.searchRows.all { it.items.isEmpty() } -> item(key = "none") {
                        EmptyState(
                            title = "No results for “$query”",
                            body = "Check the spelling, or try the original title.",
                            modifier = Modifier.fillMaxWidth().padding(top = 80.dp)
                        )
                    }
                    else -> {
                        // Rows that came back empty are noise once others have results.
                        val shown = state.searchRows.filter { it.loading || it.items.isNotEmpty() }
                        items(shown, key = { it.key }) { row ->
                            CatalogRowView(
                                row.copy(items = row.items.filter(state::allowed).withCustomPosterUrls(state.settings.layout.posterPatternFor(com.fetch.desktop.poster.CustomPosterScreen.SEARCH))),
                                onOpen = {
                                    state.rememberSearch()
                                    state.navigate(Screen.Detail(it))
                                },
                                title = typeLabel(row.catalog.apiType),
                                subtitle = row.addon.displayName,
                                showLabels = state.settings.showPosterLabels
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, onSubmit: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .widthIn(max = 720.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(FetchPalette.Surface)
            .border(1.dp, FetchPalette.Outline, shape)
            .padding(horizontal = 22.dp, vertical = 16.dp)
    ) {
        Icon(FetchIcons.Search, contentDescription = null, tint = FetchPalette.TextSecondary, modifier = Modifier.size(24.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text("Search movies and shows", color = FetchPalette.TextMuted, fontSize = 18.sp)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
                cursorBrush = SolidColor(FetchPalette.Accent),
                modifier = modifier.fillMaxWidth().onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) {
                        onSubmit()
                        true
                    } else false
                }
            )
        }
        if (value.isNotEmpty()) {
            CircleIconButton(FetchIcons.Close, "Clear", onClick = { onValueChange("") }, size = 30.dp)
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RecentSearches(recent: List<String>, onPick: (String) -> Unit, onRemove: (String) -> Unit, onClear: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("RECENT SEARCHES", style = OverlineStyle, color = FetchPalette.TextMuted)
            if (confirming) {
                Text("Clear all?", color = FetchPalette.TextSecondary, fontSize = 13.sp)
                SecondaryButton("Clear", onClick = { confirming = false; onClear() })
                SecondaryButton("Keep", onClick = { confirming = false })
            } else {
                Text(
                    "Clear",
                    color = FetchPalette.TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.clickable { confirming = true }.pointerHoverIcon(PointerIcon.Hand)
                )
            }
        }
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            recent.forEach { q ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(FetchPalette.Surface)
                        .clickable { onPick(q) }.pointerHoverIcon(PointerIcon.Hand)
                        .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp)
                ) {
                    Text(q, color = Color.White, fontSize = 14.sp)
                    CircleIconButton(FetchIcons.Close, "Remove $q", onClick = { onRemove(q) }, size = 24.dp)
                }
            }
        }
    }
}
