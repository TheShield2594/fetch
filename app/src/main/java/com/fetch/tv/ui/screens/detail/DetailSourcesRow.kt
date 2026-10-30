package com.fetch.tv.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.core.streams.BestMatchPreview
import com.fetch.tv.ui.components.FetchCardDefaults
import com.fetch.tv.ui.components.LightweightCard
import com.fetch.tv.ui.theme.FetchTheme

/**
 * The detail page's Sources row: the file Best match will play, as a large card with its addon
 * and quality tags, then a card that opens every source. It shows only what the Play button's
 * search already found, so it never asks the addons anything itself.
 *
 * With no pick (Best match off, or a reused link) only the "All sources" card shows.
 * [focusRequester] goes on the first card, where up from the rows below lands; [upFocusRequester]
 * and [downFocusRequester] pin the rows around it, so focus never skips past the row.
 */
@Composable
internal fun DetailSourcesRow(
    bestMatch: BestMatchPreview?,
    onPlayBestMatch: () -> Unit,
    onAllSources: () -> Unit,
    focusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier
) {
    val neighbours = Modifier.focusProperties {
        upFocusRequester?.let { up = it }
        downFocusRequester?.let { down = it }
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)) {
        Text(
            text = stringResource(R.string.sources_title),
            style = MaterialTheme.typography.titleMedium,
            color = FetchTheme.colors.TextPrimary
        )
        Row(horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)) {
            bestMatch?.let { pick ->
                SourceCard(
                    onClick = onPlayBestMatch,
                    modifier = neighbours
                        .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                        .widthIn(min = 300.dp, max = 420.dp)
                ) {
                    // Red marks the pick: it is what OK on Play starts.
                    Text(
                        text = stringResource(
                            if (pick.isFinal) R.string.autoplay_mode_best else R.string.fetching_best_so_far
                        ).uppercase(LocalConfiguration.current.locales[0]),
                        style = SourceLabelStyle,
                        color = FetchTheme.colors.Accent,
                        maxLines = 1
                    )
                    Text(
                        text = pick.addonName ?: stringResource(R.string.autoplay_mode_best),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = FetchTheme.colors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    SourceTags(
                        labels = pick.labels,
                        cached = pick.isCached,
                        modifier = Modifier.alpha(if (pick.isFinal) 1f else 0.7f)
                    )
                }
            }
            SourceCard(
                onClick = onAllSources,
                modifier = neighbours
                    .then(focusRequester?.takeIf { bestMatch == null }?.let { Modifier.focusRequester(it) } ?: Modifier)
                    .width(240.dp)
            ) {
                Text(
                    text = stringResource(R.string.detail_sources_all),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = FetchTheme.colors.TextPrimary,
                    maxLines = 1
                )
                Text(
                    text = when {
                        bestMatch == null -> stringResource(R.string.detail_sources_choose)
                        !bestMatch.isFinal -> stringResource(R.string.detail_sources_searching)
                        else -> pluralStringResource(R.plurals.detail_sources_found, bestMatch.sourceCount, bestMatch.sourceCount)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = FetchTheme.colors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SourceCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val shape = FetchTheme.shapes.backdropCard
    LightweightCard(
        onClick = onClick,
        shape = shape,
        containerColor = FetchTheme.colors.BackgroundCard,
        focusedBorder = Border(border = FetchTheme.focusRing.border(FetchCardDefaults.FocusedBorderWidth), shape = shape),
        restingBorder = FetchCardDefaults.restingBorder(shape),
        focusedScale = FetchTheme.focus.scale,
        modifier = modifier.height(SOURCE_CARD_HEIGHT)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = FetchTheme.spacing.lg, vertical = FetchTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xs, Alignment.CenterVertically)
        ) {
            content()
        }
    }
}

/** "4K  DV P8  TrueHD Atmos  18.2 GB  Cached" as outlined tags; Cached filled, like elsewhere. */
@Composable
private fun SourceTags(labels: List<String>, cached: Boolean, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xs)) {
        labels.forEach { label -> SourceTag(label, filled = false) }
        if (cached) SourceTag(stringResource(R.string.best_match_cached), filled = true)
    }
}

@Composable
private fun SourceTag(text: String, filled: Boolean) {
    val shape = FetchTheme.shapes.badge
    Text(
        text = text,
        style = FetchTheme.textStyles.badge,
        color = if (filled) FetchTheme.colors.OnHighlight else FetchTheme.colors.TextPrimary,
        maxLines = 1,
        modifier = Modifier
            .then(
                if (filled) {
                    Modifier.background(FetchTheme.colors.Highlight, shape)
                } else {
                    Modifier.border(FetchTheme.strokes.hairline, FetchTheme.colors.TextPrimary.copy(alpha = 0.4f), shape)
                }
            )
            .padding(horizontal = FetchTheme.spacing.sm, vertical = FetchTheme.spacing.xxs)
    )
}

private val SOURCE_CARD_HEIGHT = 104.dp

/** The small spaced capitals over the pick ("BEST MATCH"). */
private val SourceLabelStyle: androidx.compose.ui.text.TextStyle
    @Composable get() = FetchTheme.textStyles.badge.copy(letterSpacing = 1.5.sp)
