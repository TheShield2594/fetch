package com.fetch.tv.ui.screens.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.domain.model.CatalogRow
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.ui.components.CatalogRowSection
import com.fetch.tv.ui.components.FetchChip
import com.fetch.tv.ui.components.PosterCardStyle
import com.fetch.tv.ui.components.focusSafeHorizontalScroll
import com.fetch.tv.ui.theme.FetchTheme
import com.fetch.tv.ui.util.localizedGenreLabel
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Discover's shelves (#858): rows over the grid that suggest where to start while no genre or
 * filter is set. Each title row uses the catalog row component Search and Home's catalogs use.
 */
@Immutable
internal data class DiscoverShelf(
    val id: String,
    val row: CatalogRow,
    /** The genre tiles shelf: chips, not cards. */
    val genres: List<String> = emptyList(),
    /** What the shelf's See all sets on the grid; null when it has no filter to stand for. */
    val seeAllYearRange: DiscoverYearRange? = null
)

internal const val DISCOVER_SHELF_TRENDING = "trending"
internal const val DISCOVER_SHELF_GENRES = "genres"
internal const val DISCOVER_SHELF_LIBRARY = "library"
internal const val DISCOVER_SHELF_NEW = "new"

private const val SHELF_FOCUS_PREFIX = "shelf|"

/** The focus key of a shelf card, kept with the grid's focused key so Back from a title returns to it. */
internal fun discoverShelfFocusKey(shelfId: String, index: Int): String = "$SHELF_FOCUS_PREFIX$shelfId|$index"

/** The shelf and card index in a [discoverShelfFocusKey], or null for a grid card's key. */
internal fun parseDiscoverShelfFocusKey(key: String?): Pair<String, Int>? {
    if (key == null || !key.startsWith(SHELF_FOCUS_PREFIX)) return null
    val parts = key.removePrefix(SHELF_FOCUS_PREFIX).split('|')
    if (parts.size != 2) return null
    val index = parts[1].toIntOrNull() ?: return null
    return parts[0] to index
}

/** The shelves for what Discover shows now; empty when [enabled] is false or none has titles. */
@Composable
internal fun rememberDiscoverShelves(
    enabled: Boolean,
    uiState: SearchUiState,
    librarySeedGenres: Map<String, List<String>>,
    libraryIds: Set<String>,
    watchedMovieIds: Set<String>,
    watchedSeriesIds: Set<String>
): List<DiscoverShelf> {
    val trendingTitle = stringResource(R.string.discover_shelf_trending)
    val genresTitle = stringResource(R.string.discover_shelf_genres)
    val libraryTitle = stringResource(R.string.discover_shelf_because_library)
    val newTitle = stringResource(R.string.discover_shelf_new_this_year)
    val type = uiState.selectedDiscoverType
    val results = uiState.discoverResults
    val trending = uiState.discoverTrending
    val seeds = librarySeedGenres[type.trim().lowercase(Locale.ROOT)].orEmpty()
    val currentYear = remember { java.time.Year.now().value }
    return remember(
        enabled, type, results, trending, seeds, libraryIds, watchedMovieIds, watchedSeriesIds,
        uiState.discoverCatalogs, currentYear, trendingTitle, genresTitle, libraryTitle, newTitle
    ) {
        if (!enabled || results.isEmpty()) return@remember emptyList()
        val contentType = results.first().type
        fun shelfRow(id: String, title: String, items: List<MetaPreview>) = CatalogRow(
            addonId = "discover_shelf",
            addonName = "",
            addonBaseUrl = "",
            catalogId = "discover_shelf_$id",
            catalogName = title,
            type = contentType,
            rawType = type,
            items = items,
            hasMore = false
        )
        val isWatched: (MetaPreview) -> Boolean = { item ->
            if (com.fetch.tv.ui.util.isSeriesContentType(item.apiType)) item.id in watchedSeriesIds else item.id in watchedMovieIds
        }
        buildList {
            trending
                ?.takeIf { it.items.isNotEmpty() && it.apiType.equals(type, ignoreCase = true) }
                ?.let { add(DiscoverShelf(DISCOVER_SHELF_TRENDING, shelfRow(DISCOVER_SHELF_TRENDING, trendingTitle, it.items))) }
            val genres = discoverGenres(uiState.discoverCatalogs, type)
            if (genres.isNotEmpty()) {
                add(DiscoverShelf(DISCOVER_SHELF_GENRES, shelfRow(DISCOVER_SHELF_GENRES, genresTitle, emptyList()), genres = genres))
            }
            discoverBecauseInLibrary(
                items = results,
                seedGenres = seeds,
                isSaved = { isLibraryItem(it, libraryIds) },
                isWatched = isWatched
            ).takeIf { it.isNotEmpty() }?.let {
                add(DiscoverShelf(DISCOVER_SHELF_LIBRARY, shelfRow(DISCOVER_SHELF_LIBRARY, libraryTitle, it)))
            }
            discoverNewThisYear(results, currentYear).takeIf { it.isNotEmpty() }?.let {
                add(
                    DiscoverShelf(
                        DISCOVER_SHELF_NEW,
                        shelfRow(DISCOVER_SHELF_NEW, newTitle, it),
                        seeAllYearRange = DiscoverYearRange(from = currentYear, to = currentYear)
                    )
                )
            }
        }
    }
}

