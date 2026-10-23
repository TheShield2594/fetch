package com.fetch.tv.ui.screens.stream

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.core.streams.BestMatchPreview
import com.fetch.tv.ui.components.SourceChipItem
import com.fetch.tv.ui.components.SourceChipStatus
import com.fetch.tv.ui.theme.FetchTheme
import com.fetch.tv.ui.theme.FetchMotion
import kotlinx.coroutines.delay

/** The panel's background opacity; the launch morph ends on the same colour. */
internal const val FETCHING_PANEL_ALPHA = 0.94f

/**
 * Shown over the auto-play loader while addons are still answering: how many have answered,
 * the best stream so far, and a way to play it now or open every source instead of waiting.
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun FetchingPanel(
    sourceChips: List<SourceChipItem>,
    bestSoFar: BestMatchPreview?,
    deadlineElapsedMs: Long?,
    onPlayNow: () -> Unit,
    onShowAllSources: () -> Unit,
    modifier: Modifier = Modifier
) {
    val answered = sourceChips.count { it.status != SourceChipStatus.LOADING }
    val waiting = sourceChips.count { it.status == SourceChipStatus.LOADING }
    val secondsLeft = rememberSecondsUntil(deadlineElapsedMs)
    val playNowFocus = remember { FocusRequester() }
    val seeAllFocus = remember { FocusRequester() }
    val hasBest = bestSoFar != null
    // Names in the order their addons answered, so the filled dots grow from the left.
    val answerOrder = remember { mutableStateListOf<String>() }
    LaunchedEffect(sourceChips) {
        sourceChips.forEach { chip ->
            if (chip.status != SourceChipStatus.LOADING && chip.name !in answerOrder) answerOrder += chip.name
        }
    }
    val orderedChips = addonsInAnswerOrder(sourceChips, answerOrder)

    LaunchedEffect(hasBest) {
        runCatching { if (hasBest) playNowFocus.requestFocus() else seeAllFocus.requestFocus() }
    }

    Column(
        modifier = modifier
            .widthIn(max = 880.dp)
            .background(FetchTheme.colors.BackgroundElevated.copy(alpha = FETCHING_PANEL_ALPHA), RoundedCornerShape(FetchTheme.radii.md))
            .padding(FetchTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (waiting > 0) {
                    pluralStringResource(R.plurals.fetching_addons_waiting, waiting, answered, waiting)
                } else {
                    stringResource(R.string.fetching_addons_done, answered)
                },
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = FetchTheme.colors.TextPrimary
            )
            if (secondsLeft != null && hasBest) {
                Text(
                    text = pluralStringResource(R.plurals.fetching_starting_in, secondsLeft, secondsLeft),
                    style = MaterialTheme.typography.bodyMedium,
                    color = FetchTheme.colors.TextSecondary
                )
            }
        }

        if (bestSoFar != null) {
            Column(verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)) {
                Text(
                    text = stringResource(R.string.fetching_best_so_far).uppercase(),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = FetchTheme.colors.Secondary
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)
                ) {
                    bestSoFar.labels.forEach { label -> SpecChip(label) }
                    if (bestSoFar.isCached) {
                        SpecChip(stringResource(R.string.best_match_cached), highlighted = true)
                    }
                    bestSoFar.addonName?.let { name ->
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = FetchTheme.colors.TextSecondary,
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)) {
            if (bestSoFar != null) {
                PanelButton(
                    text = stringResource(R.string.fetching_play_now),
                    primary = true,
                    onClick = onPlayNow,
                    modifier = Modifier.focusRequester(playNowFocus)
                )
            }
            PanelButton(
                text = stringResource(R.string.fetching_see_all_sources),
                primary = false,
                onClick = onShowAllSources,
                modifier = Modifier.focusRequester(seeAllFocus)
            )
        }

        if (sourceChips.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xs)
            ) {
                orderedChips.forEach { chip -> key(chip.name) { AddonStatus(chip) } }
            }
        }
    }
}

@Composable
private fun rememberSecondsUntil(deadlineElapsedMs: Long?): Int? {
    var secondsLeft by remember(deadlineElapsedMs) { mutableStateOf<Int?>(null) }
    LaunchedEffect(deadlineElapsedMs) {
        if (deadlineElapsedMs == null) {
            secondsLeft = null
            return@LaunchedEffect
        }
        while (true) {
            val remainingMs = deadlineElapsedMs - SystemClock.elapsedRealtime()
            if (remainingMs <= 0L) {
                secondsLeft = null
                break
            }
            secondsLeft = ((remainingMs + 999L) / 1000L).toInt()
            delay(250L)
        }
    }
    return secondsLeft
}

@Composable
private fun SpecChip(text: String, highlighted: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        color = if (highlighted) FetchTheme.colors.OnSecondary else FetchTheme.colors.TextPrimary,
        modifier = Modifier
            .background(
                if (highlighted) FetchTheme.colors.Secondary else FetchTheme.colors.BackgroundCard,
                RoundedCornerShape(FetchTheme.radii.xs)
            )
            .padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

@Composable
private fun AddonStatus(chip: SourceChipItem) {
    val dotColor by animateColorAsState(
        targetValue = when (chip.status) {
            SourceChipStatus.SUCCESS -> FetchTheme.colors.Secondary
            SourceChipStatus.LOADING -> FetchTheme.colors.TextSecondary
            SourceChipStatus.ERROR -> FetchTheme.colors.Error
        },
        animationSpec = FetchMotion.focusTween(),
        label = "addonDot"
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xs)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(dotColor, CircleShape)
        )
        Text(
            text = chip.name,
            style = MaterialTheme.typography.bodySmall,
            color = if (chip.status == SourceChipStatus.LOADING) FetchTheme.colors.TextSecondary else FetchTheme.colors.TextPrimary
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PanelButton(
    text: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(FetchTheme.radii.sm)
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.colors(
            containerColor = if (primary) FetchTheme.colors.Secondary else FetchTheme.colors.BackgroundCard,
            focusedContainerColor = if (primary) FetchTheme.colors.Secondary else FetchTheme.colors.FocusBackground,
            contentColor = if (primary) FetchTheme.colors.OnSecondary else FetchTheme.colors.TextPrimary,
            focusedContentColor = if (primary) FetchTheme.colors.OnSecondary else FetchTheme.colors.TextPrimary
        ),
        shape = ButtonDefaults.shape(shape = shape),
        border = ButtonDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(FetchTheme.strokes.focus, if (primary) FetchTheme.colors.TextPrimary else FetchTheme.colors.FocusRing),
                shape = shape
            )
        ),
        scale = ButtonDefaults.scale(focusedScale = 1f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
        )
    }
}
