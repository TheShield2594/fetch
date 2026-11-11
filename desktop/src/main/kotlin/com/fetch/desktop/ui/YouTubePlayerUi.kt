package com.fetch.desktop.ui

import com.fetch.desktop.i18n.tr
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.fetch.desktop.AppState
import com.fetch.desktop.PlayerSession
import com.fetch.desktop.youtube.YouTubeChapters
import com.fetch.desktop.youtube.YouTubePlayback
import com.fetch.desktop.youtube.YouTubePlayerExtras
import com.fetch.desktop.youtube.YouTubePlayerPanel
import com.fetch.desktop.youtube.YouTubeUpNextPrompt
import com.fetch.desktop.youtube.YouTubeCaptions
import com.fetch.desktop.youtube.label
import com.fetch.desktop.youtube.youTubeExtras
import com.fetch.tv.data.youtube.SponsorBlockCategory
import com.fetch.tv.data.youtube.YouTubeChapter
import com.fetch.tv.data.youtube.YouTubeLikeStatus
import com.fetch.tv.data.youtube.YouTubePlaylistOption
import com.fetch.tv.data.youtube.YouTubePlaylistPrivacy
import com.fetch.tv.data.youtube.YouTubeSkipSegment
import com.fetch.tv.data.youtube.YouTubeStoryboard
import com.fetch.tv.data.youtube.YouTubeVideo
import com.fetch.tv.data.youtube.YouTubeVideoActions
import com.fetch.tv.data.youtube.YouTubeVoteCounts
import kotlinx.coroutines.delay

// The built-in player's YouTube parts, as the TV's YouTube player overlay has them: SponsorBlock
// marks, chapter breaks and storyboard frames on the seek bar, the like / dislike / subscribe /
// save row, the Chapters, Captions and Save panels, the "undo skip" notice and the end card.

/** SponsorBlock's colours for each category, the TV's (and SponsorBlock's own). */
internal fun sponsorBlockColor(category: SponsorBlockCategory?): Color = when (category) {
    SponsorBlockCategory.SPONSOR, null -> Color(0xFF00D400)
    SponsorBlockCategory.SELF_PROMO -> Color(0xFFFFFF00)
    SponsorBlockCategory.INTERACTION -> Color(0xFFCC00FF)
    SponsorBlockCategory.INTRO -> Color(0xFF00FFFF)
    SponsorBlockCategory.OUTRO -> Color(0xFF0202ED)
    SponsorBlockCategory.PREVIEW -> Color(0xFF008FD6)
    SponsorBlockCategory.MUSIC_OFFTOPIC -> Color(0xFFFF9900)
    SponsorBlockCategory.FILLER -> Color(0xFF7300FF)
}

/**
 * The player's seek bar ([content], its Slider) with a YouTube video's SponsorBlock marks and
 * chapter breaks drawn over the track, and, under the pointer, the storyboard frame and time for
 * that point. Anything else plays with the bar as it is.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun YouTubeSeekBar(session: PlayerSession, duration: Double, content: @Composable () -> Unit) {
    val extras = session.youTubeExtras()
    if (extras == null) {
        content()
        return
    }
    val chapters = playerChapters(session, extras)
    var hoverX by remember { mutableStateOf<Float?>(null) }
    var width by remember { mutableStateOf(0) }
    Box(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { width = it.width }
            .onPointerEvent(PointerEventType.Move) { hoverX = it.changes.firstOrNull()?.position?.x }
            .onPointerEvent(PointerEventType.Exit) { hoverX = null }
    ) {
        content()
        SeekBarMarks(extras.marks, chapters, duration, Modifier.matchParentSize())
        val x = hoverX
        if (x != null && duration > 0 && width > 0) {
            val inset = with(androidx.compose.ui.platform.LocalDensity.current) { SeekTrackInset.toPx() }
            val fraction = ((x - inset) / (width - 2 * inset)).coerceIn(0f, 1f)
            val positionMs = (fraction * duration * 1000).toLong()
            SeekPreviewAbove(x, Modifier.matchParentSize()) {
                SeekPreview(
                    storyboard = extras.storyboard,
                    positionMs = positionMs,
                    durationMs = (duration * 1000).toLong(),
                    chapter = YouTubeChapters.at(chapters, positionMs)?.title?.takeIf { it.isNotBlank() }
                )
            }
        }
    }
}

/** How far in from each side the Slider's track runs: half its thumb. */
internal val SeekTrackInset = 2.dp