/**
 * The shelves as full-width grid items, then the heading over the grid's cards: [shelves].size + 1
 * items, which the grid is told as its header count.
 */
internal fun LazyGridScope.discoverShelfItems(
    shelves: List<DiscoverShelf>,
    allTitlesLabel: String,
    posterCardStyle: PosterCardStyle,
    showPosterLabels: Boolean,
    /** A shelf card to give focus back to (coming back from a title), or null. */
    restoreTarget: Pair<String, Int>?,
    onRestoreHandled: () -> Unit,
    isWatched: (MetaPreview) -> Boolean,
    onItemFocused: (key: String, item: MetaPreview) -> Unit,
    onItemClick: (MetaPreview) -> Unit,
    onItemLongPress: (MetaPreview) -> Unit,
    onSelectGenre: (String) -> Unit,
    onSelectYearRange: (DiscoverYearRange) -> Unit
) {
    shelves.forEach { shelf ->
        item(
            key = "discover_shelf_${shelf.id}",
            span = { GridItemSpan(maxLineSpan) },
            contentType = if (shelf.genres.isNotEmpty()) "discover_genre_shelf" else "discover_shelf"
        ) {
            // The grid insets its cells by the screen margin and the row pads itself by the same
            // margin, so the shelf spans the margins to line up with the cards below.
            val margin = FetchTheme.spacing.screen.horizontal
            val fullBleed = Modifier.spanScreenMargins(margin)
            if (shelf.genres.isNotEmpty()) {
                DiscoverGenreShelf(
                    title = shelf.row.catalogName,
                    genres = shelf.genres,
                    onSelectGenre = onSelectGenre
                )
            } else {
                val restoreIndex = restoreTarget?.takeIf { it.first == shelf.id }?.second ?: -1
                if (restoreIndex >= 0) {
                    // The row puts focus back itself; the screen's restore is done once it has.
                    LaunchedEffect(restoreIndex) {
                        delay(SHELF_RESTORE_SETTLE_MS)
                        onRestoreHandled()
                    }
                }
                CatalogRowSection(
                    catalogRow = shelf.row,
                    onItemClick = { id, _, _ ->
                        shelf.row.items.firstOrNull { it.id == id }?.let(onItemClick)
                    },
                    modifier = fullBleed,
                    onSeeAll = { shelf.seeAllYearRange?.let(onSelectYearRange) },
                    showSeeAll = shelf.seeAllYearRange != null,
                    posterCardStyle = posterCardStyle,
                    showPosterLabels = showPosterLabels,
                    showAddonName = false,
                    showCatalogTypeSuffix = false,
                    isItemWatched = isWatched,
                    onItemLongPress = { item, _ -> onItemLongPress(item) },
                    focusedItemIndex = restoreIndex,
                    onItemFocused = { index ->
                        shelf.row.items.getOrNull(index)?.let { item ->
                            onItemFocused(discoverShelfFocusKey(shelf.id, index), item)
                        }
                    }
                )
            }
        }
    }
    item(
        key = "discover_shelf_all_heading",
        span = { GridItemSpan(maxLineSpan) },
        contentType = "discover_shelf_heading"
    ) {
        Text(
            text = allTitlesLabel,
            style = MaterialTheme.typography.headlineMedium,
            color = FetchTheme.colors.TextPrimary,
            modifier = Modifier.padding(top = FetchTheme.spacing.md)
        )
    }
}

/** Long enough for the row's own restore (a scroll and a focus request) to have run. */
private const val SHELF_RESTORE_SETTLE_MS = 500L

/** Widens a full-span grid item over the grid's side padding of [margin] on each side. */
private fun Modifier.spanScreenMargins(margin: Dp): Modifier = layout { measurable, constraints ->
    val extra = margin.roundToPx()
    val width = constraints.maxWidth + extra * 2
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra, 0) }
}

/** The genre tiles (#858): OK applies the genre to the grid below, which then takes focus. */
@Composable
private fun DiscoverGenreShelf(
    title: String,
    genres: List<String>,
    onSelectGenre: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = FetchTheme.colors.TextPrimary
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .focusSafeHorizontalScroll(),
            horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)
        ) {
            genres.forEach { genre ->
                FetchChip(
                    label = localizedGenreLabel(genre),
                    selected = false,
                    onClick = { onSelectGenre(genre) }
                )
            }
        }
    }
}
