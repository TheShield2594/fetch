@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.fetch.tv.ui.screens.settings

import com.fetch.tv.ui.theme.FetchTheme

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlin.math.roundToInt

@Composable
internal fun SliderSettingsItem(
    title: String,
    value: Int,
    valueText: String,
    minValue: Int,
    maxValue: Int,
    step: Int,
    onValueChange: (Int) -> Unit,
    subtitle: String? = null,
    onFocused: () -> Unit = {},
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val span = (maxValue - minValue).toFloat()
    val progress = if (span > 0f) (value - minValue).toFloat() / span else 0f

    SliderSettingsItemLayout(
        title = title,
        valueText = valueText,
        subtitle = subtitle,
        enabled = enabled,
        progressFraction = progress,
        rangeInfo = ProgressBarRangeInfo(
            current = value.toFloat(),
            range = minValue.toFloat()..maxValue.toFloat(),
            steps = if (step > 0) ((maxValue - minValue) / step - 1).coerceAtLeast(0) else 0
        ),
        onSetProgress = { target ->
            val stepCount = if (step > 0) ((target - minValue) / step).roundToInt() else 0
            val newValue = (minValue + stepCount * step).coerceIn(minValue, maxValue)
            if (newValue != value) onValueChange(newValue)
        },
        onDecrease = {
            val newValue = (value - step).coerceAtLeast(minValue)
            if (newValue != value) onValueChange(newValue)
        },
        onIncrease = {
            val newValue = (value + step).coerceAtMost(maxValue)
            if (newValue != value) onValueChange(newValue)
        },
        onFocused = onFocused,
        modifier = modifier
    )
}

@Composable
internal fun SliderSettingsItem(
    title: String,
    values: List<Int>,
    selected: Int,
    valueText: String,
    onValueChange: (Int) -> Unit,
    subtitle: String? = null,
    onFocused: () -> Unit = {},
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    require(values.isNotEmpty()) { "SliderSettingsItem.values must not be empty" }

    val index = values.indexOf(selected).coerceAtLeast(0)
    val lastIndex = values.lastIndex
    val progress = if (lastIndex > 0) index.toFloat() / lastIndex.toFloat() else 0f

    SliderSettingsItemLayout(
        title = title,
        valueText = valueText,
        subtitle = subtitle,
        enabled = enabled,
        progressFraction = progress,
        rangeInfo = ProgressBarRangeInfo(
            current = index.toFloat(),
            range = 0f..lastIndex.toFloat().coerceAtLeast(0f),
            steps = (lastIndex - 1).coerceAtLeast(0)
        ),
        onSetProgress = { target ->
            val newValue = values[target.roundToInt().coerceIn(0, lastIndex)]
            if (newValue != selected) onValueChange(newValue)
        },
        onDecrease = {
            val newValue = values[(index - 1).coerceAtLeast(0)]
            if (newValue != selected) onValueChange(newValue)
        },
        onIncrease = {
            val newValue = values[(index + 1).coerceAtMost(lastIndex)]
            if (newValue != selected) onValueChange(newValue)
        },
        onFocused = onFocused,
        modifier = modifier
    )
}

