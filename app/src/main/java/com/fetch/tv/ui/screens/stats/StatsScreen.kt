package com.fetch.tv.ui.screens.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.data.website.WebsiteDetailedStats
import com.fetch.tv.data.website.WebsiteGenreCount
import com.fetch.tv.data.website.WebsiteMonthlyCount
import com.fetch.tv.data.website.WebsiteRatedTitle
import com.fetch.tv.data.website.WebsiteWatchStats
import com.fetch.tv.data.website.WebsiteYearInReview
import com.fetch.tv.ui.components.StateAction
import com.fetch.tv.ui.components.StateActionButton
import com.fetch.tv.ui.screens.library.SelectedCheckmark
import com.fetch.tv.ui.screens.library.libraryButtonShape
import com.fetch.tv.ui.screens.library.selectableButtonColors
import com.fetch.tv.ui.theme.FetchTheme
import com.fetch.tv.ui.util.localizedGenreLabel
import java.text.NumberFormat
import java.time.Month
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

/**
 * Watch statistics for the active profile, as the Fetch website's stats page shows them: totals
 * and streaks, badges, the last twelve months, top genres, top-rated titles and a year in review.
 * They're the website's own figures when it's the sync method, otherwise worked out on the TV
 * from the profile's watch history.
 */
@Composable
fun StatsScreen(
    onNavigateToDetail: (itemId: String, itemType: String) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToSearch: () -> Unit,
    viewModel: StatsViewModel = hiltViewModel()
) {
    val overview by viewModel.overview.collectAsStateWithLifecycle()
    val yearReview by viewModel.yearReview.collectAsStateWithLifecycle()
    val source by viewModel.source.collectAsStateWithLifecycle()
    val firstPanelFocus = remember { FocusRequester() }

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
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.lg)
    ) {
        item(key = "header") {
            InsightsHeader(
                title = stringResource(R.string.stats_title),
                eyebrow = when (source) {
                    StatsSource.WEBSITE -> stringResource(R.string.insights_source_eyebrow)
                    StatsSource.WATCH_HISTORY -> stringResource(R.string.calendar_source_watch_history)
                }
            )
        }

        when (val state = overview) {
            WebsiteReadState.Loading -> item(key = "loading") { InsightsLoading() }
            is WebsiteReadState.Loaded -> {
                val stats = state.value
                if (stats.isEmpty) {
                    item(key = "empty") {
                        InsightsMessage(
                            title = stringResource(R.string.stats_empty_title),
                            subtitle = stringResource(R.string.stats_empty_subtitle),
                            action = StateAction(stringResource(R.string.insights_find_something), onNavigateToSearch)
                        )
                    }
                } else {
                    if (stats.source == StatsSource.WATCH_HISTORY) {
                        item(key = "history_note") {
                            Text(
                                text = listOfNotNull(
                                    stringResource(R.string.stats_watch_history_note),
                                    if (stats.detailsPartial) stringResource(R.string.stats_watch_history_partial) else null
                                ).joinToString(" "),
                                style = FetchTheme.textStyles.metadata,
                                color = FetchTheme.colors.TextTertiary
                            )
                        }
                    }
                    item(key = "summary") {
                        LaunchedEffect(Unit) { runCatching { firstPanelFocus.requestFocus() } }
                        SummaryPanel(stats.totals, stats.detailed, firstPanelFocus)
                    }
                    item(key = "achievements") { AchievementsPanel(stats.totals, stats.detailed.longestStreak) }
                    if (stats.detailed.monthly.isNotEmpty()) {
                        item(key = "monthly") { MonthlyActivityPanel(stats.detailed.monthly) }
                    }
                    if (stats.detailed.genreDistribution.isNotEmpty()) {
                        item(key = "genres") { GenresPanel(stats.detailed.genreDistribution) }
                    }
                    if (stats.detailed.topRated.isNotEmpty()) {
                        item(key = "top_rated") {
                            PosterShelf(
                                title = stringResource(R.string.stats_top_rated),
                                titles = stats.detailed.topRated,
                                personalRatings = false,
                                onOpen = onNavigateToDetail
                            )
                        }
                    }
                    item(key = "year_in_review") {
                        YearInReviewSection(
                            years = viewModel.yearOptions,
                            review = yearReview,
                            onSelectYear = viewModel::selectYear,
                            onRetryYear = viewModel::retryYear,
                            onOpen = onNavigateToDetail
                        )
                    }
                }
            }
            else -> item(key = "unavailable") {
                WebsiteUnavailable(
                    state = state,
                    notPairedTitle = stringResource(R.string.stats_not_paired_title),
                    failedMessage = stringResource(
                        when (source) {
                            StatsSource.WEBSITE -> R.string.stats_failed
                            StatsSource.WATCH_HISTORY -> R.string.stats_failed_watch_history
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
private fun rememberNumberFormat(): NumberFormat {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) { NumberFormat.getIntegerInstance(locale) }
}

@Composable
private fun SummaryPanel(totals: WebsiteWatchStats, detailed: WebsiteDetailedStats, focusRequester: FocusRequester) {
    val numbers = rememberNumberFormat()
    val tiles = listOf(
        stringResource(R.string.stats_movies) to numbers.format(totals.totalMovies),
        stringResource(R.string.stats_episodes) to numbers.format(totals.totalEpisodes),
        stringResource(R.string.stats_current_streak) to
            pluralStringResource(R.plurals.stats_days_value, detailed.currentStreak, detailed.currentStreak),
        stringResource(R.string.stats_longest_streak) to
            pluralStringResource(R.plurals.stats_days_value, detailed.longestStreak, detailed.longestStreak)
    )
    TileRowPanel(tiles, focusRequester = focusRequester)
}

@Composable
private fun TileRowPanel(tiles: List<Pair<String, String>>, title: String? = null, focusRequester: FocusRequester? = null) {
    val tileDescriptions = tiles.map { (label, value) -> stringResource(R.string.stats_tile_description, label, value) }
    InsightPanel(
        description = (listOfNotNull(title) + tileDescriptions).joinToString(". "),
        focusRequester = focusRequester
    ) {
        if (title != null) PanelTitle(title)
        TileRow(tiles)
    }
}

@Composable
private fun TileRow(tiles: List<Pair<String, String>>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)
    ) {
        tiles.forEach { (label, value) -> StatTile(label, value, modifier = Modifier.weight(1f)) }
    }
}

@Composable
private fun milestoneLabel(milestone: Milestone): String {
    val numbers = rememberNumberFormat()
    val count = milestone.threshold
    return when (milestone.kind) {
        MilestoneKind.MOVIES -> pluralStringResource(R.plurals.stats_badge_movies, count, count)
        MilestoneKind.EPISODES -> pluralStringResource(R.plurals.stats_badge_episodes, count, count)
        MilestoneKind.STREAK -> pluralStringResource(R.plurals.stats_badge_streak, count, count)
    }.replace(count.toString(), numbers.format(count))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AchievementsPanel(totals: WebsiteWatchStats, longestStreak: Int) {
    val summary = remember(totals, longestStreak) {
        milestoneSummary(totals.totalMovies, totals.totalEpisodes, longestStreak)
    }
    if (summary.earned.isEmpty() && summary.upcoming.isEmpty()) return
    val earned = summary.earned.map { milestoneLabel(it) }
    val upcoming = summary.upcoming.map { progress ->
        stringResource(
            R.string.stats_badge_progress,
            milestoneLabel(progress.milestone),
            progress.value,
            progress.milestone.threshold
        )
    }
    val noBadges = stringResource(R.string.stats_no_badges)
    val title = stringResource(R.string.stats_achievements)
    val description = listOfNotNull(
        title,
        if (earned.isEmpty()) noBadges else stringResource(R.string.stats_earned_badges_label, earned.joinToString(", ")),
        upcoming.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.stats_next_badges_label, it.joinToString(", ")) }
    ).joinToString(". ")

    InsightPanel(description = description) {
        PanelTitle(title)
        if (earned.isEmpty()) {
            Text(text = noBadges, style = FetchTheme.textStyles.body, color = FetchTheme.colors.TextSecondary)
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)
            ) {
                earned.forEach { InsightChip(it, emphasized = true) }
            }
        }
        if (upcoming.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)
            ) {
                upcoming.forEach { InsightChip(it) }
            }
        }
    }
}