/** Marks and breaks over the Slider's track, which sits in the middle of its height. */
@Composable
internal fun SeekBarMarks(marks: List<YouTubeSkipSegment>, chapters: List<YouTubeChapter>, duration: Double, modifier: Modifier = Modifier) {
    if (duration <= 0 || (marks.isEmpty() && chapters.size < 2)) return
    Canvas(modifier) {
        val inset = SeekTrackInset.toPx()
        val left = inset
        val span = size.width - 2 * inset
        val durationMs = duration * 1000
        val markHeight = SeekMarkHeight.toPx()
        val top = size.height / 2 - markHeight / 2
        marks.forEach { segment ->
            val start = (segment.startMs / durationMs).toFloat().coerceIn(0f, 1f)
            val end = (segment.endMs / durationMs).toFloat().coerceIn(0f, 1f)
            if (end <= start) return@forEach
            drawRect(
                color = sponsorBlockColor(segment.sponsorBlockCategory).copy(alpha = 0.9f),
                topLeft = Offset(left + start * span, top),
                size = Size(maxOf((end - start) * span, 2f), markHeight)
            )
        }
        // A gap between chapters, as the TV's bar breaks into one piece per chapter.
        if (chapters.size > 1) {
            val gap = 3.dp.toPx()
            val breakHeight = SeekBreakHeight.toPx()
            chapters.drop(1).forEach { chapter ->
                val at = (chapter.startMs / durationMs).toFloat()
                if (at <= 0f || at >= 1f) return@forEach
                drawRect(Color.Black.copy(alpha = 0.85f), Offset(left + at * span - gap / 2, size.height / 2 - breakHeight / 2), Size(gap, breakHeight))
            }
        }
    }
}

private val SeekMarkHeight = 6.dp
private val SeekBreakHeight = 18.dp

/** Places [content] centred over [x], just above the seek bar, kept inside the bar's width. */
@Composable
private fun SeekPreviewAbove(x: Float, modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val placeable = measurables.first().measure(Constraints())
        layout(constraints.maxWidth, constraints.maxHeight) {
            val left = (x - placeable.width / 2).toInt().coerceIn(0, (constraints.maxWidth - placeable.width).coerceAtLeast(0))
            placeable.place(left, -placeable.height - 4.dp.roundToPx())
        }
    }
}

