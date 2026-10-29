package com.fetch.tv.ui.screens.youtube

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.data.local.SubtitleStyleSettings
import com.fetch.tv.data.youtube.YouTubeCaptionCue
import com.fetch.tv.data.youtube.YouTubeCaptionTrack
import com.fetch.tv.data.youtube.YouTubeLikeStatus
import com.fetch.tv.data.youtube.YouTubeSkipSegment
import com.fetch.tv.data.youtube.YouTubeVideo
import com.fetch.tv.data.youtube.YouTubeVideoActions
import com.fetch.tv.data.youtube.cueAt
import com.fetch.tv.ui.components.LoadingIndicator
import com.fetch.tv.ui.components.TrailerPlayer
import com.fetch.tv.ui.screens.ambient.AmbientDialogEffect
import com.fetch.tv.ui.screens.detail.TrailerSeekOverlayHost
import com.fetch.tv.ui.screens.detail.requestFocusAfterFrames
import com.fetch.tv.ui.screens.detail.TrailerSeekOverlayState
import com.fetch.tv.ui.theme.FetchMotion
import com.fetch.tv.ui.theme.FetchTheme
import kotlinx.coroutines.delay

/**
 * Full-screen playback for a YouTube video. Built on the trailer player, with what a long video
 * needs: seeking that scales with the press, resuming where YouTube says you stopped, skipping
 * SponsorBlock segments, and reporting progress for YouTube history.
 */
