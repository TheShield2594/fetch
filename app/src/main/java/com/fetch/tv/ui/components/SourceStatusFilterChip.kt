package com.fetch.tv.ui.components

import com.fetch.tv.ui.theme.FetchMotion
import com.fetch.tv.ui.theme.FetchRadii
import com.fetch.tv.ui.theme.FetchTheme
import com.fetch.tv.ui.util.contentTextDirection

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.FilterChipDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

enum class SourceChipStatus {
    LOADING,
    SUCCESS,
    ERROR
}

data class SourceChipItem(
    val name: String,
    val status: SourceChipStatus
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SourceStatusFilterChip(
    name: String,
    isSelected: Boolean,
    status: SourceChipStatus = SourceChipStatus.SUCCESS,
    isSelectable: Boolean = true,
    onClick: () -> Unit,
    onFocusSelect: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val isError = status == SourceChipStatus.ERROR
    val isLoading = status == SourceChipStatus.LOADING
    val shouldUseNormalColors = !isError
    val shakeOffsetPx = remember { Animatable(0f) }
    val alphaPulse = remember { Animatable(1f) }

    LaunchedEffect(isError) {
        shakeOffsetPx.snapTo(0f)
        alphaPulse.snapTo(1f)
        if (!isError) return@LaunchedEffect

        alphaPulse.animateTo(
            targetValue = 0.9f,
            animationSpec = tween(durationMillis = FetchMotion.tokens.durations.quick, easing = FastOutSlowInEasing)
        )
        alphaPulse.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = FetchMotion.tokens.durations.fast, easing = FastOutSlowInEasing)
        )

        // Two gentle shakes (less abrupt than the previous infinite shake/blink loop).
        shakeOffsetPx.animateTo(3f, tween(FetchMotion.tokens.durations.quick, easing = FastOutSlowInEasing))
        shakeOffsetPx.animateTo(-3f, tween(FetchMotion.tokens.durations.quick, easing = FastOutSlowInEasing))
        shakeOffsetPx.animateTo(2f, tween(FetchMotion.tokens.durations.quick, easing = FastOutSlowInEasing))
        shakeOffsetPx.animateTo(-2f, tween(FetchMotion.tokens.durations.quick, easing = FastOutSlowInEasing))
        shakeOffsetPx.animateTo(0f, tween(FetchMotion.tokens.durations.quick, easing = FastOutSlowInEasing))
    }

    val textColor = when {
        isError -> FetchTheme.colors.Error
        isFocused || isSelected -> FetchTheme.colors.OnSecondary
        else -> FetchTheme.colors.TextSecondary
    }

    FilterChip(
        selected = isSelected,
        onClick = { if (isSelectable) onClick() },
        modifier = modifier
            .graphicsLayer {
                alpha = alphaPulse.value
                translationX = shakeOffsetPx.value
            }
            .onFocusChanged {
                val nowFocused = it.isFocused
                isFocused = nowFocused
                if (nowFocused && !isSelected && isSelectable) {
                    onFocusSelect?.invoke()
                }
            },
        colors = FilterChipDefaults.colors(
            containerColor = if (shouldUseNormalColors) FetchTheme.colors.BackgroundCard else FetchTheme.colors.Error.copy(alpha = 0.05f),
            focusedContainerColor = if (shouldUseNormalColors) FetchTheme.colors.Secondary else FetchTheme.colors.Error.copy(alpha = 0.08f),
            selectedContainerColor = if (shouldUseNormalColors) FetchTheme.colors.Secondary else FetchTheme.colors.Error.copy(alpha = 0.07f),
            focusedSelectedContainerColor = if (shouldUseNormalColors) FetchTheme.colors.Secondary else FetchTheme.colors.Error.copy(alpha = 0.09f),
            contentColor = textColor,
            focusedContentColor = textColor,
            selectedContentColor = textColor,
            focusedSelectedContentColor = textColor
        ),
        border = FilterChipDefaults.border(
            border = Border(
                border = BorderStroke(FetchTheme.spacing.hairline, if (isError) FetchTheme.colors.Error.copy(alpha = 0.7f) else FetchTheme.colors.Border),
                shape = RoundedCornerShape(FetchRadii.tokens.xxl)
            ),
            focusedBorder = Border(
                border = if (isError) {
                    BorderStroke(FetchTheme.spacing.xxs, FetchTheme.colors.Error.copy(alpha = 0.8f))
                } else {
                    FetchTheme.focusRing.border(FetchTheme.spacing.xxs)
                },
                shape = RoundedCornerShape(FetchRadii.tokens.xxl)
            ),
            selectedBorder = Border(
                border = BorderStroke(FetchTheme.spacing.hairline, if (isError) FetchTheme.colors.Error.copy(alpha = 0.75f) else FetchTheme.colors.Primary),
                shape = RoundedCornerShape(FetchRadii.tokens.xxl)
            ),
            focusedSelectedBorder = Border(
                border = if (isError) {
                    BorderStroke(FetchTheme.spacing.xxs, FetchTheme.colors.Error.copy(alpha = 0.8f))
                } else {
                    FetchTheme.focusRing.border(FetchTheme.spacing.xxs)
                },
                shape = RoundedCornerShape(FetchRadii.tokens.xxl)
            )
        ),
        shape = FilterChipDefaults.shape(shape = RoundedCornerShape(FetchRadii.tokens.xxl))
    ) {
        Text(
            text = name,
            modifier = Modifier.shimmer(isLoading),
            style = MaterialTheme.typography.labelLarge.copy(
                textDirection = name.contentTextDirection()
            ),
            color = textColor
        )
    }
}
