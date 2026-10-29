package com.fetch.tv.ui.screens.home

import com.fetch.tv.core.streams.SpecLine
import com.fetch.tv.ui.components.SpecStrip
import com.fetch.tv.ui.components.offersPlayLaunchOrigin
import com.fetch.tv.ui.theme.FetchMotion

import com.fetch.tv.ui.theme.FetchTheme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.fetch.tv.ui.util.LocalRecompositionHighlighterEnabled
import com.fetch.tv.ui.util.contentTextDirection
import com.fetch.tv.ui.util.recompositionHighlighter
import com.fetch.tv.R
import kotlinx.coroutines.delay
import com.fetch.tv.ui.components.ImdbRatingSourceLabel
import com.fetch.tv.ui.components.TrailerPlayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration

private data class ModernHeroSecondaryMeta(
    val highlightText: String?,
    val ageRating: String?,
    val status: String?,
    val details: List<String>
)

@Composable
internal fun ModernHeroScene(
    state: () -> ModernHeroSceneState,
    isFullScreen: () -> Boolean,
    bgColor: Color,
    modifier: Modifier,
    requestWidthPx: Int,
    requestHeightPx: Int,
    onTrailerEnded: () -> Unit,
    onFirstFrameRendered: () -> Unit
) {
    ModernHeroMediaLayer(
        heroBackdrop = { state().heroBackdrop },
        enrichmentActive = { state().enrichmentActive },
        shouldPlayHeroTrailer = { state().shouldPlayTrailer },
        heroTrailerFirstFrameRendered = { state().trailerFirstFrameRendered },
        heroTrailerUrl = { state().trailerUrl },
        heroTrailerAudioUrl = { state().trailerAudioUrl },
        heroTrailerPlaybackKey = { state().trailerPlaybackKey },
        muted = { state().trailerMuted },
        onTrailerEnded = onTrailerEnded,
        onFirstFrameRendered = onFirstFrameRendered,
        modifier = modifier,
        requestWidthPx = requestWidthPx,
        requestHeightPx = requestHeightPx
    )
    val isTrailerPlayingFullScreen = {
        val s = state()
        s.fullScreenBackdrop && s.shouldPlayTrailer && s.trailerFirstFrameRendered
    }
    ModernHeroGradientLayer(
        bgColor = bgColor,
        isFullScreen = isFullScreen,
        isTrailerPlayingFullScreen = isTrailerPlayingFullScreen,
        modifier = modifier
    )
}