@Composable
fun YouTubePlayerOverlay(
    playback: YouTubePlaybackState,
    onProgress: (positionMs: Long, durationMs: Long) -> Unit,
    onSelectCaption: (YouTubeCaptionTrack?) -> Unit,
    onPlayNext: (YouTubeVideo) -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onRate: (YouTubeLikeStatus) -> Unit,
    onToggleSubscription: () -> Unit,
    onOpenChannel: () -> Unit
) {
    val video = playback.video
    var isPaused by remember(video.videoId) { mutableStateOf(false) }
    var controlsVisible by remember(video.videoId) { mutableStateOf(false) }
    val seekState = remember(video.videoId) { TrailerSeekOverlayState() }
    var seekToken by remember(video.videoId) { mutableIntStateOf(0) }
    var seekDeltaMs by remember(video.videoId) { mutableLongStateOf(0L) }
    var resumed by remember(video.videoId) { mutableStateOf(false) }
    var skipped by remember(video.videoId) { mutableStateOf(emptySet<YouTubeSkipSegment>()) }
    var skipNoticeToken by remember(video.videoId) { mutableIntStateOf(0) }
    var skipNoticeVisible by remember(video.videoId) { mutableStateOf(false) }
    val currentOnProgress by rememberUpdatedState(onProgress)
    val currentSegments by rememberUpdatedState(playback.skipSegments)
    var captionMenuOpen by remember(video.videoId) { mutableStateOf(false) }
    var actionsMenuOpen by remember(video.videoId) { mutableStateOf(false) }
    var positionMs by remember(video.videoId) { mutableLongStateOf(0L) }
    var ended by remember(video.videoId) { mutableStateOf(false) }

    val focusRequester = remember { FocusRequester() }
    val canControl = !playback.videoUrl.isNullOrBlank() && !playback.isLoading && !playback.failed
    // Without the watch page (it failed, or hasn't loaded), the menu still offers the tile's channel.
    val menuActions = playback.actions ?: video.channelId?.let {
        YouTubeVideoActions(likeStatus = null, channelId = it, channelName = video.channelName, subscribed = null)
    }

    fun seekBy(deltaMs: Long) {
        seekDeltaMs = deltaMs
        seekToken += 1
    }

    LaunchedEffect(controlsVisible, seekToken, isPaused) {
        // Paused, the controls stay up so the position is always visible.
        if (controlsVisible && !isPaused) {
            delay(CONTROLS_HIDE_DELAY_MS)
            controlsVisible = false
        }
    }
    LaunchedEffect(skipNoticeToken) {
        if (skipNoticeToken == 0) return@LaunchedEffect
        skipNoticeVisible = true
        delay(SKIP_NOTICE_MS)
        skipNoticeVisible = false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        AmbientDialogEffect(onDismiss)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                    if (ended) {
                        // The end card's buttons take the remote; Back leaves the player.
                        if (native.keyCode == KeyEvent.KEYCODE_BACK || native.keyCode == KeyEvent.KEYCODE_ESCAPE) {
                            onDismiss()
                            return@onPreviewKeyEvent true
                        }
                        return@onPreviewKeyEvent false
                    }
                    if (captionMenuOpen || actionsMenuOpen) {
                        // The menu takes the remote; Back closes it and returns to the video.
                        if (native.keyCode == KeyEvent.KEYCODE_BACK || native.keyCode == KeyEvent.KEYCODE_ESCAPE) {
                            captionMenuOpen = false
                            actionsMenuOpen = false
                            runCatching { focusRequester.requestFocus() }
                            return@onPreviewKeyEvent true
                        }
                        return@onPreviewKeyEvent false
                    }
                    when (native.keyCode) {
                        KeyEvent.KEYCODE_CAPTIONS -> {
                            if (!canControl || playback.captionTracks.isEmpty()) return@onPreviewKeyEvent false
                            // The remote's CC button turns captions on or off without the menu.
                            onSelectCaption(
                                if (playback.selectedCaption != null) null
                                else playback.captionTracks.first()
                            )
                            true
                        }
                        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                            if (controlsVisible && !isPaused) controlsVisible = false else onDismiss()
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                            if (!canControl) return@onPreviewKeyEvent false
                            isPaused = !isPaused
                            controlsVisible = true
                            true
                        }
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> canControl.also { if (it) { isPaused = true; controlsVisible = true } }
                        KeyEvent.KEYCODE_MEDIA_PLAY -> canControl.also { if (it) isPaused = false }
                        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                            if (!canControl) return@onPreviewKeyEvent false
                            seekBy(-seekStepMs(native.repeatCount))
                            controlsVisible = true
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                            if (!canControl) return@onPreviewKeyEvent false
                            seekBy(seekStepMs(native.repeatCount))
                            controlsVisible = true
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (!canControl) return@onPreviewKeyEvent false
                            // Up opens like, subscribe and the channel once the watch page says what they are.
                            if (menuActions != null) {
                                actionsMenuOpen = true
                                controlsVisible = false
                            } else {
                                controlsVisible = true
                            }
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (!canControl) return@onPreviewKeyEvent false
                            if (playback.captionTracks.isNotEmpty()) {
                                captionMenuOpen = true
                                controlsVisible = false
                            } else {
                                controlsVisible = isPaused
                            }
                            true
                        }
                        else -> false
                    }
                }
        ) {
            LaunchedEffect(Unit) { focusRequester.requestFocusAfterFrames() }

            if (!playback.videoUrl.isNullOrBlank() && !playback.failed && !ended) {
                TrailerPlayer(
                    trailerUrl = playback.videoUrl,
                    trailerAudioUrl = playback.audioUrl,
                    isPlaying = true,
                    isPaused = isPaused,
                    seekRequestToken = seekToken,
                    seekDeltaMs = seekDeltaMs,
                    onProgressChanged = progress@{ position, duration ->
                        positionMs = position
                        seekState.positionMs = position
                        seekState.durationMs = duration
                        if (duration <= 0L) return@progress
                        if (!resumed) {
                            resumed = true
                            resumePositionMs(video.watchedPercent, duration)?.let { seekBy(it - position) }
                            return@progress
                        }
                        segmentToSkip(currentSegments, position, skipped)?.let { segment ->
                            skipped = skipped + segment
                            seekBy(segment.endMs - position)
                            skipNoticeToken += 1
                        }
                        currentOnProgress(position, duration)
                    },
                    onEnded = {
                        if (playback.upNext != null || playback.related.isNotEmpty()) {
                            ended = true
                            captionMenuOpen = false
                            actionsMenuOpen = false
                        } else {
                            onDismiss()
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            AnimatedVisibility(
                visible = controlsVisible || !canControl,
                enter = fadeIn(tween(FetchMotion.tokens.durations.quick)),
                exit = fadeOut(tween(FetchMotion.tokens.durations.quick)),
                modifier = Modifier.align(Alignment.TopStart)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = FetchTheme.spacing.xxl, vertical = FetchTheme.spacing.xl),
                    verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xs)
                ) {
                    Text(text = video.title, style = MaterialTheme.typography.titleLarge, color = Color.White)
                    video.channelName?.let {
                        Text(text = it, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.82f))
                    }
                    if (canControl && menuActions != null) {
                        Text(
                            text = stringResource(R.string.youtube_actions_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.82f)
                        )
                    }
                }
            }

            if (playback.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
            }

            if (playback.failed) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = FetchTheme.spacing.xxl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)
                ) {
                    Text(
                        text = stringResource(R.string.youtube_play_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                    Button(onClick = onRetry) { Text(text = stringResource(R.string.action_retry)) }
                }
            }

            AnimatedVisibility(
                visible = skipNoticeVisible,
                enter = fadeIn(tween(FetchMotion.tokens.durations.quick)),
                exit = fadeOut(tween(FetchMotion.tokens.durations.medium)),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(horizontal = FetchTheme.spacing.xxl, vertical = FetchTheme.spacing.xl)
            ) {
                Text(
                    text = stringResource(R.string.youtube_sponsor_skipped),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(FetchTheme.radii.sm))
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = FetchTheme.spacing.md, vertical = FetchTheme.spacing.xs)
                )
            }

            if (playback.selectedCaption != null) {
                CaptionText(
                    cue = cueAt(playback.captionCues, positionMs),
                    style = playback.captionStyle,
                    // Rises above the seek bar while it shows, so neither covers the other.
                    liftForControls = canControl && controlsVisible,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }

            TrailerSeekOverlayHost(
                visible = canControl && controlsVisible,
                overlayState = seekState,
                modifier = Modifier.align(Alignment.BottomCenter)
            )

            if (canControl && controlsVisible && playback.captionTracks.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.youtube_captions_hint,
                        playback.selectedCaption?.let { captionLabel(it) } ?: stringResource(R.string.youtube_captions_off)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.82f),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = FetchTheme.spacing.xxl, vertical = FetchTheme.spacing.xl)
                )
            }

            if (ended) {
                UpNextCard(
                    playback = playback,
                    onPlay = onPlayNext,
                    onClose = onDismiss
                )
            }

            if (actionsMenuOpen && menuActions != null && !ended) {
                VideoActionsMenu(
                    actions = menuActions,
                    failed = playback.actionFailed,
                    onRate = onRate,
                    onToggleSubscription = onToggleSubscription,
                    onOpenChannel = onOpenChannel,
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }

            if (captionMenuOpen) {
                CaptionMenu(
                    playback = playback,
                    onSelect = { track ->
                        onSelectCaption(track)
                        captionMenuOpen = false
                        runCatching { focusRequester.requestFocus() }
                    },
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }
        }
    }
}

