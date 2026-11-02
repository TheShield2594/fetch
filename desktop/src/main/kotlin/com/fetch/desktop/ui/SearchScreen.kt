package com.fetch.desktop.ui

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

    ScrollbarBox(listState, Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = 40.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(36.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "field") {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(horizontal = PageGutter)
                ) {
                    SearchField(
                        value = state.searchQuery,
                        onValueChange = state::search,
                        modifier = Modifier.focusRequester(focus)
                    )
                    if (!state.addonsLoading && !state.hasSearchableCatalogs) {
                        Text(
                            "None of your addons can search. Install one with a search catalog, such as Cinemeta, on the Addons page.",
                            color = FetchPalette.TextMuted
                        )
                    }
                }
            }
            val query = state.searchQuery.trim()
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
                            row,
                            onOpen = { state.navigate(Screen.Detail(it)) },
                            title = typeLabel(row.catalog.apiType),
                            subtitle = row.addon.displayName
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
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
                modifier = modifier.fillMaxWidth()
            )
        }
        if (value.isNotEmpty()) {
            CircleIconButton(FetchIcons.Close, "Clear", onClick = { onValueChange("") }, size = 30.dp)
        }
    }
}
