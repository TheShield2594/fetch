package com.fetch.tv.ui.screens.search

import com.fetch.tv.ui.components.focusSafeHorizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.fetch.tv.R
import com.fetch.tv.ui.components.FetchChip
import com.fetch.tv.ui.components.FetchFilterChip
import com.fetch.tv.ui.components.FetchFilterOption
import com.fetch.tv.ui.theme.FetchTheme
import com.fetch.tv.ui.util.localizedGenreLabel

/** The filters Discover and See all share. Each is a chip that opens its choices; Type has its own chips. */
internal enum class DiscoverFilterKind { GENRE, YEAR, RATING, SORT }

private const val ALL_KEY = "__all__"

/**
 * Discover's and See all's filter chips (#851): Genre (only when there are genres), Year, Rating
 * and Sort, each a [FetchFilterChip]. Emitted into the caller's row; [openFilter] says which one
 * has its panel open.
 */
@Composable
internal fun DiscoverFilterChips(
    genres: List<String>,
    selectedGenre: String?,
    filters: DiscoverFilters,
    openFilter: DiscoverFilterKind?,
    onOpenFilterChange: (DiscoverFilterKind?) -> Unit,
    onSelectGenre: (String?) -> Unit,
    onSelectYearRange: (DiscoverYearRange?) -> Unit,
    onSelectMinRating: (Int?) -> Unit,
    onSelectSort: (DiscoverSort) -> Unit,
    focusEnabled: Boolean = true
) {
    val resources = LocalResources.current
    val currentYear = remember { java.time.Year.now().value }
    val yearRanges = remember(currentYear) { listOf<DiscoverYearRange?>(null) + discoverYearRanges(currentYear) }
    val ratings = remember { listOf<Int?>(null) + DISCOVER_RATING_THRESHOLDS }
    val allGenresLabel = stringResource(R.string.discover_genre_default)

    DiscoverFilterKind.entries
        .filter { it != DiscoverFilterKind.GENRE || genres.isNotEmpty() }
        .forEach { kind ->
            val expanded = openFilter == kind
            fun onExpandedChange(open: Boolean) {
                if (open) onOpenFilterChange(kind) else if (openFilter == kind) onOpenFilterChange(null)
            }
            when (kind) {
                DiscoverFilterKind.GENRE -> FetchFilterChip(
                    title = stringResource(R.string.discover_filter_genre),
                    value = selectedGenre?.let { localizedGenreLabel(it) } ?: allGenresLabel,
                    active = selectedGenre != null,
                    expanded = expanded,
                    options = buildList {
                        add(FetchFilterOption(ALL_KEY, allGenresLabel, selectedGenre == null))
                        genres.forEach { genre ->
                            add(
                                FetchFilterOption(
                                    key = genre,
                                    label = localizedGenreLabel(genre),
                                    selected = genre.equals(selectedGenre, ignoreCase = true)
                                )
                            )
                        }
                    },
                    onExpandedChange = ::onExpandedChange,
                    onSelect = { onSelectGenre(it.key.takeUnless { key -> key == ALL_KEY }) },
                    focusEnabled = focusEnabled
                )
                DiscoverFilterKind.YEAR -> FetchFilterChip(
                    title = stringResource(R.string.discover_filter_year),
                    value = discoverYearLabel(resources, filters.yearRange),
                    active = filters.yearRange != null,
                    expanded = expanded,
                    options = yearRanges.mapIndexed { index, range ->
                        FetchFilterOption(index.toString(), discoverYearLabel(resources, range), range == filters.yearRange)
                    },
                    onExpandedChange = ::onExpandedChange,
                    onSelect = { option -> onSelectYearRange(yearRanges[option.key.toInt()]) },
                    focusEnabled = focusEnabled
                )
                DiscoverFilterKind.RATING -> FetchFilterChip(
                    title = stringResource(R.string.discover_filter_rating),
                    value = discoverRatingLabel(resources, filters.minRating),
                    active = filters.minRating != null,
                    expanded = expanded,
                    options = ratings.map { rating ->
                        FetchFilterOption(rating?.toString() ?: ALL_KEY, discoverRatingLabel(resources, rating), rating == filters.minRating)
                    },
                    onExpandedChange = ::onExpandedChange,
                    onSelect = { option -> onSelectMinRating(option.key.toIntOrNull()) },
                    focusEnabled = focusEnabled
                )
                DiscoverFilterKind.SORT -> FetchFilterChip(
                    title = stringResource(R.string.discover_filter_sort),
                    value = discoverSortLabel(resources, filters.sort),
                    active = filters.sort != DiscoverSort.CATALOG,
                    expanded = expanded,
                    options = DiscoverSort.entries.map { sort ->
                        FetchFilterOption(sort.name, discoverSortLabel(resources, sort), sort == filters.sort)
                    },
                    onExpandedChange = ::onExpandedChange,
                    onSelect = { option -> onSelectSort(DiscoverSort.valueOf(option.key)) },
                    focusEnabled = focusEnabled
                )
            }
        }
}

/**
 * See all's filter chips (#651): Discover's filters (#851), then Hide watched, which toggles.
 */
@Composable
internal fun SeeAllFilterBar(
    genres: List<String>,
    selectedGenre: String?,
    filters: DiscoverFilters,
    hideWatched: Boolean,
    onSelectGenre: (String?) -> Unit,
    onSelectYearRange: (DiscoverYearRange?) -> Unit,
    onSelectMinRating: (Int?) -> Unit,
    onSelectSort: (DiscoverSort) -> Unit,
    onToggleHideWatched: () -> Unit,
    modifier: Modifier = Modifier,
    /** Focuses the bar's first filter: the browse Back ladder's last step on See all (#850). */
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null
) {
    var openFilter by remember { mutableStateOf<DiscoverFilterKind?>(null) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusSafeHorizontalScroll(fadeEdges = true),
        horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DiscoverFilterChips(
            genres = genres,
            selectedGenre = selectedGenre,
            filters = filters,
            openFilter = openFilter,
            onOpenFilterChange = { openFilter = it },
            onSelectGenre = onSelectGenre,
            onSelectYearRange = onSelectYearRange,
            onSelectMinRating = onSelectMinRating,
            onSelectSort = onSelectSort
        )
        FetchChip(
            label = stringResource(R.string.catalog_see_all_hide_watched),
            selected = hideWatched,
            onClick = {
                openFilter = null
                onToggleHideWatched()
            }
        )
    }
}
