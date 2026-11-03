package com.fetch.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fetch.desktop.SourcesState
import com.fetch.desktop.playback.AddonFetchStatus
import com.fetch.desktop.playback.AddonProgress
import com.fetch.desktop.playback.FetchProgress
import com.fetch.tv.core.streams.SpecLine
import com.fetch.tv.core.streams.ranking.RankedStream
import kotlinx.coroutines.delay

private val Success = Color(0xFF4CAF50)

/**
 * The TV's fetching panel, shown while auto-play waits for the addons: how many have answered,
 * the best source so far, "Play now" and "See all sources", and the seconds left before the
 * best so far starts on its own.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FetchingPanel(
    sources: SourcesState,
    deadlineMs: Long?,
    onPlayNow: () -> Unit,
    onShowAllSources: () -> Unit,
    modifier: Modifier = Modifier
) {
    val best = sources.best
    val secondsLeft = rememberSecondsUntil(deadlineMs)
    // Kept while "Best so far" fades out, if the best is ever withdrawn.
    var shownBest by remember { mutableStateOf(best) }
    if (best != null) shownBest = best
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
            .widthIn(max = 720.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(FetchPalette.SurfaceRaised.copy(alpha = 0.97f))
            .border(1.dp, FetchPalette.SurfaceHigh, RoundedCornerShape(16.dp))
            .padding(28.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(FetchProgress.headline(sources.addons), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            if (secondsLeft != null && best != null) {
                Text(if (secondsLeft == 1) "Starting in 1 second" else "Starting in $secondsLeft seconds", color = FetchPalette.TextSecondary, fontSize = 15.sp)
            }
        }
        AnimatedVisibility(visible = best != null, enter = fadeIn(), exit = fadeOut()) {
            shownBest?.let { BestSoFar(it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AnimatedVisibility(visible = best != null, enter = fadeIn(), exit = fadeOut()) {
                PrimaryButton("Play now", onClick = onPlayNow)
            }
            SecondaryButton("See all sources", onClick = onShowAllSources, icon = FetchIcons.List)
        }
        if (sources.addons.isNotEmpty()) AddonChips(sources.addons)
    }
}

/** The addons' progress as a line of dots, for the sources section while the list fills in. */
@Composable
fun FetchingProgressLine(sources: SourcesState, modifier: Modifier = Modifier) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
        Text(FetchProgress.headline(sources.addons), color = FetchPalette.TextSecondary, fontSize = 15.sp)
        if (sources.addons.isNotEmpty()) AddonChips(sources.addons)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BestSoFar(best: RankedStream) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("BEST SO FAR", style = OverlineStyle, color = FetchPalette.TextSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            val labels = best.descriptor.summaryLabels()
            // The spec line's words, as on the Play button; size and addon are the quiet detail.
            val size = labels.lastOrNull()?.takeIf { best.descriptor.sizeBytes != null }
            labels.filter { it != size }.forEach { SpecChip(it) }
            if (best.descriptor.isDebridCached) SpecChip("Cached", filled = true)
            listOfNotNull(size, best.stream.addonName.ifBlank { null }).joinToString(SpecLine.SEPARATOR).takeIf { it.isNotEmpty() }?.let {
                Text(it, color = FetchPalette.TextSecondary, fontSize = 15.sp)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddonChips(addons: List<AddonProgress>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FetchProgress.inAnswerOrder(addons).forEach { chip -> key(chip.name) { AddonStatus(chip) } }
    }
}

@Composable
private fun AddonStatus(chip: AddonProgress) {
    val dot by animateColorAsState(
        when (chip.status) {
            AddonFetchStatus.SUCCESS -> Success
            AddonFetchStatus.LOADING -> FetchPalette.TextMuted
            AddonFetchStatus.ERROR, AddonFetchStatus.TIMED_OUT -> FetchPalette.Error
        }
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Text(
            chip.label,
            fontSize = 13.sp,
            color = if (chip.status == AddonFetchStatus.LOADING) FetchPalette.TextSecondary else FetchPalette.TextPrimary
        )
    }
}

@Composable
private fun rememberSecondsUntil(deadlineMs: Long?): Int? {
    var seconds by remember(deadlineMs) { mutableStateOf<Int?>(null) }
    LaunchedEffect(deadlineMs) {
        if (deadlineMs == null) {
            seconds = null
            return@LaunchedEffect
        }
        while (true) {
            val remaining = deadlineMs - System.currentTimeMillis()
            if (remaining <= 0) {
                seconds = null
                break
            }
            seconds = ((remaining + 999) / 1000).toInt()
            delay(250)
        }
    }
    return seconds
}
