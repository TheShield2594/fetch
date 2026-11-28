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
import com.fetch.tv.ui.components.browseGridItemMotion
import com.fetch.tv.ui.components.formatContinueWatchingProgressLabel
import com.fetch.tv.ui.theme.FetchTheme
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** The height of the Library's empty and loading states, on every tab: the browse screens' one (#850). */
internal val LibraryEmptyStateHeight = com.fetch.tv.ui.components.BrowseDefaults.EmptyStateHeight

/** Focus keys of the activity tabs, kept apart from the Saved grid's "type:id" keys. */
internal fun inProgressFocusKey(item: LibraryActivityItem) = "progress:${item.key}"
internal fun historyFocusKey(item: LibraryActivityItem) = "history:${item.key}"

/** What an activity card does; shared by the In progress and History tabs. */
internal class LibraryActivityCardActions(
    val focusRequesterFor: (String) -> FocusRequester?,
    val onFocused: (key: String, item: LibraryActivityItem) -> Unit,
    val onClick: (key: String, item: LibraryActivityItem) -> Unit,
    val onLongPress: (key: String, preview: MetaPreview, item: LibraryActivityItem) -> Unit,
    val isWatched: (LibraryActivityItem) -> Boolean,
    // Selection mode (#653): null outside it, else whether the card is chosen.
    val selectedFor: (key: String) -> Boolean? = { null },
    // The new-episode badge's text and whether it's a new season (#653).
    val newEpisodeBadgeFor: (LibraryActivityItem) -> Pair<String, Boolean>? = { null },
    // Saved progress 0..1 for the bar along the card's bottom edge (#854).
    val progressFor: (LibraryActivityItem) -> Float? = { null },
    // The ready chip's text, when the title starts at once (#854).
    val readyLabelFor: (LibraryActivityItem) -> String? = { null },
    // Play/Pause on the card (#856); null when it isn't offered.
    val onPlay: ((key: String, item: LibraryActivityItem) -> Unit)? = null,
    // Drawn on every card, after the grid motion: the screen's content fade (#853).
    val cardModifier: Modifier = Modifier
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
                height = LibraryEmptyStateHeight,
                modifier = Modifier.padding(bottom = FetchTheme.spacing.xxl),
                action = com.fetch.tv.ui.components.StateAction(
                    stringResource(R.string.library_action_search_titles),
                    onFindSomething
                )
            )
        }
        else -> items(items, key = ::inProgressFocusKey) { item ->
            val detail = activityDetail(item, showProgress = true)
            ActivityCard(inProgressFocusKey(item), item, detail, posterCardStyle, actions, browseGridItemMotion().then(actions.cardModifier))
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
                height = LibraryEmptyStateHeight,
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
                ActivityCard(historyFocusKey(item), item, detail, posterCardStyle, actions, browseGridItemMotion().then(actions.cardModifier))
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
    actions: LibraryActivityCardActions,
    modifier: Modifier = Modifier
) {
    val preview = remember(item, detail) { item.toMetaPreview(detail) }
    val selected = actions.selectedFor(focusKey)
    val badge = actions.newEpisodeBadgeFor(item)
    GridContentCard(
        item = preview,
        posterCardStyle = posterCardStyle,
        isWatched = actions.isWatched(item),
        focusRequester = actions.focusRequesterFor(focusKey),
        showLabel = true,
        showReleaseInfo = detail != null,
        onFocused = { actions.onFocused(focusKey, item) },
        onClick = { actions.onClick(focusKey, item) },
        // While choosing, OK only toggles the card; the options menu waits until selection ends.
        onLongPress = if (selected != null) null else ({ actions.onLongPress(focusKey, preview, item) }),
        selected = selected,
        newEpisodeBadge = badge?.first,
        newEpisodeBadgeIsSeason = badge?.second == true,
        progress = actions.progressFor(item),
        readyLabel = actions.readyLabelFor(item),
        onPlay = if (selected != null) null else actions.onPlay?.let { play -> { play(focusKey, item) } },
        modifier = modifier
    )
}

/**
 * The card's second line: the episode for a series, and on In progress how much is left ("38m
 * left"), as Continue Watching says it (#854); the percentage only when the length isn't known.
 */
@Composable
private fun activityDetail(item: LibraryActivityItem, showProgress: Boolean): String? {
    val episode = if (item.isSeries && item.season != null && item.episode != null) {
        stringResource(R.string.season_episode_format, item.season, item.episode)
    } else {
        null
    }
    val resumeLabel = stringResource(R.string.cw_resume)
    val percentLabel = stringResource(R.string.library_in_progress_percent)
    val hoursMinLeftLabel = stringResource(R.string.cw_hours_min_left)
    val minLeftLabel = stringResource(R.string.cw_min_left)
    val progress = item.resume?.takeIf { showProgress && (item.progressPercent ?: 0) > 0 }?.let { resume ->
        formatContinueWatchingProgressLabel(
            progress = resume,
            resumeLabel = resumeLabel,
            percentWatchedLabel = percentLabel,
            hoursMinLeftLabel = hoursMinLeftLabel,
            minLeftLabel = minLeftLabel
        ).takeIf { it != resumeLabel }
    } ?: item.progressPercent
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
