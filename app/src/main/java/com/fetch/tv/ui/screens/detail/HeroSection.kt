package com.fetch.tv.ui.screens.detail

import com.fetch.tv.ui.theme.FetchRadii
import androidx.tv.material3.LocalContentColor
import com.fetch.tv.core.streams.SpecLine
import com.fetch.tv.ui.components.SpecStrip
import com.fetch.tv.ui.components.PlayLaunchOrigin
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import com.fetch.tv.ui.theme.FetchMotion

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import androidx.compose.ui.res.stringResource
import android.util.Log
import com.fetch.tv.R
import com.fetch.tv.domain.model.ContentType
import com.fetch.tv.domain.model.Meta
import com.fetch.tv.domain.model.MDBListRatings
import com.fetch.tv.domain.model.Video
import com.fetch.tv.domain.model.NextToWatch
import com.fetch.tv.ui.components.ImdbRatingSourceLabel
import com.fetch.tv.ui.components.MDBListRatingsRow
import com.fetch.tv.ui.components.SynopsisDescription
import com.fetch.tv.ui.theme.FetchTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.fetch.tv.core.streams.ranking.label
import androidx.compose.ui.graphics.painter.Painter
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.fetch.tv.ui.util.localizedGenreLabel
import com.fetch.tv.ui.util.rememberLongPressKeyTracker
import com.fetch.tv.ui.util.contentTextDirection
import com.fetch.tv.ui.util.rememberRawSvgPainter
import java.util.Locale
import com.fetch.tv.ui.theme.FetchStatusTones

