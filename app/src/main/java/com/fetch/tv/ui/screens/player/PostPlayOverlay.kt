@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.fetch.tv.ui.screens.player

import com.fetch.tv.ui.theme.FetchRadii
import com.fetch.tv.ui.theme.FetchMotion

import com.fetch.tv.ui.theme.FetchTheme

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusRequester.Companion.Cancel
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fetch.tv.ui.util.localizeEpisodeTitle
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.transformations
import com.fetch.tv.R
import com.fetch.tv.ui.util.BlurTransformation
import com.fetch.tv.ui.theme.FetchPrimitives

@Composable
fun PostPlayOverlay(
    mode: PostPlayMode?,
    controlsVisible: Boolean,
    blurUnwatchedEpisodes: Boolean,
    nextEpisodeFocusRequester: FocusRequester,
    progressBarFocusRequester: FocusRequester?,
    leftFocusRequester: FocusRequester?,
    onPlayNext: () -> Unit,
    onContinueStillWatching: () -> Unit,
    onDismissStillWatching: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isAutoPlay = mode is PostPlayMode.AutoPlay
    val isAutoPlayPlayable = (mode as? PostPlayMode.AutoPlay)?.nextEpisode?.hasAired == true

    var placedFocused by remember(isAutoPlay, controlsVisible) { mutableStateOf(false) }

    val transition = updateTransition(targetState = mode, label = "PostPlayMode")

    transition.AnimatedVisibility(
        visible = { it != null },
        enter = if (FetchTheme.fullMotion) {
            slideInHorizontally(animationSpec = tween(FetchMotion.tokens.durations.fast), initialOffsetX = { it / 2 }) +
                fadeIn(animationSpec = tween(FetchMotion.tokens.durations.fast))
        } else {
            fadeIn(animationSpec = tween(FetchMotion.tokens.durations.fast))
        },
        exit = if (FetchTheme.fullMotion) {
            slideOutHorizontally(animationSpec = tween(FetchMotion.tokens.durations.fast), targetOffsetX = { it / 2 }) +
                fadeOut(animationSpec = tween(FetchMotion.tokens.durations.fast))
        } else {
            fadeOut(animationSpec = tween(FetchMotion.tokens.durations.fast))
        },
        modifier = modifier,
    ) {
        val onCardClick: () -> Unit = when {
            isAutoPlay && isAutoPlayPlayable -> onPlayNext
            mode is PostPlayMode.StillWatching -> onContinueStillWatching
            else -> ({})
        }
        Card(
            onClick = onCardClick,
            shape = CardDefaults.shape(shape = RoundedCornerShape(FetchRadii.tokens.md)),
            colors = CardDefaults.colors(
                containerColor = FetchPrimitives.neutral900.copy(alpha = 0.89f),
                focusedContainerColor = FetchPrimitives.neutral900.copy(alpha = 0.89f),
            ),
            border = CardDefaults.border(
                border = Border(
                    border = BorderStroke(FetchTheme.spacing.hairline, FetchTheme.colors.Player.Divider),
                    shape = RoundedCornerShape(FetchRadii.tokens.md),
                ),
                focusedBorder = Border(
                    border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs),
                    shape = RoundedCornerShape(FetchRadii.tokens.md),
                ),
            ),
            scale = CardDefaults.scale(focusedScale = 1f),
            modifier = Modifier
                .width(420.dp)
                .focusRequester(nextEpisodeFocusRequester)
                .onPlaced {
                    if (isAutoPlay && !controlsVisible && !placedFocused) {
                        placedFocused = true
                        runCatching { nextEpisodeFocusRequester.requestFocus() }
                    }
                }
                .then(
                    if (progressBarFocusRequester != null || leftFocusRequester != null) {
                        Modifier.focusProperties {
                            progressBarFocusRequester?.let { down = it }
                            leftFocusRequester?.let { left = it }
                        }
                    } else {
                        Modifier
                    }
                ),
        ) {
            transition.AnimatedContent(
                transitionSpec = {
                    fadeIn(animationSpec = tween(FetchMotion.tokens.durations.fast)) togetherWith
                        fadeOut(animationSpec = tween(FetchMotion.tokens.durations.quick))
                },
                contentKey = { it?.let { current -> current::class } },
            ) { current ->
                when (current) {
                    is PostPlayMode.AutoPlay -> AutoPlayBody(
                        mode = current,
                        blurUnwatchedEpisodes = blurUnwatchedEpisodes,
                    )
                    is PostPlayMode.StillWatching -> StillWatchingBody(
                        mode = current,
                        blurUnwatchedEpisodes = blurUnwatchedEpisodes,
                        onContinue = onContinueStillWatching,
                        onDismiss = onDismissStillWatching,
                    )
                    null -> Unit
                }
            }
        }
    }
}

