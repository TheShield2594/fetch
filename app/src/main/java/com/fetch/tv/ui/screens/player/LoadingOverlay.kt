package com.fetch.tv.ui.screens.player

import com.fetch.tv.ui.theme.FetchMotion
import com.fetch.tv.ui.theme.FetchTheme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import androidx.compose.ui.platform.LocalContext
import com.fetch.tv.ui.components.LoadingIndicator
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.ui.theme.FetchPrimitives

@Composable
fun LoadingOverlay(
    visible: Boolean,
    backdropUrl: String?,
    logoUrl: String?,
    title: String? = null,
    message: String? = null,
    progress: Float? = null,
    modifier: Modifier = Modifier,
    /** Height of a panel drawn over the bottom of the overlay; the logo centres above it. */
    bottomInset: Dp = 0.dp
) {
    var logoLoadFailed by remember(logoUrl) { mutableStateOf(false) }
    val showLogo = !logoUrl.isNullOrBlank() && !logoLoadFailed
    val fullMotion = FetchTheme.fullMotion
    // Layout takes the new inset at once; the drawn content glides there (see the BoxWithConstraints).
    val drawnBottomInset = remember { Animatable(bottomInset, Dp.VectorConverter) }
    LaunchedEffect(bottomInset, fullMotion) {
        // The first inset arrives a frame after the panel is measured, while the overlay is
        // still fading in: snap it so the logo never drifts on open.
        if (!fullMotion || drawnBottomInset.value == 0.dp) {
            drawnBottomInset.snapTo(bottomInset)
        } else {
            drawnBottomInset.animateTo(bottomInset, FetchMotion.moveTween())
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(FetchMotion.tokens.durations.fast)),
        exit = fadeOut(animationSpec = tween(FetchMotion.tokens.durations.fast)),
        modifier = modifier
    ) {
        val context = LocalContext.current
        // Only the idle logo or title breathes; the fill mode holds still.
        val logoPulse = rememberLogoPulse(enabled = fullMotion && !(showLogo && progress != null))
        val backdropRequest = remember(context, backdropUrl) {
            backdropUrl?.takeIf { it.isNotBlank() }?.let { url ->
                ImageRequest.Builder(context)
                    .data(url)
                    .crossfade(true)
                    .build()
            }
        }
        val logoRequest = remember(context, logoUrl) {
            logoUrl?.takeIf { it.isNotBlank() }?.let { url ->
                ImageRequest.Builder(context)
                    .data(url)
                    .crossfade(true)
                    .build()
            }
        }
        val overlayBrush = remember {
            Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to FetchPrimitives.black.copy(alpha = 0.3f),
                    0.35f to FetchPrimitives.black.copy(alpha = 0.6f),
                    0.7f to FetchPrimitives.black.copy(alpha = 0.8f),
                    1f to FetchPrimitives.black.copy(alpha = 0.9f)
                )
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(FetchPrimitives.black)
        ) {
            if (backdropRequest != null) {
                AsyncImage(
                    model = backdropRequest,
                    contentDescription = stringResource(R.string.cd_loading_backdrop),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopEnd
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(overlayBrush)
            )

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = bottomInset)
                    .graphicsLayer {
                        // Centred content moves half as far as its bottom padding changes.
                        translationY = (bottomInset - drawnBottomInset.value).toPx() / 2f
                    },
                contentAlignment = Alignment.Center
            ) {
                // Shrinks the logo only when a bottom panel leaves less room than it needs.
                val logoHeight = (maxHeight - FetchTheme.spacing.xl * 2).coerceIn(48.dp, 180.dp)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (showLogo) {
                        val isLogoFillActive = progress != null
                        val targetFill = (progress ?: 0f).coerceIn(0f, 1f)
                        val animatedFill by animateFloatAsState(
                            targetValue = targetFill,
                            // When completing (target ≈ 1.0), animate faster so the
                            // fill finishes before the overlay's 200ms fade-out;
                            // otherwise use the smoother 400ms tween for buffering.
                            animationSpec = tween(
                                durationMillis = if (targetFill >= 0.999f) 160 else 400,
                                easing = LinearEasing
                            ),
                            label = "loadingLogoFill"
                        )
                        Box(
                            modifier = Modifier
                                .width(logoHeight * 16f / 9f)
                                .height(logoHeight)
                        ) {
                            // Base layer: semi-transparent logo (always visible).
                            // When the fill effect is active we hold a steady low
                            // alpha; otherwise fall back to the original fade+pulse.
                            AsyncImage(
                                model = logoRequest,
                                contentDescription = stringResource(R.string.cd_loading_logo),
                                onError = { logoLoadFailed = true },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        alpha = if (isLogoFillActive) 0.25f else 1f
                                        val scale = logoPulse?.value ?: 1f
                                        scaleX = scale
                                        scaleY = scale
                                    },
                                contentScale = ContentScale.Fit
                            )
                            // Foreground layer: full-alpha logo clipped from left
                            // to right by progress, giving the illusion that the
                            // base logo is filling up as buffering proceeds.
                            if (isLogoFillActive) {
                                AsyncImage(
                                    model = logoRequest,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .drawWithContent {
                                            val clipWidth = size.width * animatedFill
                                            if (clipWidth > 0f) {
                                                clipRect(right = clipWidth) {
                                                    this@drawWithContent.drawContent()
                                                }
                                            }
                                        },
                                    contentScale = ContentScale.Fit
                                )
                            }
                        }
                    } else if (!title.isNullOrBlank()) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineMedium,
                            color = FetchTheme.colors.Player.TextPrimary,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            modifier = Modifier
                                .padding(horizontal = FetchTheme.spacing.xl)
                                .graphicsLayer {
                                    val scale = logoPulse?.value ?: 1f
                                    scaleX = scale
                                    scaleY = scale
                                }
                        )
                    } else {
                        Box(
                            modifier = Modifier.size(180.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            LoadingIndicator()
                        }
                    }

                }

                // The horizontal progress bar is suppressed when the show logo
                // is acting as the fill indicator. The text message stays visible.
                val showHorizontalBar = progress != null && !showLogo
                if (!message.isNullOrBlank() || showHorizontalBar) {
                    val messageOffset = if (showLogo || !title.isNullOrBlank()) 94.dp else 86.dp
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset(y = messageOffset)
                            .padding(horizontal = FetchTheme.spacing.xl)
                    ) {
                        Crossfade(
                            targetState = message?.takeIf { it.isNotBlank() },
                            animationSpec = tween(durationMillis = FetchMotion.tokens.durations.fast),
                            label = "loadingMessageCrossfade",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                        ) { loadingMessage ->
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                if (loadingMessage != null) {
                                    Text(
                                        text = loadingMessage,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = FetchTheme.colors.Player.TextTertiary,
                                        textAlign = TextAlign.Center,
                                        maxLines = 2,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                        if (showHorizontalBar) {
                            Spacer(modifier = Modifier.height(10.dp))
                            val animatedProgress = animateFloatAsState(
                                targetValue = progress.coerceIn(0f, 1f),
                                // Constant-rate indicator: linear, and read only while drawing.
                                animationSpec = tween(durationMillis = FetchMotion.tokens.durations.overlay, easing = LinearEasing),
                                label = "loadingProgress"
                            )
                            val fillColor = FetchPrimitives.white.copy(alpha = 0.85f)
                            val fillRadius = FetchTheme.radii.xxs
                            Box(
                                modifier = Modifier
                                    .width(240.dp)
                                    .height(FetchTheme.spacing.xs)
                                    .background(
                                        color = FetchPrimitives.white.copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(FetchTheme.radii.xxs)
                                    )
                                    .drawBehind {
                                        val fillWidth = size.width * animatedProgress.value
                                        if (fillWidth > 0f) {
                                            drawRoundRect(
                                                color = fillColor,
                                                size = Size(fillWidth, size.height),
                                                cornerRadius = CornerRadius(fillRadius.toPx())
                                            )
                                        }
                                    }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The idle logo's slow breathing scale, or null when it should hold still. Read the value
 * only in a graphicsLayer, so the pulse redraws without recomposing the overlay.
 */
@Composable
private fun rememberLogoPulse(enabled: Boolean): State<Float>? {
    if (!enabled) return null
    val transition = rememberInfiniteTransition(label = "loadingLogoPulse")
    return transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = FetchMotion.tokens.easings.inOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "loadingLogoScale"
    )
}