/** The storyboard frame for [positionMs] (when YouTube made one), with the time and chapter. */
@Composable
internal fun SeekPreview(storyboard: YouTubeStoryboard?, positionMs: Long, durationMs: Long, chapter: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        storyboard?.frameAt(positionMs, durationMs)?.let { frame -> StoryboardFrame(frame, storyboard) }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = PreviewWidth).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.8f))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            chapter?.let { Text(it, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Text(clock(positionMs / 1000.0), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** One storyboard frame: its sheet, drawn at a scale and shifted so only the frame shows (the TV's). */
@Composable
private fun StoryboardFrame(frame: YouTubeStoryboard.Frame, storyboard: YouTubeStoryboard) {
    val scale = PreviewWidth.value / frame.width
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .size(PreviewWidth, (frame.height * scale).dp)
            .clip(shape)
            .border(2.dp, Color.White, shape)
            .background(Color.Black)
            .clipToBounds()
    ) {
        AsyncImage(
            model = frame.sheetUrl,
            contentDescription = null,
            // The last sheet can have fewer rows: fit its width and keep it at the top.
            contentScale = ContentScale.FillWidth,
            alignment = Alignment.TopStart,
            modifier = Modifier
                .wrapContentSize(Alignment.TopStart, unbounded = true)
                .requiredSize((storyboard.columns * frame.width * scale).dp, (storyboard.rows * frame.height * scale).dp)
                .offset(x = (-frame.x * scale).dp, y = (-frame.y * scale).dp)
        )
    }
}

private val PreviewWidth = 240.dp

/** The video's chapters: mpv's (yt-dlp reads them), else the ones on YouTube's watch page. */
@Composable
private fun playerChapters(session: PlayerSession, extras: YouTubePlayerExtras): List<YouTubeChapter> {
    val fromMpv by produceState(emptyList<YouTubeChapter>(), extras) {
        // mpv knows them once the file opens; ask until it says.
        repeat(60) {
            val chapters = YouTubeChapters.fromMpv(session.mpv.property("chapter-list"))
            if (chapters.isNotEmpty()) {
                value = chapters
                return@produceState
            }
            delay(2_000)
        }
    }
    return fromMpv.ifEmpty { extras.chapters }
}

/** The bottom row's YouTube buttons: Chapters (when the video has them) and Captions. */
@Composable
fun YouTubePlayerButtons(session: PlayerSession) {
    val extras = session.youTubeExtras() ?: return
    val chapters = playerChapters(session, extras)
    fun toggle(panel: YouTubePlayerPanel) {
        session.panel = null
        extras.panel = if (extras.panel == panel) null else panel
    }
    if (chapters.isNotEmpty()) SecondaryButton("Chapters", onClick = { toggle(YouTubePlayerPanel.CHAPTERS) })
    SecondaryButton("Captions", onClick = { toggle(YouTubePlayerPanel.CAPTIONS) })
}

/**
 * Over the built-in player while a YouTube video plays: the actions row (top right, with the
 * controls), the "Skipped sponsor · Watch it" notice and the YouTube side panel.
 */
@Composable
fun BoxScope.YouTubePlayerLayer(state: AppState, session: PlayerSession, controlsVisible: Boolean) {
    val extras = session.youTubeExtras() ?: return
    val yt = state.youtube
    // The player's own panels take the right side; this one makes way.
    LaunchedEffect(session.panel) {
        if (session.panel != null) extras.panel = null
    }
    // Picking another subtitle in the player's own menu turns Fetch's caption off, including one
    // still downloading (so it can't land over the pick). mpv showing the caption itself (its
    // fallback) isn't another subtitle.
    val mpvSubtitle = session.mpv.tracks.firstOrNull { it.type == "sub" && it.selected }?.id
    LaunchedEffect(mpvSubtitle) {
        if (mpvSubtitle != null && extras.selectedCaption != null && mpvSubtitle !in extras.addedCaptions.values) {
            extras.captionCues = emptyList()
            extras.selectedCaption = null
        }
    }
    if (extras.captionCues.isNotEmpty()) {
        YouTubeCaptionText(state, session, extras, controlsVisible)
    }
    AnimatedVisibility(
        controlsVisible && extras.panel == null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.TopEnd).padding(top = 24.dp, end = 32.dp)
    ) {
        YouTubeActionsRow(
            actions = extras.actions,
            votes = extras.votes,
            failed = extras.actionFailed,
            signedIn = yt.signedIn,
            onRate = yt::ratePlaying,
            onSubscribe = yt::togglePlayingSubscription,
            onSave = {
                session.panel = null
                extras.panel = YouTubePlayerPanel.SAVE
            },
            onChannel = yt::openPlayingChannel
        )
    }
    extras.skipNotice?.let { notice ->
        LaunchedEffect(notice) {
            delay(SKIP_NOTICE_MS)
            if (extras.skipNotice == notice) extras.skipNotice = null
        }
        SkipNoticeCard(notice.label, onWatch = {
            extras.skipNotice = null
            session.controls.seekTo(notice.startSec)
        }, modifier = Modifier.align(Alignment.TopEnd).padding(top = 96.dp, end = 32.dp))
    }
    extras.panel?.takeIf { session.panel == null }?.let { panel ->
        YouTubeSidePanel(state, session, extras, panel, Modifier.align(Alignment.CenterEnd))
    }
}

/**
 * The YouTube caption showing now, drawn by Fetch as the TV's caption renderer draws it: in the
 * player's subtitle style (size, bold, colour, outline or background box, position, the HDR
 * colour), lifted above the controls while they show.
 */
@Composable
private fun BoxScope.YouTubeCaptionText(state: AppState, session: PlayerSession, extras: YouTubePlayerExtras, controlsVisible: Boolean) {
    val cue = com.fetch.desktop.youtube.cueAt(extras.captionCues, (session.mpv.position * 1000).toLong()) ?: return
    val density = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.matchParentSize()) {
        val heightPx = with(density) { maxHeight.toPx() }
        val lift = com.fetch.desktop.player.SubtitleStyle.liftPercent(
            previewConfiguredPosition = false,
            viewHeightPx = heightPx,
            controlsVisible = controlsVisible,
            controlsClearancePx = with(density) { 170.dp.toPx() },
            skipVisible = session.extras.skipVisible,
            skipClearancePx = with(density) { 120.dp.toPx() }
        )
        val look = com.fetch.desktop.player.SubtitleStyle.captionLook(state.settings.player, session.extras.hdr, lift)
        // mpv's sizes are in a 720-line view: its 55 px default font, and the outline in the same units.
        val unit = maxHeight / 720f
        val fontSize = with(density) { (unit * 55f * 0.8f * look.scale).toSp() }
        val text = androidx.compose.ui.text.TextStyle(
            fontSize = fontSize,
            lineHeight = fontSize * 1.2f,
            fontWeight = if (look.bold) FontWeight.Bold else FontWeight.Medium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 48.dp, end = 48.dp, bottom = maxHeight * (look.bottomPercent / 100f) + unit * 22f)
                .then(look.backgroundArgb?.let { Modifier.background(Color(it), RoundedCornerShape(4.dp)) } ?: Modifier)
                .padding(horizontal = 10.dp, vertical = 3.dp)
        ) {
            look.outlineArgb?.let { outline ->
                Text(
                    cue.text,
                    style = text.copy(
                        color = Color(outline),
                        drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = with(density) { (unit * look.outlineSize * 2f).toPx() },
                            join = androidx.compose.ui.graphics.StrokeJoin.Round
                        )
                    )
                )
            }
            Text(cue.text, style = text.copy(color = Color(look.textArgb)))
        }
    }
}

