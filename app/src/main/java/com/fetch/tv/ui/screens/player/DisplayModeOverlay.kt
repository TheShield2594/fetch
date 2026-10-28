@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.fetch.tv.ui.screens.player

import com.fetch.tv.ui.theme.FetchRadii
import com.fetch.tv.ui.theme.FetchTheme

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import androidx.compose.ui.res.stringResource
import com.fetch.tv.R
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private val ROW_HEIGHT = 18.dp
private val ROW_GAP = FetchTheme.spacing.xxs

@Composable
fun DisplayModeOverlay(
    info: DisplayModeInfo?,
    isVisible: Boolean,
    onAnimationComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (info == null) return

    val items = listOf(
        stringResource(R.string.display_mode_refresh) to formatHz(info.refreshRate),
        stringResource(R.string.display_mode_resolution) to "${info.width}x${info.height}"
    )
    val count = items.size
    val totalLineHeight = (ROW_HEIGHT.value * count) + (ROW_GAP.value * (count - 1))

    val containerAlpha = remember { Animatable(0f) }
    val lineHeightFraction = remember { Animatable(0f) }
    val itemAlphas = remember(info) { List(count) { Animatable(0f) } }
    var animating by remember { mutableStateOf(false) }

    val fullMotion = FetchTheme.fullMotion

    LaunchedEffect(isVisible, info) {
        if (isVisible && !animating) {
            animating = true
            playStartOverlayIn(fullMotion, containerAlpha, lineHeightFraction, itemAlphas)
            delay(START_OVERLAY_HOLD_MS)
            playStartOverlayOut(fullMotion, containerAlpha, lineHeightFraction, itemAlphas)
            animating = false
            onAnimationComplete()
        } else if (!isVisible && animating) {
            // Quick hide if something forces it off (e.g. controls shown during animation)
            for (i in (count - 1) downTo 0) {
                itemAlphas[i].snapTo(0f)
            }
            lineHeightFraction.snapTo(0f)
            containerAlpha.snapTo(0f)
            animating = false
            onAnimationComplete()
        }
    }

    // Recompose only when the overlay appears or disappears, not on every fade frame.
    val shown by remember { derivedStateOf { containerAlpha.value > 0f } }
    if (!shown) return

    Column(
        modifier = modifier
            .graphicsLayer { alpha = containerAlpha.value }
            .padding(end = FetchTheme.spacing.xxl, top = FetchTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm),
        horizontalAlignment = Alignment.End
    ) {
        val statusMessage = info.statusMessage?.takeIf { it.isNotBlank() }
        if (statusMessage != null) {
            Text(
                text = statusMessage,
                fontSize = 12.sp,
                color = FetchTheme.colors.Secondary,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .clip(RoundedCornerShape(FetchRadii.tokens.sm))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = FetchTheme.spacing.md, vertical = FetchTheme.spacing.xs)
            )
        }

        Row(verticalAlignment = Alignment.Top) {
            Column(
                modifier = Modifier.padding(end = 10.dp),
                verticalArrangement = Arrangement.spacedBy(ROW_GAP),
                horizontalAlignment = Alignment.End
            ) {
                items.forEachIndexed { index, item ->
                    Row(
                        modifier = Modifier
                            .height(ROW_HEIGHT)
                            .graphicsLayer { alpha = itemAlphas.getOrNull(index)?.value ?: 0f },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = item.first,
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.85f),
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.End
                        )
                        Text(
                            text = " - ",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.4f),
                        )
                        Text(
                            text = item.second,
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.6f),
                            textAlign = TextAlign.End
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(totalLineHeight.dp)
                    .graphicsLayer {
                        scaleY = lineHeightFraction.value
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    }
                    .clip(RoundedCornerShape(FetchTheme.spacing.hairline))
                    .background(FetchTheme.colors.Secondary)
            )
        }
    }
}

internal fun formatHz(rate: Float): String {
    val rounded = (rate * 1000f).roundToInt() / 1000f
    val whole = rounded.roundToInt()
    val display = if (abs(rounded - whole.toFloat()) < 0.01f) {
        whole.toString()
    } else {
        String.format("%.3f", rounded)
    }
    return "$display Hz"
}