@Composable
private fun SliderSettingsItemLayout(
    title: String,
    valueText: String,
    subtitle: String?,
    enabled: Boolean,
    progressFraction: Float,
    rangeInfo: ProgressBarRangeInfo,
    onSetProgress: (Float) -> Unit,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val contentAlpha = if (enabled) 1f else SettingsDisabledContentAlpha
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val shape = settingsTallRowShape()

    Card(
        onClick = { },
        modifier = modifier
            .settingsRowTarget(title)
            .fillMaxWidth()
            .onFocusChanged { state ->
                val nowFocused = state.isFocused
                if (isFocused != nowFocused) {
                    isFocused = nowFocused
                    if (nowFocused) onFocused()
                }
            }
            .onKeyEvent { event ->
                if (!enabled) return@onKeyEvent false
                if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (isRtl) onIncrease() else onDecrease()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (isRtl) onDecrease() else onIncrease()
                        true
                    }
                    else -> false
                }
            }
            // The row is the one control: Left/Right move the value, and TalkBack hears an
            // adjustable range with its current value rather than a button.
            .semantics(mergeDescendants = true) {
                if (!enabled) disabled()
                progressBarRangeInfo = rangeInfo
                stateDescription = valueText
                setProgress { target ->
                    if (enabled) onSetProgress(target)
                    enabled
                }
            },
        colors = settingsRowColors(),
        border = settingsRowBorder(contentAlpha = if (enabled) 1f else 0.3f, shape = shape),
        shape = CardDefaults.shape(shape = shape),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SettingsRowContentInset, vertical = FetchTheme.spacing.md)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = FetchTheme.colors.TextPrimary.copy(alpha = contentAlpha),
                        maxLines = settingsRowTextMaxLines(isFocused),
                        overflow = TextOverflow.Ellipsis
                    )
                    if (subtitle != null) {
                        Spacer(modifier = Modifier.height(FetchTheme.spacing.xxs))
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = FetchTheme.colors.TextSecondary.copy(alpha = contentAlpha),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(FetchTheme.spacing.md))

                Text(
                    text = valueText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = FetchTheme.colors.TextPrimary.copy(alpha = contentAlpha)
                )
            }

            Spacer(modifier = Modifier.height(FetchTheme.spacing.sm))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md),
                modifier = Modifier.fillMaxWidth()
            ) {
                SliderStepIcon(icon = Icons.Default.Remove, enabled = enabled)

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(FetchTheme.spacing.sm)
                        .clip(RoundedCornerShape(FetchTheme.radii.xs))
                        .background(FetchTheme.colors.BackgroundElevated)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progressFraction.coerceIn(0f, 1f))
                            .height(FetchTheme.spacing.sm)
                            .clip(RoundedCornerShape(FetchTheme.radii.xs))
                            .background(FetchTheme.colors.Accent.copy(alpha = contentAlpha))
                    )
                }

                SliderStepIcon(icon = Icons.Default.Add, enabled = enabled)
            }
        }
    }
}

/**
 * The − and + beside the track. Only a hint at what Left and Right do: they can't take focus,
 * since the row already handles both keys and extra stops only trapped them.
 */
@Composable
private fun SliderStepIcon(
    icon: ImageVector,
    enabled: Boolean
) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = FetchTheme.colors.TextSecondary.copy(alpha = if (enabled) 1f else SettingsDisabledContentAlpha),
        modifier = Modifier.size(20.dp)
    )
}

@Composable
internal fun ColorSettingsItem(
    title: String,
    currentColor: Color,
    showTransparent: Boolean = false,
    onClick: () -> Unit,
    onFocused: () -> Unit = {},
    enabled: Boolean = true
) {
    var isFocused by remember { mutableStateOf(false) }
    val contentAlpha = if (enabled) 1f else SettingsDisabledContentAlpha

    Card(
        onClick = { if (enabled) onClick() },
        modifier = Modifier
            .settingsRowTarget(title)
            .fillMaxWidth()
            .onFocusChanged { state ->
                val nowFocused = state.isFocused
                if (isFocused != nowFocused) {
                    isFocused = nowFocused
                    if (nowFocused) onFocused()
                }
            },
        colors = settingsRowColors(),
        border = settingsRowBorder(contentAlpha = if (enabled) 1f else 0.3f),
        shape = CardDefaults.shape(shape = settingsRowShape()),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = SettingsRowMinHeight)
                .padding(horizontal = SettingsRowContentInset, vertical = FetchTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = FetchTheme.colors.TextPrimary.copy(alpha = contentAlpha),
                maxLines = settingsRowTextMaxLines(isFocused),
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(FetchTheme.spacing.md))

            if (showTransparent || currentColor.alpha == 0f) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.Gray)
                        .border(FetchTheme.spacing.xxs, FetchTheme.colors.Border, CircleShape)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(brush = Brush.linearGradient(colors = listOf(Color.White, Color.Gray, Color.White)))
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(currentColor)
                        .border(FetchTheme.spacing.xxs, FetchTheme.colors.Border, CircleShape)
                )
            }
        }
    }
}
