package com.fetch.tv.ui.screens.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.ui.components.EmptyScreenState
import com.fetch.tv.ui.components.GridContentCard
import com.fetch.tv.ui.components.PosterCardStyle
import com.fetch.tv.ui.components.PosterGridSkeleton
import com.fetch.tv.ui.theme.FetchTheme
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** Focus keys of the activity tabs, kept apart from the Saved grid's "type:id" keys. */
internal fun inProgressFocusKey(item: LibraryActivityItem) = "progress:${item.key}"
internal fun historyFocusKey(item: LibraryActivityItem) = "history:${item.key}"

/** What an activity card does; shared by the In progress and History tabs. */
internal class LibraryActivityCardActions(
    val focusRequesterFor: (String) -> FocusRequester?,
    val onFocused: (key: String, item: LibraryActivityItem) -> Unit,
    val onClick: (key: String, item: LibraryActivityItem) -> Unit,
    val onLongPress: (key: String, preview: MetaPreview, item: LibraryActivityItem) -> Unit,
    val isWatched: (LibraryActivityItem) -> Boolean
)

internal fun LazyGridScope.libraryInProgressContent(
    items: List<LibraryActivityItem>?,
    posterCardStyle: PosterCardStyle,
    actions: LibraryActivityCardActions,
    onFindSomething: () -> Unit
) {
    when {
        items == null -> item(span = { GridItemSpan(maxLineSpan) }) {
            PosterGridSkeleton(cardWidth = posterCardStyle.width, cardHeight = posterCardStyle.height)
        }
        items.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
            EmptyScreenState(
                title = stringResource(R.string.library_in_progress_empty_title),
                subtitle = stringResource(R.string.library_in_progress_empty_subtitle),
                icon = Icons.Default.PlayCircleOutline,
                height = 300.dp,
                modifier = Modifier.padding(bottom = FetchTheme.spacing.xxl),
                action = com.fetch.tv.ui.components.StateAction(
                    stringResource(R.string.library_action_search_titles),
                    onFindSomething
                )
            )
        }
        else -> items(items, key = ::inProgressFocusKey) { item ->
            val detail = activityDetail(item, showProgress = true)
            ActivityCard(inProgressFocusKey(item), item, detail, posterCardStyle, actions)
        }
    }
}

internal fun LazyGridScope.libraryHistoryContent(
    months: List<LibraryHistoryMonth>?,
    posterCardStyle: PosterCardStyle,
    actions: LibraryActivityCardActions,
    onFindSomething: () -> Unit
) {
    when {
        months == null -> item(span = { GridItemSpan(maxLineSpan) }) {
            PosterGridSkeleton(cardWidth = posterCardStyle.width, cardHeight = posterCardStyle.height)
        }
        months.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
            EmptyScreenState(
                title = stringResource(R.string.library_history_empty_title),
                subtitle = stringResource(R.string.library_history_empty_subtitle),
                icon = Icons.Default.History,
                height = 300.dp,
                modifier = Modifier.padding(bottom = FetchTheme.spacing.xxl),
                action = com.fetch.tv.ui.components.StateAction(
                    stringResource(R.string.library_action_search_titles),
                    onFindSomething
                )
            )
        }
        else -> months.forEach { month ->
            item(key = "history-month:${month.month}", span = { GridItemSpan(maxLineSpan) }, contentType = "month") {
                HistoryMonthHeader(month.month)
            }
            items(month.items, key = ::historyFocusKey, contentType = { "card" }) { item ->
                val detail = activityDetail(item, showProgress = false)
                ActivityCard(historyFocusKey(item), item, detail, posterCardStyle, actions)
            }
        }
    }
}

@Composable
private fun ActivityCard(
    focusKey: String,
    item: LibraryActivityItem,
    detail: String?,
    posterCardStyle: PosterCardStyle,
    actions: LibraryActivityCardActions
) {
    val preview = remember(item, detail) { item.toMetaPreview(detail) }
    GridContentCard(
        item = preview,
        posterCardStyle = posterCardStyle,
        isWatched = actions.isWatched(item),
        focusRequester = actions.focusRequesterFor(focusKey),
        showLabel = true,
        showReleaseInfo = detail != null,
        onFocused = { actions.onFocused(focusKey, item) },
        onClick = { actions.onClick(focusKey, item) },
        onLongPress = { actions.onLongPress(focusKey, preview, item) }
    )
}

/** The card's second line: the episode for a series, and on In progress how far in. */
@Composable
private fun activityDetail(item: LibraryActivityItem, showProgress: Boolean): String? {
    val episode = if (item.isSeries && item.season != null && item.episode != null) {
        stringResource(R.string.season_episode_format, item.season, item.episode)
    } else {
        null
    }
    val progress = item.progressPercent
        ?.takeIf { showProgress && it > 0 }
        ?.let { stringResource(R.string.library_in_progress_percent, it) }
    return listOfNotNull(episode, progress).joinToString(" · ").ifBlank { null }
}

@Composable
private fun HistoryMonthHeader(month: YearMonth) {
    val locale = LocalConfiguration.current.locales[0]
    // "LLLL" is the stand-alone month name, which some languages inflect differently from "MMMM".
    val label = remember(month, locale) {
        month.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = FetchTheme.spacing.md)
    ) {
        Text(
            text = label,
            style = FetchTheme.textStyles.eyebrow,
            color = FetchTheme.colors.TextSecondary,
            modifier = Modifier.semantics { heading() }
        )
    }
}
