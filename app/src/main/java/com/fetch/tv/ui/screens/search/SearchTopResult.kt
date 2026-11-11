package com.fetch.tv.ui.screens.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.fetch.tv.R
import com.fetch.tv.core.streams.SpecLine
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.ui.components.FetchBadge
import com.fetch.tv.ui.components.FetchBadgeTone
import com.fetch.tv.ui.components.FetchChip
import com.fetch.tv.ui.components.FetchMediaCard
import com.fetch.tv.ui.components.MonochromePosterPlaceholder
import com.fetch.tv.ui.components.SpecStrip
import com.fetch.tv.ui.theme.FetchTheme

/**
 * The best match across every addon (roadmap D3), above the merged rows: poster, title, the spec
 * line (year, type, rating) and which addons returned it. It takes focus on the first Down from
 * the search field, so the most likely title is one press away while the keyboard is still up.
 */
@Composable
internal fun SearchTopResultCard(
    result: MergedSearchResult,
    focusRequester: FocusRequester,
    upFocusRequester: FocusRequester?,
    onFocused: () -> Unit,
    onClick: (MetaPreview) -> Unit,
    onLongClick: (MetaPreview) -> Unit,
    modifier: Modifier = Modifier
) {
    val item = result.item
    // One title, so the singular; other types are named as their row is.
    val typeLabel = when (val typeKey = SearchResultMerger.typeKey(item)) {
        "movie" -> stringResource(R.string.type_movie)
        "series" -> stringResource(R.string.type_series)
        "other" -> stringResource(R.string.search_results_other)
        else -> addonTypeDisplayName(typeKey)
    }
    val specLine = remember(item, typeLabel) { topResultSpecLine(item, typeLabel) }
    val sources = topResultSources(result.addonNames)
    val topResultLabel = stringResource(R.string.search_top_result)
    val shape = FetchTheme.shapes.posterCard
    FetchMediaCard(
        onClick = { onClick(item) },
        shape = shape,
        modifier = modifier
            .fillMaxWidth()
            .height(TOP_RESULT_HEIGHT)
            .focusRequester(focusRequester)
            .focusProperties { if (upFocusRequester != null) up = upFocusRequester }
            .onFocusChanged { if (it.isFocused) onFocused() }
            .semantics { contentDescription = "$topResultLabel: ${item.name}" }
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(POSTER_ASPECT)
                    .clip(shape)
            ) {
                val image = item.poster ?: item.landscapePoster ?: item.background
                if (image.isNullOrBlank()) {
                    MonochromePosterPlaceholder()
                } else {
                    AsyncImage(
                        model = image,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(FetchTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)
            ) {
                FetchBadge(text = topResultLabel, tone = FetchBadgeTone.Accent)
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.headlineSmall,
                    color = FetchTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                specLine?.let { SpecStrip(line = it) }
                item.description?.takeIf { it.isNotBlank() }?.let { description ->
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = FetchTheme.colors.TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                sources?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        color = FetchTheme.colors.TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** Year, type and rating, in the spec style the detail page and sources use. */
internal fun topResultSpecLine(item: MetaPreview, typeLabel: String): SpecLine? {
    val year = item.releaseInfo?.trim()?.takeIf { it.isNotEmpty() }
    val rating = item.imdbRating?.takeIf { it > 0f }?.let { "★ %.1f".format(java.util.Locale.ROOT, it) }
    return SpecLine.of(
        listOfNotNull(year, typeLabel, rating, item.runtime?.takeIf { it.isNotBlank() }),
        isCached = false,
        cachedLabel = ""
    )
}

@Composable
private fun topResultSources(addonNames: List<String>): String? {
    val names = addonNames.filter { it.isNotBlank() }.distinct()
    if (names.isEmpty()) return null
    return if (names.size <= MAX_NAMED_SOURCES) {
        stringResource(R.string.search_top_result_sources, names.joinToString(", "))
    } else {
        stringResource(
            R.string.search_top_result_sources_more,
            names.take(MAX_NAMED_SOURCES).joinToString(", "),
            names.size - MAX_NAMED_SOURCES
        )
    }
}

/**
 * The type filters over merged results: All, Movies, Series (each only when that type is in the
 * results) and In my library (when the library has anything). Selecting one narrows the rows and
 * the top result.
 */
@Composable
internal fun SearchTypeFilterRow(
    selected: SearchTypeFilter,
    available: List<SearchTypeFilter>,
    onSelect: (SearchTypeFilter) -> Unit,
    upFocusRequester: FocusRequester?,
    modifier: Modifier = Modifier
) {
    val description = stringResource(R.string.search_filters_label)
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(available, key = { it.name }) { filter ->
            FetchChip(
                label = stringResource(filter.labelRes()),
                selected = filter == selected,
                onClick = { onSelect(filter) },
                modifier = Modifier.focusProperties { if (upFocusRequester != null) up = upFocusRequester }
            )
        }
    }
}

/** Which filters are worth offering for [results] (unfiltered) and a library that [hasLibrary]. */
internal fun availableTypeFilters(typeKeys: Set<String>, hasLibrary: Boolean): List<SearchTypeFilter> =
    buildList {
        add(SearchTypeFilter.ALL)
        if ("movie" in typeKeys) add(SearchTypeFilter.MOVIES)
        if ("series" in typeKeys) add(SearchTypeFilter.SERIES)
        if (hasLibrary) add(SearchTypeFilter.IN_LIBRARY)
    }

private fun SearchTypeFilter.labelRes(): Int = when (this) {
    SearchTypeFilter.ALL -> R.string.search_filter_all
    SearchTypeFilter.MOVIES -> R.string.search_filter_movies
    SearchTypeFilter.SERIES -> R.string.search_filter_series
    SearchTypeFilter.IN_LIBRARY -> R.string.search_filter_in_library
}

private const val MAX_NAMED_SOURCES = 2
private const val POSTER_ASPECT = 2f / 3f
private val TOP_RESULT_HEIGHT = 216.dp