private val ChartHeight = 140.dp

@Composable
private fun MonthlyActivityPanel(monthly: List<WebsiteMonthlyCount>) {
    val locale = LocalConfiguration.current.locales[0]
    val numbers = rememberNumberFormat()
    val max = remember(monthly) { monthly.maxOf { it.total }.coerceAtLeast(1) }
    val comparison = remember(monthly) { monthOverMonth(monthly) }
    val movieColor = FetchTheme.colors.Accent
    val episodeColor = FetchTheme.colors.TextSecondary
    val longMonth = remember(locale) { DateTimeFormatter.ofPattern("LLLL yyyy", locale) }

    val title = stringResource(R.string.stats_monthly_activity)
    val comparisonText = comparison?.let { c ->
        val signed = (if (c.diff > 0) "+" else "") + numbers.format(c.diff)
        if (c.percent != null) {
            stringResource(R.string.stats_vs_last_month_percent, signed, (if (c.percent > 0) "+" else "") + c.percent)
        } else {
            stringResource(R.string.stats_vs_last_month, signed)
        }
    }
    val monthDescriptions = monthly.map { m ->
        val name = monthName(m.month, longMonth) ?: m.month
        stringResource(
            R.string.stats_month_description,
            name,
            pluralStringResource(R.plurals.stats_badge_movies, m.movies, m.movies),
            pluralStringResource(R.plurals.stats_badge_episodes, m.episodes, m.episodes)
        )
    }

    InsightPanel(description = (listOfNotNull(title, comparisonText) + monthDescriptions).joinToString(". ")) {
        PanelTitle(title) {
            if (comparisonText != null) {
                Text(
                    text = comparisonText,
                    style = FetchTheme.textStyles.metadata,
                    color = when {
                        (comparison?.diff ?: 0) > 0 -> FetchTheme.colors.Success
                        (comparison?.diff ?: 0) < 0 -> FetchTheme.colors.Error
                        else -> FetchTheme.colors.TextSecondary
                    }
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm),
            verticalAlignment = Alignment.Bottom
        ) {
            monthly.forEach { m ->
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xs)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().height(ChartHeight + 20.dp),
                        verticalArrangement = Arrangement.Bottom,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (m.total > 0) {
                            Text(
                                text = numbers.format(m.total),
                                style = FetchTheme.textStyles.metadata,
                                color = FetchTheme.colors.TextTertiary,
                                maxLines = 1
                            )
                            if (m.episodes > 0) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(ChartHeight * barFraction(m.episodes, max))
                                        .clip(RoundedCornerShape(topStart = FetchTheme.radii.xs, topEnd = FetchTheme.radii.xs))
                                        .background(episodeColor)
                                )
                            }
                            if (m.movies > 0) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(ChartHeight * barFraction(m.movies, max))
                                        .clip(
                                            if (m.episodes > 0) RoundedCornerShape(bottomStart = FetchTheme.radii.xs, bottomEnd = FetchTheme.radii.xs)
                                            else RoundedCornerShape(FetchTheme.radii.xs)
                                        )
                                        .background(movieColor)
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(FetchTheme.radii.xxs))
                                    .background(FetchTheme.colors.Divider)
                            )
                        }
                    }
                    Text(
                        text = monthIndexOf(m.month)?.let { Month.of(it + 1).getDisplayName(TextStyle.SHORT_STANDALONE, locale) } ?: m.month,
                        style = FetchTheme.textStyles.metadata,
                        color = FetchTheme.colors.TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Clip
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.lg)) {
            LegendItem(movieColor, stringResource(R.string.stats_movies))
            LegendItem(episodeColor, stringResource(R.string.stats_episodes))
        }
    }
}