@Composable
internal fun ModernHeroMediaLayer(
    heroBackdrop: () -> String?,
    enrichmentActive: () -> Boolean,
    shouldPlayHeroTrailer: () -> Boolean,
    heroTrailerFirstFrameRendered: () -> Boolean,
    heroTrailerUrl: () -> String?,
    heroTrailerAudioUrl: () -> String?,
    heroTrailerPlaybackKey: () -> String?,
    muted: () -> Boolean,
    onTrailerEnded: () -> Unit,
    onFirstFrameRendered: () -> Unit,
    modifier: Modifier,
    requestWidthPx: Int,
    requestHeightPx: Int
) {
    val shouldPlay by remember { derivedStateOf { shouldPlayHeroTrailer() } }
    val trailerRendered by remember { derivedStateOf { heroTrailerFirstFrameRendered() } }
    val transitionProgressState = animateFloatAsState(
        targetValue = if (shouldPlay && trailerRendered) 1f else 0f,
        animationSpec = tween(durationMillis = FetchMotion.tokens.durations.slow),
        label = "heroBackdropTrailerCrossfadeProgress"
    )
    val localContext = LocalContext.current

    // Backdrop URL is managed upstream (heroSceneStateLambda freezes it
    // during rapid nav / scroll). Only update when enrichment is not active
    val rawBackdrop by remember { derivedStateOf { heroBackdrop() } }
    val enriching by remember { derivedStateOf { enrichmentActive() } }
    var displayedBackdrop by remember { mutableStateOf(HeroBackdropState.lastDisplayedUrl ?: heroBackdrop()) }
    if (rawBackdrop != null && rawBackdrop != displayedBackdrop && !enriching) {
        displayedBackdrop = rawBackdrop!!
    }
    val imageModel = remember(
        localContext,
        displayedBackdrop,
        requestWidthPx,
        requestHeightPx
    ) {
        displayedBackdrop?.let {
            ImageRequest.Builder(localContext)
                .data(it)
                .size(width = requestWidthPx, height = requestHeightPx)
                .build()
        }
    }
    // Keep HeroBackdropState in sync for navigation transitions.
    LaunchedEffect(displayedBackdrop) {
        displayedBackdrop?.let { HeroBackdropState.update(it) }
    }

    // A slow, eased blend so moving between titles feels like a cut in a film, not a flicker.
    // It only starts once focus settles (the URL is frozen upstream during fast moves), and is
    // skipped entirely when motion is reduced (also the low-RAM path).
    val backdropCrossfadeSpec: FiniteAnimationSpec<Float> =
        if (FetchTheme.fullMotion) {
            tween(FetchMotion.tokens.durations.backdropCrossfade, easing = FetchMotion.tokens.easings.out)
        } else {
            snap()
        }

    Box(modifier = modifier) {
        androidx.compose.animation.Crossfade(
            targetState = imageModel,
            animationSpec = backdropCrossfadeSpec,
            label = "heroBackdropCrossfade"
        ) { model ->
            AsyncImage(
                model = model,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    // A single image draw, so modulating alpha looks the same as an offscreen
                    // layer without allocating a full-screen buffer for each crossfading backdrop.
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                        alpha = 1f - transitionProgressState.value
                    },
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopEnd
            )
        }
        if (shouldPlay) {
            val trailerUrlVal = heroTrailerUrl()
            val playbackKeyVal = heroTrailerPlaybackKey()
            val audioUrlVal = heroTrailerAudioUrl()
            val mutedVal = muted()
            key(playbackKeyVal ?: trailerUrlVal) {
                TrailerPlayer(
                    trailerUrl = trailerUrlVal,
                    trailerAudioUrl = audioUrlVal,
                    isPlaying = true,
                    onEnded = onTrailerEnded,
                    onFirstFrameRendered = onFirstFrameRendered,
                    muted = mutedVal,
                    cropToFill = com.fetch.tv.ui.components.LocalTrailerCropToFill.current,
                    overscanZoom = MODERN_TRAILER_OVERSCAN_ZOOM,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = transitionProgressState.value
                        }
                )
            }
        }
    }
}

@Composable
internal fun ModernHeroGradientLayer(
    bgColor: Color,
    isFullScreen: () -> Boolean,
    isTrailerPlayingFullScreen: () -> Boolean = { false },
    modifier: Modifier
) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(
        modifier = modifier
            // Alpha is only ever 0 or 1 here, so the overlapping gradients need no offscreen buffer.
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.ModulateAlpha
                alpha = if (isTrailerPlayingFullScreen()) 0f else 1f
            }
            .drawWithCache {
                val fullScreen = isFullScreen()
                val horizontalFadeEndX = size.width * if (fullScreen) 0.65f else 0.45f
                val colorStops = if (fullScreen) {
                    arrayOf(
                        0.0f to bgColor,
                        0.22f to bgColor.copy(alpha = 0.90f),
                        0.46f to bgColor.copy(alpha = 0.80f),
                        0.76f to bgColor.copy(alpha = 0.42f),
                        1.0f to Color.Transparent
                    )
                } else {
                    arrayOf(
                        0.0f to bgColor,
                        0.22f to bgColor.copy(alpha = 0.86f),
                        0.46f to bgColor.copy(alpha = 0.56f),
                        0.76f to bgColor.copy(alpha = 0.16f),
                        1.0f to Color.Transparent
                    )
                }
                val horizontalGradient = if (isRtl) {
                    Brush.horizontalGradient(
                        colorStops = colorStops,
                        startX = size.width,
                        endX = size.width - horizontalFadeEndX
                    )
                } else {
                    Brush.horizontalGradient(
                        colorStops = colorStops,
                        startX = 0f,
                        endX = horizontalFadeEndX
                    )
                }

                val bottomStripStartY = size.height * if (fullScreen) 0.64f else 0.82f
                val verticalGradient = Brush.verticalGradient(
                    colorStops = if (fullScreen) {
                        arrayOf(
                            0.0f to Color.Transparent,
                            0.30f to bgColor.copy(alpha = 0.35f),
                            0.60f to bgColor.copy(alpha = 0.75f),
                            1.0f to bgColor
                        )
                    } else {
                        arrayOf(
                            0.0f to Color.Transparent,
                            0.40f to bgColor.copy(alpha = 0.25f),
                            0.75f to bgColor.copy(alpha = 0.65f),
                            1.0f to bgColor
                        )
                    },
                    startY = bottomStripStartY,
                    endY = size.height
                )

                onDrawBehind {
                    // 1. Horizontal fade (reversed in RTL)
                    val rectLeft = if (isRtl) size.width - horizontalFadeEndX else 0f
                    drawRect(
                        brush = horizontalGradient,
                        topLeft = Offset(rectLeft, 0f),
                        size = Size(horizontalFadeEndX, size.height)
                    )
                    
                    // 2. Bottom vertical strip
                    drawRect(
                        brush = verticalGradient,
                        topLeft = Offset(0f, bottomStripStartY),
                        size = Size(size.width, size.height - bottomStripStartY)
                    )
                }
            }
    )
}