private enum class ActionRow { LIKE, SUBSCRIBE, CHANNEL }

/**
 * Like, dislike, subscribe and go to channel, for the playing video. It stays open after a choice
 * so the tick shows; Back closes it.
 */
@Composable
private fun VideoActionsMenu(
    actions: YouTubeVideoActions,
    failed: Boolean,
    onRate: (YouTubeLikeStatus) -> Unit,
    onToggleSubscription: () -> Unit,
    onOpenChannel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val firstFocus = remember { FocusRequester() }
    Column(
        modifier = modifier
            .padding(FetchTheme.spacing.xl)
            .width(CAPTION_MENU_WIDTH)
            .clip(RoundedCornerShape(FetchTheme.radii.md))
            .background(Color.Black.copy(alpha = 0.85f))
            .padding(FetchTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xxs)
    ) {
        actions.channelName?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                modifier = Modifier.padding(bottom = FetchTheme.spacing.xs)
            )
        }
        if (failed) {
            Text(
                text = stringResource(R.string.youtube_action_failed),
                style = MaterialTheme.typography.bodySmall,
                color = FetchTheme.colors.Error
            )
        }
        val status = actions.likeStatus
        val subscribed = actions.subscribed?.takeIf { actions.channelId != null }
        // Focus starts on the top row, whichever rows YouTube offered.
        val first = when {
            status != null -> ActionRow.LIKE
            subscribed != null -> ActionRow.SUBSCRIBE
            else -> ActionRow.CHANNEL
        }
        fun focusIf(row: ActionRow) = if (row == first) Modifier.focusRequester(firstFocus) else Modifier
        if (status != null) {
            CaptionMenuRow(
                label = stringResource(R.string.youtube_action_like),
                selected = status == YouTubeLikeStatus.LIKE,
                onClick = { onRate(if (status == YouTubeLikeStatus.LIKE) YouTubeLikeStatus.INDIFFERENT else YouTubeLikeStatus.LIKE) },
                modifier = focusIf(ActionRow.LIKE)
            )
            CaptionMenuRow(
                label = stringResource(R.string.youtube_action_dislike),
                selected = status == YouTubeLikeStatus.DISLIKE,
                onClick = { onRate(if (status == YouTubeLikeStatus.DISLIKE) YouTubeLikeStatus.INDIFFERENT else YouTubeLikeStatus.DISLIKE) }
            )
        }
        if (subscribed != null) {
            CaptionMenuRow(
                label = stringResource(if (subscribed) R.string.youtube_action_subscribed else R.string.youtube_action_subscribe),
                selected = subscribed,
                onClick = onToggleSubscription,
                modifier = focusIf(ActionRow.SUBSCRIBE)
            )
        }
        if (actions.channelId != null) {
            CaptionMenuRow(
                label = stringResource(R.string.youtube_menu_go_to_channel),
                selected = false,
                onClick = onOpenChannel,
                modifier = focusIf(ActionRow.CHANNEL)
            )
        }
    }
    LaunchedEffect(Unit) { firstFocus.requestFocusAfterFrames() }
}