private fun monthName(month: String, formatter: DateTimeFormatter): String? {
    val index = monthIndexOf(month) ?: return null
    val year = yearOf(month) ?: return null
    return YearMonth.of(year, index + 1).format(formatter)
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(12.dp).clip(RoundedCornerShape(FetchTheme.radii.xxs)).background(color))
        Spacer(modifier = Modifier.width(FetchTheme.spacing.sm))
        Text(text = label, style = FetchTheme.textStyles.metadata, color = FetchTheme.colors.TextSecondary)
    }
}

@Composable
private fun GenresPanel(genres: List<WebsiteGenreCount>) {
    val numbers = rememberNumberFormat()
    val max = genres.maxOf { it.count }.coerceAtLeast(1)
    val title = stringResource(R.string.stats_top_genres)
    val labels = genres.map { localizedGenreLabel(it.genre) }
    val descriptions = genres.mapIndexed { i, g -> stringResource(R.string.stats_genre_description, labels[i], g.count) }
    InsightPanel(description = (listOf(title) + descriptions).joinToString(". ")) {
        PanelTitle(title)
        genres.forEachIndexed { index, genre ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)
            ) {
                Text(
                    text = labels[index],
                    style = FetchTheme.textStyles.metadata,
                    color = FetchTheme.colors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(160.dp)
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(50))
                        .background(FetchTheme.colors.Surface)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(barFraction(genre.count, max))
                            .clip(RoundedCornerShape(50))
                            .background(FetchTheme.colors.Accent)
                    )
                }
                Text(
                    text = numbers.format(genre.count),
                    style = FetchTheme.textStyles.metadata,
                    color = FetchTheme.colors.TextTertiary,
                    modifier = Modifier.width(48.dp)
                )
            }
        }
    }
}

