package com.fetch.tv.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.domain.model.CustomThemeColors
import com.fetch.tv.domain.model.formatHexColor
import com.fetch.tv.ui.components.FetchDialog
import com.fetch.tv.ui.screens.detail.requestFocusAfterFrames
import com.fetch.tv.ui.theme.FetchTheme
import com.fetch.tv.ui.theme.accentBrush
import com.fetch.tv.ui.theme.adjustedForContrast
import com.fetch.tv.ui.theme.toColorPalette

@Composable
internal fun CustomThemeDialog(
    initialColors: CustomThemeColors,
    allowGradient: Boolean,
    onSave: (CustomThemeColors) -> Unit,
    onDismiss: () -> Unit
) {
    var gradientEnabled by remember(allowGradient) { mutableStateOf(allowGradient && !initialColors.isSolid) }
    FetchDialog(
        onDismiss = onDismiss,
        title = stringResource(
            if (gradientEnabled) R.string.custom_theme_title else R.string.custom_theme_solid_title
        ),
        subtitle = stringResource(
            if (gradientEnabled) R.string.custom_theme_subtitle else R.string.custom_theme_solid_subtitle
        ),
        width = 840.dp,
        usePlatformDefaultWidth = false,
        contentPadding = 20.dp,
        contentSpacing = 12.dp,
        scrollable = true
    ) {
        CustomThemeEditor(
            initialColors = initialColors,
            gradientEnabled = gradientEnabled,
            onSave = onSave,
            onDismiss = onDismiss,
            onGradientChanged = if (allowGradient) { { gradientEnabled = it } } else null
        )
    }
}

@Composable
internal fun CustomThemeEditor(
    initialColors: CustomThemeColors,
    gradientEnabled: Boolean,
    onSave: (CustomThemeColors) -> Unit,
    onDismiss: () -> Unit,
    onGradientChanged: ((Boolean) -> Unit)? = null
) {
    var draftColors by remember { mutableStateOf(initialColors) }
    val colors = if (gradientEnabled) draftColors else CustomThemeColors.solid(draftColors.second)
    var selectedIndex by remember(gradientEnabled) { mutableStateOf(0) }
    var showHexEditor by remember(gradientEnabled) { mutableStateOf(false) }
    var restoreHexFocus by remember(gradientEnabled) { mutableStateOf(false) }
    val firstColorFocusRequester = remember { FocusRequester() }
    val firstSwatchFocusRequester = remember { FocusRequester() }
    val modeFocusRequester = remember { FocusRequester() }
    val hexFocusRequester = remember { FocusRequester() }
    val cancelFocusRequester = remember { FocusRequester() }
    val selectedColor = colors.colors[selectedIndex]

    fun updateColor(color: Int) {
        draftColors = draftColors.withColor(if (gradientEnabled) selectedIndex else 1, color)
    }

    LaunchedEffect(Unit) {
        val focusRequester = when {
            onGradientChanged != null -> modeFocusRequester
            gradientEnabled -> firstColorFocusRequester
            else -> firstSwatchFocusRequester
        }
        focusRequester.requestFocusAfterFrames()
    }
    LaunchedEffect(showHexEditor) {
        if (!showHexEditor && restoreHexFocus) {
            restoreHexFocus = false
            hexFocusRequester.requestFocusAfterFrames()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)) {
        if (onGradientChanged != null) {
            Row(
                modifier = Modifier.selectableGroup().settingsOptionRow(modeFocusRequester),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(true, false).forEach { gradient ->
                    val selected = gradientEnabled == gradient
                    SettingsChoiceChip(
                        label = stringResource(
                            if (gradient) R.string.custom_theme_mode_gradient else R.string.custom_theme_mode_solid
                        ),
                        selected = selected,
                        onClick = { onGradientChanged(gradient) },
                        modifier = Modifier
                            .then(if (selected) Modifier.focusRequester(modeFocusRequester) else Modifier)
                            .semantics {
                                role = Role.RadioButton
                                this.selected = selected
                            }
                    )
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
        ) {
            Column(
                modifier = Modifier.weight(1.25f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (gradientEnabled) {
                    Row(
                        modifier = Modifier.settingsOptionRow(firstColorFocusRequester),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        colors.colors.forEachIndexed { index, color ->
                            ThemeColorSlot(
                                index = index,
                                color = color,
                                selected = index == selectedIndex,
                                onClick = { selectedIndex = index },
                                modifier = Modifier.weight(1f).then(
                                    if (index == 0) Modifier.focusRequester(firstColorFocusRequester) else Modifier
                                )
                            )
                        }
                    }
                }

                ThemeColorPicker(
                    color = selectedColor,
                    colorIndex = selectedIndex,
                    onColorChanged = ::updateColor,
                    firstSwatchFocusRequester = firstSwatchFocusRequester
                )

                Card(
                    onClick = { restoreHexFocus = true; showHexEditor = true },
                    modifier = Modifier.fillMaxWidth().focusRequester(hexFocusRequester),
                    colors = CardDefaults.colors(
                        containerColor = FetchTheme.colors.Background,
                        focusedContainerColor = FetchTheme.colors.Background
                    ),
                    shape = CardDefaults.shape(RoundedCornerShape(FetchTheme.radii.sm)),
                    border = CardDefaults.border(
                        focusedBorder = Border(FetchTheme.focusRing.border(FetchTheme.strokes.focus), shape = RoundedCornerShape(FetchTheme.radii.sm))
                    ),
                    scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = FetchTheme.spacing.md, vertical = FetchTheme.spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(
                            stringResource(R.string.custom_theme_enter_hex),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            formatHexColor(selectedColor),
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace)
                        )
                    }
                }
            }

            CustomThemeSample(colors, modifier = Modifier.weight(1f).fillMaxHeight())
        }

        Row(
            modifier = Modifier.fillMaxWidth().settingsOptionRow(cancelFocusRequester),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(R.string.custom_theme_remote_hint),
                style = MaterialTheme.typography.bodySmall,
                color = FetchTheme.colors.TextSecondary,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .height(FetchTheme.components.buttonHeight)
                    .focusRequester(cancelFocusRequester)
                    .focusProperties { up = hexFocusRequester }
            ) {
                Text(stringResource(R.string.action_cancel))
            }
            Button(
                onClick = { onSave(colors) },
                modifier = Modifier.height(FetchTheme.components.buttonHeight).focusProperties { up = hexFocusRequester },
                // Save is the primary action: the accent, kept on focus and marked by the ring.
                colors = ButtonDefaults.colors(
                    containerColor = FetchTheme.colors.Secondary,
                    contentColor = FetchTheme.colors.OnSecondary,
                    focusedContainerColor = FetchTheme.colors.Secondary,
                    focusedContentColor = FetchTheme.colors.OnSecondary
                ),
                border = ButtonDefaults.border(
                    focusedBorder = Border(FetchTheme.focusRing.border(FetchTheme.strokes.focus), shape = FetchTheme.shapes.button)
                )
            ) { Text(stringResource(R.string.custom_theme_save)) }
        }
    }

    if (showHexEditor) {
        HexColorDialog(
            initialColor = selectedColor,
            onConfirm = {
                updateColor(it)
                showHexEditor = false
            },
            onDismiss = { showHexEditor = false }
        )
    }
}

