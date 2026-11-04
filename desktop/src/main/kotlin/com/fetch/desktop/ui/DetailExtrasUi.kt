package com.fetch.desktop.ui

import com.fetch.desktop.i18n.tr
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.fetch.desktop.AppState
import com.fetch.desktop.data.LayoutPrefs
import com.fetch.desktop.data.episodeTag
import com.fetch.desktop.detail.CommentsTarget
import com.fetch.desktop.detail.episodeRatingColor
import com.fetch.desktop.detail.episodeRatingTextColor
import com.fetch.tv.domain.model.EpisodeOptionsOverlayStyle
import com.fetch.tv.domain.model.EpisodeShuffleSettings
import com.fetch.tv.domain.model.Meta
import com.fetch.tv.domain.model.MetaPreview
import com.fetch.tv.domain.model.RandomEpisodePicker
import com.fetch.tv.domain.model.TraktCommentReview
import com.fetch.tv.domain.model.Video
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Layout's title page options, for the episode cards and the hero. */
val LocalDetailLayout = staticCompositionLocalOf { LayoutPrefs() }

/** "Mar 14, 2024" for a movie with "Show full release date" on, else the release info. */
fun detailReleaseLabel(meta: Meta?, preview: MetaPreview, isSeries: Boolean, full: Boolean): String? {
    val info = meta?.releaseInfo ?: preview.releaseInfo
    if (!full || isSeries) return info
    val date = (meta?.released ?: preview.released)?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return info
    return date.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH))
}

/**
 * What sits behind an episode's sources (Layout › Episode Options Overlay): its artwork blurred,
 * its artwork, or the plain dark scrim. A movie always gets the scrim.
 */
@Composable
fun EpisodeOptionsScrim(style: EpisodeOptionsOverlayStyle, artwork: String?, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxSize()
            .background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
    ) {
        if (artwork != null && style != EpisodeOptionsOverlayStyle.NONE) {
            AsyncImage(
                model = artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().then(if (style == EpisodeOptionsOverlayStyle.BLUR) Modifier.blur(40.dp) else Modifier)
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (style == EpisodeOptionsOverlayStyle.BLUR) 0.45f else 0.55f)))
        } else {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.0f)))
        }
    }
}

// --- Episode ratings grid ---

/**
 * The TV's ratings grid: each season's episodes as coloured IMDb rating chips. Hidden with
 * Layout › Episode Ratings off; "Hide Unwatched" blanks the episodes you haven't seen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EpisodeRatingsSection(state: AppState, meta: Meta) {
    val visibility = state.settings.layout.detailRatings
    if (!visibility.showEpisodeRatings || !state.detailExtras.ratingsClient.available) return
    LaunchedEffect(meta.id) { state.detailExtras.loadRatings(meta.id, meta.imdbId) }
    val view = state.detailExtras.ratings?.takeIf { it.contentId == meta.id } ?: return
    if (!view.loading && view.ratings.isEmpty()) return
    val seasons = meta.videos.mapNotNull { it.season }.filter { it > 0 }.distinct().sorted()
    if (seasons.isEmpty()) return
    var season by remember(meta.id) { mutableStateOf(seasons.first()) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(horizontal = PageGutter)) {
        Text(tr("Ratings"), style = MaterialTheme.typography.titleLarge)
        if (view.loading) {
            Text(tr("Loading ratings…"), color = FetchPalette.TextMuted)
            return@Column
        }
        if (seasons.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(seasons) { s -> Pill(tr("Season %1\$s", s), selected = s == season) { season = s } }
            }
        }
        val episodes = meta.videos.filter { it.season == season && it.episode != null }.distinctBy { it.episode }.sortedBy { it.episode }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            episodes.forEach { video ->
                val rating = view.ratings[season to video.episode!!]?.takeIf { visibility.showEpisodeRating(state.isWatched(video.id)) }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(64.dp).clip(RoundedCornerShape(8.dp))
                        .background(rating?.let { Color(episodeRatingColor(it)) } ?: FetchPalette.SurfaceRaised)
                        .padding(vertical = 8.dp)
                ) {
                    val text = rating?.let { Color(episodeRatingTextColor(it)) } ?: FetchPalette.TextSecondary
                    Text(tr("E%1\$s", video.episode), fontSize = 12.sp, color = text.copy(alpha = 0.8f))
                    Text(rating?.let { "%.1f".format(Locale.ROOT, it) } ?: "—", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = text)
                }
            }
        }
    }
}

@Composable
private fun Pill(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontWeight = FontWeight.SemiBold,
        color = if (selected) Color.Black else Color.White,
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) Color.White else Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

// --- Trakt comments ---

/**
 * Trakt's comments and reviews, most liked first, for the title or, for a show, the episode
 * picked below. Spoilers stay hidden until clicked; a click opens the whole comment.
 */
