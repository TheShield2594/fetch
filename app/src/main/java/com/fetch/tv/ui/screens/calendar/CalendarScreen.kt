package com.fetch.tv.ui.screens.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.fetch.tv.R
import com.fetch.tv.data.website.WebsiteCalendarEntry
import com.fetch.tv.ui.components.MonochromePosterPlaceholder
import com.fetch.tv.ui.components.StateAction
import com.fetch.tv.ui.screens.library.SelectedCheckmark
import com.fetch.tv.ui.screens.library.libraryButtonShape
import com.fetch.tv.ui.screens.library.selectableButtonColors
import com.fetch.tv.ui.screens.stats.InsightsHeader
import com.fetch.tv.ui.screens.stats.InsightsMessage
import com.fetch.tv.ui.screens.stats.WebsiteReadState
import com.fetch.tv.ui.screens.stats.WebsiteUnavailable
import com.fetch.tv.ui.theme.FetchTheme
import java.time.format.DateTimeFormatter

/**
 * Upcoming episodes of the series the active profile has started: the Fetch website's calendar
 * when that's the sync method, otherwise worked out from the profile's watch history and TMDB.
 * An agenda of the next 14 to 90 days, one heading per day. Choosing an episode opens its series.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CalendarScreen(
    onNavigateToDetail: (itemId: String, itemType: String) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToSearch: () -> Unit,
    viewModel: CalendarViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val rangeFocus = remember { FocusRequester() }
    val showRanges = uiState.state is WebsiteReadState.Loaded || uiState.state == WebsiteReadState.Loading

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(FetchTheme.colors.Background),
        contentPadding = PaddingValues(
            start = FetchTheme.spacing.xxxl,
            end = FetchTheme.spacing.xxxl,
            top = FetchTheme.spacing.xl,
            bottom = FetchTheme.spacing.xxl
        ),
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)
    ) {
        item(key = "header") {
            InsightsHeader(
                title = stringResource(R.string.calendar_title),
                eyebrow = when (uiState.source) {
                    CalendarSource.WEBSITE -> stringResource(R.string.insights_source_eyebrow)
                    CalendarSource.WATCH_HISTORY -> stringResource(R.string.calendar_source_watch_history)
                }
            )
        }

        if (showRanges) {
            item(key = "ranges") {
                // The range buttons stay put while a new range loads, so focus has somewhere to be.
                LaunchedEffect(Unit) { runCatching { rangeFocus.requestFocus() } }
                Row(horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)) {
                    CalendarRanges.forEach { days ->
                        val isSelected = days == uiState.rangeDays
                        Button(
                            onClick = { viewModel.selectRange(days) },
                            modifier = Modifier
                                .then(if (isSelected) Modifier.focusRequester(rangeFocus) else Modifier)
                                .semantics { selected = isSelected },
                            shape = libraryButtonShape(),
                            colors = selectableButtonColors(isSelected)
                        ) {
                            if (isSelected) SelectedCheckmark()
                            Text(pluralStringResource(R.plurals.calendar_range_days, days, days))
                        }
                    }
                }
            }
        }

        when (val state = uiState.state) {
            WebsiteReadState.Loading -> item(key = "loading") {
                Text(
                    text = stringResource(R.string.insights_loading),
                    style = FetchTheme.textStyles.body,
                    color = FetchTheme.colors.TextSecondary,
                    modifier = Modifier.padding(vertical = FetchTheme.spacing.lg)
                )
            }
            is WebsiteReadState.Loaded -> {
                val days = state.value
                if (days.isEmpty()) {
                    item(key = "empty") {
                        InsightsMessage(
                            title = pluralStringResource(R.plurals.calendar_empty_title, uiState.rangeDays, uiState.rangeDays),
                            subtitle = stringResource(
                                when (uiState.source) {
                                    CalendarSource.WEBSITE -> R.string.calendar_empty_subtitle
                                    CalendarSource.WATCH_HISTORY -> R.string.calendar_empty_subtitle_watch_history
                                }
                            ),
                            action = StateAction(stringResource(R.string.calendar_find_series), onNavigateToSearch)
                        )
                    }
                } else {
                    days.forEach { day ->
                        val dayKey = day.date?.toString() ?: "undated"
                        item(key = "day:$dayKey") { DayHeading(day) }
                        items(day.entries, key = { "entry:$dayKey:${it.seriesImdbId}:${it.season}:${it.episode}" }) { entry ->
                            CalendarEntryRow(entry, onClick = { onNavigateToDetail(entry.seriesImdbId, "series") })
                        }
                    }
                }
            }
            else -> item(key = "unavailable") {
                WebsiteUnavailable(
                    state = state,
                    notPairedTitle = stringResource(R.string.calendar_not_paired_title),
                    failedMessage = stringResource(
                        when (uiState.source) {
                            CalendarSource.WEBSITE -> R.string.calendar_failed
                            CalendarSource.WATCH_HISTORY -> R.string.calendar_failed_watch_history
                        }
                    ),
                    onOpenSettings = onNavigateToSettings,
                    onRetry = viewModel::retry
                )
            }
        }
    }
}

@Composable
private fun DayHeading(day: CalendarDay) {
    val locale = LocalConfiguration.current.locales[0]
    // Weekday, day and month in the locale's own order, without the year: "Monday, November 9".
    val formatter = remember(locale) {
        DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd"), locale)
    }
    val label = when {
        day.date == null -> stringResource(R.string.calendar_unknown_date)
        day.kind == CalendarDayKind.TODAY -> stringResource(R.string.calendar_today)
        day.kind == CalendarDayKind.TOMORROW -> stringResource(R.string.calendar_tomorrow)
        else -> day.date.format(formatter)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = FetchTheme.spacing.md)
            .semantics(mergeDescendants = true) { heading() },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        Text(text = label, style = FetchTheme.textStyles.cardTitle, color = FetchTheme.colors.TextPrimary)
        Text(
            text = pluralStringResource(R.plurals.calendar_day_count, day.entries.size, day.entries.size),
            style = FetchTheme.textStyles.metadata,
            color = FetchTheme.colors.TextTertiary
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CalendarEntryRow(entry: WebsiteCalendarEntry, onClick: () -> Unit) {
    val shape = RoundedCornerShape(FetchTheme.radii.md)
    val code = episodeCode(entry.season, entry.episode)
    val episodeLine = entry.episodeName?.takeIf { it.isNotBlank() }
        ?.let { stringResource(R.string.calendar_episode_with_name, code, it) }
        ?: code
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = FetchTheme.colors.BackgroundCard,
            contentColor = FetchTheme.colors.TextPrimary,
            focusedContainerColor = FetchTheme.colors.Highlight,
            focusedContentColor = FetchTheme.colors.OnHighlight
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f)
    ) {
        Row(
            modifier = Modifier.padding(FetchTheme.spacing.md),
            horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(width = 56.dp, height = 84.dp)
                    .clip(RoundedCornerShape(FetchTheme.radii.sm))
                    .background(FetchTheme.colors.Surface)
            ) {
                if (!entry.poster.isNullOrBlank()) {
                    AsyncImage(
                        model = entry.poster,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    MonochromePosterPlaceholder()
                }
            }
            // Text colours come from the surface, so they flip with it when the row is focused.
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xxs)) {
                Text(
                    text = entry.seriesName,
                    style = FetchTheme.textStyles.cardTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = episodeLine,
                    style = FetchTheme.textStyles.metadata,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                entry.overview?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = FetchTheme.textStyles.bodyCompact,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = FetchTheme.spacing.xxs)
                    )
                }
            }
        }
    }
}
