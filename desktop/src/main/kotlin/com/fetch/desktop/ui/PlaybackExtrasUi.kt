package com.fetch.desktop.ui

import com.fetch.desktop.i18n.tr
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.fetch.desktop.AppState
import com.fetch.desktop.PlaybackExtras
import com.fetch.desktop.PlayerSession
import com.fetch.desktop.label
import com.fetch.desktop.playback.NextEpisodeRules
import com.fetch.desktop.playback.ParentalWarning
import com.fetch.desktop.playback.PostPlayTiming
import com.fetch.desktop.playback.QueueItem
import com.fetch.tv.core.streams.ranking.PreferenceSuggestion
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.Video

// --- Adding to the queue ---

/** "Add to queue" on a title page: the movie, or the episode the page is offering. */
@Composable
fun QueueDetailButton(state: AppState, preview: MetaPreview) {
    val sources = state.sources?.takeIf { it.preview.id == preview.id }
    val videoId = sources?.videoId ?: preview.id.takeIf { preview.type != ContentType.SERIES } ?: return
    val queued = state.extras.isQueued(videoId)
    SecondaryButton(
        if (queued) "In queue" else "Add to queue",
        icon = if (queued) FetchIcons.Check else FetchIcons.Add,
        onClick = {
            val video = sources?.video
            when {
                queued -> state.extras.remove(videoId)
                video != null -> state.addEpisodeToQueue(preview, video)
                else -> state.addToQueue(preview)
            }
        }
    )
}

/** An episode's or poster's "Add to queue" / "Remove from queue". */
fun queueMenuItem(state: AppState, preview: MetaPreview, video: Video? = null): ContextMenuItem? {
    val videoId = video?.id ?: preview.id.takeIf { preview.type != ContentType.SERIES }
    if (videoId != null && state.extras.isQueued(videoId)) return ContextMenuItem(tr("Remove from queue")) { state.extras.remove(videoId) }
    return ContextMenuItem(tr("Add to queue")) { if (video != null) state.addEpisodeToQueue(preview, video) else state.addToQueue(preview) }
}

// --- In the built-in player ---

/**
 * What the TV player shows beside its controls that the desktop adds: the content warnings at
 * the start, the Queue panel, the "Up next in queue" card, and the movie's recommendation.
 */
@Composable
fun BoxScope.PlayerExtrasLayer(state: AppState, session: PlayerSession) {
    val extras = state.extras
    ParentalGuideOverlay(extras.guideLines, extras.guideVisible, Modifier.align(Alignment.TopStart).padding(start = 48.dp, top = 96.dp))

    // Nothing of this show next, but something queued: "Up next in queue" from the threshold.
    val target = session.target
    val head = extras.upcoming(target?.videoId).firstOrNull()
    val mpv = session.mpv
    if (head != null && session.nextEpisode == null && !session.upNextDismissed && target != null && extras.postPlay?.visible != true &&
        state.settings.player.autoplayNextEpisode &&
        NextEpisodeRules.thresholdReached(mpv.position, mpv.duration, session.segments, state.settings.autoPlay)
    ) {
        QueueUpNextCard(
            head,
            seconds = (mpv.duration - mpv.position).toInt().coerceAtLeast(0),
            onPlay = { state.playQueueItem(head) },
            onCancel = state::cancelUpNext,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 150.dp)
        )
    }

    // The recommendation, from its point near the end (it stays after the movie ends).
    val postPlay = extras.postPlay
    if (postPlay != null && target != null && postPlay.forVideoId == target.videoId && !postPlay.dismissed && !postPlay.loading) {
        val due = postPlay.visible || PostPlayTiming.shouldShow(mpv.position, mpv.duration, session.segments, state.settings.autoPlay)
        LaunchedEffect(due) { if (due) extras.showPostPlay(target.videoId, inPlayer = true) }
        if (postPlay.visible && postPlay.inPlayer) {
            val countdown = postPlay.endCountdown
                ?: PostPlayTiming.countdownSeconds(mpv.position, mpv.duration)?.takeIf { postPlay.current?.trailerYtId != null && !postPlay.trailerPlayed }
            PostPlayOverlay(state, postPlay, countdown, Modifier.align(Alignment.BottomStart))
        }
    }

    if (extras.queuePanelOpen) {
        QueueSidePanel(state, currentVideoId = target?.videoId, modifier = Modifier.align(Alignment.CenterEnd))
    }
}

