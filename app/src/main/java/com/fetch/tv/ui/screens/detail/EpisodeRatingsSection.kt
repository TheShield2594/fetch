package com.fetch.tv.ui.screens.detail

import com.fetch.tv.ui.theme.FetchTheme

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.ui.res.stringResource
import com.fetch.tv.R
import com.fetch.tv.domain.model.Video
import com.fetch.tv.ui.theme.FetchContentColors
import com.fetch.tv.ui.theme.FetchPrimitives

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun EpisodeRatingsSection(
    episodes: List<Video>,
    ratings: Map<Pair<Int, Int>, Double>,
    isLoading: Boolean,
    error: String?,
    modifier: Modifier = Modifier,
    title: String = "Ratings",
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    firstItemFocusRequester: FocusRequester? = null,
    ratingsGridFocusRequester: FocusRequester? = null
) {
    val seasonNumbers = remember(episodes) {
        episodes
            .mapNotNull { it.season }
            .filter { it > 0 } // Never show specials season (S0)
            .distinct()
            .sorted()
    }
    val seasonSignature = remember(seasonNumbers) { seasonNumbers.joinToString(",") }
    val seasonFocusRequesters = remember(seasonNumbers) {
        seasonNumbers.associateWith { FocusRequester() }
    }
    val internalRatingsGridFocusRequester = remember { FocusRequester() }
    val effectiveRatingsGridFocusRequester = ratingsGridFocusRequester ?: internalRatingsGridFocusRequester
    val firstEpisodeRatingFocusRequester = remember { FocusRequester() }
    val defaultSeason = remember(seasonNumbers) {
        seasonNumbers.firstOrNull { it > 0 } ?: seasonNumbers.firstOrNull() ?: 0
    }
    var selectedSeason by rememberSaveable(seasonSignature) {
        mutableIntStateOf(defaultSeason)
    }

    LaunchedEffect(seasonNumbers, defaultSeason) {
        if (selectedSeason !in seasonNumbers) {
            selectedSeason = defaultSeason
        }
    }

    val episodesForSeason = remember(episodes, selectedSeason) {
        episodes
            .filter { it.season == selectedSeason && it.episode != null }
            .distinctBy { it.season to it.episode }
            .sortedBy { it.episode }
    }
    val defaultChipColor = FetchTheme.colors.BackgroundCard
    val defaultChipTextColor = FetchTheme.colors.TextSecondary
    val seasonRatings = remember(episodesForSeason, ratings) {
        episodesForSeason.mapNotNull { episode ->
            val season = episode.season ?: return@mapNotNull null
            val episodeNumber = episode.episode ?: return@mapNotNull null
            val rating = ratings[season to episodeNumber]
            val ratingText = rating?.let { String.format("%.1f", it) } ?: "—"
            val chipColor = rating?.let(::episodeRatingChipColor) ?: defaultChipColor
            val chipTextColor = rating?.let(::episodeRatingChipTextColor) ?: defaultChipTextColor
            EpisodeRatingChipUi(
                id = episode.id,
                seasonNumber = season,
                episodeNumber = episodeNumber,
                ratingText = ratingText,
                chipColor = chipColor,
                chipTextColor = chipTextColor
            )
        }
    }
    val hasTitle = title.isNotBlank()
    val currentUpFocusRequester by rememberUpdatedState(upFocusRequester)
    val currentDownFocusRequester by rememberUpdatedState(downFocusRequester)
    val selectedSeasonRequester = firstItemFocusRequester
        ?: seasonFocusRequesters[selectedSeason]
        ?: FocusRequester.Default

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = if (hasTitle) 14.dp else 6.dp, bottom = FetchTheme.spacing.sm)
    ) {
        if (hasTitle) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = FetchTheme.colors.TextPrimary,
                modifier = Modifier.padding(horizontal = FetchTheme.spacing.xxxl)
            )
        }

        when {
            isLoading -> {
                Text(
                    text = stringResource(R.string.ratings_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = FetchTheme.colors.TextSecondary,
                    modifier = Modifier.padding(horizontal = FetchTheme.spacing.xxxl, vertical = FetchTheme.spacing.md)
                )
            }
            error != null -> {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = FetchTheme.colors.TextSecondary,
                    modifier = Modifier.padding(horizontal = FetchTheme.spacing.xxxl, vertical = FetchTheme.spacing.md)
                )
            }
            seasonNumbers.isEmpty() -> {
                Text(
                    text = stringResource(R.string.ratings_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = FetchTheme.colors.TextSecondary,
                    modifier = Modifier.padding(horizontal = FetchTheme.spacing.xxxl, vertical = FetchTheme.spacing.md)
                )
            }
            else -> {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRestorer { selectedSeasonRequester }
                        .focusGroup(),
                    contentPadding = PaddingValues(horizontal = FetchTheme.spacing.xxxl, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(seasonNumbers, key = { it }) { season ->
                        val isSelected = season == selectedSeason
                        val focusRequester = if (isSelected) {
                            selectedSeasonRequester
                        } else {
                            seasonFocusRequesters.getValue(season)
                        }
                        var isFocused by remember { mutableStateOf(false) }

                        Card(
                            onClick = { selectedSeason = season },
                            modifier = Modifier
                                .focusRequester(focusRequester)
                                .focusProperties {
                                    val upRequester = currentUpFocusRequester
                                    if (upRequester != null) up = upRequester
                                    down = effectiveRatingsGridFocusRequester
                                }
                                .onFocusChanged { state ->
                                    isFocused = state.isFocused
                                    if (state.isFocused && selectedSeason != season) {
                                        selectedSeason = season
                                    }
                                },
                            shape = CardDefaults.shape(shape = RoundedCornerShape(FetchTheme.radii.md)),
                            colors = CardDefaults.colors(
                                containerColor = if (isSelected) {
                                    FetchTheme.colors.Highlight
                                } else {
                                    FetchTheme.colors.BackgroundCard
                                },
                                focusedContainerColor = FetchTheme.colors.Highlight
                            ),
                            border = CardDefaults.border(
                                focusedBorder = Border(
                                    border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs),
                                    shape = RoundedCornerShape(FetchTheme.radii.md)
                                )
                            ),
                            scale = CardDefaults.scale(focusedScale = 1f)
                        ) {
                            Text(
                                text = stringResource(R.string.ratings_season_label, season),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isSelected || isFocused) FetchTheme.colors.OnHighlight else FetchTheme.colors.TextPrimary,
                                modifier = Modifier.padding(horizontal = FetchTheme.spacing.md, vertical = FetchTheme.spacing.xs)
                            )
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.ratings_season_summary, selectedSeason, episodesForSeason.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = FetchTheme.colors.TextTertiary,
                    modifier = Modifier.padding(horizontal = FetchTheme.spacing.xxxl, vertical = FetchTheme.spacing.xxs)
                )

                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(effectiveRatingsGridFocusRequester)
                        .focusRestorer(firstEpisodeRatingFocusRequester)
                        .focusGroup(),
                    contentPadding = PaddingValues(horizontal = FetchTheme.spacing.xxxl, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(seasonRatings, key = { "${it.seasonNumber}:${it.episodeNumber}" }) { episodeRating ->
                        val isFirstEpisode = episodeRating == seasonRatings.firstOrNull()

                        Card(
                            onClick = { },
                            modifier = Modifier
                                .focusProperties {
                                    up = selectedSeasonRequester
                                    val downRequester = currentDownFocusRequester
                                    if (downRequester != null) down = downRequester
                                }
                                .then(
                                    if (isFirstEpisode) Modifier.focusRequester(firstEpisodeRatingFocusRequester) else Modifier
                                ),
                            shape = CardDefaults.shape(shape = RoundedCornerShape(FetchTheme.radii.md)),
                            colors = CardDefaults.colors(
                                containerColor = episodeRating.chipColor,
                                focusedContainerColor = episodeRating.chipColor
                            ),
                            border = CardDefaults.border(
                                focusedBorder = Border(
                                    border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs),
                                    shape = RoundedCornerShape(FetchTheme.radii.md)
                                )
                            ),
                            scale = CardDefaults.scale(focusedScale = 1.03f)
                        ) {
                            Column(
                                modifier = Modifier
                                    .size(width = 72.dp, height = 46.dp)
                                    .padding(horizontal = FetchTheme.spacing.sm, vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.ratings_episode_label, episodeRating.episodeNumber),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = episodeRating.chipTextColor
                                )
                                Text(
                                    text = episodeRating.ratingText,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = episodeRating.chipTextColor
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The heatmap fill for an episode rated [value]. */
internal fun episodeRatingChipColor(value: Double): Color {
    return when {
        value >= 9.7 -> FetchContentColors.ratingExceptional
        value >= 9.0 -> FetchContentColors.ratingGreat
        value >= 8.0 -> FetchContentColors.ratingVeryGood
        value >= 7.5 -> FetchContentColors.ratingGood
        value >= 7.0 -> FetchContentColors.ratingFair
        value >= 6.0 -> FetchContentColors.ratingPoor
        else -> FetchContentColors.ratingBad
    }
}

/**
 * The text drawn on [episodeRatingChipColor]. Only the two darkest fills (Great and Bad) take
 * white; the rest take dark text so every band reaches 4.5:1 (ThemeContrastTest checks this).
 */
internal fun episodeRatingChipTextColor(value: Double): Color {
    return when {
        value >= 9.7 -> FetchContentColors.ratingTextOnLight
        value >= 9.0 -> FetchPrimitives.white
        value >= 6.0 -> FetchContentColors.ratingTextOnLight
        else -> FetchPrimitives.white
    }
}

private data class EpisodeRatingChipUi(
    val id: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val ratingText: String,
    val chipColor: Color,
    val chipTextColor: Color
)