@Composable
internal fun HeroTitleBlock(
    previewProvider: () -> HeroPreview?,
    bestMatchFor: (String) -> com.fetch.tv.data.local.CachedBestMatch? = { null },
    portraitMode: Boolean,
    showImdbRatings: Boolean,
    mdbListShowOnHero: Boolean = false,
    mdbListRatingOrder: List<String> = com.fetch.tv.domain.model.MDBListSettings.DEFAULT_RATING_ORDER,
    trailerPlaying: () -> Boolean = { false },
    modifier: Modifier = Modifier
) {
    // The provider reads hero state that is rewritten on every focus move, often with an equal
    // value. Deriving it recomposes this block only when what it shows actually changes.
    val latestPreviewProvider = rememberUpdatedState(previewProvider)
    val currentPreview by remember { derivedStateOf { latestPreviewProvider.value() } }

    // Keeps the last title on screen while focus passes over a gap with nothing to show.
    var stablePreview by remember { mutableStateOf<HeroPreview?>(null) }
    LaunchedEffect(Unit) {
        snapshotFlow { previewProvider() }.collect { p ->
            if (p != null && stablePreview != p) stablePreview = p
        }
    }

    val displayPreview = currentPreview ?: stablePreview
    if (displayPreview == null) return
    
    Box(
        modifier = modifier,
        contentAlignment = Alignment.BottomStart
    ) {
        HeroTitleContent(
            previewProvider = { displayPreview },
            bestMatchFor = bestMatchFor,
            portraitMode = portraitMode,
            showImdbRatings = showImdbRatings,
            mdbListShowOnHero = mdbListShowOnHero,
            mdbListRatingOrder = mdbListRatingOrder,
            trailerPlaying = trailerPlaying
        )
    }
}

