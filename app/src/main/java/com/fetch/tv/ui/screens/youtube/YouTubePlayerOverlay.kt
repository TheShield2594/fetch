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
import com.fetch.tv.data.youtube.YouTubeCaptions
import com.fetch.tv.data.youtube.YouTubeLikeStatus
import com.fetch.tv.data.youtube.YouTubePlaylistOption
import com.fetch.tv.data.youtube.YouTubePlaylistPrivacy
import com.fetch.tv.data.youtube.SponsorBlockCategory
import com.fetch.tv.data.youtube.SponsorBlockMode
import com.fetch.tv.data.youtube.YouTubeChapter
import com.fetch.tv.data.youtube.YouTubeSkipSegment
import com.fetch.tv.data.youtube.YouTubeResume
import com.fetch.tv.data.youtube.YouTubeVideo
import com.fetch.tv.data.youtube.YouTubeVideoActions
import com.fetch.tv.data.youtube.cueAt
import com.fetch.tv.ui.components.LoadingIndicator
import com.fetch.tv.ui.screens.ambient.AmbientDialogEffect
import com.fetch.tv.ui.screens.detail.requestFocusAfterFrames
import com.fetch.tv.ui.screens.detail.TrailerSeekOverlayState
import com.fetch.tv.ui.theme.FetchMotion
import com.fetch.tv.ui.theme.FetchTheme
import kotlinx.coroutines.delay
import com.fetch.tv.data.trailer.YouTubeExtractionFailure

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
    /** The remote's CC button: captions off, or on in the saved or preferred language. */
    onToggleCaptions: () -> Unit,
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
    onResumed: (positionMs: Long) -> Unit = {},
    /** The player stopped on an error; the overlay then offers Retry. */
    onPlaybackError: () -> Unit = {},
    onSetQuality: (Int?) -> Unit = {},
    onLoadDetails: () -> Unit = {},
    onLoadMoreComments: () -> Unit = {},
    /** Up Next, started by the end card's countdown rather than picked. */
    onAutoplayNext: (YouTubeVideo) -> Unit = onPlayNext
) {
    val video = playback.video
    var isPaused by remember(video.videoId) { mutableStateOf(false) }
    var controlsVisible by remember(video.videoId) { mutableStateOf(false) }
    val seekState = remember(video.videoId) { TrailerSeekOverlayState() }
    // Per attempt: a Retry's new player must not replay the last attempt's seek.
    var seekToken by remember(video.videoId, playback.attempt) { mutableIntStateOf(0) }
    var seekDeltaMs by remember(video.videoId, playback.attempt) { mutableLongStateOf(0L) }
    // Held D-pad presses and rewind/fast-forward scrub: the player seeks once they pause.
    var seekScrub by remember(video.videoId, playback.attempt) { mutableStateOf(false) }
    // Per attempt, not per video: Retry's fresh stream resumes from the saved place again.
    var resumed by remember(video.videoId, playback.attempt) { mutableStateOf(false) }
    // A quality change restarts the stream: it carries on where it was, once, per new link.
    var continued by remember(playback.videoUrl) { mutableStateOf(false) }
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
    val currentContinueFrom by rememberUpdatedState(playback.continueFromMs)
    // Down's menu: its first page, or one of captions, speed and chapters.
    var optionsPage by remember(video.videoId) { mutableStateOf<PlaybackOptionsPage?>(null) }
    val captionMenuOpen = optionsPage != null
    var actionsMenuOpen by remember(video.videoId) { mutableStateOf(false) }
    // The Save list opens over the actions panel, in a dialog of its own; playback carries on behind.
    var playlistPickerOpen by remember(video.videoId) { mutableStateOf(false) }
    var playlistPickerClosed by remember(video.videoId) { mutableIntStateOf(0) }
    var positionMs by remember(video.videoId) { mutableLongStateOf(0L) }
    var ended by remember(video.videoId) { mutableStateOf(false) }
    // Per video, across attempts: an expired link is re-extracted once by itself; a second
    // failure offers Retry, so a link that keeps failing never loops (#740).
    var expiredLinkRetried by remember(video.videoId) { mutableStateOf(false) }

    val focusRequester = remember { FocusRequester() }
    val saveFocusRequester = remember { FocusRequester() }
    val retryFocusRequester = remember { FocusRequester() }
    // The quality a pick is fetching, for the spinner on its row; the menu stays open meanwhile.
    var pendingQuality by remember(video.videoId) { mutableStateOf<Int?>(null) }
    var qualityPickInFlight by remember(video.videoId) { mutableStateOf(false) }
    val canControl = !playback.videoUrl.isNullOrBlank() && !playback.isLoading && !playback.failed
    // Without the watch page (it failed, or hasn't loaded), the menu still offers Save and the tile's channel.
    val menuActions = playback.actions
        ?: YouTubeVideoActions(likeStatus = null, channelId = video.channelId, channelName = video.channelName, subscribed = null)

    fun seekBy(deltaMs: Long, scrub: Boolean = false) {
        seekDeltaMs = deltaMs
        seekScrub = scrub
        seekToken += 1
        // Where the seek lands, until the next progress report says otherwise: a seek straight
        // after this one (Left to undo an auto-skip) must not measure from the old place.
        positionMs = (positionMs + deltaMs).coerceAtLeast(0L)
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
    // On failure, Retry takes focus so the first OK press retries; back on the video once it goes.
    LaunchedEffect(playback.failed) {
        if (playback.failed) retryFocusRequester.requestFocusAfterFrames()
        else focusRequester.requestFocusAfterFrames()
    }
    // A quality pick keeps its menu open with a spinner, and closes it once the stream is back.
    LaunchedEffect(playback.qualityLoading) {
        if (playback.qualityLoading || !qualityPickInFlight) return@LaunchedEffect
        qualityPickInFlight = false
        pendingQuality = null
        if (optionsPage == PlaybackOptionsPage.QUALITY) closeMenus()
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
                            onToggleCaptions()
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
                                seekBy(-seekStepMs(native.repeatCount), scrub = true)
                            }
                            controlsVisible = true
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                            if (!canControl) return@onPreviewKeyEvent false
                            seekBy(seekStepMs(native.repeatCount), scrub = true)
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

            val videoUrl = playback.videoUrl
            if (!videoUrl.isNullOrBlank() && !playback.failed && !ended) {
                YouTubeVideoPlayer(
                    videoUrl = videoUrl,
                    audioUrl = playback.audioUrl,
                    onError = { error ->
                        if (!expiredLinkRetried && isExpiredStreamLink(error)) {
                            // Retry carries on from where this attempt stopped, with a fresh link.
                            expiredLinkRetried = true
                            onRetry()
                        } else {
                            onPlaybackError()
                        }
                    },
                    onPlayPauseRequest = { play ->
                        isPaused = !play
                        controlsVisible = true
                    },
                    isPaused = isPaused,
                    playbackSpeed = playbackSpeed,
                    seekRequestToken = seekToken,
                    seekDeltaMs = seekDeltaMs,
                    seekScrub = seekScrub,
                    // The link opens where it should start, rather than at 0:00 and then jumping:
                    // a Retry or a quality change where the last link was (#742), a new video
                    // where it was left, when its tile says how long it is (#743).
                    startPositionMs = when {
                        !resumed -> playback.continueFromMs
                            ?: YouTubeResume.durationLabelMs(video.duration)?.let {
                                YouTubeResume.startPositionMs(playback.resumeFromMs, video.watchedPercent, it)
                            }
                        !continued -> playback.continueFromMs
                        else -> null
                    } ?: 0L,
                    onProgressChanged = progress@{ position, duration, seeking ->
                        positionMs = position
                        seekState.positionMs = position
                        seekState.durationMs = duration
                        // A scrub's target only moves the seek bar: skips and saved progress wait
                        // until it commits, so a target passing a segment doesn't cut the scrub short.
                        if (duration <= 0L || seeking) return@progress
                        val continueFrom = currentContinueFrom
                        if (!continued && continueFrom != null && resumed) {
                            continued = true
                            // The new link already started there; seek only if it didn't.
                            if (kotlin.math.abs(continueFrom - position) > CONTINUE_TOLERANCE_MS) {
                                seekBy(continueFrom - position)
                            }
                            return@progress
                        }
                        if (!resumed) {
                            resumed = true
                            // A Retry carries on exactly where the failed attempt stopped, wherever that was.
                            val retryFrom = continueFrom?.takeIf { !continued }
                            if (retryFrom != null) continued = true
                            // Otherwise Fetch's own exact place first, then YouTube's percent from the tile.
                            // YouTube's wins when it's clearly further on (watched on another device since).
                            val target = retryFrom
                                ?: YouTubeResume.startPositionMs(currentResumeFrom, video.watchedPercent, duration)
                            target?.let {
                                // The link usually opened there already; seek only if it didn't.
                                if (kotlin.math.abs(it - position) > CONTINUE_TOLERANCE_MS) seekBy(it - position)
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
                        text = stringResource(youTubeFailureMessage(playback.failureReason)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                    Button(
                        onClick = onRetry,
                        modifier = Modifier.focusRequester(retryFocusRequester)
                    ) { Text(text = stringResource(R.string.action_retry)) }
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
                    onAutoplay = onAutoplayNext,
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
                    qualityLabel = playingQualityLabel(playback.quality, playback.playingHeight, playback.defaultQuality),
                    currentChapter = chapterAt(playback.chapters, positionMs)?.title,
                    hasChapters = playback.chapters.isNotEmpty(),
                    onOpen = { page ->
                        if (page == PlaybackOptionsPage.DETAILS) onLoadDetails()
                        optionsPage = page
                    },
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
                PlaybackOptionsPage.QUALITY -> QualityMenu(
                    quality = playback.quality,
                    defaultQuality = playback.defaultQuality,
                    available = playback.availableQualities,
                    loading = playback.qualityLoading,
                    pending = pendingQuality,
                    onSelect = { quality ->
                        if (quality == playback.quality && !playback.qualityLoading) {
                            closeMenus()
                        } else {
                            pendingQuality = quality
                            qualityPickInFlight = true
                            onSetQuality(quality)
                        }
                    },
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
                PlaybackOptionsPage.DETAILS -> VideoDetailsPanel(
                    details = playback.details,
                    loading = playback.detailsLoading,
                    failed = playback.detailsFailed,
                    loadingMore = playback.commentsLoadingMore,
                    onNearEnd = onLoadMoreComments,
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
    onAutoplay: (YouTubeVideo) -> Unit,
    onClose: () -> Unit
) {
    val next = playback.upNext
    val nextFocus = remember { FocusRequester() }
    // After enough autoplayed videos in a row, it asks rather than playing on to an empty room.
    val askStillWatching = playback.autoplay && next != null && shouldAskStillWatching(playback.autoplayStreak, playback.stillWatchingAfter)
    var secondsLeft by remember(next?.videoId) { mutableIntStateOf(UP_NEXT_COUNTDOWN_SECONDS) }
    var countdownStopped by remember(next?.videoId) { mutableStateOf(!playback.autoplay || next == null || askStillWatching) }
    val currentOnPlay by rememberUpdatedState(onAutoplay)
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
                        text = when {
                            askStillWatching -> stringResource(R.string.still_watching_title)
                            countdownStopped -> stringResource(R.string.youtube_up_next_select)
                            else -> pluralStringResource(R.plurals.youtube_up_next_countdown, secondsLeft, secondsLeft)
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

/** Whether the end card asks "Are you still watching?": [streak] autoplayed videos reached [after]. */
internal fun shouldAskStillWatching(streak: Int, after: Int?): Boolean = after != null && streak >= after
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
                        drawStyle = Stroke(width = SubtitleStyleSettings.OUTLINE_WIDTH * CAPTION_OUTLINE_SCALE),
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
        YouTubeCaptions.menuOrder(playback.captionTracks)
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
/** How far from the place it should carry on from a swapped stream may start before it seeks. */
private const val CONTINUE_TOLERANCE_MS = 2_000L

/** 10 seconds a press; holding the button speeds up to 30 seconds, then a minute. */
internal fun seekStepMs(repeatCount: Int): Long = when {
    repeatCount >= 20 -> 60_000L
    repeatCount >= 8 -> 30_000L
    else -> 10_000L
}

/** A 403 or 410 from googlevideo: the stream link expired (a long pause) or was revoked. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
/** The message for a video that won't play; [reason] null is an error during playback (#741). */
@androidx.annotation.StringRes
internal fun youTubeFailureMessage(reason: YouTubeExtractionFailure?): Int = when (reason) {
    YouTubeExtractionFailure.AGE_RESTRICTED -> R.string.youtube_play_failed_age_restricted
    YouTubeExtractionFailure.SIGN_IN_REQUIRED -> R.string.youtube_play_failed_sign_in
    YouTubeExtractionFailure.REGION_BLOCKED -> R.string.youtube_play_failed_region
    YouTubeExtractionFailure.LIVE_NOT_STARTED -> R.string.youtube_play_failed_live_not_started
    YouTubeExtractionFailure.UNAVAILABLE -> R.string.youtube_play_failed_unavailable
    YouTubeExtractionFailure.RATE_LIMITED -> R.string.youtube_play_failed_rate_limited
    YouTubeExtractionFailure.FAILED -> R.string.youtube_play_failed_network
    YouTubeExtractionFailure.NO_PLAYABLE_STREAM, null -> R.string.youtube_play_failed
}

internal fun isExpiredStreamLink(error: Throwable): Boolean {
    var cause: Throwable? = error
    var depth = 0
    while (cause != null && depth < 8) {
        if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
            return cause.responseCode == 403 || cause.responseCode == 410
        }
        cause = cause.cause
        depth++
    }
    return false
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