@Composable
private fun typeLabel(type: String): String? = when (type.lowercase()) {
    "movie" -> stringResource(R.string.stats_type_movie)
    "series", "show", "tv" -> stringResource(R.string.stats_type_series)
    else -> null
}

/** Opens a title on its detail page; a rated series opens as the series. */
private fun detailType(type: String): String = if (type.lowercase() == "movie") "movie" else "series"

/**
 * A row of posters. [personalRatings] says whose rating each carries: the profile's own (shown
 * as stars out of five) or the community score out of ten.
 */
@Composable
private fun PosterShelf(
    title: String,
    titles: List<WebsiteRatedTitle>,
    personalRatings: Boolean,
    onOpen: (String, String) -> Unit,
    showTitle: Boolean = true
) {
    Column(verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)) {
        if (showTitle) PanelTitle(title)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md),
            contentPadding = PaddingValues(vertical = FetchTheme.spacing.xs)
        ) {
            items(titles, key = { "${it.type}:${it.imdbId}" }) { item ->
                val rating = item.rating
                val badge = rating?.let { if (personalRatings) "★ ${formatStars(it)}" else formatTenPointRating(it) }
                val badgeDescription = rating?.let {
                    if (personalRatings) stringResource(R.string.stats_rated_out_of_five, formatStars(it))
                    else stringResource(R.string.stats_rated_out_of_ten, formatTenPointRating(it))
                }
                InsightPoster(
                    poster = item.poster,
                    name = item.name ?: item.imdbId,
                    subtitle = typeLabel(item.type),
                    badge = badge,
                    badgeDescription = badgeDescription,
                    onClick = { onOpen(item.imdbId, detailType(item.type)) }
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun YearInReviewSection(
    years: List<Int>,
    review: YearReviewUiState,
    onSelectYear: (Int) -> Unit,
    onRetryYear: () -> Unit,
    onOpen: (String, String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)) {
        PanelTitle(stringResource(R.string.stats_year_in_review))
        Row(horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)) {
            years.forEach { year ->
                val isSelected = year == review.year
                Button(
                    onClick = { onSelectYear(year) },
                    modifier = Modifier.semantics { selected = isSelected },
                    shape = libraryButtonShape(),
                    colors = selectableButtonColors(isSelected)
                ) {
                    if (isSelected) SelectedCheckmark()
                    // A year, not a quantity: no grouping separator.
                    Text(year.toString())
                }
            }
        }
        when (val state = review.state) {
            WebsiteReadState.Loading -> Text(
                text = stringResource(R.string.insights_loading),
                style = FetchTheme.textStyles.body,
                color = FetchTheme.colors.TextSecondary
            )
            is WebsiteReadState.Loaded -> YearInReviewContent(state.value, onOpen)
            else -> Row(
                horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.stats_year_failed),
                    style = FetchTheme.textStyles.body,
                    color = FetchTheme.colors.Error
                )
                StateActionButton(StateAction(stringResource(R.string.action_retry), onRetryYear))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun YearInReviewContent(review: WebsiteYearInReview, onOpen: (String, String) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val numbers = rememberNumberFormat()
    val busiest = review.busiestMonth
        ?.takeIf { it in 0..11 }
        ?.let { Month.of(it + 1).getDisplayName(TextStyle.FULL_STANDALONE, locale) }
        ?: "—"
    TileRowPanel(
        tiles = listOf(
            stringResource(R.string.stats_movies_watched) to numbers.format(review.totalMovies),
            stringResource(R.string.stats_episodes_watched) to numbers.format(review.totalEpisodes),
            stringResource(R.string.stats_hours_watched) to numbers.format(Math.round(review.totalRuntimeMinutes / 60.0)),
            stringResource(R.string.stats_busiest_month) to busiest.replaceFirstChar { it.titlecase(locale) }
        )
    )
    if (review.topGenres.isNotEmpty()) {
        val title = stringResource(R.string.stats_top_genres)
        val chips = review.topGenres.map { "${localizedGenreLabel(it.genre)} ${numbers.format(it.count)}" }
        InsightPanel(description = (listOf(title) + chips).joinToString(". ")) {
            PanelTitle(title)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)
            ) {
                chips.forEach { InsightChip(it) }
            }
        }
    }
    if (review.topRated.isNotEmpty()) {
        PosterShelf(
            title = stringResource(R.string.stats_top_rated_picks),
            titles = review.topRated,
            personalRatings = true,
            onOpen = onOpen
        )
    }
}
