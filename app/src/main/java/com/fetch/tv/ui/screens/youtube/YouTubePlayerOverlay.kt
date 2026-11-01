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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Spacer
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
import com.fetch.tv.data.youtube.YouTubePlaylistOption
import com.fetch.tv.data.youtube.YouTubePlaylistPrivacy
import com.fetch.tv.data.local.resumableMs
import com.fetch.tv.data.youtube.SponsorBlockCategory
import com.fetch.tv.data.youtube.SponsorBlockMode
import com.fetch.tv.data.youtube.YouTubeChapter
import com.fetch.tv.data.youtube.YouTubeSkipSegment
import com.fetch.tv.data.youtube.YouTubeVideo
import com.fetch.tv.data.youtube.YouTubeVideoActions
import com.fetch.tv.data.youtube.cueAt
import com.fetch.tv.ui.components.LoadingIndicator
import com.fetch.tv.ui.components.TrailerPlayer
import com.fetch.tv.ui.screens.ambient.AmbientDialogEffect
import com.fetch.tv.ui.screens.detail.requestFocusAfterFrames
import com.fetch.tv.ui.screens.detail.TrailerSeekOverlayState
import com.fetch.tv.ui.theme.FetchMotion
import com.fetch.tv.ui.theme.FetchTheme
import kotlinx.coroutines.delay