private const val MAX_VISIBLE_HERO_GENRES = 6

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HeroContentSection(
    meta: Meta,
    nextEpisode: Video?,
    nextToWatch: NextToWatch?,
    bestMatch: com.fetch.tv.core.streams.BestMatchPreview? = null,
    onPlayClick: () -> Unit,
    isPlayEnabled: Boolean = true,
    onPlayLongPress: (() -> Unit)? = null,
    isInLibrary: Boolean,
    onToggleLibrary: () -> Unit,
    onLibraryLongPress: () -> Unit,
    isMovieWatched: Boolean,
    isMovieWatchedPending: Boolean,
    onToggleMovieWatched: () -> Unit,
    trailerAvailable: Boolean = false,
    onTrailerClick: () -> Unit = {},
    showRandomEpisodeButton: Boolean = false,
    episodeShuffle: com.fetch.tv.domain.model.EpisodeShuffleSettings = com.fetch.tv.domain.model.EpisodeShuffleSettings(),
    shuffleActionPending: Boolean = false,
    onRandomEpisodeClick: () -> Unit = {},
    randomEpisodeFocusRequester: FocusRequester? = null,
    hideLogoDuringTrailer: Boolean = false,
    mdbListRatings: MDBListRatings? = null,
    mdbListRatingOrder: List<String> = com.fetch.tv.domain.model.MDBListSettings.DEFAULT_RATING_ORDER,
    hideMetaInfoImdb: Boolean = false,
    tmdbRating: Float? = null,
    showFullReleaseDate: Boolean = true,
    isTrailerPlaying: Boolean = false,
    playButtonFocusRequester: FocusRequester? = null,
    restorePlayFocusToken: Int = 0,
    onHeroActionFocused: () -> Unit = {},
    onPlayFocusRestored: () -> Unit = {},
    onShowFullDescription: () -> Unit = {},
    onTruncationChanged: (Boolean) -> Unit = {},
    /**
     * For a series in progress: the next episodes, shown right under Play in place of the
     * credits, ratings and synopsis, because the next episode is the most likely action.
     */
    upNext: (@Composable () -> Unit)? = null
) {
    val context = LocalContext.current
    val isSeriesApi = remember(meta.apiType) {
        meta.apiType.equals("series", ignoreCase = true) || meta.apiType.equals("tv", ignoreCase = true)
    }
    val logoModel = remember(context, meta.logo) {
        meta.logo?.let { logo ->
            ImageRequest.Builder(context)
                .data(logo)
                .crossfade(true)
                .build()
        }
    }
    var logoLoadFailed by remember(meta.logo) { mutableStateOf(false) }
    val shouldShowLogo =
        !meta.logo.isNullOrBlank() &&
            !logoLoadFailed &&
            !(isTrailerPlaying && hideLogoDuringTrailer)
    val libraryAddPainter = rememberRawSvgPainter(rawIconRes = com.fetch.tv.R.raw.library_add_plus)
    val trailerPainter = rememberRawSvgPainter(rawIconRes = com.fetch.tv.R.raw.trailer_play_button)
    val strCreator = stringResource(R.string.hero_creator)
    val strDirector = stringResource(R.string.hero_director)
    val strWriter = stringResource(R.string.hero_writer)
    val creditLine = remember(meta.director, meta.writer, isSeriesApi) {
        val directorLine = meta.director.takeIf { it.isNotEmpty() }?.joinToString(", ")
        val writerLine = meta.writer.takeIf { it.isNotEmpty() }?.joinToString(", ")
        when {
            !directorLine.isNullOrBlank() -> {
                if (isSeriesApi) strCreator.format(directorLine) else strDirector.format(directorLine)
            }
            !writerLine.isNullOrBlank() -> strWriter.format(writerLine)
            else -> null
        }
    }

    // Animate logo properties for trailer mode
    val logoHeight by animateDpAsState(
        targetValue = if (isTrailerPlaying) 60.dp else 100.dp,
        animationSpec = tween(FetchMotion.tokens.durations.long),
        label = "logoHeight"
    )
    val logoBottomPadding by animateDpAsState(
        targetValue = if (isTrailerPlaying) FetchTheme.spacing.xl else FetchTheme.spacing.lg,
        animationSpec = tween(FetchMotion.tokens.durations.long),
        label = "logoPadding"
    )
    val logoMaxWidth by animateFloatAsState(
        targetValue = if (isTrailerPlaying) 0.25f else 0.4f,
        animationSpec = tween(FetchMotion.tokens.durations.long),
        label = "logoWidth"
    )
    // During a trailer the hero fills the screen so the logo sits at the bottom
    // on screens taller than the hero (lower-density TV boxes).
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val heroHeight by animateDpAsState(
        targetValue = if (isTrailerPlaying) maxOf(540.dp, screenHeight) else 540.dp,
        animationSpec = tween(FetchMotion.tokens.durations.long),
        label = "heroHeight"
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(heroHeight),
        verticalArrangement = Arrangement.Bottom
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(
                    animationSpec = tween(FetchMotion.tokens.durations.long)
                )
                .padding(start = FetchTheme.spacing.xxxl, end = FetchTheme.spacing.xxxl, bottom = FetchTheme.spacing.lg),
            verticalArrangement = Arrangement.Bottom
        ) {
            // Logo/Title — always visible during trailer, animates size
            if (shouldShowLogo) {
                AsyncImage(
                    model = logoModel,
                    contentDescription = meta.name,
                    onError = { logoLoadFailed = true },
                    modifier = Modifier
                        .height(logoHeight)
                        .fillMaxWidth(logoMaxWidth)
                        .padding(bottom = logoBottomPadding),
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart
                )
            } else {
                // Text title hides entirely during trailer
                AnimatedVisibility(
                    visible = !isTrailerPlaying,
                    enter = fadeIn(tween(FetchMotion.tokens.durations.overlay)),
                    exit = fadeOut(tween(FetchMotion.tokens.durations.overlay))
                ) {
                    Text(
                        text = meta.name,
                        style = MaterialTheme.typography.displayMedium,
                        color = FetchTheme.colors.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = FetchTheme.spacing.sm)
                    )
                }
            }

            // Everything below the logo fades out during trailer
            AnimatedVisibility(
                visible = isTrailerPlaying && !hideLogoDuringTrailer,
                enter = fadeIn(tween(FetchMotion.tokens.durations.long)),
                exit = fadeOut(tween(FetchMotion.tokens.durations.medium))
            ) {
                Text(
                    text = stringResource(R.string.hero_press_back_trailer),
                    style = MaterialTheme.typography.labelMedium,
                    color = FetchTheme.colors.TextTertiary,
                    modifier = Modifier.padding(bottom = FetchTheme.spacing.sm)
                )
            }

            // Everything below the logo fades out during trailer
            AnimatedVisibility(
                visible = !isTrailerPlaying,
                enter = fadeIn(tween(FetchMotion.tokens.durations.overlay)),
                exit = fadeOut(tween(FetchMotion.tokens.durations.overlay))
            ) {
                Column {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PlayButton(
                            text = if (isPlayEnabled) nextToWatch?.displayText else stringResource(R.string.playback_unavailable),
                            bestMatch = bestMatch,
                            enabled = isPlayEnabled,
                            onClick = onPlayClick,
                            onLongPress = onPlayLongPress,
                            focusRequester = playButtonFocusRequester,
                            restoreFocusToken = restorePlayFocusToken,
                            onFocusRestored = {
                                onHeroActionFocused()
                                onPlayFocusRestored()
                            }
                        )

                        ActionIconButton(
                            icon = if (isInLibrary) Icons.Default.Check else null,
                            painter = if (!isInLibrary) {
                                libraryAddPainter
                            } else {
                                null
                            },
                            contentDescription = if (isInLibrary) stringResource(R.string.hero_remove_from_library) else stringResource(R.string.hero_add_to_library),
                            onClick = onToggleLibrary,
                            onLongPress = onLibraryLongPress,
                            onFocused = onHeroActionFocused
                        )

                        if (meta.apiType == "movie") {
                            ActionIconButton(
                                icon = if (isMovieWatched) {
                                    Icons.Default.Visibility
                                } else {
                                    Icons.Default.VisibilityOff
                                },
                                contentDescription = if (isMovieWatched) {
                                    stringResource(R.string.hero_mark_unwatched)
                                } else {
                                    stringResource(R.string.hero_mark_watched)
                                },
                                onClick = onToggleMovieWatched,
                                enabled = !isMovieWatchedPending,
                                selected = isMovieWatched,
                                selectedContainerColor = Color.White,
                                selectedContentColor = Color.Black,
                                onFocused = onHeroActionFocused
                            )
                        }

                        if (trailerAvailable) {
                            ActionIconButtonPainter(
                                painter = trailerPainter,
                                contentDescription = stringResource(R.string.hero_play_trailer),
                                onClick = onTrailerClick,
                                onFocused = onHeroActionFocused
                            )
                        }

                        if (showRandomEpisodeButton) {
                            ShuffleButton(
                                active = episodeShuffle.enabled,
                                pending = shuffleActionPending,
                                onClick = onRandomEpisodeClick,
                                focusRequester = randomEpisodeFocusRequester,
                                onFocused = onHeroActionFocused
                            )
                        }
                    }

                    bestMatch?.skipped?.takeIf { isPlayEnabled }?.let { skipped ->
                        val context = LocalContext.current
                        val reason = remember(skipped, context) { skipped.reason.label(context, skipped.descriptor) }
                        Text(
                            text = stringResource(
                                R.string.best_match_skipped,
                                skipped.pictureLabels.joinToString(" "),
                                reason
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = FetchTheme.colors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth(0.6f)
                                .padding(top = FetchTheme.spacing.sm)
                        )
                    }

                    if (upNext == null) {
                        Spacer(modifier = Modifier.height(FetchTheme.spacing.lg))

                        // Director/Writer line above description
                        if (!creditLine.isNullOrBlank()) {
                            Text(
                                text = creditLine,
                                style = MaterialTheme.typography.labelLarge.copy(
                                    textDirection = creditLine.contentTextDirection()
                                ),
                                color = FetchTheme.extendedColors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth(0.6f)
                            )
                            Spacer(modifier = Modifier.height(FetchTheme.spacing.md))
                        }

                        if (mdbListRatings?.isEmpty() == false) {
                            MDBListRatingsRow(ratings = mdbListRatings, order = mdbListRatingOrder)
                            Spacer(modifier = Modifier.height(14.dp))
                        }

                        meta.description?.let { description ->
                            SynopsisDescription(
                                description = description,
                                onShowFullDescription = onShowFullDescription,
                                upFocusRequester = playButtonFocusRequester,
                                onFocused = onHeroActionFocused,
                                onTruncationChanged = onTruncationChanged,
                                modifier = Modifier
                                    .fillMaxWidth(0.6f)
                                    .padding(bottom = FetchTheme.spacing.md)
                            )
                        }

                        MetaInfoRow(
                            meta = meta,
                            hideImdbRating = hideMetaInfoImdb,
                            showFullReleaseDate = showFullReleaseDate,
                            tmdbRating = tmdbRating
                        )
                    }
                }
            }
        }
        // Full width, so the row keeps its own edge padding and scrolls to the screen edge.
        if (upNext != null) {
            AnimatedVisibility(
                visible = !isTrailerPlaying,
                enter = fadeIn(tween(FetchMotion.tokens.durations.overlay)),
                exit = fadeOut(tween(FetchMotion.tokens.durations.overlay))
            ) {
                upNext()
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
internal fun PlayButton(
    text: String?,
    bestMatch: com.fetch.tv.core.streams.BestMatchPreview? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    restoreFocusToken: Int = 0,
    onFocusRestored: () -> Unit = {}
) {
    var longPressTriggered by remember { mutableStateOf(false) }
    val longPressKeyTracker = rememberLongPressKeyTracker()

    LaunchedEffect(restoreFocusToken) {
        if (restoreFocusToken > 0 && focusRequester != null) {
            focusRequester.requestFocusAfterFrames()
        }
    }
    val playPainter = rememberRawSvgPainter(rawIconRes = com.fetch.tv.R.raw.ic_player_play)
    // Where the button is, so the Fetching panel can grow out of it; not state, nothing redraws.
    val launchBounds = remember { arrayOfNulls<Rect>(1) }

    Button(
        enabled = enabled,
        onClick = {
            if (longPressTriggered) {
                longPressTriggered = false
            } else {
                launchBounds[0]?.let { PlayLaunchOrigin.launchFrom(it) }
                onClick()
            }
        },
        modifier = modifier
            .onGloballyPositioned { launchBounds[0] = it.boundsInWindow() }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                if (it.isFocused) {
                    onFocusRestored()
                }
            }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount == 0 && isSelectKey(native.keyCode)) {
                    longPressTriggered = false
                }
                if (enabled && onLongPress != null && native.action == AndroidKeyEvent.ACTION_DOWN) {
                    if (native.keyCode == AndroidKeyEvent.KEYCODE_MENU) {
                        longPressTriggered = true
                        if (native.repeatCount == 0) onLongPress()
                        return@onPreviewKeyEvent true
                    }
                }
                if (enabled && onLongPress != null &&
                    longPressKeyTracker.handle(native, ::isSelectKey) {
                        longPressTriggered = true
                        onLongPress()
                    }
                ) {
                    if (native.action == AndroidKeyEvent.ACTION_UP) {
                        longPressTriggered = false
                    }
                    return@onPreviewKeyEvent true
                }

                if (native.action == AndroidKeyEvent.ACTION_UP && longPressTriggered) {
                    if (isSelectOrMenuKey(native.keyCode)) {
                        longPressTriggered = false
                        return@onPreviewKeyEvent true
                    }
                }
                false
            }
            .focusProperties { up = FocusRequester.Cancel },
        // The primary action is always the accent; focus adds the usual neutral ring.
        colors = ButtonDefaults.colors(
            containerColor = FetchTheme.colors.Secondary,
            focusedContainerColor = FetchTheme.colors.Secondary,
            contentColor = FetchTheme.colors.OnSecondary,
            focusedContentColor = FetchTheme.colors.OnSecondary
        ),
        shape = ButtonDefaults.shape(
            shape = FetchTheme.shapes.button
        ),
        border = ButtonDefaults.border(
            focusedBorder = Border(
                border = FetchTheme.focusRing.border(FetchTheme.strokes.focus),
                shape = FetchTheme.shapes.button
            )
        ),
        contentPadding = PaddingValues(horizontal = FetchTheme.spacing.xl, vertical = 14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm),
            modifier = Modifier.animateContentSize(
                animationSpec = tween(
                    durationMillis = FetchMotion.tokens.durations.fast,
                    easing = FetchMotion.tokens.easings.standard
                )
            )
        ) {
            Icon(
                painter = playPainter,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            AnimatedVisibility(
                visible = text != null,
                enter = fadeIn(animationSpec = tween(FetchMotion.tokens.durations.fast)),
                exit = fadeOut(animationSpec = tween(FetchMotion.tokens.durations.quick))
            ) {
                Column {
                    Text(
                        text = text ?: "",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                    )
                    val specLine = bestMatch?.let { rememberBestMatchSpecLine(it) }
                    AnimatedVisibility(
                        visible = specLine != null,
                        enter = fadeIn(animationSpec = tween(FetchMotion.tokens.durations.fast)),
                        exit = fadeOut(animationSpec = tween(FetchMotion.tokens.durations.quick))
                    ) {
                        // The accent would vanish on the accent button, so Cached keeps the content colour.
                        val contentColor = LocalContentColor.current
                        specLine?.let { line ->
                            SpecStrip(
                                line = line,
                                style = FetchTheme.textStyles.spec.copy(fontWeight = FontWeight.SemiBold),
                                color = contentColor,
                                cachedColor = contentColor,
                                modifier = Modifier.alpha(if (bestMatch?.isFinal == true) 1f else 0.7f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun ActionIconButtonPainter(
    painter: Painter,
    contentDescription: String,
    onClick: () -> Unit,
    onFocused: () -> Unit = {},
    enabled: Boolean = true
) {
    var isFocused by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .size(FetchTheme.spacing.xxxl)
                .onFocusChanged { state ->
                    isFocused = state.isFocused
                    if (state.isFocused) onFocused()
                }
                .focusProperties { up = FocusRequester.Cancel },
            colors = IconButtonDefaults.colors(
                containerColor = FetchTheme.colors.BackgroundCard,
                focusedContainerColor = FetchTheme.colors.Secondary,
                contentColor = FetchTheme.colors.TextPrimary,
                focusedContentColor = FetchTheme.colors.OnSecondary
            ),
            border = IconButtonDefaults.border(
                focusedBorder = Border(
                    border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs),
                    shape = CircleShape
                )
            ),
            shape = IconButtonDefaults.shape(
                shape = CircleShape
            )
        ) {
            Icon(
                painter = painter,
                contentDescription = contentDescription,
                modifier = Modifier.size(22.dp)
            )
        }
        ActionFocusLabel(text = contentDescription, visible = isFocused)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun ActionIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    painter: Painter? = null,
    contentDescription: String,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    selectedContainerColor: Color = FetchStatusTones.toggleOn,
    selectedContentColor: Color = Color.Black,
    onFocused: () -> Unit = {},
    focusRequester: FocusRequester? = null
) {
    var longPressTriggered by remember { mutableStateOf(false) }
    val longPressKeyTracker = rememberLongPressKeyTracker()
    var isFocused by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = {
                if (longPressTriggered) {
                    longPressTriggered = false
                } else {
                    onClick()
                }
            },
            enabled = enabled,
            modifier = Modifier
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .size(FetchTheme.spacing.xxxl)
                .onFocusChanged { state ->
                    isFocused = state.isFocused
                    if (state.isFocused) onFocused()
                }
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount == 0 && isSelectKey(native.keyCode)) {
                        longPressTriggered = false
                    }
                    if (onLongPress != null && native.action == AndroidKeyEvent.ACTION_DOWN) {
                        if (native.keyCode == AndroidKeyEvent.KEYCODE_MENU) {
                            longPressTriggered = true
                            if (native.repeatCount == 0) onLongPress()
                            return@onPreviewKeyEvent true
                        }
                    }
                    if (onLongPress != null &&
                        longPressKeyTracker.handle(native, ::isSelectKey) {
                            longPressTriggered = true
                            onLongPress()
                        }
                    ) {
                        if (native.action == AndroidKeyEvent.ACTION_UP) {
                            longPressTriggered = false
                        }
                        return@onPreviewKeyEvent true
                    }

                    if (native.action == AndroidKeyEvent.ACTION_UP && longPressTriggered) {
                        if (isSelectOrMenuKey(native.keyCode)) {
                            longPressTriggered = false
                            return@onPreviewKeyEvent true
                        }
                    }
                    false
                }
                .focusProperties { up = FocusRequester.Cancel },
            colors = IconButtonDefaults.colors(
                containerColor = if (selected) selectedContainerColor else FetchTheme.colors.BackgroundCard,
                focusedContainerColor = FetchTheme.colors.Secondary,
                contentColor = if (selected) selectedContentColor else FetchTheme.colors.TextPrimary,
                focusedContentColor = FetchTheme.colors.OnSecondary
            ),
            border = IconButtonDefaults.border(
                focusedBorder = Border(
                    border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs),
                    shape = CircleShape
                )
            ),
            shape = IconButtonDefaults.shape(
                shape = CircleShape
            )
        ) {
            when {
                painter != null -> Icon(
                    painter = painter,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(22.dp)
                )
                icon != null -> Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(FetchTheme.spacing.xl)
                )
            }
        }
        ActionFocusLabel(text = contentDescription, visible = isFocused)
    }
}