/** How long "Skipped sponsor · Watch it" stays (the TV's 2.5 s, longer for a mouse to reach it). */
private const val SKIP_NOTICE_MS = 6_000L

@Composable
internal fun SkipNoticeCard(label: String, onWatch: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.75f)).padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
    ) {
        Text(tr("Skipped %1\$s", label), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        SecondaryButton("Watch it", onClick = onWatch, icon = FetchIcons.Replay10)
    }
}

/** Like (with the count), dislike (with Return YouTube Dislike's estimate), subscribe, save and the channel. */
@Composable
internal fun YouTubeActionsRow(
    actions: YouTubeVideoActions?,
    votes: YouTubeVoteCounts?,
    failed: Boolean,
    signedIn: Boolean,
    onRate: (YouTubeLikeStatus) -> Unit,
    onSubscribe: () -> Unit,
    onSave: () -> Unit,
    onChannel: () -> Unit
) {
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val status = actions?.likeStatus
            if (status != null || votes != null) {
                ActionPill(
                    icon = ThumbUpIcon,
                    text = votes?.let { YouTubePlayback.compact(it.likes) },
                    description = tr("Like"),
                    selected = status == YouTubeLikeStatus.LIKE,
                    enabled = status != null,
                    onClick = { onRate(if (status == YouTubeLikeStatus.LIKE) YouTubeLikeStatus.INDIFFERENT else YouTubeLikeStatus.LIKE) }
                )
                ActionPill(
                    icon = ThumbDownIcon,
                    text = votes?.let { YouTubePlayback.compact(it.dislikes) },
                    description = tr("Dislike"),
                    selected = status == YouTubeLikeStatus.DISLIKE,
                    enabled = status != null,
                    onClick = { onRate(if (status == YouTubeLikeStatus.DISLIKE) YouTubeLikeStatus.INDIFFERENT else YouTubeLikeStatus.DISLIKE) }
                )
            }
            val subscribed = actions?.subscribed?.takeIf { actions.channelId != null }
            if (subscribed != null) {
                ActionPill(icon = null, text = if (subscribed) tr("Subscribed") else tr("Subscribe"), description = null, selected = subscribed, filled = !subscribed, onClick = onSubscribe)
            }
            if (signedIn) ActionPill(icon = FetchIcons.Add, text = tr("Save"), description = tr("Save to playlist"), selected = false, onClick = onSave)
            actions?.channelName?.takeIf { actions.channelId != null }?.let { name ->
                ActionPill(icon = null, text = name, description = tr("Go to channel"), selected = false, onClick = onChannel)
            }
        }
        if (failed) Text(tr("YouTube didn't accept that. Try again."), color = FetchPalette.Error, fontSize = 13.sp)
    }
}

