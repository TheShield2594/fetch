@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.fetch.tv.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.fetch.tv.R
import com.fetch.tv.ui.components.FetchChip
import com.fetch.tv.ui.theme.FetchSizes
import com.fetch.tv.ui.theme.FetchTheme
import com.fetch.tv.ui.util.StableList

/** Room the filter takes above the first row (#814): one ordinary control's height. */
internal val HOME_CONTENT_FILTER_BAR_HEIGHT = FetchSizes.tokens.controls.default

/**
 * All · Movies · Shows (· Anime) above Home's rows (#814). Up from the first row reaches it, and
 * Left/Right changes the filter as focus moves, so there is no OK to press. Focus comes in on the
 * chip in effect, never on whichever chip happens to sit above the focused card.
 */
@Composable
internal fun HomeContentFilterBar(
    filters: StableList<HomeContentFilter>,
    selected: HomeContentFilter,
    onSelected: (HomeContentFilter) -> Unit,
    modifier: Modifier = Modifier,
    /** False while Home is scrolled down and the bar is hidden: no chip takes focus then. */
    focusable: Boolean = true
) {
    val requesters = remember(filters) { filters.list.associateWith { FocusRequester() } }
    val latestSelected = rememberUpdatedState(selected)
    val description = stringResource(R.string.home_filter_description)
    Row(
        modifier = modifier
            .height(HOME_CONTENT_FILTER_BAR_HEIGHT)
            .semantics { contentDescription = description }
            .focusRestorer { requesters[latestSelected.value] ?: FocusRequester.Default },
        horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        filters.list.forEach { filter ->
            FetchChip(
                label = stringResource(filter.labelRes()),
                selected = filter == selected,
                onClick = { onSelected(filter) },
                modifier = Modifier
                    .focusProperties { canFocus = focusable }
                    .focusRequester(requesters.getValue(filter)),
                onFocusChanged = { focused -> if (focused) onSelected(filter) }
            )
        }
    }
}

private fun HomeContentFilter.labelRes(): Int = when (this) {
    HomeContentFilter.ALL -> R.string.home_filter_all
    HomeContentFilter.MOVIES -> R.string.home_filter_movies
    HomeContentFilter.SHOWS -> R.string.home_filter_shows
    HomeContentFilter.ANIME -> R.string.home_filter_anime
}
