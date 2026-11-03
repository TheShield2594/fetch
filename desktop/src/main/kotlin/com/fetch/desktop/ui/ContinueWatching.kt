package com.fetch.desktop.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.fetch.desktop.data.ProgressEntry
import com.fetch.desktop.data.episodeTag
import com.fetch.desktop.data.remainingLabel

/** What a Continue Watching card does when picked. */
class ContinueWatchingActions(
    val onPlay: (ProgressEntry) -> Unit,
    val onDetails: (ProgressEntry) -> Unit,
    val onStartOver: (ProgressEntry) -> Unit,
    val onRemove: (ProgressEntry) -> Unit
)

/** The 0–1 progress through [entry]; 0 for a Next Up card. */
fun ProgressEntry.fraction(): Float = when {
    upNext -> 0f
    durationMs > 0 -> (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    else -> ((remotePercent ?: 0f) / 100f).coerceIn(0f, 1f)
}

/**
 * A Next Up card's badge, as on TV: "Airs Fri, Nov 7" (or Today, Tomorrow) before the episode
 * airs, "New episode" for one that aired in the last week, else "Next Up".
 */
fun ProgressEntry.nextUpBadge(today: java.time.LocalDate = java.time.LocalDate.now()): String {
    val date = com.fetch.desktop.data.WatchHistory.airDateOf(this) ?: return "Next Up"
    return when {
        date.isAfter(today.plusDays(1)) -> "Airs " + date.format(
            java.time.format.DateTimeFormatter.ofPattern(if (date.year == today.year) "EEE, MMM d" else "MMM d, yyyy")
        )
        date == today.plusDays(1) -> "Airs tomorrow"
        date == today -> "New today"
        !date.isBefore(today.minusDays(7)) -> "New episode"
        else -> "Next Up"
    }
}

/** "Resume S2 E5", "Resume", or "Play S2 E6" for a Next Up card, as on the TV hero. */
fun ProgressEntry.playLabel(): String {
    val tag = episodeTag(season, episode)
    return when {
        upNext -> listOfNotNull("Play", tag).joinToString(" ")
        else -> listOfNotNull("Resume", tag).joinToString(" ")
    }
}

/**
 * The TV app's Continue Watching row: landscape cards with the time left, a progress bar in the
 * accent and the episode under the title. Clicking resumes; right-click (or the × on hover)
 * offers the TV long-press options.
 */
@Composable
fun ContinueWatchingRow(
    entries: List<ProgressEntry>,
    actions: ContinueWatchingActions,
    onHover: (ProgressEntry) -> Unit,
    title: String = "Continue Watching"
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = FetchPalette.TextPrimary,
            modifier = Modifier.padding(horizontal = PageGutter)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = PageGutter, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            items(entries, key = { it.contentId }) { entry ->
                ContinueCard(entry, actions, onHover)
            }
        }
    }
}

private val CardWidth: Dp = 320.dp
private val CardHeight: Dp = 180.dp

@Composable
private fun ContinueCard(entry: ProgressEntry, actions: ContinueWatchingActions, onHover: (ProgressEntry) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    if (hovered) LaunchedEffect(entry.contentId) { onHover(entry) }
    val scale by animateFloatAsState(if (hovered) 1.04f else 1f, tween(160))
    val shape = RoundedCornerShape(12.dp)

    ContextMenuArea(items = {
        buildList {
            val upcoming = com.fetch.desktop.data.WatchHistory.isUpcoming(entry, java.time.LocalDate.now())
            if (!upcoming) add(ContextMenuItem(entry.playLabel()) { actions.onPlay(entry) })
            add(ContextMenuItem("Go to details") { actions.onDetails(entry) })
            if (!entry.upNext) add(ContextMenuItem("Start from beginning") { actions.onStartOver(entry) })
            add(ContextMenuItem("Remove") { actions.onRemove(entry) })
        }
    }) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .width(CardWidth)
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null) { actions.onPlay(entry) }
                .pointerHoverIcon(PointerIcon.Hand)
        ) {
            Box(
                Modifier
                    .size(CardWidth, CardHeight)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .clip(shape)
                    .background(FetchPalette.SurfaceRaised)
                    .then(if (hovered) Modifier.border(3.dp, Color.White, shape) else Modifier)
            ) {
                ArtPlaceholder(entry.name)
                val art = entry.backdrop ?: entry.poster
                if (art != null) {
                    AsyncImage(model = art, contentDescription = entry.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                // Darken the bottom so the progress bar reads on any artwork.
                Box(
                    Modifier.fillMaxWidth().height(70.dp).align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                )
                val badge = if (entry.upNext) entry.nextUpBadge() else remainingLabel(entry)
                if (badge != null) {
                    Text(
                        badge,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.72f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                if (hovered) {
                    CircleIconButton(
                        FetchIcons.Close,
                        "Remove from Continue Watching",
                        onClick = { actions.onRemove(entry) },
                        size = 30.dp,
                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                    )
                }
                if (!entry.upNext) {
                    ProgressBar(
                        entry.fraction(),
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
            }
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp)
            )
            val subtitle = listOfNotNull(episodeTag(entry.season, entry.episode), entry.episodeTitle).joinToString(" · ")
            Text(
                subtitle.ifEmpty { "Movie" },
                style = MaterialTheme.typography.bodySmall,
                color = FetchPalette.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** A thin accent bar over a dim track. */
@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.28f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(FetchPalette.Accent))
    }
}