@Composable
private fun ActionPill(
    icon: ImageVector?,
    text: String?,
    description: String?,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    filled: Boolean = false
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = when {
        filled -> if (hovered) Color.White.copy(alpha = 0.85f) else Color.White
        selected -> if (hovered) Color.White.copy(alpha = 0.32f) else Color.White.copy(alpha = 0.24f)
        hovered && enabled -> Color.White.copy(alpha = 0.22f)
        else -> Color.Black.copy(alpha = 0.55f)
    }
    val content = if (filled) Color.Black else Color.White
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .hoverable(interaction)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .padding(horizontal = if (text == null) 12.dp else 16.dp, vertical = 10.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = description, tint = if (selected) FetchPalette.Accent else content, modifier = Modifier.size(20.dp))
        }
        if (text != null) Text(text, color = content, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
    }
}

/** Chapters, Captions and Save to playlist, on the player's right like its own panels. */
@Composable
private fun YouTubeSidePanel(state: AppState, session: PlayerSession, extras: YouTubePlayerExtras, panel: YouTubePlayerPanel, modifier: Modifier) {
    val yt = state.youtube
    val chapters = playerChapters(session, extras)
    LaunchedEffect(panel) { if (panel == YouTubePlayerPanel.SAVE && extras.playlists == null) yt.loadPlayingPlaylists() }
    val listState = rememberLazyListState()
    Column(
        modifier
            .width(440.dp)
            .fillMaxHeight()
            .background(FetchPalette.Background.copy(alpha = 0.96f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(top = 24.dp, start = 24.dp, end = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp, bottom = 12.dp)) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                items(YouTubePlayerPanel.entries.filter { it != YouTubePlayerPanel.CHAPTERS || chapters.isNotEmpty() }.filter { it != YouTubePlayerPanel.SAVE || yt.signedIn }) { p ->
                    YtTab(p.label, selected = p == panel) { extras.panel = p }
                }
            }
            CircleIconButton(FetchIcons.Close, "Close", onClick = { extras.panel = null }, size = 40.dp)
        }
        ScrollbarBox(listState, Modifier.weight(1f)) {
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(end = 14.dp, bottom = 24.dp)) {
                when (panel) {
                    YouTubePlayerPanel.CHAPTERS -> {
                        val current = YouTubeChapters.at(chapters, (session.mpv.position * 1000).toLong())
                        items(chapters, key = { it.startMs }) { chapter ->
                            YtRow(chapter.title.ifBlank { "Chapter" }, detail = clock(chapter.startMs / 1000.0), selected = chapter == current) {
                                session.controls.seekTo(chapter.startMs / 1000.0)
                            }
                        }
                    }
                    YouTubePlayerPanel.CAPTIONS -> {
                        val tracks = YouTubeCaptions.menuOrder(extras.captionTracks)
                        val subs = session.mpv.tracks.filter { it.type == "sub" }
                        val selectedMpv = subs.firstOrNull { it.selected }?.id
                        item { YtRow(tr("Off"), selected = selectedMpv == null && extras.selectedCaption == null) { yt.selectCaption(session, null) } }
                        if (tracks.isNotEmpty()) {
                            items(tracks, key = { it.id }) { track ->
                                // Drawn by Fetch, or by mpv when its cues couldn't be read.
                                val mpvId = extras.addedCaptions[track.id]
                                val selected = extras.selectedCaption == track.id && (selectedMpv == null || mpvId == selectedMpv)
                                YtRow(track.label, selected = selected) { yt.selectCaption(session, track) }
                            }
                        } else {
                            // Signed out, or YouTube didn't list them: the tracks yt-dlp loaded.
                            items(subs, key = { it.id }) { t -> YtRow(t.label, selected = t.selected) { session.mpv.selectSubtitle(t.id) } }
                            if (subs.isEmpty()) item { Text(tr("No captions for this video in your subtitle languages."), color = FetchPalette.TextMuted, modifier = Modifier.padding(8.dp)) }
                        }
                        item {
                            Text(
                                tr("Fetch draws YouTube's captions in your subtitle style from Settings › Player (size, colour, outline, background and position). Your choice carries on to the next video."),
                                color = FetchPalette.TextMuted,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 12.dp, start = 4.dp, end = 4.dp)
                            )
                        }
                    }
                    YouTubePlayerPanel.SAVE -> {
                        val options = extras.playlists
                        when {
                            extras.playlistsFailed -> item { Text(tr("Couldn't load your playlists. Try again later."), color = FetchPalette.TextMuted, modifier = Modifier.padding(8.dp)) }
                            options == null -> item {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                                    CircularProgressIndicator(Modifier.size(18.dp), color = FetchPalette.Accent, strokeWidth = 2.dp)
                                    Text(tr("Loading your playlists…"), color = FetchPalette.TextSecondary)
                                }
                            }
                            else -> items(options, key = { it.playlistId }) { option: YouTubePlaylistOption ->
                                YtRow(option.title, selected = option.containsVideo) { yt.togglePlayingPlaylist(option) }
                            }
                        }
                        item { NewPlaylistRow(onCreate = yt::createPlayingPlaylist) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NewPlaylistRow(onCreate: (String, YouTubePlaylistPrivacy) -> Unit) {
    var name by remember { mutableStateOf("") }
    var privacy by remember { mutableStateOf(YouTubePlaylistPrivacy.PRIVATE) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 14.dp, start = 4.dp)) {
        Text(tr("NEW PLAYLIST"), style = OverlineStyle, color = FetchPalette.TextMuted)
        BasicTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
            cursorBrush = SolidColor(FetchPalette.Accent),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(FetchPalette.SurfaceRaised).padding(12.dp)) {
                    if (name.isEmpty()) Text(tr("Name"), color = FetchPalette.TextMuted)
                    inner()
                }
            }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            YouTubePlaylistPrivacy.entries.forEach { p -> YtTab(p.label, selected = privacy == p) { privacy = p } }
            Box(Modifier.weight(1f))
            SecondaryButton("Create", enabled = name.isNotBlank(), onClick = {
                onCreate(name, privacy)
                name = ""
            })
        }
    }
}