/**
 * Shown when a video ends: YouTube's pick, counting down to play it when autoplay is on, and
 * the related videos. Moving to anything else stops the countdown, as on YouTube.
 */
@Composable
private fun UpNextCard(
    playback: YouTubePlaybackState,
    onPlay: (YouTubeVideo) -> Unit,
    onClose: () -> Unit
) {
    val next = playback.upNext
    val nextFocus = remember { FocusRequester() }
    var secondsLeft by remember(next?.videoId) { mutableIntStateOf(UP_NEXT_COUNTDOWN_SECONDS) }
    var countdownStopped by remember(next?.videoId) { mutableStateOf(!playback.autoplay || next == null) }
    val currentOnPlay by rememberUpdatedState(onPlay)
    val others = remember(playback.related, next) {
        playback.related.filter { it.videoId != next?.videoId }.take(UP_NEXT_RELATED_COUNT)
    }

    LaunchedEffect(next?.videoId, countdownStopped) {
        if (countdownStopped || next == null) return@LaunchedEffect
        while (secondsLeft > 0) {
            delay(1_000L)
            secondsLeft -= 1
        }
        currentOnPlay(next)
    }
    LaunchedEffect(Unit) { nextFocus.requestFocusAfterFrames() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = FetchTheme.spacing.screen.horizontal, vertical = FetchTheme.spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xl)
    ) {
        Text(
            text = stringResource(R.string.youtube_up_next),
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White
        )
        if (next != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xl),
                verticalAlignment = Alignment.CenterVertically
            ) {
                YouTubeVideoCard(
                    video = next,
                    onClick = { onPlay(next) },
                    modifier = Modifier.width(UP_NEXT_CARD_WIDTH).focusRequester(nextFocus)
                )
                Column(verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)) {
                    Text(
                        text = if (countdownStopped) {
                            stringResource(R.string.youtube_up_next_select)
                        } else {
                            pluralStringResource(R.plurals.youtube_up_next_countdown, secondsLeft, secondsLeft)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                    Button(
                        onClick = onClose,
                        modifier = Modifier.onFocusChanged { if (it.isFocused) countdownStopped = true }
                    ) { Text(text = stringResource(R.string.youtube_up_next_close)) }
                }
            }
        }
        if (others.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.lg),
                modifier = Modifier.onFocusChanged { if (it.hasFocus) countdownStopped = true }
            ) {
                items(others, key = { it.videoId }) { video ->
                    YouTubeVideoCard(
                        video = video,
                        onClick = { onPlay(video) },
                        modifier = Modifier.width(UP_NEXT_RELATED_WIDTH)
                    )
                }
            }
        }
    }
}