/** Over the whole window (the player or the app): "Prefer this next time", and a movie's recommendation after mpv's window closed. */
@Composable
fun BoxScope.AppExtrasLayer(state: AppState) {
    val extras = state.extras
    extras.preferSuggestions?.let { suggestions ->
        PreferNextTimePrompt(
            suggestions,
            onPrefer = extras::prefer,
            onDismiss = extras::dismissPrefer,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 48.dp, end = 48.dp)
        )
    }
    val postPlay = extras.postPlay
    if (postPlay != null && postPlay.visible && !postPlay.inPlayer && state.playerSession == null) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}) {
            PostPlayOverlay(state, postPlay, countdown = null, Modifier.align(Alignment.BottomStart))
        }
    }
}

// --- The pieces ---

/**
 * The TV's parental guide overlay: an accent line that grows down beside the warnings, shown for
 * five seconds as playback starts.
 */
@Composable
fun ParentalGuideOverlay(lines: List<ParentalWarning>, visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible && lines.isNotEmpty(),
        enter = fadeIn(tween(250)) + expandVertically(tween(300), expandFrom = Alignment.Top),
        exit = fadeOut(tween(250)),
        modifier = modifier
    ) {
        Row {
            Box(Modifier.width(3.dp).height((lines.size * 22).dp).clip(RoundedCornerShape(2.dp)).background(FetchPalette.Accent))
            Column(Modifier.padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                lines.forEach { line ->
                    Row(Modifier.height(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(line.label, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(" · ", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp)
                        Text(line.severity, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/** "Prefer this next time?" with up to two suggestions and Not now (the TV's PreferNextTimePrompt). */
@Composable
fun PreferNextTimePrompt(
    suggestions: List<PreferenceSuggestion>,
    onPrefer: (PreferenceSuggestion) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .widthIn(max = 560.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.82f))
            .border(1.dp, FetchPalette.SurfaceHigh, RoundedCornerShape(14.dp))
            .padding(20.dp)
    ) {
        Text(tr("Prefer this next time?"), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            suggestions.forEachIndexed { index, suggestion ->
                if (index == 0) PrimaryButton(suggestion.label, onClick = { onPrefer(suggestion) }, icon = FetchIcons.Check)
                else SecondaryButton(suggestion.label, onClick = { onPrefer(suggestion) })
            }
            SecondaryButton("Not now", onClick = onDismiss)
        }
    }
}

/** The Up Next card for the queue's next item ("Up next in queue"). */
@Composable
private fun QueueUpNextCard(item: QueueItem, seconds: Int, onPlay: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .width(460.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(FetchPalette.Surface.copy(alpha = 0.95f))
            .border(1.dp, FetchPalette.SurfaceHigh, RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        QueueThumb(item)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("UP NEXT IN QUEUE · $seconds S", style = OverlineStyle, color = FetchPalette.Accent)
            Text(item.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            item.subtitle?.let { Text(it, color = FetchPalette.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                PrimaryButton("Play now", onClick = onPlay)
                SecondaryButton("Cancel", onClick = onCancel)
            }
        }
    }
}

@Composable
private fun QueueThumb(item: QueueItem) {
    Box(Modifier.width(112.dp).height(63.dp).clip(RoundedCornerShape(8.dp)).background(FetchPalette.SurfaceHigh)) {
        AsyncImage(model = item.backdrop ?: item.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/** The player's Queue panel: what plays after this, in order, with play, reorder, remove and undo (the TV's QueueSidePanel). */
@Composable
fun QueueSidePanel(state: AppState, currentVideoId: String?, modifier: Modifier = Modifier) {
    val extras = state.extras
    val upcoming = extras.upcoming(currentVideoId)
    val listState = rememberLazyListState()
    Column(
        modifier
            .width(460.dp)
            .fillMaxHeight()
            .background(FetchPalette.Background.copy(alpha = 0.96f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(top = 24.dp, start = 24.dp, end = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp, bottom = 16.dp)) {
            Text(tr("Queue"), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            CircleIconButton(FetchIcons.Close, "Close", onClick = { extras.queuePanelOpen = false }, size = 40.dp)
        }
        extras.lastRemoved?.let { (item, _) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 12.dp, bottom = 12.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp)).background(FetchPalette.SurfaceRaised).padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(tr("Removed %1\$s from the queue", item.label), color = FetchPalette.TextSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                SecondaryButton("Undo", onClick = extras::undoRemove)
            }
        }
        if (upcoming.isEmpty()) {
            Text(
                tr("Nothing in your queue. Right-click a title, an episode or a Continue Watching card and choose Add to queue."),
                color = FetchPalette.TextMuted,
                modifier = Modifier.padding(end = 12.dp)
            )
            return@Column
        }
        ScrollbarBox(listState, Modifier.weight(1f)) {
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(end = 14.dp)) {
                itemsIndexed(upcoming, key = { _, item -> item.videoId }) { index, item ->
                    QueueRow(
                        item,
                        canMoveUp = index > 0,
                        canMoveDown = index < upcoming.size - 1,
                        onPlay = { state.playQueueItem(item) },
                        onMove = { up -> extras.move(item.videoId, up) },
                        onRemove = { extras.remove(item.videoId, undoable = true) }
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun QueueRow(item: QueueItem, canMoveUp: Boolean, canMoveDown: Boolean, onPlay: () -> Unit, onMove: (Boolean) -> Unit, onRemove: () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(FetchPalette.Surface)
            .clickable(onClick = onPlay).padding(10.dp)
    ) {
        QueueThumb(item)
        Column(Modifier.weight(1f)) {
            Text(item.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            item.subtitle?.let { Text(it, color = FetchPalette.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        CircleIconButton(FetchIcons.ArrowUp, "Move up", onClick = { onMove(true) }, size = 34.dp, enabled = canMoveUp)
        CircleIconButton(FetchIcons.ArrowDown, "Move down", onClick = { onMove(false) }, size = 34.dp, enabled = canMoveDown)
        CircleIconButton(FetchIcons.Delete, "Remove", onClick = onRemove, size = 34.dp)
    }
}

/**
 * The recommendation near the end of a movie (the TV's PostPlayRecommendationOverlay): backdrop,
 * logo or title, facts, description, Play, Details, Trailer, previous/next, and Back.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PostPlayOverlay(state: AppState, postPlay: PlaybackExtras.PostPlay, countdown: Int?, modifier: Modifier = Modifier) {
    val recommendation = postPlay.current ?: return
    Box(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))
            .padding(horizontal = 48.dp, vertical = 40.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.width(384.dp).height(216.dp).clip(RoundedCornerShape(14.dp)).background(FetchPalette.SurfaceHigh)) {
                AsyncImage(model = recommendation.backdrop, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Because you watched %1\$s", postPlay.movieTitle).uppercase(), style = OverlineStyle, color = FetchPalette.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (recommendation.logo != null) {
                    AsyncImage(model = recommendation.logo, contentDescription = recommendation.title, modifier = Modifier.height(64.dp).widthIn(max = 360.dp), alignment = Alignment.CenterStart)
                } else {
                    Text(recommendation.title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                FactsLine(recommendation.facts, imdbRating = recommendation.rating)
                recommendation.description?.let {
                    Text(it, color = FetchPalette.TextSecondary, fontSize = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 760.dp))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                    PrimaryButton(if (recommendation.preview.type == ContentType.SERIES) "Open" else "Play", onClick = { state.playRecommendation(recommendation) })
                    SecondaryButton("Details", icon = FetchIcons.Info, onClick = { state.playRecommendation(recommendation, details = true) })
                    if (recommendation.trailerYtId != null && !postPlay.trailerPlaying) {
                        SecondaryButton(countdown?.let { "Trailer in $it" } ?: "Trailer", icon = FetchIcons.Trailer, onClick = { state.recommendationTrailer(recommendation) })
                    }
                    if (postPlay.trailerPlaying) Text(tr("Trailer playing"), color = FetchPalette.TextSecondary, modifier = Modifier.align(Alignment.CenterVertically))
                    if (postPlay.items.size > 1) {
                        CircleIconButton(FetchIcons.ChevronLeft, "Previous recommendation", onClick = { state.extras.pagePostPlay(-1) }, enabled = postPlay.index > 0)
                        CircleIconButton(FetchIcons.ChevronRight, "Next recommendation", onClick = { state.extras.pagePostPlay(1) }, enabled = postPlay.index < postPlay.items.size - 1)
                    }
                    SecondaryButton("Back", icon = FetchIcons.Back, onClick = state::closeRecommendation)
                }
            }
        }
    }
}