/**
 * Full-screen playback for a YouTube video. Built on the trailer player, with what a long video
 * needs: seeking that scales with the press and previews frames, chapters, speed, resuming where
 * you stopped, skipping SponsorBlock segments, and reporting progress for YouTube history.
 *
 * The remote: OK pauses (or takes an offered skip), left and right seek, Up opens the video's
 * actions, Down the playback options (captions, speed, chapters), and the media next and
 * previous keys move between chapters.
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
    onOpenChannel: () -> Unit,
    onLoadPlaylists: () -> Unit,
    onTogglePlaylist: (YouTubePlaylistOption) -> Unit,
    onCreatePlaylist: (title: String, privacy: YouTubePlaylistPrivacy) -> Unit,
    playbackSpeed: Float = 1f,
    onSetSpeed: (Float) -> Unit = {},
    onResumed: (positionMs: Long) -> Unit = {}
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
    // The segment just skipped automatically: Left, while its notice shows, goes back to watch it.
    var lastSkipped by remember(video.videoId) { mutableStateOf<YouTubeSkipSegment?>(null) }
    // The segment playback is in that the profile asked to be offered: OK skips it.
    var offeredSkip by remember(video.videoId) { mutableStateOf<YouTubeSkipSegment?>(null) }
    val currentOnProgress by rememberUpdatedState(onProgress)
    val currentOnResumed by rememberUpdatedState(onResumed)
    val currentSegments by rememberUpdatedState(playback.skipSegments)
    val currentModes by rememberUpdatedState(playback.sponsorBlockModes)
    val currentResumeFrom by rememberUpdatedState(playback.resumeFromMs)
    // Down's menu: its first page, or one of captions, speed and chapters.
    var optionsPage by remember(video.videoId) { mutableStateOf<PlaybackOptionsPage?>(null) }
    val captionMenuOpen = optionsPage != null
    var actionsMenuOpen by remember(video.videoId) { mutableStateOf(false) }
    // The Save list opens over the actions panel, in a dialog of its own; playback carries on behind.
    var playlistPickerOpen by remember(video.videoId) { mutableStateOf(false) }
    var playlistPickerClosed by remember(video.videoId) { mutableIntStateOf(0) }
    var positionMs by remember(video.videoId) { mutableLongStateOf(0L) }
    var ended by remember(video.videoId) { mutableStateOf(false) }

    val focusRequester = remember { FocusRequester() }
    val saveFocusRequester = remember { FocusRequester() }
    val canControl = !playback.videoUrl.isNullOrBlank() && !playback.isLoading && !playback.failed
    // Without the watch page (it failed, or hasn't loaded), the menu still offers Save and the tile's channel.
    val menuActions = playback.actions
        ?: YouTubeVideoActions(likeStatus = null, channelId = video.channelId, channelName = video.channelName, subscribed = null)

    fun seekBy(deltaMs: Long) {
        seekDeltaMs = deltaMs
        seekToken += 1
        // Until the next progress report, the playhead and the offer are unknown.
        offeredSkip = null
    }

    fun seekTo(targetMs: Long) = seekBy(targetMs - positionMs)

    fun closeMenus() {
        optionsPage = null
        actionsMenuOpen = false
        runCatching { focusRequester.requestFocus() }
    }

    LaunchedEffect(controlsVisible, seekToken, isPaused) {
        // Paused, the controls stay up so the position is always visible.
        if (controlsVisible && !isPaused) {
            delay(CONTROLS_HIDE_DELAY_MS)
            controlsVisible = false
        }
    }
    // Closing the Save list goes back to the Save entry.
    LaunchedEffect(playlistPickerClosed) {
        if (playlistPickerClosed > 0) saveFocusRequester.requestFocusAfterFrames()
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
                        // The menu takes the remote; Back goes up a page, then returns to the video.
                        if (native.keyCode == KeyEvent.KEYCODE_BACK || native.keyCode == KeyEvent.KEYCODE_ESCAPE) {
                            if (optionsPage != null && optionsPage != PlaybackOptionsPage.MAIN) {
                                optionsPage = PlaybackOptionsPage.MAIN
                            } else {
                                closeMenus()
                            }
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
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            if (!canControl) return@onPreviewKeyEvent false
                            val offer = offeredSkip
                            if (offer != null) {
                                // OK takes the offered skip rather than pausing.
                                skipped = skipped + offer
                                offeredSkip = null
                                seekTo(offer.endMs)
                            } else {
                                isPaused = !isPaused
                                controlsVisible = true
                            }
                            true
                        }
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                            if (!canControl) return@onPreviewKeyEvent false
                            isPaused = !isPaused
                            controlsVisible = true
                            true
                        }
                        KeyEvent.KEYCODE_MEDIA_NEXT -> {
                            if (!canControl) return@onPreviewKeyEvent false
                            val next = nextChapterStart(playback.chapters, positionMs)
                            when {
                                next != null -> { seekTo(next); controlsVisible = true }
                                playback.upNext != null -> onPlayNext(playback.upNext)
                                else -> return@onPreviewKeyEvent false
                            }
                            true
                        }
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                            if (!canControl) return@onPreviewKeyEvent false
                            seekTo(previousChapterStart(playback.chapters, positionMs) ?: 0L)
                            controlsVisible = true
                            true
                        }
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> canControl.also { if (it) { isPaused = true; controlsVisible = true } }
                        KeyEvent.KEYCODE_MEDIA_PLAY -> canControl.also { if (it) isPaused = false }
                        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                            if (!canControl) return@onPreviewKeyEvent false
                            val undo = lastSkipped?.takeIf { skipNoticeVisible && native.repeatCount == 0 }
                            if (undo != null) {
                                // Back to the start of what was just skipped; it plays this time.
                                lastSkipped = null
                                skipNoticeVisible = false
                                seekTo(undo.startMs)
                            } else {
                                seekBy(-seekStepMs(native.repeatCount))
                            }
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
                            // Up opens Save at once; like, subscribe and the channel once the watch page says what they are.
                            actionsMenuOpen = true
                            controlsVisible = false
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (!canControl) return@onPreviewKeyEvent false
                            optionsPage = PlaybackOptionsPage.MAIN
                            controlsVisible = false
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
                    playbackSpeed = playbackSpeed,
                    seekRequestToken = seekToken,
                    seekDeltaMs = seekDeltaMs,
                    onProgressChanged = progress@{ position, duration ->
                        positionMs = position
                        seekState.positionMs = position
                        seekState.durationMs = duration
                        if (duration <= 0L) return@progress
                        if (!resumed) {
                            resumed = true
                            // Fetch's own exact place first, then YouTube's percent from the tile.
                            val target = currentResumeFrom?.let { resumableMs(it, duration) }
                                ?: resumePositionMs(video.watchedPercent, duration)
                            target?.let {
                                seekBy(it - position)
                                currentOnResumed(it)
                            }
                            return@progress
                        }
                        segmentToSkip(currentSegments, position, skipped, currentModes)?.let { segment ->
                            skipped = skipped + segment
                            lastSkipped = segment
                            seekBy(segment.endMs - position)
                            skipNoticeToken += 1
                        }
                        offeredSkip = segmentToOffer(currentSegments, position, skipped, currentModes)
                        currentOnProgress(position, duration)
                    },
                    onEnded = {
                        if (playback.upNext != null || playback.related.isNotEmpty()) {
                            ended = true
                            optionsPage = null
                            actionsMenuOpen = false
                            playlistPickerOpen = false
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
                    playback.playlist?.let { playlist ->
                        playlistPositionText(playlist)?.let {
                            Text(text = it, style = MaterialTheme.typography.bodySmall, color = FetchTheme.colors.Player.TextSecondary)
                        }
                    }
                    playback.votes?.let { votes ->
                        Text(
                            text = stringResource(R.string.youtube_votes, formatCount(votes.likes), formatCount(votes.dislikes)),
                            style = MaterialTheme.typography.bodySmall,
                            color = FetchTheme.colors.Player.TextSecondary
                        )
                    }
                    if (canControl) {
                        Text(
                            text = stringResource(R.string.youtube_actions_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.82f)
                        )
                        // The bottom of the screen is the seek bar's, with the chapter's name.
                        Text(
                            text = stringResource(R.string.youtube_options_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = FetchTheme.colors.Player.TextSecondary
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
                    text = stringResource(
                        R.string.youtube_sponsor_skipped_undo,
                        lastSkipped?.sponsorBlockCategory?.let { sponsorBlockCategoryLabel(it) }
                            ?: stringResource(R.string.youtube_sponsor_block_category_sponsor)
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(FetchTheme.radii.sm))
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = FetchTheme.spacing.md, vertical = FetchTheme.spacing.xs)
                )
            }

            // The offered skip shows while playback is inside the segment.
            AnimatedVisibility(
                visible = offeredSkip != null && !captionMenuOpen && !actionsMenuOpen && !ended,
                enter = fadeIn(tween(FetchMotion.tokens.durations.quick)),
                exit = fadeOut(tween(FetchMotion.tokens.durations.quick)),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(horizontal = FetchTheme.spacing.xxl, vertical = SKIP_OFFER_BOTTOM)
            ) {
                val category = offeredSkip?.sponsorBlockCategory
                Text(
                    text = stringResource(
                        R.string.youtube_sponsor_skip_offer,
                        category?.let { sponsorBlockCategoryLabel(it) } ?: stringResource(R.string.youtube_sponsor_block_category_sponsor)
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = FetchTheme.colors.Player.OnFocus,
                    modifier = Modifier
                        .clip(RoundedCornerShape(FetchTheme.radii.sm))
                        .background(FetchTheme.colors.Player.Focus)
                        .padding(horizontal = FetchTheme.spacing.lg, vertical = FetchTheme.spacing.sm)
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

            YouTubeSeekBarHost(
                visible = canControl && controlsVisible,
                state = seekState,
                chapters = playback.chapters,
                segments = playback.skipSegments.filter { modeOf(it, playback.sponsorBlockModes) != SponsorBlockMode.OFF },
                storyboard = playback.storyboard,
                playbackSpeed = playbackSpeed,
                modifier = Modifier.align(Alignment.BottomCenter)
            )

            if (ended) {
                UpNextCard(
                    playback = playback,
                    onPlay = onPlayNext,
                    onClose = onDismiss
                )
            }

            if (actionsMenuOpen && !ended) {
                VideoActionsMenu(
                    actions = menuActions,
                    failed = playback.actionFailed,
                    onRate = onRate,
                    onToggleSubscription = onToggleSubscription,
                    onOpenChannel = onOpenChannel,
                    onSave = {
                        // Read now rather than with every video, so starting playback makes no extra request.
                        onLoadPlaylists()
                        playlistPickerOpen = true
                    },
                    saveFocusRequester = saveFocusRequester,
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }

            // Inside the player's dialog, so it opens above the video rather than behind it.
            if (playlistPickerOpen && actionsMenuOpen && !ended) {
                YouTubePlaylistPicker(
                    videoTitle = video.title,
                    playlists = playback.playlists,
                    failed = playback.playlistsFailed,
                    onBack = {
                        playlistPickerOpen = false
                        playlistPickerClosed += 1
                    },
                    onToggle = onTogglePlaylist,
                    onCreate = onCreatePlaylist
                )
            }

            when (optionsPage) {
                null -> Unit
                PlaybackOptionsPage.MAIN -> PlaybackOptionsMenu(
                    captionLabel = if (playback.captionTracks.isEmpty()) {
                        null
                    } else {
                        playback.selectedCaption?.let { captionLabel(it) } ?: stringResource(R.string.youtube_captions_off)
                    },
                    speed = playbackSpeed,
                    currentChapter = chapterAt(playback.chapters, positionMs)?.title,
                    hasChapters = playback.chapters.isNotEmpty(),
                    onOpen = { optionsPage = it },
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
                PlaybackOptionsPage.CAPTIONS -> CaptionMenu(
                    playback = playback,
                    onSelect = { track ->
                        onSelectCaption(track)
                        closeMenus()
                    },
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
                PlaybackOptionsPage.SPEED -> SpeedMenu(
                    speed = playbackSpeed,
                    onSelect = { speed ->
                        onSetSpeed(speed)
                        closeMenus()
                    },
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
                PlaybackOptionsPage.CHAPTERS -> ChapterMenu(
                    chapters = playback.chapters,
                    current = chapterAt(playback.chapters, positionMs),
                    onSelect = { chapter ->
                        seekTo(chapter.startMs)
                        controlsVisible = true
                        closeMenus()
                    },
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }
        }
    }
}

/**
 * Like, dislike, save to Watch later or a playlist, subscribe and go to channel, for the playing
 * video. It stays open after a choice so the tick shows; Back closes it.
 */