@Composable
private fun YtTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontWeight = FontWeight.SemiBold,
        color = if (selected) Color.Black else Color.White,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Color.White else Color.White.copy(alpha = 0.1f))
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    )
}

@Composable
private fun YtRow(title: String, detail: String? = null, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    selected -> FetchPalette.Accent.copy(alpha = 0.18f)
                    hovered -> Color.White.copy(alpha = 0.08f)
                    else -> Color.Transparent
                }
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = Color.White, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            detail?.let { Text(it, color = FetchPalette.TextMuted, fontSize = 12.sp) }
        }
        if (selected) Icon(FetchIcons.Check, contentDescription = null, tint = FetchPalette.Accent, modifier = Modifier.size(20.dp))
    }
}

/**
 * The TV's end card when a YouTube video ends: YouTube's pick counting down ten seconds (Play now,
 * Cancel to stop it, Close), and the related videos. Over the built-in player, or over the app
 * when mpv's window played it.
 */
@Composable
fun YouTubeUpNextOverlay(state: AppState) {
    val yt = state.youtube
    val prompt = yt.upNext ?: return
    if (prompt.builtIn && state.playerSession == null) return
    UpNextEndCard(
        prompt,
        onPlay = yt::playUpNext,
        onCancel = yt::stopUpNextCountdown,
        onClose = yt::closeUpNext
    )
}

