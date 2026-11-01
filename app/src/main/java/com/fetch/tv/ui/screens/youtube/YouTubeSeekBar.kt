package com.fetch.tv.ui.screens.youtube

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.fetch.tv.data.youtube.YouTubeChapter
import com.fetch.tv.data.youtube.YouTubeSkipSegment
import com.fetch.tv.data.youtube.YouTubeStoryboard
import com.fetch.tv.ui.screens.detail.TrailerSeekOverlayState
import com.fetch.tv.ui.screens.detail.formatPlaybackTime
import com.fetch.tv.ui.theme.FetchMotion
import com.fetch.tv.ui.theme.FetchTheme

/**
 * The YouTube player's seek bar: the trailer player's, with what a long video adds. Chapters
 * break the bar and name the one playing, SponsorBlock segments show in their colours, a frame
 * from the storyboard previews where playback is, and a speed other than normal is shown.
 */
@Composable
internal fun YouTubeSeekBarHost(
    visible: Boolean,
    state: TrailerSeekOverlayState,
    chapters: List<YouTubeChapter>,
    segments: List<YouTubeSkipSegment>,
    storyboard: YouTubeStoryboard?,
    playbackSpeed: Float,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(FetchMotion.tokens.durations.quick)),
        exit = fadeOut(tween(FetchMotion.tokens.durations.quick)),
        modifier = modifier
    ) {
        YouTubeSeekBar(
            positionMs = state.positionMs,
            durationMs = state.durationMs,
            chapters = chapters,
            segments = segments,
            storyboard = storyboard,
            playbackSpeed = playbackSpeed
        )
    }
}

@Composable
private fun YouTubeSeekBar(
    positionMs: Long,
    durationMs: Long,
    chapters: List<YouTubeChapter>,
    segments: List<YouTubeSkipSegment>,
    storyboard: YouTubeStoryboard?,
    playbackSpeed: Float
) {
    val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val chapter = chapterAt(chapters, positionMs)
    val played = FetchTheme.colors.Secondary
    // The bar reads left to right in every language, as video players do.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FetchTheme.spacing.xxl, vertical = FetchTheme.spacing.xl)
        ) {
            val barWidth = maxWidth
            Column {
                storyboard?.frameAt(positionMs, durationMs)?.let { frame ->
                    StoryboardFrame(
                        frame = frame,
                        storyboard = storyboard,
                        // Centred over the playhead, kept on screen.
                        offsetX = (barWidth * progress - PREVIEW_WIDTH / 2).coerceIn(0.dp, maxOf(0.dp, barWidth - PREVIEW_WIDTH))
                    )
                    Spacer(modifier = Modifier.height(FetchTheme.spacing.sm))
                }
                Canvas(modifier = Modifier.fillMaxWidth().height(BAR_HEIGHT)) {
                    val radius = CornerRadius(size.height / 2, size.height / 2)
                    val gap = if (chapters.size > 1) CHAPTER_GAP.toPx() else 0f
                    val starts = if (chapters.size > 1 && durationMs > 0) {
                        chapters.map { (it.startMs.toFloat() / durationMs).coerceIn(0f, 1f) }
                    } else {
                        listOf(0f)
                    }
                    // One piece of bar per chapter, with a gap between.
                    starts.forEachIndexed { index, start ->
                        val end = starts.getOrNull(index + 1) ?: 1f
                        val left = start * size.width + if (index > 0) gap / 2 else 0f
                        val right = end * size.width - if (index < starts.lastIndex) gap / 2 else 0f
                        if (right <= left) return@forEachIndexed
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.3f),
                            topLeft = Offset(left, 0f),
                            size = Size(right - left, size.height),
                            cornerRadius = radius
                        )
                        val playedRight = minOf(right, progress * size.width)
                        if (playedRight > left) {
                            drawRoundRect(
                                color = played,
                                topLeft = Offset(left, 0f),
                                size = Size(playedRight - left, size.height),
                                cornerRadius = radius
                            )
                        }
                    }
                    if (durationMs > 0) {
                        segments.forEach { segment ->
                            val left = (segment.startMs.toFloat() / durationMs).coerceIn(0f, 1f) * size.width
                            val right = (segment.endMs.toFloat() / durationMs).coerceIn(0f, 1f) * size.width
                            if (right > left) {
                                drawRect(
                                    color = sponsorBlockColor(segment.sponsorBlockCategory).copy(alpha = 0.85f),
                                    topLeft = Offset(left, 0f),
                                    size = Size(maxOf(right - left, 2f), size.height)
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(FetchTheme.spacing.md))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = chapter?.title.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (playbackSpeed != 1f) {
                        Text(
                            text = speedLabel(playbackSpeed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.9f)
                        )
                    }
                    Text(
                        text = "${formatPlaybackTime(positionMs)} / ${formatPlaybackTime(durationMs)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.9f)
                    )
                }
            }
        }
    }
}

/** One storyboard frame: its sheet, drawn at a scale and shifted so only the frame shows. */
@Composable
private fun StoryboardFrame(frame: YouTubeStoryboard.Frame, storyboard: YouTubeStoryboard, offsetX: Dp) {
    val scale = PREVIEW_WIDTH.value / frame.width
    val height = (frame.height * scale).dp
    Box(
        modifier = Modifier
            .offset(x = offsetX)
            .size(PREVIEW_WIDTH, height)
            .clip(RoundedCornerShape(FetchTheme.radii.sm))
            .border(PREVIEW_BORDER, Color.White, RoundedCornerShape(FetchTheme.radii.sm))
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

private val BAR_HEIGHT = 6.dp
private val CHAPTER_GAP = 3.dp
private val PREVIEW_WIDTH = 240.dp
private val PREVIEW_BORDER = 2.dp