@Composable
private fun VideoActionsMenu(
    actions: YouTubeVideoActions,
    failed: Boolean,
    onRate: (YouTubeLikeStatus) -> Unit,
    onToggleSubscription: () -> Unit,
    onOpenChannel: () -> Unit,
    onSave: () -> Unit,
    saveFocusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    val likeFocus = remember { FocusRequester() }
    val status = actions.likeStatus
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
        val subscribed = actions.subscribed?.takeIf { actions.channelId != null }
        if (status != null) {
            CaptionMenuRow(
                label = stringResource(R.string.youtube_action_like),
                selected = status == YouTubeLikeStatus.LIKE,
                onClick = { onRate(if (status == YouTubeLikeStatus.LIKE) YouTubeLikeStatus.INDIFFERENT else YouTubeLikeStatus.LIKE) },
                modifier = Modifier.focusRequester(likeFocus)
            )
            CaptionMenuRow(
                label = stringResource(R.string.youtube_action_dislike),
                selected = status == YouTubeLikeStatus.DISLIKE,
                onClick = { onRate(if (status == YouTubeLikeStatus.DISLIKE) YouTubeLikeStatus.INDIFFERENT else YouTubeLikeStatus.DISLIKE) }
            )
        }
        CaptionMenuRow(
            label = stringResource(R.string.youtube_action_save),
            selected = false,
            onClick = onSave,
            modifier = Modifier.focusRequester(saveFocusRequester)
        )
        if (subscribed != null) {
            CaptionMenuRow(
                label = stringResource(if (subscribed) R.string.youtube_action_subscribed else R.string.youtube_action_subscribe),
                selected = subscribed,
                onClick = onToggleSubscription
            )
        }
        if (actions.channelId != null) {
            CaptionMenuRow(
                label = stringResource(R.string.youtube_menu_go_to_channel),
                selected = false,
                onClick = onOpenChannel
            )
        }
    }
    // Focus starts on the top row: Like, when YouTube offered it, else Save.
    LaunchedEffect(Unit) { (if (status != null) likeFocus else saveFocusRequester).requestFocusAfterFrames() }
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
        val playlistTitle = playback.playlist?.title?.takeIf { next?.playlistId == playback.playlist.playlistId }
        Text(
            text = playlistTitle?.let { stringResource(R.string.youtube_up_next_in_playlist, it) }
                ?: stringResource(R.string.youtube_up_next),
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
internal fun CaptionMenuRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    /** Shown at the end of the row: the current choice of a row that opens a list. */
    value: String? = null
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
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = FetchTheme.colors.Player.TextPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false)
            )
            value?.let {
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = FetchTheme.colors.Player.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
internal fun captionLabel(track: YouTubeCaptionTrack): String = when {
    track.isTranslated -> stringResource(R.string.youtube_captions_translated, track.name)
    track.isAutoGenerated -> stringResource(R.string.youtube_captions_auto, track.name)
    else -> track.name
}

internal val CAPTION_MENU_WIDTH = 360.dp
internal val CAPTION_MENU_MAX_HEIGHT = 420.dp
private val SKIP_OFFER_BOTTOM = 120.dp
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

/**
 * The segment playback is inside, set to be skipped, and not skipped yet, if any. Seeking back
 * into one plays it.
 */
internal fun segmentToSkip(
    segments: List<YouTubeSkipSegment>,
    positionMs: Long,
    alreadySkipped: Set<YouTubeSkipSegment>,
    modes: Map<SponsorBlockCategory, SponsorBlockMode> = emptyMap()
): YouTubeSkipSegment? = segments.firstOrNull { segment ->
    modeOf(segment, modes) == SponsorBlockMode.SKIP && isInside(segment, positionMs, alreadySkipped)
}

/** The segment playback is inside that the profile wants offered rather than skipped, if any. */
internal fun segmentToOffer(
    segments: List<YouTubeSkipSegment>,
    positionMs: Long,
    alreadySkipped: Set<YouTubeSkipSegment>,
    modes: Map<SponsorBlockCategory, SponsorBlockMode>
): YouTubeSkipSegment? = segments.firstOrNull { segment ->
    modeOf(segment, modes) == SponsorBlockMode.ASK && isInside(segment, positionMs, alreadySkipped)
}

private fun isInside(segment: YouTubeSkipSegment, positionMs: Long, alreadySkipped: Set<YouTubeSkipSegment>) =
    segment !in alreadySkipped &&
        positionMs >= segment.startMs &&
        // Within the last half second the skip would land about where playback is anyway.
        positionMs < segment.endMs - 500

/** What playback does with [segment]: its category's choice, else the category's default. Unknown categories skip. */
internal fun modeOf(segment: YouTubeSkipSegment, modes: Map<SponsorBlockCategory, SponsorBlockMode>): SponsorBlockMode {
    val category = segment.sponsorBlockCategory ?: return SponsorBlockMode.SKIP
    return modes[category] ?: category.defaultMode
}

/** The chapter playback is in, if the video has chapters. */
internal fun chapterAt(chapters: List<YouTubeChapter>, positionMs: Long): YouTubeChapter? =
    chapters.lastOrNull { it.startMs <= positionMs }

/** Where the chapter after the one playing starts, or null in the last chapter. */
internal fun nextChapterStart(chapters: List<YouTubeChapter>, positionMs: Long): Long? =
    chapters.firstOrNull { it.startMs > positionMs + CHAPTER_EDGE_MS }?.startMs

/**
 * Where Previous goes: the start of the playing chapter, or the chapter before it when playback
 * is just past that start, as a music player's Previous does. Null without chapters.
 */
internal fun previousChapterStart(chapters: List<YouTubeChapter>, positionMs: Long): Long? {
    if (chapters.isEmpty()) return null
    val current = chapters.indexOfLast { it.startMs <= positionMs }
    if (current < 0) return 0L
    val start = chapters[current].startMs
    return if (positionMs - start > PREVIOUS_RESTARTS_AFTER_MS || current == 0) start else chapters[current - 1].startMs
}

/** A chapter starting this close ahead counts as the one playing, so Next doesn't land a moment on. */
private const val CHAPTER_EDGE_MS = 1_000L
private const val PREVIOUS_RESTARTS_AFTER_MS = 3_000L