@Composable
private fun AutoPlayBody(
    mode: PostPlayMode.AutoPlay,
    blurUnwatchedEpisodes: Boolean,
) {
    val nextEpisode = mode.nextEpisode
    val isPlayable = nextEpisode.hasAired
    // Elapsed share of the autoplay countdown, paced by the controller's own ticks.
    val countdownFill = remember { Animatable(0f) }
    val fullMotion = FetchTheme.fullMotion
    val countdownSec = mode.countdownSec
    LaunchedEffect(countdownSec) {
        if (countdownSec == null) {
            countdownFill.snapTo(0f)
            return@LaunchedEffect
        }
        val total = AUTO_PLAY_COUNTDOWN_SECONDS.toFloat()
        val tickStart = ((total - countdownSec) / total).coerceIn(0f, 1f)
        val tickEnd = ((total - countdownSec + 1) / total).coerceIn(0f, 1f)
        if (countdownFill.value < tickStart) countdownFill.snapTo(tickStart)
        if (fullMotion) {
            countdownFill.animateTo(tickEnd, tween(POST_PLAY_COUNTDOWN_TICK_MS, easing = LinearEasing))
        } else {
            countdownFill.snapTo(tickEnd)
        }
    }
    val showCountdownFill = isPlayable && countdownSec != null
    Row(
        modifier = Modifier.padding(horizontal = FetchTheme.spacing.md, vertical = FetchTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NextEpisodeThumbnail(
            thumbnail = nextEpisode.thumbnail,
            contentDescription = stringResource(R.string.cd_next_episode_thumbnail),
            blurred = blurUnwatchedEpisodes,
        )

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                text = stringResource(R.string.next_episode_label),
                color = FetchTheme.colors.Player.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(FetchTheme.spacing.xxs))
            Text(
                text = nextEpisodeDisplayLabel(nextEpisode),
                color = FetchTheme.colors.Player.TextPrimary,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
            )
            val statusText = when {
                !isPlayable && !nextEpisode.unairedMessage.isNullOrBlank() -> nextEpisode.unairedMessage
                mode.searching -> stringResource(R.string.next_episode_finding_source)
                !mode.sourceName.isNullOrBlank() && mode.countdownSec != null ->
                    stringResource(R.string.next_episode_playing_via, mode.sourceName, mode.countdownSec)
                else -> null
            }
            if (statusText != null) {
                Spacer(modifier = Modifier.height(FetchTheme.spacing.xxs))
                NextEpisodeStatusLine(text = statusText)
            }
        }

        Row(
            modifier = Modifier
                .padding(start = FetchTheme.spacing.sm)
                .clip(CircleShape)
                // Autoplay countdown, read while drawing only.
                .drawBehind {
                    if (!showCountdownFill) return@drawBehind
                    val fraction = countdownFill.value.coerceIn(0f, 1f)
                    if (fraction <= 0f) return@drawBehind
                    val fillWidth = size.width * fraction
                    val left = if (layoutDirection == LayoutDirection.Rtl) size.width - fillWidth else 0f
                    drawRect(
                        color = FetchPrimitives.white.copy(alpha = 0.16f),
                        topLeft = Offset(left, 0f),
                        size = Size(fillWidth, size.height),
                    )
                }
                .border(
                    BorderStroke(FetchTheme.spacing.hairline, FetchTheme.colors.Player.ControlSelected),
                    CircleShape,
                )
                .padding(horizontal = FetchTheme.spacing.md, vertical = FetchTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = if (isPlayable) FetchTheme.colors.Player.TextPrimary else FetchTheme.colors.Player.TextTertiary,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = if (isPlayable) {
                    stringResource(R.string.next_episode_play)
                } else {
                    stringResource(R.string.next_episode_unaired)
                },
                color = if (isPlayable) FetchTheme.colors.Player.TextPrimary else FetchTheme.colors.Player.TextTertiary,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = FetchTheme.spacing.xs),
            )
        }
    }
}

