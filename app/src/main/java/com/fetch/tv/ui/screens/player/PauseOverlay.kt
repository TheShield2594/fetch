package com.fetch.tv.ui.screens.player

import com.fetch.tv.ui.theme.FetchTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import androidx.compose.ui.platform.LocalContext
import com.fetch.tv.domain.model.MetaCastMember
import android.text.format.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource
import com.fetch.tv.R
import com.fetch.tv.ui.util.contentTextDirection
import com.fetch.tv.ui.util.localizeEpisodeTitle
import com.fetch.tv.ui.util.toAbsoluteAlignment

@Composable
fun PauseOverlay(
    visible: Boolean,
    onClose: () -> Unit,
    title: String,
    logo: String?,
    episodeTitle: String?,
    season: Int?,
    episode: Int?,
    year: String?,
    type: String?,
    description: String?,
    cast: List<MetaCastMember>,
    modifier: Modifier = Modifier,
    episodeGuestStars: List<MetaCastMember> = emptyList(),
    showClock: Boolean = true
) {
    PlayerOverlayScaffold(
        visible = visible,
        onDismiss = onClose,
        modifier = modifier,
        captureKeys = false,
        dismissOnBackgroundClick = true,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = FetchTheme.spacing.huge,
            end = FetchTheme.spacing.huge,
            top = 40.dp,
            // The player controls are hidden while this overlay is up, so the
            // content can sit lower and leave room for the cast headshots.
            bottom = 72.dp
        ),
        topEndContent = {
            if (showClock) {
                PauseOverlayClock()
            }
        }
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Bottom
        ) {
            PauseMetadataView(
                title = title,
                logo = logo,
                episodeTitle = episodeTitle,
                season = season,
                episode = episode,
                year = year,
                type = type,
                description = description,
                cast = cast,
                episodeGuestStars = episodeGuestStars
            )
        }
    }
}

@Composable
private fun PauseOverlayClock(modifier: Modifier = Modifier) {
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    val context = LocalContext.current
    val formatter = remember(context) { DateFormat.getTimeFormat(context) }

    LaunchedEffect(Unit) {
        while (true) {
            val current = System.currentTimeMillis()
            nowMillis = current
            val delayMs = (60_000L - (current % 60_000L)).coerceAtLeast(1_000L)
            delay(delayMs)
        }
    }

    Text(
        text = formatter.format(Date(nowMillis)),
        // On the type scale (32sp), close to the former 34sp so the clock keeps its weight.
        style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Normal),
        color = FetchTheme.colors.Player.TextPrimary,
        modifier = modifier
    )
}