@Composable
internal fun UpNextEndCard(prompt: YouTubeUpNextPrompt, onPlay: (YouTubeVideo) -> Unit, onCancel: () -> Unit, onClose: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
    ) {
        Column(Modifier.align(Alignment.CenterStart).padding(horizontal = 64.dp, vertical = 48.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Text(prompt.heading, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            prompt.next?.let { next ->
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    YouTubeVideoCard(next, onClick = { onPlay(next) }, width = 420.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(prompt.status, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PrimaryButton("Play now", onClick = { onPlay(next) })
                            if (prompt.counting) SecondaryButton("Cancel", onClick = onCancel)
                            SecondaryButton("Close", onClick = onClose)
                        }
                    }
                }
            } ?: Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(prompt.status, color = Color.White, fontSize = 18.sp)
                SecondaryButton("Close", onClick = onClose)
            }
            if (prompt.related.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    items(prompt.related, key = { it.videoId }) { video -> YouTubeVideoCard(video, onClick = { onPlay(video) }, width = RelatedWidth) }
                }
            }
        }
    }
}

private val RelatedWidth: Dp = 260.dp

private fun icon(pathData: String): ImageVector =
    ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black)).build()

/** Material Symbols' thumb up and down (Apache 2.0). */
private val ThumbUpIcon = icon(
    "M1,21h4V9H1v12zM23,10c0,-1.1 -0.9,-2 -2,-2h-6.31l0.95,-4.57 0.03,-0.32c0,-0.41 -0.17,-0.79 -0.44,-1.06L14.17,1 7.59,7.59" +
        "C7.22,7.95 7,8.45 7,9v10c0,1.1 0.9,2 2,2h9c0.83,0 1.54,-0.5 1.84,-1.22l3.02,-7.05c0.09,-0.23 0.14,-0.47 0.14,-0.73v-2z"
)
private val ThumbDownIcon = icon(
    "M15,3H6c-0.83,0 -1.54,0.5 -1.84,1.22l-3.02,7.05c-0.09,0.23 -0.14,0.47 -0.14,0.73v2c0,1.1 0.9,2 2,2h6.31l-0.95,4.57" +
        " -0.03,0.32c0,0.41 0.17,0.79 0.44,1.06L9.83,23l6.59,-6.59c0.36,-0.36 0.58,-0.86 0.58,-1.41V5c0,-1.1 -0.9,-2 -2,-2zM19,3v12h4V3h-4z"
)
