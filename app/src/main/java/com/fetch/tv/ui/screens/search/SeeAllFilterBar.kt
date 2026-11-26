package com.fetch.tv.ui.screens.search

import com.fetch.tv.ui.components.focusSafeHorizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.ui.screens.detail.requestFocusAfterFrames
import com.fetch.tv.ui.theme.FetchTheme
import com.fetch.tv.ui.util.localizedGenreLabel

private enum class SeeAllFilterKind { GENRE, YEAR, RATING, SORT }

/**
 * See all's filter chips (#651), built from Discover's: Genre (only when the catalog declares
 * genres), Year, Rating and Sort open a row of choices; Hide watched toggles.
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
    modifier: Modifier = Modifier
) {
    val resources = LocalResources.current
    val currentYear = remember { java.time.Year.now().value }
    val yearRanges = remember(currentYear) { discoverYearRanges(currentYear) }
    var openFilter by remember { mutableStateOf<SeeAllFilterKind?>(null) }
    val chipRequesters = remember { SeeAllFilterKind.entries.associateWith { FocusRequester() } }
    val optionFocusRequester = remember { FocusRequester() }

    fun closeFilter(returnFocus: Boolean) {
        val kind = openFilter ?: return
        if (returnFocus) runCatching { chipRequesters.getValue(kind).requestFocus() }
        openFilter = null
    }

    androidx.activity.compose.BackHandler(enabled = openFilter != null) { closeFilter(returnFocus = true) }

    val allGenresLabel = stringResource(R.string.discover_genre_default)
    val selectedGenreLabel = selectedGenre?.let { localizedGenreLabel(it) } ?: allGenresLabel

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .focusSafeHorizontalScroll(),
            horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SeeAllFilterKind.entries
                .filter { it != SeeAllFilterKind.GENRE || genres.isNotEmpty() }
                .forEach { kind ->
                    val (title, value, active) = when (kind) {
                        SeeAllFilterKind.GENRE -> Triple(
                            stringResource(R.string.discover_filter_genre),
                            selectedGenreLabel,
                            selectedGenre != null
                        )
                        SeeAllFilterKind.YEAR -> Triple(
                            stringResource(R.string.discover_filter_year),
                            discoverYearLabel(resources, filters.yearRange),
                            filters.yearRange != null
                        )
                        SeeAllFilterKind.RATING -> Triple(
                            stringResource(R.string.discover_filter_rating),
                            discoverRatingLabel(resources, filters.minRating),
                            filters.minRating != null
                        )
                        SeeAllFilterKind.SORT -> Triple(
                            stringResource(R.string.discover_filter_sort),
                            discoverSortLabel(resources, filters.sort),
                            filters.sort != DiscoverSort.CATALOG
                        )
                    }
                    val expanded = openFilter == kind
                    DiscoverChip(
                        selected = active,
                        onClick = { openFilter = if (expanded) null else kind },
                        modifier = Modifier.focusRequester(chipRequesters.getValue(kind))
                    ) { contentColor ->
                        DiscoverFilterChipLabel(title, value, expanded, contentColor)
                    }
                }

            DiscoverChip(
                selected = hideWatched,
                onClick = {
                    closeFilter(returnFocus = false)
                    onToggleHideWatched()
                }
            ) { contentColor ->
                Text(
                    text = stringResource(R.string.catalog_see_all_hide_watched),
                    style = MaterialTheme.typography.labelLarge,
                    color = contentColor,
                    maxLines = 1
                )
            }
        }

        openFilter?.let { kind ->
            val choices: List<DiscoverChoice> = when (kind) {
                SeeAllFilterKind.GENRE -> buildList {
                    add(DiscoverChoice(allGenresLabel, selectedGenre == null) { onSelectGenre(null) })
                    genres.forEach { genre ->
                        add(
                            DiscoverChoice(
                                label = localizedGenreLabel(genre),
                                selected = genre.equals(selectedGenre, ignoreCase = true)
                            ) { onSelectGenre(genre) }
                        )
                    }
                }
                SeeAllFilterKind.YEAR -> (listOf<DiscoverYearRange?>(null) + yearRanges).map { range ->
                    DiscoverChoice(discoverYearLabel(resources, range), range == filters.yearRange) {
                        onSelectYearRange(range)
                    }
                }
                SeeAllFilterKind.RATING -> (listOf<Int?>(null) + DISCOVER_RATING_THRESHOLDS).map { rating ->
                    DiscoverChoice(discoverRatingLabel(resources, rating), rating == filters.minRating) {
                        onSelectMinRating(rating)
                    }
                }
                SeeAllFilterKind.SORT -> DiscoverSort.entries.map { sort ->
                    DiscoverChoice(discoverSortLabel(resources, sort), sort == filters.sort) { onSelectSort(sort) }
                }
            }
            key(kind) {
                DiscoverChoiceRow(
                    choices = choices,
                    focusRequester = optionFocusRequester,
                    onChoose = { choice ->
                        closeFilter(returnFocus = true)
                        choice.onSelect()
                    },
                    onFocusLeft = { if (openFilter == kind) openFilter = null }
                )
            }
            LaunchedEffect(kind) {
                optionFocusRequester.requestFocusAfterFrames(frames = 1)
            }
        }
    }
}