private const val UP_NEXT_COUNTDOWN_SECONDS = 10
private const val UP_NEXT_RELATED_COUNT = 8
private val UP_NEXT_CARD_WIDTH = 480.dp
private val UP_NEXT_RELATED_WIDTH = 280.dp

/** One cue in the viewer's subtitle style from Fetch's playback settings. */
@Composable
private fun CaptionText(
    cue: YouTubeCaptionCue?,
    style: SubtitleStyleSettings,
    liftForControls: Boolean,
    modifier: Modifier = Modifier
) {
    val text = cue?.text ?: return
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val offset = maxHeight * (style.verticalOffset.coerceIn(0, 50) / 100f)
        val bottom = offset + if (liftForControls) CAPTION_CONTROLS_LIFT else FetchTheme.spacing.none
        val base = MaterialTheme.typography.headlineSmall
        val textStyle = base.copy(
            fontSize = base.fontSize * (style.size.coerceIn(50, 200) / 100f),
            fontWeight = if (style.bold) FontWeight.Bold else base.fontWeight,
            textAlign = TextAlign.Center
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = FetchTheme.spacing.xxl, end = FetchTheme.spacing.xxl, bottom = bottom)
                .background(Color(style.backgroundColor), RoundedCornerShape(FetchTheme.radii.xs))
                .padding(horizontal = FetchTheme.spacing.sm, vertical = FetchTheme.spacing.xxs)
        ) {
            if (style.outlineEnabled) {
                Text(
                    text = text,
                    style = textStyle.copy(
                        drawStyle = Stroke(width = style.outlineWidth.coerceIn(1, 5) * CAPTION_OUTLINE_SCALE),
                        color = Color(style.outlineColor)
                    )
                )
            }
            Text(text = text, style = textStyle.copy(color = Color(style.textColor)))
        }
    }
}