@Composable
private fun ThemeColorSlot(
    index: Int,
    color: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(FetchTheme.radii.md)
    val label = stringResource(R.string.custom_theme_color_number, index + 1)
    Card(
        onClick = onClick,
        modifier = modifier.semantics {
            this.selected = selected
            contentDescription = "$label, ${formatHexColor(color)}"
        },
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(
            containerColor = FetchTheme.colors.Background,
            focusedContainerColor = FetchTheme.colors.Background
        ),
        border = CardDefaults.border(
            border = if (selected) Border(FetchTheme.focusRing.border(FetchTheme.spacing.hairline, alpha = 0.5f), shape = shape) else Border.None,
            focusedBorder = Border(FetchTheme.focusRing.border(FetchTheme.strokes.focus), shape = shape)
        ),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = FetchTheme.spacing.md, vertical = FetchTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(Modifier.size(22.dp).background(Color(color or 0xFF000000.toInt()), CircleShape))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = FetchTheme.colors.TextPrimary
            )
        }
    }
}

@Composable
private fun CustomThemeSample(colors: CustomThemeColors, modifier: Modifier = Modifier) {
    val palette = remember(colors) { colors.toColorPalette() }
    val brush = palette.accentBrush()
    val sampleShape = RoundedCornerShape(FetchTheme.radii.md)
    Column(
        modifier = modifier
            .background(palette.background, RoundedCornerShape(FetchTheme.radii.xl))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            stringResource(R.string.custom_theme_preview),
            style = MaterialTheme.typography.labelMedium,
            color = FetchTheme.colors.TextSecondary
        )
        // The accent marks primary actions, so the preview shows it as a Play button on a card.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(palette.backgroundCard, sampleShape)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                stringResource(R.string.custom_theme_preview_action),
                style = MaterialTheme.typography.titleMedium,
                color = palette.onSecondary,
                modifier = Modifier
                    .background(brush, FetchTheme.shapes.button)
                    .padding(horizontal = FetchTheme.spacing.xl, vertical = FetchTheme.spacing.sm)
            )
        }
        if (colors.adjustedForContrast) {
            Text(
                stringResource(R.string.custom_theme_adjusted_for_contrast),
                style = MaterialTheme.typography.bodySmall,
                color = FetchTheme.colors.TextSecondary
            )
        }
    }
}
