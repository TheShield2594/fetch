package com.fetch.tv.ui.screens.player

import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fetch.tv.data.repository.SkipInterval

// Animation state is read during drawing, not composition or measurement.
internal fun Modifier.playerProgressFill(
    progress: () -> Float,
    bufferedProgress: () -> Float,
    bufferedColor: Color,
    playedBrush: Brush
): Modifier = drawBehind {
    val radius = CornerRadius(3.dp.toPx())
    val buffered = bufferedProgress().coerceIn(0f, 1f)
    if (buffered > 0f) {
        drawRoundRect(
            color = bufferedColor.copy(alpha = 0.35f),
            size = Size(size.width * buffered, size.height),
            cornerRadius = radius
        )
    }
    val played = progress().coerceIn(0f, 1f)
    if (played > 0f) {
        // Match the former child Box: its gradient spans the played width.
        inset(left = 0f, top = 0f, right = size.width * (1f - played), bottom = 0f) {
            drawRoundRect(brush = playedBrush, cornerRadius = radius)
        }
    }
}

/**
 * The playhead: the same accent dot as a card's ready mark, centred on the end of the played
 * fill. Drawn over the bar on a parent that isn't clipped, so the dot can stand taller than it.
 * The seek bar passes a larger [radius] and the white focus colour while it has focus.
 */
internal fun Modifier.playerPlayhead(
    progress: () -> Float,
    dotColor: Color,
    ringColor: Color,
    radius: Dp = 6.dp
): Modifier = drawWithContent {
    drawContent()
    val ring = 2.dp.toPx()
    val radiusPx = radius.toPx()
    if (size.width < radiusPx * 2f) return@drawWithContent
    val x = (size.width * progress().coerceIn(0f, 1f)).coerceIn(radiusPx, size.width - radiusPx)
    val center = Offset(x, size.height / 2f)
    drawCircle(color = ringColor, radius = radiusPx, center = center)
    drawCircle(color = dotColor, radius = radiusPx - ring, center = center)
}

/** A skip segment's place on the scrubber, as fractions of the title's length. */
@Immutable
internal data class ScrubberSegment(val start: Float, val end: Float)

/** Segment types drawn on the scrubber: intros, recaps and credits. */
private val SCRUBBER_SEGMENT_TYPES = setOf(
    "intro", "op", "mixed-op", "recap", "outro", "ed", "mixed-ed", "credits", "movie-credits"
)

/**
 * The intro, recap and credits segments of [intervals] as fractions of [durationMs], clipped to
 * the title and sorted. Post-credits scenes are left off: they are something to watch, not skip.
 */
internal fun scrubberSegments(intervals: List<SkipInterval>, durationMs: Long): List<ScrubberSegment> {
    if (durationMs <= 0L || intervals.isEmpty()) return emptyList()
    val durationSec = durationMs / 1_000.0
    return intervals
        .asSequence()
        .filter { it.type.lowercase() in SCRUBBER_SEGMENT_TYPES }
        .mapNotNull { interval ->
            val start = (interval.startTime / durationSec).coerceIn(0.0, 1.0).toFloat()
            val end = (interval.endTime / durationSec).coerceIn(0.0, 1.0).toFloat()
            if (end > start) ScrubberSegment(start, end) else null
        }
        .sortedBy { it.start }
        .toList()
}

/**
 * Draws [segments] over the bar as lighter bands with a hairline at each edge, so intros and
 * credits read at a glance. Only drawing: the segments come from state that changes once per
 * title, so this adds no recomposition while playing.
 */
internal fun Modifier.playerSegmentMarks(segments: List<ScrubberSegment>, color: Color, edgeColor: Color): Modifier =
    if (segments.isEmpty()) {
        this
    } else {
        drawWithContent {
            drawContent()
            val edge = 1.5.dp.toPx()
            segments.forEach { segment ->
                val left = size.width * segment.start
                val width = (size.width * (segment.end - segment.start)).coerceAtLeast(edge)
                drawRect(color = color, topLeft = Offset(left, 0f), size = Size(width, size.height))
                drawRect(color = edgeColor, topLeft = Offset(left, 0f), size = Size(edge, size.height))
                drawRect(
                    color = edgeColor,
                    topLeft = Offset((left + width - edge).coerceAtLeast(left), 0f),
                    size = Size(edge, size.height)
                )
            }
        }
    }