@Composable
fun TraktCommentsSection(state: AppState, preview: MetaPreview, meta: Meta?, isSeries: Boolean) {
    if (!state.detailExtras.commentsClient.available) return
    val episode = state.sources?.video?.takeIf { isSeries && it.season != null && it.episode != null }
    var forEpisode by remember(preview.id) { mutableStateOf(false) }
    val useEpisode = forEpisode && episode != null
    val id = meta?.id ?: preview.id
    val imdb = meta?.imdbId ?: preview.imdbId
    LaunchedEffect(id, useEpisode, episode?.id) {
        state.detailExtras.loadComments(id, imdb, isSeries, episode?.season?.takeIf { useEpisode }, episode?.episode?.takeIf { useEpisode })
    }
    val view = state.detailExtras.comments ?: return
    var open by remember { mutableStateOf<TraktCommentReview?>(null) }
    val revealed = remember(view.target) { mutableStateOf(setOf<Long>()) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = PageGutter)
        ) {
            Text(tr("Comments"), style = MaterialTheme.typography.titleLarge)
            if (view.total > 0) Text(tr("%1\$s on Trakt", view.total), color = FetchPalette.TextMuted)
            if (episode != null) {
                Pill(if (isSeries) tr("This show") else tr("This title"), selected = !useEpisode) { forEpisode = false }
                Pill(episodeTag(episode.season, episode.episode) ?: tr("Episode"), selected = useEpisode) { forEpisode = true }
            }
        }
        when {
            view.items.isEmpty() && view.loading -> Text(tr("Loading comments…"), color = FetchPalette.TextMuted, modifier = Modifier.padding(horizontal = PageGutter))
            view.items.isEmpty() && view.error != null -> Text(view.error, color = FetchPalette.TextMuted, modifier = Modifier.padding(horizontal = PageGutter))
            view.items.isEmpty() -> Text(tr("No comments on Trakt yet."), color = FetchPalette.TextMuted, modifier = Modifier.padding(horizontal = PageGutter))
            else -> LazyRow(contentPadding = PaddingValues(horizontal = PageGutter), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(view.items, key = { it.id }) { c ->
                    val hidden = c.hasSpoilerContent && c.id !in revealed.value
                    CommentCard(c, hidden) {
                        if (hidden) revealed.value = revealed.value + c.id else open = c
                    }
                }
                if (view.hasMore || view.loading) {
                    item(key = "more") {
                        Box(Modifier.height(200.dp).width(160.dp), contentAlignment = Alignment.Center) {
                            if (view.loading) Text(tr("Loading…"), color = FetchPalette.TextMuted)
                            else SecondaryButton("Load more", onClick = state.detailExtras::loadMoreComments)
                        }
                    }
                }
            }
        }
    }
    open?.let { c ->
        AlertDialog(
            onDismissRequest = { open = null },
            containerColor = FetchPalette.SurfaceRaised,
            title = { CommentHeader(c) },
            text = {
                Text(
                    c.comment,
                    color = FetchPalette.TextPrimary,
                    lineHeight = 22.sp,
                    modifier = Modifier.width(620.dp).heightIn(max = 480.dp).verticalScroll(rememberScrollState())
                )
            },
            confirmButton = { SecondaryButton("Close", onClick = { open = null }) }
        )
    }
}

@Composable
private fun CommentHeader(c: TraktCommentReview) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(c.authorDisplayName, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        c.rating?.let { SpecChip("★ $it/10") }
        if (c.review) SpecChip("Review")
        if (c.likes > 0) Text("♥ ${c.likes}", color = FetchPalette.TextMuted, fontSize = 13.sp)
        commentDate(c.createdAt)?.let { Text(it, color = FetchPalette.TextMuted, fontSize = 13.sp) }
    }
}

private fun commentDate(raw: String?): String? = raw?.let {
    runCatching { OffsetDateTime.parse(it).toLocalDate().format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)) }.getOrNull()
}

@Composable
private fun CommentCard(c: TraktCommentReview, hidden: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.width(380.dp).height(200.dp).clip(shape)
            .background(FetchPalette.Surface)
            .border(1.dp, FetchPalette.SurfaceHigh, shape)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(16.dp)
    ) {
        CommentHeader(c)
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Text(
                c.comment,
                color = FetchPalette.TextSecondary,
                lineHeight = 21.sp,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
                modifier = if (hidden) Modifier.blur(10.dp) else Modifier
            )
            if (hidden) {
                Text(
                    tr("Spoiler: click to show"),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }
    }
}