@Composable
private fun StillWatchingBody(
    mode: PostPlayMode.StillWatching,
    blurUnwatchedEpisodes: Boolean,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    val continueFocusRequester = remember { FocusRequester() }
    val exitFocusRequester = remember { FocusRequester() }
    var continueFocused by remember { mutableStateOf(false) }

    val nextEpisode = mode.nextEpisode
    Row(
        modifier = Modifier.padding(horizontal = FetchTheme.spacing.md, vertical = FetchTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (nextEpisode.thumbnail != null) {
            NextEpisodeThumbnail(
                thumbnail = nextEpisode.thumbnail,
                contentDescription = stringResource(R.string.cd_next_episode_thumbnail),
                blurred = blurUnwatchedEpisodes,
            )
            Spacer(modifier = Modifier.width(10.dp))
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.still_watching_title),
                color = FetchTheme.colors.Player.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(FetchTheme.spacing.xxs))
            Text(
                text = nextEpisodeDisplayLabel(nextEpisode),
                color = FetchTheme.colors.Player.TextPrimary,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
            )
            if (mode.countdownSec != null) {
                Spacer(modifier = Modifier.height(FetchTheme.spacing.xxs))
                NextEpisodeStatusLine(
                    text = stringResource(R.string.still_watching_countdown, mode.countdownSec),
                )
            }
        }
        val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        Row(
            modifier = Modifier.padding(start = FetchTheme.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PostPlayPillButton(
                icon = Icons.Default.PlayArrow,
                iconTint = FetchTheme.colors.Player.TextPrimary,
                label = stringResource(R.string.still_watching_continue),
                textColor = FetchTheme.colors.Player.TextPrimary,
                onClick = onContinue,
                focusRequester = continueFocusRequester,
                modifier = Modifier
                    .onPlaced {
                        if (!continueFocused) {
                            continueFocused = true
                            runCatching { continueFocusRequester.requestFocus() }
                        }
                    }
                    .focusProperties {
                        if (isRtl) left = exitFocusRequester else right = exitFocusRequester
                        if (isRtl) right = Cancel else left = Cancel
                    },
            )
            PostPlayPillButton(
                icon = Icons.Default.Close,
                iconTint = FetchTheme.colors.Player.TextTertiary,
                label = stringResource(R.string.still_watching_exit),
                textColor = FetchTheme.colors.Player.TextTertiary,
                onClick = onDismiss,
                focusRequester = exitFocusRequester,
                modifier = Modifier.focusProperties {
                    if (isRtl) right = continueFocusRequester else left = continueFocusRequester
                    if (isRtl) left = Cancel else right = Cancel
                },
            )
        }
    }
}

@Composable
private fun PostPlayPillButton(
    icon: ImageVector,
    iconTint: Color,
    label: String,
    textColor: Color,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        shape = CardDefaults.shape(shape = CircleShape),
        colors = CardDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = FetchTheme.colors.Player.ControlSelected,
        ),
        border = CardDefaults.border(
            border = Border(
                border = BorderStroke(FetchTheme.spacing.hairline, FetchTheme.colors.Player.ControlSelected),
                shape = CircleShape,
            ),
            focusedBorder = Border(
                border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs),
                shape = CircleShape,
            ),
        ),
        scale = CardDefaults.scale(focusedScale = 1f),
        modifier = modifier.focusRequester(focusRequester),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = FetchTheme.spacing.md, vertical = FetchTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = label,
                color = textColor,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = FetchTheme.spacing.xs),
            )
        }
    }
}

@Composable
private fun NextEpisodeThumbnail(
    thumbnail: String?,
    contentDescription: String?,
    blurred: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val imageRequest = remember(context, thumbnail, blurred) {
        ImageRequest.Builder(context)
            .data(thumbnail)
            .crossfade(true)
            .apply {
                if (blurred) transformations(BlurTransformation())
            }
            .build()
    }
    Box(
        modifier = modifier
            .size(width = 112.dp, height = 64.dp)
            .clip(RoundedCornerShape(FetchRadii.tokens.sm)),
    ) {
        AsyncImage(
            model = imageRequest,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, FetchTheme.colors.Player.ScrimLight),
                    ),
                ),
        )
    }
}

@Composable
private fun NextEpisodeStatusLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = FetchTheme.colors.Player.TextTertiary,
        fontSize = 12.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
private fun nextEpisodeDisplayLabel(nextEpisode: NextEpisodeInfo): String {
    if (nextEpisode.isOtherType) return nextEpisode.title
    val context = LocalContext.current
    val code = stringResource(
        R.string.season_episode_format,
        nextEpisode.season,
        nextEpisode.episode,
    )
    val localizedTitle = nextEpisode.title.localizeEpisodeTitle(context)
    return "$code • $localizedTitle"
}
