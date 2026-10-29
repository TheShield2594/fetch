package com.fetch.tv.ui.screens.player

import com.fetch.tv.ui.theme.FetchTheme

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.unit.sp
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
            bottom = 120.dp
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
                cast = cast
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
        style = MaterialTheme.typography.headlineSmall.copy(
            fontWeight = FontWeight.Normal,
            fontSize = 34.sp
        ),
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
    cast: List<MetaCastMember>
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Bottom
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.pause_you_are_watching),
                style = MaterialTheme.typography.bodyLarge,
                color = FetchTheme.colors.TextTertiary
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
                    " • " + stringResource(R.string.season_episode_format, season, episode)
                } else {
                    ""
                }

                Text(
                    text = "$year$episodeLabel",
                    style = MaterialTheme.typography.bodyLarge,
                    color = FetchTheme.colors.TextSecondary,
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
                    color = FetchTheme.colors.TextSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = FetchTheme.spacing.lg)
                )
            }

            if (cast.isNotEmpty()) {
                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = stringResource(R.string.pause_cast_label),
                    style = MaterialTheme.typography.titleSmall,
                    color = FetchTheme.colors.TextTertiary
                )

                Spacer(modifier = Modifier.height(FetchTheme.spacing.md))

                // The overlay doesn't take focus (any D-pad key dismisses it), so
                // the cast is shown as plain text rather than as chips that look
                // selectable but can't be reached with a remote.
                Row(horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xl)) {
                    cast.take(8).forEach { member ->
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