@Composable
private fun HeroTitleContent(
    previewProvider: () -> HeroPreview?,
    bestMatchFor: (String) -> com.fetch.tv.data.local.CachedBestMatch?,
    portraitMode: Boolean,
    showImdbRatings: Boolean,
    mdbListShowOnHero: Boolean = false,
    mdbListRatingOrder: List<String> = com.fetch.tv.domain.model.MDBListSettings.DEFAULT_RATING_ORDER,
    trailerPlaying: () -> Boolean = { false }
) {
    val preview = previewProvider() ?: return
    val highlighterEnabled = LocalRecompositionHighlighterEnabled.current
    val eyebrowText = preview.eyebrowText?.trim()?.takeIf { it.isNotBlank() }
    // The hero keeps little text over the art: two lines of description at most, one when the
    // eyebrow takes a line, and none when Resume is there to be the main thing.
    val descriptionMaxLines = if (eyebrowText != null) 1 else 2
    val descriptionScale = if (portraitMode) 0.90f else 1f
    val titleScale = if (portraitMode) 0.92f else 1f
    val metaScale = 1f
    val titleSpacing = FetchTheme.spacing.sm * titleScale
    val metaSpacing = FetchTheme.spacing.sm * metaScale
    val imdbMetaSpacing = FetchTheme.spacing.xs * metaScale
    val context = LocalContext.current
    val density = LocalDensity.current
    val headlineLarge = MaterialTheme.typography.headlineLarge
    val labelMedium = MaterialTheme.typography.labelMedium
    val bodyMedium = MaterialTheme.typography.bodyMedium
    val logoMaxWidthPx = remember(density) { with(density) { 220.dp.roundToPx() } }
    val logoHeightPx = remember(density) { with(density) { 100.dp.roundToPx() } }

    val logoModel = remember(context, preview.logo, logoMaxWidthPx, logoHeightPx) {
        preview.logo?.let {
            ImageRequest.Builder(context)
                .data(it)
                .crossfade(true)
                .size(width = logoMaxWidthPx, height = logoHeightPx)
                .build()
        }
    }
    // Same idea as in HeroTitleBlock: only a flip of the result should recompose this content.
    val latestTrailerPlaying = rememberUpdatedState(trailerPlaying)
    val trailerPlayingValue by remember { derivedStateOf { latestTrailerPlaying.value() } }
    val metaAlpha by animateFloatAsState(
        targetValue = if (trailerPlayingValue) 0f else 1f,
        animationSpec = tween(durationMillis = FetchMotion.tokens.durations.slow),
        label = "heroMetaFade"
    )
    // Without a logo the title is set in a medium weight: a title card, not a block headline.
    val scaledTitleStyle = remember(headlineLarge, titleScale) {
        headlineLarge.copy(
            fontWeight = FontWeight.Medium,
            fontSize = headlineLarge.fontSize * titleScale,
            lineHeight = headlineLarge.lineHeight * titleScale
        )
    }
    val scaledDescriptionStyle = remember(bodyMedium, descriptionScale) {
        bodyMedium.copy(
            fontSize = bodyMedium.fontSize * descriptionScale,
            lineHeight = bodyMedium.lineHeight * descriptionScale
        )
    }

    Column(
        modifier = if (highlighterEnabled) Modifier.recompositionHighlighter() else Modifier,
        verticalArrangement = Arrangement.spacedBy(titleSpacing)
    ) {
        eyebrowText?.let { text ->
            val displayLocale = LocalConfiguration.current.locales[0]
            val eyebrowStyle = remember(labelMedium) {
                labelMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
            }
            Text(
                text = text.uppercase(displayLocale),
                style = eyebrowStyle.copy(textDirection = text.contentTextDirection()),
                color = FetchTheme.colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.graphicsLayer { alpha = metaAlpha }
            )
        }

        var logoLoadFailed by remember(preview.logo) { mutableStateOf(false) }
        val showLogo = !preview.logo.isNullOrBlank() && !logoLoadFailed
        if (showLogo) {
            AsyncImage(
                model = logoModel,
                contentDescription = preview.title,
                onError = { logoLoadFailed = true },
                modifier = Modifier
                    .height(100.dp)
                    .widthIn(min = 100.dp, max = 220.dp)
                    .fillMaxWidth(),
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart
            )
        } else if (preview.title.isNotBlank()) {
            Text(
                text = preview.title,
                style = scaledTitleStyle,
                color = FetchTheme.colors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        val strStatusEnded = stringResource(if (preview.isSeries) R.string.series_status_ended else R.string.movie_status_ended)
        val strStatusContinuing = stringResource(if (preview.isSeries) R.string.series_status_continuing else R.string.movie_status_continuing)
        val strStatusCurrent = stringResource(if (preview.isSeries) R.string.series_status_current else R.string.movie_status_current)
        val strStatusCancelled = stringResource(if (preview.isSeries) R.string.series_status_cancelled else R.string.movie_status_cancelled)
        val strStatusReleased = stringResource(if (preview.isSeries) R.string.series_status_released else R.string.movie_status_released)
        val strStatusPlanned = stringResource(if (preview.isSeries) R.string.series_status_planned else R.string.movie_status_planned)
        val strStatusRumored = stringResource(if (preview.isSeries) R.string.series_status_rumored else R.string.movie_status_rumored)
        val strStatusInProduction = stringResource(if (preview.isSeries) R.string.series_status_in_production else R.string.movie_status_in_production)
        val strStatusPostProduction = stringResource(if (preview.isSeries) R.string.series_status_post_production else R.string.movie_status_post_production)
        val displayLocale = LocalConfiguration.current.locales[0]
        val secondaryMeta = remember(
            preview.secondaryHighlightText,
            preview.ageRatingText,
            preview.statusText,
            preview.languageText
        ) {
            ModernHeroSecondaryMeta(
                highlightText = preview.secondaryHighlightText?.trim()?.takeIf { it.isNotBlank() },
                ageRating = preview.ageRatingText?.trim()?.takeIf { it.isNotBlank() },
                status = when (preview.statusText?.trim()?.lowercase()) {
                    "ended" -> strStatusEnded.uppercase(displayLocale)
                    "continuing", "returning series" -> strStatusContinuing.uppercase(displayLocale)
                    "current" -> strStatusCurrent.uppercase(displayLocale)
                    "cancelled", "canceled" -> strStatusCancelled.uppercase(displayLocale)
                    "released" -> strStatusReleased.uppercase(displayLocale)
                    "planned" -> strStatusPlanned.uppercase(displayLocale)
                    "rumored" -> strStatusRumored.uppercase(displayLocale)
                    "in production" -> strStatusInProduction.uppercase(displayLocale)
                    "post production" -> strStatusPostProduction.uppercase(displayLocale)
                    else -> preview.statusText?.trim()?.takeIf { it.isNotBlank() }?.uppercase(displayLocale)
                },
                details = buildList {
                    preview.languageText?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
                }
            )
        }

        val secondaryHighlightText = secondaryMeta.highlightText
        val ageRatingBadge = secondaryMeta.ageRating
        val statusBadge = secondaryMeta.status
        val secondaryDetails = secondaryMeta.details
        val hasSecondaryBadge = ageRatingBadge != null || statusBadge != null
        val hasImdbRatingForLayout = !preview.imdbText.isNullOrBlank()
        val hasMdbListRatings = mdbListShowOnHero && preview.mdbListRatings != null && !preview.mdbListRatings.isEmpty()
        // When MDBList ratings are shown, don't reserve space for standalone IMDb badge.
        val reserveImdbInPrimary = !hasMdbListRatings && !preview.isSeries && !hasSecondaryBadge && hasImdbRatingForLayout
        val reserveImdbInPrimaryWithHighlight = reserveImdbInPrimary && secondaryHighlightText == null
        val reserveImdbInSecondary = !hasMdbListRatings && hasImdbRatingForLayout &&
            (preview.isSeries || hasSecondaryBadge || secondaryHighlightText != null)
        val showImdbInPrimaryWithHighlight = showImdbRatings && reserveImdbInPrimaryWithHighlight
        val showImdbInSecondary = showImdbRatings && reserveImdbInSecondary

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = metaAlpha },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(metaSpacing)
        ) {
            // Separate texts with the same dot divider as the trailing meta, so every gap in the
            // line matches (a joined " • " drew a tighter, darker bullet than its neighbours).
            val leadingMeta = remember(preview.contentTypeText, preview.genres, context) {
                buildList {
                    preview.contentTypeText?.takeIf { it.isNotBlank() }?.let(::add)
                    preview.genres.firstOrNull()?.takeIf { it.isNotBlank() }?.let { genre ->
                        add(com.fetch.tv.ui.util.localizedGenreLabel(context, genre))
                    }
                }
            }
            val hasLeadingMeta = leadingMeta.isNotEmpty()

            val runtimeText = preview.runtimeText
            val yearText = preview.yearText
            val hasTrailingMeta = !runtimeText.isNullOrBlank() ||
                !yearText.isNullOrBlank() ||
                reserveImdbInPrimaryWithHighlight

            leadingMeta.forEachIndexed { index, text ->
                if (index > 0) {
                    HeroMetaDivider(metaScale)
                }
                Text(
                    text = text,
                    style = labelMedium,
                    color = FetchTheme.colors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (hasTrailingMeta && index == leadingMeta.lastIndex) {
                        Modifier.weight(1f, fill = false)
                    } else {
                        Modifier
                    }
                )
            }

            if (hasTrailingMeta) {
                if (hasLeadingMeta) {
                    HeroMetaDivider(metaScale)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(metaSpacing)
                ) {
                    if (!runtimeText.isNullOrBlank()) {
                        Text(
                            text = runtimeText,
                            style = labelMedium,
                            color = FetchTheme.colors.TextSecondary,
                            maxLines = 1
                        )
                    }
                    if (!runtimeText.isNullOrBlank() && !yearText.isNullOrBlank()) {
                        HeroMetaDivider(metaScale)
                    }
                    if (!yearText.isNullOrBlank()) {
                        Text(
                            text = yearText,
                            style = labelMedium,
                            color = FetchTheme.colors.TextSecondary,
                            maxLines = 1
                        )
                    }
                    if (reserveImdbInPrimaryWithHighlight) {
                        HeroImdbMeta(
                            imdbText = preview.imdbText.orEmpty(),
                            textStyle = labelMedium,
                            textColor = FetchTheme.colors.TextSecondary,
                            logoSize = FetchTheme.spacing.xl * metaScale,
                            spacing = imdbMetaSpacing,
                            visible = showImdbInPrimaryWithHighlight
                        )
                    }
                }
            }
        }

        if (secondaryHighlightText != null || ageRatingBadge != null || reserveImdbInSecondary || statusBadge != null || secondaryDetails.isNotEmpty() || hasMdbListRatings) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = metaAlpha },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(metaSpacing)
            ) {
                val semiBoldLabelMedium = remember(labelMedium) { labelMedium.copy(fontWeight = FontWeight.SemiBold) }
        secondaryHighlightText?.let { text ->
                    Text(
                        text = text,
                        style = semiBoldLabelMedium,
                        color = FetchTheme.colors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (secondaryHighlightText != null && (hasSecondaryBadge || reserveImdbInSecondary || secondaryDetails.isNotEmpty() || hasMdbListRatings)) {
                    HeroMetaDivider(
                        scale = metaScale,
                        visible = hasSecondaryBadge || showImdbInSecondary || secondaryDetails.isNotEmpty() || hasMdbListRatings
                    )
                }
                if (ageRatingBadge != null && statusBadge != null) {
                    HeroCombinedMetaBadge(
                        leftText = ageRatingBadge,
                        rightText = statusBadge,
                        textStyle = labelMedium,
                        contentColor = FetchTheme.colors.TextPrimary
                    )
                } else {
                    ageRatingBadge?.let { badge ->
                        HeroMetaBadge(
                            text = badge,
                            textStyle = labelMedium,
                            contentColor = FetchTheme.colors.TextPrimary
                        )
                    }
                    statusBadge?.let { badge ->
                        HeroMetaBadge(
                            text = badge,
                            textStyle = labelMedium,
                            contentColor = FetchTheme.colors.TextPrimary
                        )
                    }
                }
                if ((ageRatingBadge != null || statusBadge != null) && (reserveImdbInSecondary || secondaryDetails.isNotEmpty() || hasMdbListRatings)) {
                    HeroMetaDivider(
                        scale = metaScale,
                        visible = showImdbInSecondary || secondaryDetails.isNotEmpty() || hasMdbListRatings
                    )
                }
                if (reserveImdbInSecondary) {
                    HeroImdbMeta(
                        imdbText = preview.imdbText.orEmpty(),
                        textStyle = labelMedium,
                        textColor = FetchTheme.colors.TextSecondary,
                        logoSize = FetchTheme.spacing.xl * metaScale,
                        spacing = imdbMetaSpacing,
                        visible = showImdbInSecondary
                    )
                }
                if (reserveImdbInSecondary && secondaryDetails.isNotEmpty()) {
                    HeroMetaDivider(
                        scale = metaScale,
                        visible = showImdbInSecondary
                    )
                }
                secondaryDetails.forEachIndexed { index, value ->
                    Text(
                        text = value,
                        style = labelMedium,
                        color = FetchTheme.colors.TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (index < secondaryDetails.lastIndex) {
                        HeroMetaDivider(metaScale)
                    }
                }
                // MDBList ratings inline after other secondary meta.
                if (showImdbRatings && hasMdbListRatings) {
                    // Divider before MDBList ratings is already handled by the
                    // badge/IMDb/details divider logic above — only add one
                    // when IMDb or details were the last visible element.
                    if (showImdbInSecondary || secondaryDetails.isNotEmpty()) {
                        HeroMetaDivider(metaScale)
                    }
                    com.fetch.tv.ui.components.MDBListRatingsRow(
                        ratings = preview.mdbListRatings!!,
                        maxItems = 3,
                        order = mdbListRatingOrder
                    )
                }
            }
        }

        preview.description?.takeIf { it.isNotBlank() && preview.resumeActionText == null }?.let { description ->
            Text(
                text = description,
                style = scaledDescriptionStyle.copy(textDirection = description.contentTextDirection()),
                color = FetchTheme.colors.TextPrimary,
                maxLines = descriptionMaxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.graphicsLayer { alpha = metaAlpha }
            )
        }

        preview.resumeActionText?.let { actionText ->
            val cachedMatch = preview.bestMatchKey?.let(bestMatchFor)
            val cachedLabel = stringResource(R.string.best_match_cached)
            val specLine = remember(preview.resumeTimeLeftText, cachedMatch, cachedLabel) {
                heroResumeSpecLine(preview.resumeTimeLeftText, cachedMatch, cachedLabel)
            }
            HeroResumeRow(
                actionText = actionText,
                specLine = specLine,
                progressFraction = preview.resumeProgressFraction,
                textStyle = labelMedium,
                modifier = Modifier.graphicsLayer { alpha = metaAlpha }
            )
        }
    }
}