@Composable
private fun PauseMetadataView(
    title: String,
    logo: String?,
    episodeTitle: String?,
    season: Int?,
    episode: Int?,
    year: String?,
    type: String?,
    description: String?,
    cast: List<MetaCastMember>,
    episodeGuestStars: List<MetaCastMember>
) {
    val showCastStrip = (cast + episodeGuestStars).any { !it.photo.isNullOrBlank() }
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Bottom
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.pause_you_are_watching),
                style = MaterialTheme.typography.bodyLarge,
                color = FetchTheme.colors.Player.TextTertiary
            )

            Spacer(modifier = Modifier.height(FetchTheme.spacing.md))

            if (!logo.isNullOrBlank()) {
                var logoFailed by remember(logo) { mutableStateOf(false) }
                if (!logoFailed) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(logo)
                            .memoryCacheKey(logo)
                            .crossfade(true)
                            .build(),
                        contentDescription = title,
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.BottomStart,
                        modifier = Modifier.height(96.dp),
                        onError = { logoFailed = true }
                    )
                } else {
                    Text(
                        text = title,
                        modifier = Modifier.align(title.contentTextDirection().toAbsoluteAlignment()),
                        style = MaterialTheme.typography.headlineLarge,
                        color = FetchTheme.colors.Player.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                Text(
                    text = title,
                    modifier = Modifier.align(title.contentTextDirection().toAbsoluteAlignment()),
                    style = MaterialTheme.typography.headlineLarge,
                    color = FetchTheme.colors.Player.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (!year.isNullOrBlank()) {
                val episodeLabel = if (type in listOf("series", "tv") && season != null && episode != null) {
                    stringResource(R.string.season_episode_format, season, episode)
                } else {
                    null
                }

                Text(
                    text = joinPlayerMeta(year, episodeLabel),
                    style = MaterialTheme.typography.bodyLarge,
                    color = FetchTheme.colors.Player.TextSecondary,
                    modifier = Modifier.padding(top = FetchTheme.spacing.sm)
                )
            }

            if (!episodeTitle.isNullOrBlank()) {
                val context = LocalContext.current
                Text(
                    text = episodeTitle.localizeEpisodeTitle(context),
                    style = MaterialTheme.typography.titleLarge,
                    color = FetchTheme.colors.Player.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = FetchTheme.spacing.md)
                )
            }

            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        textDirection = description.contentTextDirection()
                    ),
                    color = FetchTheme.colors.Player.TextSecondary,
                    // Two lines when the headshot strip is shown, so it all fits at 540dp.
                    maxLines = if (showCastStrip) 2 else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = FetchTheme.spacing.lg)
                )
            }

            if (cast.isNotEmpty() || episodeGuestStars.isNotEmpty()) {
                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = stringResource(R.string.pause_cast_label),
                    style = MaterialTheme.typography.titleSmall,
                    color = FetchTheme.colors.Player.TextTertiary
                )

                Spacer(modifier = Modifier.height(FetchTheme.spacing.md))

                // The overlay doesn't take focus (any D-pad key dismisses it), so the
                // cast is drawn as plain, non-focusable items rather than as cards that
                // look selectable but can't be reached with a remote.
                if (showCastStrip) {
                    PauseCastStrip(cast = cast, episodeGuestStars = episodeGuestStars)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xl)) {
                        pauseOverlayCast(cast, episodeGuestStars, limit = 8).forEach { member ->
                            Text(
                                text = member.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = FetchTheme.colors.Player.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

private val PauseCastPhotoSize = 56.dp
private val PauseCastItemWidth = 104.dp

/**
 * X-Ray-style row: a headshot, the actor and the character for as many people as fit on one
 * line, with part of the row kept for an episode's guest stars (see [pauseOverlayCast]).
 */
@Composable
private fun PauseCastStrip(cast: List<MetaCastMember>, episodeGuestStars: List<MetaCastMember>) {
    val spacing = FetchTheme.spacing.lg
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val fitting = ((maxWidth + spacing) / (PauseCastItemWidth + spacing)).toInt()
            .coerceIn(1, PAUSE_CAST_MAX_ITEMS)
        Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
            val shown = remember(cast, episodeGuestStars, fitting) {
                pauseOverlayCast(cast, episodeGuestStars, limit = fitting)
            }
            shown.forEach { member ->
                PauseCastItem(member = member)
            }
        }
    }
}

private const val PAUSE_CAST_MAX_ITEMS = 10

@Composable
private fun PauseCastItem(member: MetaCastMember) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val photoPx = remember(density) { with(density) { PauseCastPhotoSize.roundToPx() } }
    val photo = member.photo?.takeIf { it.isNotBlank() }
    var photoFailed by remember(photo) { mutableStateOf(false) }

    Column(
        modifier = Modifier.width(PauseCastItemWidth),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(PauseCastPhotoSize)
                .clip(CircleShape)
                .background(FetchTheme.colors.Player.Control),
            contentAlignment = Alignment.Center
        ) {
            if (photo != null && !photoFailed) {
                AsyncImage(
                    model = remember(context, photo, photoPx) {
                        ImageRequest.Builder(context)
                            .data(photo)
                            .size(photoPx, photoPx)
                            .crossfade(true)
                            .build()
                    },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    onError = { photoFailed = true }
                )
            } else {
                Text(
                    text = member.name.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.titleLarge,
                    color = FetchTheme.colors.Player.TextPrimary
                )
            }
        }

        Spacer(modifier = Modifier.height(FetchTheme.spacing.sm))

        Text(
            text = member.name,
            style = MaterialTheme.typography.labelMedium.copy(
                textDirection = member.name.contentTextDirection()
            ),
            color = FetchTheme.colors.Player.TextPrimary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        val character = member.character
        if (!character.isNullOrBlank()) {
            Text(
                text = character,
                style = MaterialTheme.typography.labelSmall.copy(
                    textDirection = character.contentTextDirection()
                ),
                color = FetchTheme.colors.Player.TextTertiary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