/**
 * The name of a round hero action, slid out beside it while it has focus, the way the sidebar
 * labels its icons. The button already carries the name as its content description.
 */
@Composable
private fun ActionFocusLabel(text: String, visible: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(FetchMotion.tokens.durations.fast)) +
            expandHorizontally(tween(FetchMotion.tokens.durations.fast)),
        exit = fadeOut(tween(FetchMotion.tokens.durations.quick)) +
            shrinkHorizontally(tween(FetchMotion.tokens.durations.quick))
    ) {
        Text(
            text = text,
            color = FetchTheme.colors.TextPrimary,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = FetchTheme.spacing.sm)
                .clearAndSetSemantics { }
                .background(FetchTheme.colors.BackgroundCard, FetchTheme.shapes.navItem)
                .padding(horizontal = FetchTheme.spacing.md, vertical = FetchTheme.spacing.xs)
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MetaInfoRow(
    meta: Meta,
    hideImdbRating: Boolean,
    showFullReleaseDate: Boolean = true,
    tmdbRating: Float? = null
) {
    val context = LocalContext.current
    val genreLabels = remember(meta.genres) {
        meta.genres
            .take(MAX_VISIBLE_HERO_GENRES)
            .map { localizedGenreLabel(context, it) }
    }
    val runtimeText = remember(meta.runtime) { meta.runtime?.let { formatRuntime(it) } }
    val yearText = remember(meta.releaseInfo, meta.released, meta.type, showFullReleaseDate) {
        if (showFullReleaseDate && meta.type == ContentType.MOVIE) {
            meta.released
                ?.let { runCatching { java.time.OffsetDateTime.parse(it).toLocalDate() }.getOrNull() }
                ?.let { val locale = java.util.Locale.getDefault(); java.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMMMy"), locale).format(java.util.Date(it.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())) }
                ?: formatYearRange(meta.releaseInfo)
        } else {
            formatYearRange(meta.releaseInfo)
        }
    }
    val imdbRating = if (hideImdbRating) null else meta.imdbRating
    val reserveImdbRatingHeight = meta.imdbRating != null
    val shouldShowImdbRating = imdbRating != null
    val shouldShowTmdbRating = tmdbRating != null
    val tmdbModel = remember(context) {
        ImageRequest.Builder(context)
            .data(com.fetch.tv.R.raw.mdblist_tmdb)
            .build()
    }
    val ageRatingBadge = remember(meta.ageRating) {
        meta.ageRating?.trim()?.takeIf { it.isNotBlank() }
    }
    val isSeries = meta.type == ContentType.SERIES || meta.type == ContentType.TV
    val strStatusEnded = stringResource(if (isSeries) R.string.series_status_ended else R.string.movie_status_ended)
    val strStatusContinuing = stringResource(if (isSeries) R.string.series_status_continuing else R.string.movie_status_continuing)
    val strStatusCurrent = stringResource(if (isSeries) R.string.series_status_current else R.string.movie_status_current)
    val strStatusCancelled = stringResource(if (isSeries) R.string.series_status_cancelled else R.string.movie_status_cancelled)
    val strStatusReleased = stringResource(if (isSeries) R.string.series_status_released else R.string.movie_status_released)
    val strStatusPlanned = stringResource(if (isSeries) R.string.series_status_planned else R.string.movie_status_planned)
    val strStatusRumored = stringResource(if (isSeries) R.string.series_status_rumored else R.string.movie_status_rumored)
    val strStatusInProduction = stringResource(if (isSeries) R.string.series_status_in_production else R.string.movie_status_in_production)
    val strStatusPostProduction = stringResource(if (isSeries) R.string.series_status_post_production else R.string.movie_status_post_production)
    val statusBadge = remember(meta.status, isSeries) {
        when (meta.status?.trim()?.lowercase()) {
            "ended" -> strStatusEnded.uppercase()
            "continuing", "returning series" -> strStatusContinuing.uppercase()
            "current" -> strStatusCurrent.uppercase()
            "cancelled", "canceled" -> strStatusCancelled.uppercase()
            "released" -> strStatusReleased.uppercase()
            "planned" -> strStatusPlanned.uppercase()
            "rumored" -> strStatusRumored.uppercase()
            "in production" -> strStatusInProduction.uppercase()
            "post production" -> strStatusPostProduction.uppercase()
            else -> meta.status?.trim()?.takeIf { it.isNotBlank() }?.uppercase()
        }
    }
    Log.d("HeroBadge", "name=${meta.name} ageRating=${meta.ageRating} status=${meta.status} ageRatingBadge=$ageRatingBadge statusBadge=$statusBadge")
    val secondaryItems = remember(runtimeText, meta.country, meta.language) {
        buildList<String> {
            runtimeText?.takeIf { it.isNotBlank() }?.let { add(it) }
            meta.country?.trim()?.takeIf { it.isNotBlank() }?.let { add(normalizeCountryLabel(it)) }
            meta.language?.trim()?.takeIf { it.isNotBlank() }?.let { add(it.uppercase()) }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)) {
        // Primary row: Genres, Release, Ratings
        Row(
            modifier = if (reserveImdbRatingHeight) Modifier.height(30.dp) else Modifier,
            horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (genreLabels.isNotEmpty()) {
                // One Text per genre with the shared divider, so every gap in the row matches.
                genreLabels.forEachIndexed { index, genre ->
                    if (index > 0) {
                        MetaInfoDivider()
                    }
                    Text(
                        text = genre,
                        modifier = if (index == genreLabels.lastIndex) Modifier.weight(1f, fill = false) else Modifier,
                        style = MaterialTheme.typography.labelLarge,
                        color = FetchTheme.extendedColors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (yearText != null || shouldShowImdbRating || shouldShowTmdbRating) {
                    MetaInfoDivider()
                }
            }

            yearText?.let { year ->
                Text(
                    text = year,
                    style = MaterialTheme.typography.labelLarge,
                    color = FetchTheme.extendedColors.textSecondary
                )
                if (shouldShowImdbRating || shouldShowTmdbRating) {
                    MetaInfoDivider()
                }
            }

            imdbRating?.let { rating ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xs)
                ) {
                    ImdbRatingSourceLabel(
                        logoModifier = Modifier.size(30.dp),
                        textStyle = MaterialTheme.typography.labelLarge,
                        textColor = FetchTheme.extendedColors.textSecondary
                    )
                    val ratingText = remember(rating) { String.format("%.1f", rating) }
                    Text(
                        text = ratingText,
                        style = MaterialTheme.typography.labelLarge,
                        color = FetchTheme.extendedColors.textSecondary
                    )
                }
            }

            tmdbRating?.let { rating ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xs)
                ) {
                    AsyncImage(
                        model = tmdbModel,
                        contentDescription = null,
                        modifier = Modifier.size(FetchTheme.spacing.xl),
                        contentScale = ContentScale.Fit
                    )
                    val ratingText = remember(rating) { (rating * 10).toInt().toString() }
                    Text(
                        text = ratingText,
                        style = MaterialTheme.typography.labelLarge,
                        color = FetchTheme.extendedColors.textSecondary
                    )
                }
            }
        }

        // Secondary row: Runtime, Age Rating, Status, Country, Language
        if (ageRatingBadge != null || statusBadge != null || secondaryItems.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (ageRatingBadge != null && statusBadge != null) {
                    CombinedMetaBadge(
                        leftText = ageRatingBadge,
                        leftColor = FetchTheme.colors.TextSecondary,
                        rightText = statusBadge,
                        rightColor = FetchTheme.colors.TextPrimary
                    )
                } else {
                    ageRatingBadge?.let { badge ->
                        HeroMetaBadge(text = badge)
                    }
                    statusBadge?.let { badge ->
                        HeroMetaBadge(
                            text = badge,
                            contentColor = FetchTheme.colors.TextPrimary
                        )
                    }
                }
                if ((ageRatingBadge != null || statusBadge != null) && secondaryItems.isNotEmpty()) {
                    MetaInfoDivider()
                }
                secondaryItems.forEachIndexed { index, value ->
                    Text(
                        text = value,
                        style = MaterialTheme.typography.labelLarge,
                        color = FetchTheme.extendedColors.textSecondary
                    )
                    if (index < secondaryItems.lastIndex) {
                        MetaInfoDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroMetaBadge(
    text: String,
    contentColor: Color = FetchTheme.colors.TextSecondary
) {
    Box(
        modifier = Modifier
            .border(
                border = BorderStroke(FetchTheme.spacing.hairline, contentColor.copy(alpha = 0.55f)),
                shape = RoundedCornerShape(FetchRadii.tokens.sm)
            )
            .padding(horizontal = FetchTheme.spacing.sm, vertical = FetchTheme.spacing.xs),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            maxLines = 1
        )
    }
}

@Composable
private fun CombinedMetaBadge(
    leftText: String,
    leftColor: Color = FetchTheme.colors.TextSecondary,
    rightText: String,
    rightColor: Color = FetchTheme.colors.TextPrimary
) {
    val dividerColor = leftColor.copy(alpha = 0.55f)
    Row(
        modifier = Modifier
            .border(
                border = BorderStroke(FetchTheme.spacing.hairline, dividerColor),
                shape = RoundedCornerShape(FetchRadii.tokens.sm)
            )
            .padding(horizontal = FetchTheme.spacing.sm, vertical = FetchTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)
    ) {
        Text(
            text = leftText,
            style = MaterialTheme.typography.labelMedium,
            color = leftColor,
            maxLines = 1
        )
        Box(
            modifier = Modifier
                .width(FetchTheme.spacing.hairline)
                .height(FetchTheme.spacing.md)
                .background(dividerColor)
        )
        Text(
            text = rightText,
            style = MaterialTheme.typography.labelMedium,
            color = rightColor,
            maxLines = 1
        )
    }
}

private fun normalizeCountryLabel(raw: String): String {
    val displayLocale = Locale.getDefault()
    return raw
        .split(",")
        .joinToString(", ") { part ->
            val code = part.trim()
            if (code.matches(Regex("[A-Za-z]{2}"))) {
                Locale("", code).getDisplayCountry(displayLocale).takeIf { it.isNotBlank() } ?: code
            } else {
                code
            }
        }
}

private fun isSelectKey(keyCode: Int): Boolean {
    return keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
}

private fun isSelectOrMenuKey(keyCode: Int): Boolean {
    return isSelectKey(keyCode) || keyCode == AndroidKeyEvent.KEYCODE_MENU
}


private fun formatYearRange(releaseInfo: String?): String? {
    if (releaseInfo.isNullOrBlank()) return null
    return releaseInfo.trim()
}

/**
 * Null when the runtime is zero or unparseable-as-positive.
 *
 * TMDB answers with runtime 0, not null, for a title whose length it does not know yet, so
 * every branch below could reach the end with a total of zero and render a literal "0m" in the
 * metadata row. The caller already drops a null.
 */
private fun formatRuntime(runtime: String): String? {
    val trimmed = runtime.trim()
    // Already in "Xh Ym" or "Xh" format
    if (trimmed.contains('h') || trimmed.contains('m')) {
        val hours = Regex("(\\d+)\\s*h").find(trimmed)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val mins = Regex("(\\d+)\\s*m").find(trimmed)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val total = hours * 60 + mins
        if (total > 0) return if (total >= 60) {
            val h = total / 60; val m = total % 60
            if (m > 0) "${h}h ${m}m" else "${h}h"
        } else "${total}m"
    }
    // "H:MM" or "HH:MM" format
    if (trimmed.contains(':')) {
        val parts = trimmed.split(':')
        val hours = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val mins = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val total = hours * 60 + mins
        if (total > 0) return if (total >= 60) {
            val m = total % 60
            if (m > 0) "${hours}h ${m}m" else "${hours}h"
        } else "${total}m"
    }
    // Plain number (minutes)
    val minutes = trimmed.filter { it.isDigit() }.toIntOrNull() ?: return runtime
    if (minutes <= 0) return null
    return if (minutes >= 60) {
        val hours = minutes / 60
        val mins = minutes % 60
        if (mins > 0) "${hours}h ${mins}m" else "${hours}h"
    } else {
        "${minutes}m"
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MetaInfoDivider() {
    Text(
        text = "•",
        style = MaterialTheme.typography.labelLarge,
        color = FetchTheme.extendedColors.textTertiary
    )
}

/** "4K · Dolby Vision · Atmos · Cached": the file Best match will start, in plain words. */
@Composable
private fun rememberBestMatchSpecLine(preview: com.fetch.tv.core.streams.BestMatchPreview): SpecLine? {
    val cachedLabel = stringResource(R.string.best_match_cached)
    return remember(preview, cachedLabel) {
        SpecLine.of(preview.buttonLabels, preview.isCached, cachedLabel)
    }
}