/**
 * "38m left · 4K · DV · Atmos · Cached". Only the cached Best match adds stream details, so with
 * nothing cached the line says the time left and nothing it can't verify.
 */
internal fun heroResumeSpecLine(
    timeLeftText: String?,
    cachedMatch: com.fetch.tv.data.local.CachedBestMatch?,
    cachedLabel: String
): SpecLine? = SpecLine.of(
    labels = cachedMatch?.labels.orEmpty(),
    isCached = cachedMatch?.isCached == true,
    cachedLabel = cachedLabel,
    leading = timeLeftText
)

/**
 * Not focusable: it names what OK on the focused Continue Watching card does. It is drawn as a
 * hint (an outlined OK key and the action), never as a filled button, because focus is on the
 * card below and a white pill here read as a second focused control. The accent line under it
 * is the title's watched progress, the same line the card carries.
 */
@Composable
private fun HeroResumeRow(
    actionText: String,
    specLine: SpecLine?,
    progressFraction: Float?,
    textStyle: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier
) {
    val accent = FetchTheme.colors.Secondary
    val keyStyle = remember(textStyle) { textStyle.copy(fontWeight = FontWeight.Bold) }
    val actionStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)
        ) {
            // The launch morph grows from here, so the hint and the action stay one origin.
            Row(
                modifier = Modifier.offersPlayLaunchOrigin(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)
            ) {
                Text(
                    text = stringResource(R.string.hero_resume_ok_key),
                    style = keyStyle,
                    color = accent,
                    maxLines = 1,
                    modifier = Modifier
                        .border(FetchTheme.strokes.focus, accent, CircleShape)
                        .padding(horizontal = FetchTheme.spacing.sm, vertical = FetchTheme.spacing.xxs)
                )
                // Capped, so a long (or translated) action can't take the whole row and leave
                // the spec line nothing; the row's leftover goes to the spec line's weight.
                Text(
                    text = actionText,
                    style = actionStyle,
                    color = FetchTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = HERO_ACTION_MAX_WIDTH)
                )
            }
            specLine?.let { line ->
                SpecStrip(line = line, style = textStyle, modifier = Modifier.weight(1f, fill = false))
            }
        }
        progressFraction?.let { fraction ->
            HeroProgressLine(fraction = fraction, color = accent)
        }
    }
}

