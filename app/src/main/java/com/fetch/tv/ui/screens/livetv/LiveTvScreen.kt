package com.fetch.tv.ui.screens.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.fetch.tv.R
import com.fetch.tv.core.livetv.LiveTvChannel
import com.fetch.tv.core.livetv.LiveTvProgram
import com.fetch.tv.core.livetv.LiveTvRecording
import com.fetch.tv.ui.components.EmptyScreenState
import com.fetch.tv.ui.components.FetchChip
import com.fetch.tv.ui.components.LoadingIndicator
import com.fetch.tv.ui.components.StateAction
import com.fetch.tv.ui.theme.FetchTheme
import com.fetch.tv.ui.theme.LiveTvGuideTokens
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

private val CHANNEL_COLUMN_WIDTH = LiveTvGuideTokens.channelColumnWidth
private val ROW_HEIGHT = LiveTvGuideTokens.rowHeight

/**
 * Live TV (#660): the channels of the profile's live-TV addons and media servers, with a guide.
 * The top row picks the source; each row is a channel, and to its right what's on over the next
 * three hours when the source has a guide. Selecting a channel, or anything on its row, tunes in.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LiveTvScreen(
    onPlayChannel: (LiveTvChannel) -> Unit,
    onPlayRecording: (LiveTvRecording) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: LiveTvViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // The "now" marker moves on its own; the window itself is set when a source loads.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            nowMs = System.currentTimeMillis()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FetchTheme.colors.Background)
            .padding(
                horizontal = FetchTheme.spacing.screen.horizontal,
                vertical = FetchTheme.spacing.screen.vertical
            ),
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)
    ) {
        Text(
            text = stringResource(R.string.live_tv_title),
            style = MaterialTheme.typography.headlineMedium,
            color = FetchTheme.colors.TextPrimary
        )

        when {
            state.loadingSources -> Box(Modifier.fillMaxWidth().height(LiveTvGuideTokens.loadingAreaHeight), contentAlignment = Alignment.Center) {
                LoadingIndicator(Modifier.size(LiveTvGuideTokens.loadingIndicatorSize))
            }
            state.sources.isEmpty() -> EmptyScreenState(
                title = stringResource(R.string.live_tv_empty_title),
                subtitle = stringResource(R.string.live_tv_empty_subtitle),
                icon = Icons.Default.LiveTv,
                action = StateAction(stringResource(R.string.live_tv_open_settings), onOpenSettings)
            )
            else -> {
                if (state.sources.size > 1) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)) {
                        items(state.sources, key = { it.key }) { source ->
                            FetchChip(
                                label = source.label,
                                selected = source.key == state.selectedKey,
                                onClick = { viewModel.select(source.key) }
                            )
                        }
                    }
                }
                LiveTvGuide(
                    state = state,
                    nowMs = nowMs,
                    onPlayChannel = { channel ->
                        viewModel.onPlay(channel)
                        onPlayChannel(channel)
                    },
                    onRetry = { state.selectedKey?.let(viewModel::select) },
                    onPlayRecording = onPlayRecording,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun LiveTvGuide(
    state: LiveTvUiState,
    nowMs: Long,
    onPlayChannel: (LiveTvChannel) -> Unit,
    onRetry: () -> Unit,
    onPlayRecording: (LiveTvRecording) -> Unit,
    modifier: Modifier = Modifier
) {
    when {
        state.loadingChannels -> Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            LoadingIndicator(Modifier.size(LiveTvGuideTokens.loadingIndicatorSize))
        }
        state.error != null -> EmptyScreenState(
            title = stringResource(R.string.live_tv_error_title),
            subtitle = state.error,
            action = StateAction(stringResource(R.string.action_retry), onRetry),
            modifier = modifier
        )
        state.channels.isEmpty() -> EmptyScreenState(
            title = stringResource(R.string.live_tv_no_channels),
            modifier = modifier
        )
        else -> BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
            val gridWidth = maxWidth - CHANNEL_COLUMN_WIDTH - FetchTheme.spacing.sm
            val dpPerMs = gridWidth.value / LiveTvGuideWindow.WIDTH_MS
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xs),
                contentPadding = PaddingValues(bottom = FetchTheme.spacing.xl)
            ) {
                item(key = "ruler") {
                    TimeRuler(state.windowStartMs, nowMs, gridWidth, dpPerMs)
                }
                items(state.channels, key = { it.key }) { channel ->
                    ChannelRow(
                        channel = channel,
                        programs = LiveTvGuideWindow.visible(state.programs[channel.key].orEmpty(), state.windowStartMs),
                        windowStartMs = state.windowStartMs,
                        nowMs = nowMs,
                        gridWidth = gridWidth,
                        dpPerMs = dpPerMs,
                        onPlay = { onPlayChannel(channel) }
                    )
                }
                if (state.recordings.isNotEmpty()) {
                    item(key = "recordings") {
                        RecordingsRow(state.recordings, onPlayRecording)
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeRuler(windowStartMs: Long, nowMs: Long, gridWidth: Dp, dpPerMs: Float) {
    val format = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    Row(Modifier.fillMaxWidth().height(LiveTvGuideTokens.rulerHeight)) {
        Spacer(Modifier.width(CHANNEL_COLUMN_WIDTH + FetchTheme.spacing.sm))
        Box(Modifier.width(gridWidth).fillMaxHeight()) {
            val slots = (LiveTvGuideWindow.WIDTH_MS / LiveTvGuideWindow.SLOT_MS).toInt()
            for (index in 0 until slots) {
                val slotStart = windowStartMs + index * LiveTvGuideWindow.SLOT_MS
                Text(
                    text = format.format(Date(slotStart)),
                    style = MaterialTheme.typography.labelMedium,
                    color = FetchTheme.colors.TextSecondary,
                    modifier = Modifier.offset(x = (index * LiveTvGuideWindow.SLOT_MS * dpPerMs).dp)
                )
            }
            val nowOffset = (nowMs - windowStartMs) * dpPerMs
            if (nowOffset in 0f..gridWidth.value) {
                Box(
                    Modifier
                        .offset(x = nowOffset.dp)
                        .width(LiveTvGuideTokens.nowMarkerWidth)
                        .fillMaxHeight()
                        .background(FetchTheme.colors.Error)
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ChannelRow(
    channel: LiveTvChannel,
    programs: List<LiveTvProgram>,
    windowStartMs: Long,
    nowMs: Long,
    gridWidth: Dp,
    dpPerMs: Float,
    onPlay: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT),
        horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GuideCell(onClick = onPlay, modifier = Modifier.width(CHANNEL_COLUMN_WIDTH).fillMaxHeight()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)
            ) {
                if (channel.logo != null) {
                    AsyncImage(
                        model = channel.logo,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(width = LiveTvGuideTokens.channelLogoWidth, height = LiveTvGuideTokens.channelLogoHeight)
                    )
                }
                Column {
                    channel.number?.let {
                        Text(text = it, style = MaterialTheme.typography.labelSmall, color = FetchTheme.colors.TextTertiary)
                    }
                    Text(
                        text = channel.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = FetchTheme.colors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Box(Modifier.width(gridWidth).fillMaxHeight()) {
            if (programs.isEmpty()) {
                // No guide for this channel (addons have none): one block, live now.
                GuideCell(onClick = onPlay, modifier = Modifier.fillMaxSize()) {
                    ProgramText(
                        title = stringResource(R.string.live_tv_on_now),
                        subtitle = channel.description,
                        live = true
                    )
                }
            } else {
                val windowEnd = windowStartMs + LiveTvGuideWindow.WIDTH_MS
                val format = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
                programs.forEach { program ->
                    val start = program.startMs.coerceAtLeast(windowStartMs)
                    val end = program.endMs.coerceAtMost(windowEnd)
                    val width = ((end - start) * dpPerMs).dp - LiveTvGuideTokens.programGap
                    if (width <= Dp.Hairline) return@forEach
                    val live = nowMs in program.startMs until program.endMs
                    GuideCell(
                        onClick = onPlay,
                        modifier = Modifier
                            .offset(x = ((start - windowStartMs) * dpPerMs).dp)
                            .width(width)
                            .fillMaxHeight()
                            .semantics {
                                contentDescription = "${channel.name}, ${program.title}, " +
                                    "${format.format(Date(program.startMs))}–${format.format(Date(program.endMs))}"
                            }
                    ) {
                        ProgramText(
                            title = program.title,
                            subtitle = "${format.format(Date(program.startMs))}–${format.format(Date(program.endMs))}",
                            live = live
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgramText(title: String, subtitle: String?, live: Boolean) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = if (live) FetchTheme.colors.TextPrimary else FetchTheme.colors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = FetchTheme.colors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GuideCell(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(FetchTheme.radii.sm)
    Surface(
        onClick = onClick,
        modifier = modifier,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = FetchTheme.colors.BackgroundCard,
            focusedContainerColor = FetchTheme.colors.FocusBackground
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(border = FetchTheme.focusRing.border(), shape = shape)
        ),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = LiveTvGuideTokens.FOCUSED_SCALE)
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(horizontal = FetchTheme.spacing.md),
            contentAlignment = Alignment.CenterStart
        ) {
            content()
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RecordingsRow(recordings: List<LiveTvRecording>, onPlay: (LiveTvRecording) -> Unit) {
    Column(
        modifier = Modifier.padding(top = FetchTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)
    ) {
        Text(
            text = stringResource(R.string.live_tv_recordings),
            style = MaterialTheme.typography.titleMedium,
            color = FetchTheme.colors.TextPrimary
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)) {
            items(recordings, key = { it.videoId }) { recording ->
                GuideCell(
                    onClick = { onPlay(recording) },
                    modifier = Modifier.width(LiveTvGuideTokens.recordingWidth).height(LiveTvGuideTokens.recordingHeight)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xs)) {
                        if (recording.image != null) {
                            AsyncImage(
                                model = recording.image,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().height(LiveTvGuideTokens.recordingImageHeight)
                            )
                        }
                        Text(
                            text = recording.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = FetchTheme.colors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = recording.sourceLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = FetchTheme.colors.TextTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