/** Off, then the video's tracks: its own first, then YouTube's automatic and translated ones. */
@Composable
private fun CaptionMenu(
    playback: YouTubePlaybackState,
    onSelect: (YouTubeCaptionTrack?) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedFocus = remember { FocusRequester() }
    val tracks = remember(playback.captionTracks) {
        playback.captionTracks.sortedWith(compareBy({ it.isTranslated }, { it.isAutoGenerated }, { it.name }))
    }
    Column(
        modifier = modifier
            .padding(FetchTheme.spacing.xl)
            .width(CAPTION_MENU_WIDTH)
            .clip(RoundedCornerShape(FetchTheme.radii.md))
            .background(Color.Black.copy(alpha = 0.85f))
            .padding(FetchTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xs)
    ) {
        Text(
            text = stringResource(R.string.youtube_captions_title),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            modifier = Modifier.padding(bottom = FetchTheme.spacing.xs)
        )
        if (playback.captionsFailed) {
            Text(
                text = stringResource(R.string.youtube_captions_failed),
                style = MaterialTheme.typography.bodySmall,
                color = FetchTheme.colors.Error
            )
        }
        LazyColumn(
            modifier = Modifier.heightIn(max = CAPTION_MENU_MAX_HEIGHT),
            verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xxs)
        ) {
            item(key = "off") {
                CaptionMenuRow(
                    label = stringResource(R.string.youtube_captions_off),
                    selected = playback.selectedCaption == null,
                    onClick = { onSelect(null) },
                    modifier = if (playback.selectedCaption == null) Modifier.focusRequester(selectedFocus) else Modifier
                )
            }
            items(tracks, key = { it.id }) { track ->
                val selected = playback.selectedCaption?.id == track.id
                CaptionMenuRow(
                    label = captionLabel(track),
                    selected = selected,
                    loading = selected && playback.captionsLoading,
                    onClick = { onSelect(track) },
                    modifier = if (selected) Modifier.focusRequester(selectedFocus) else Modifier
                )
            }
        }
    }
    LaunchedEffect(Unit) { selectedFocus.requestFocusAfterFrames() }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CaptionMenuRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false
) {
    val shape = RoundedCornerShape(FetchTheme.radii.sm)
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.White.copy(alpha = 0.18f)
        ),
        scale = CardDefaults.scale(focusedScale = 1f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = FetchTheme.spacing.md, vertical = FetchTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)
        ) {
            Box(modifier = Modifier.size(FetchTheme.spacing.lg), contentAlignment = Alignment.Center) {
                when {
                    loading -> LoadingIndicator(modifier = Modifier.size(FetchTheme.spacing.lg))
                    selected -> Icon(Icons.Default.Check, contentDescription = null, tint = Color.White)
                }
            }
            Text(text = label, style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 1)
        }
    }
}

@Composable
private fun captionLabel(track: YouTubeCaptionTrack): String = when {
    track.isTranslated -> stringResource(R.string.youtube_captions_translated, track.name)
    track.isAutoGenerated -> stringResource(R.string.youtube_captions_auto, track.name)
    else -> track.name
}

private val CAPTION_MENU_WIDTH = 360.dp
private val CAPTION_MENU_MAX_HEIGHT = 420.dp
private val CAPTION_CONTROLS_LIFT = 96.dp
private const val CAPTION_OUTLINE_SCALE = 2f

private const val CONTROLS_HIDE_DELAY_MS = 3_000L
private const val SKIP_NOTICE_MS = 2_500L

/** 10 seconds a press; holding the button speeds up to 30 seconds, then a minute. */
internal fun seekStepMs(repeatCount: Int): Long = when {
    repeatCount >= 20 -> 60_000L
    repeatCount >= 8 -> 30_000L
    else -> 10_000L
}

/**
 * Where to start from YouTube's watched percentage: nowhere when barely started or nearly
 * finished, since those start over, like YouTube's own apps.
 */
internal fun resumePositionMs(watchedPercent: Int?, durationMs: Long): Long? {
    val percent = watchedPercent ?: return null
    if (percent < 3 || percent > 95 || durationMs <= 0L) return null
    return durationMs * percent / 100
}

/** The segment playback is inside and hasn't skipped yet, if any. Seeking back into one plays it. */
internal fun segmentToSkip(
    segments: List<YouTubeSkipSegment>,
    positionMs: Long,
    alreadySkipped: Set<YouTubeSkipSegment>
): YouTubeSkipSegment? = segments.firstOrNull { segment ->
    segment !in alreadySkipped &&
        positionMs >= segment.startMs &&
        // Within the last half second the skip would land about where playback is anyway.
        positionMs < segment.endMs - 500
}