/** The Fetch line: a thin track with the watched part in the accent. Drawn, not laid out. */
@Composable
private fun HeroProgressLine(fraction: Float, color: Color) {
    val track = FetchTheme.colors.TextPrimary.copy(alpha = 0.16f)
    val clamped = fraction.coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            // The card and hero text only say the time left; this announces how much is watched.
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(clamped, 0f..1f) }
            .width(HERO_PROGRESS_LINE_WIDTH)
            .height(FetchTheme.spacing.xs)
            .clip(CircleShape)
            .drawBehind {
                drawRect(track)
                drawRect(color, size = size.copy(width = size.width * clamped))
            }
    )
}

private val HERO_PROGRESS_LINE_WIDTH = 280.dp
private val HERO_ACTION_MAX_WIDTH = 240.dp

@Composable
private fun HeroImdbMeta(
    imdbText: String,
    textStyle: androidx.compose.ui.text.TextStyle,
    textColor: Color,
    logoSize: androidx.compose.ui.unit.Dp,
    spacing: androidx.compose.ui.unit.Dp,
    visible: Boolean = true
) {
    Row(
        modifier = Modifier
            .graphicsLayer { alpha = if (visible) 1f else 0f }
            .then(if (visible) Modifier else Modifier.clearAndSetSemantics {}),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing)
    ) {
        ImdbRatingSourceLabel(
            logoModifier = Modifier.size(logoSize),
            textStyle = textStyle,
            textColor = textColor
        )
        Text(
            text = imdbText,
            style = textStyle,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HeroCombinedMetaBadge(
    leftText: String,
    rightText: String,
    textStyle: androidx.compose.ui.text.TextStyle,
    contentColor: Color
) {
    val dividerColor = contentColor.copy(alpha = 0.55f)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(FetchTheme.radii.sm))
            .border(
                border = BorderStroke(FetchTheme.spacing.hairline, dividerColor),
                shape = RoundedCornerShape(FetchTheme.radii.sm)
            )
            .padding(horizontal = FetchTheme.spacing.sm, vertical = FetchTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)
    ) {
        val semiBoldStyle = remember(textStyle) { textStyle.copy(fontWeight = FontWeight.SemiBold) }
        Text(
            text = leftText,
            style = semiBoldStyle,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Box(
            modifier = Modifier
                .width(FetchTheme.spacing.hairline)
                .height(FetchTheme.spacing.md)
                .background(dividerColor)
        )
        Text(
            text = rightText,
            style = semiBoldStyle,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HeroMetaBadge(
    text: String,
    textStyle: androidx.compose.ui.text.TextStyle,
    contentColor: Color
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(FetchTheme.radii.sm))
            .border(
                border = BorderStroke(FetchTheme.spacing.hairline, contentColor.copy(alpha = 0.55f)),
                shape = RoundedCornerShape(FetchTheme.radii.sm)
            )
            .padding(horizontal = FetchTheme.spacing.sm, vertical = FetchTheme.spacing.xs),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = remember(textStyle) { textStyle.copy(fontWeight = FontWeight.SemiBold) },
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HeroMetaDivider(
    scale: Float,
    visible: Boolean = true
) {
    Box(
        modifier = Modifier
            .size((FetchTheme.spacing.xs * scale).coerceAtLeast(FetchTheme.spacing.xxs))
            .clip(RoundedCornerShape(percent = 50))
            .graphicsLayer { alpha = if (visible) 1f else 0f }
            .background(FetchTheme.colors.TextTertiary.copy(alpha = 0.78f))
    )
}