// --- Shuffle ---

/** The title page's Shuffle button (Layout › Shuffle), for shows with episodes. */
@Composable
fun ShuffleDetailButton(state: AppState, preview: MetaPreview, meta: Meta?) {
    if (!state.settings.layout.randomEpisodeEnabled || meta == null) return
    if (meta.videos.none { (it.season ?: 0) > 0 }) return
    var open by remember { mutableStateOf(false) }
    val on = state.homeExtras.shuffleFor(meta.id).enabled
    SecondaryButton(if (on) "Shuffle on" else "Shuffle", onClick = { open = true }, icon = FetchIcons.Shuffle)
    if (open) {
        EpisodeShuffleDialog(state, preview, meta, onDismiss = { open = false })
    }
}

/**
 * The TV's shuffle dialog: shuffle unwatched episodes or all of them, see the pick (blurred until
 * watched with "Blur Unwatched Episodes"), play it, shuffle again, or turn shuffle off for the show.
 */
@Composable
fun EpisodeShuffleDialog(state: AppState, preview: MetaPreview, meta: Meta, onDismiss: () -> Unit) {
    val watched = remember(meta.videos, state.progress) {
        meta.videos.filter { state.isWatched(it.id) }.mapNotNull { v -> v.season?.let { s -> v.episode?.let { s to it } } }.toSet()
    }
    val picker = remember(meta.id, watched) { RandomEpisodePicker(meta.id, meta.videos, watched, emptyMap()) }
    val saved = state.homeExtras.shuffleFor(meta.id)
    var includeWatched by remember { mutableStateOf(saved.includeWatched) }
    var pick by remember { mutableStateOf<Video?>(null) }
    val blur = state.settings.layout.blurUnwatchedEpisodes
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = FetchPalette.SurfaceRaised,
        title = { Text(tr("Shuffle"), color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.width(520.dp)) {
                val current = pick
                if (current == null) {
                    val unwatched = picker.count(false)
                    val all = picker.count(true)
                    when {
                        all == 0 -> Text(tr("No episodes are available to shuffle."), color = FetchPalette.TextSecondary)
                        else -> {
                            Text(tr("Choose which episodes to shuffle."), color = FetchPalette.TextSecondary)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                SecondaryButton("Unwatched episodes ($unwatched)", enabled = unwatched > 0, onClick = {
                                    includeWatched = false
                                    pick = picker.pick(false)
                                })
                                SecondaryButton("All episodes ($all)", onClick = {
                                    includeWatched = true
                                    pick = picker.pick(true)
                                })
                            }
                            if (unwatched == 0) Text(tr("You have watched every episode. Choose All episodes to watch again."), color = FetchPalette.TextMuted)
                        }
                    }
                } else {
                    val isWatched = picker.isWatched(current)
                    Box(Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(10.dp)).background(FetchPalette.Surface)) {
                        val art = current.thumbnail ?: meta.background
                        if (art != null) {
                            AsyncImage(
                                model = art,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().then(if (blur && !isWatched && current.thumbnail != null) Modifier.blur(24.dp) else Modifier)
                            )
                        }
                    }
                    Text(
                        listOfNotNull(episodeTag(current.season, current.episode), current.title.takeIf { it.isNotBlank() }).joinToString(" · "),
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp
                    )
                    current.overview?.takeIf { it.isNotBlank() && !(blur && !isWatched) }?.let {
                        Text(it, color = FetchPalette.TextSecondary, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = {
            val current = pick
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (saved.enabled) {
                    SecondaryButton("Turn off shuffle", onClick = {
                        state.homeExtras.saveShuffle(meta.id, saved.copy(enabled = false))
                        onDismiss()
                    })
                }
                if (current != null) {
                    if (picker.count(includeWatched) > 1) SecondaryButton("Shuffle again", onClick = { pick = picker.pick(includeWatched) }, icon = FetchIcons.Shuffle)
                    PrimaryButton("Play", onClick = {
                        state.homeExtras.saveShuffle(meta.id, EpisodeShuffleSettings(enabled = true, includeWatched = includeWatched))
                        state.loadSources(preview, current)
                        state.playBest()
                        onDismiss()
                    })
                } else {
                    SecondaryButton("Close", onClick = onDismiss)
                }
            }
        }
    )
}
