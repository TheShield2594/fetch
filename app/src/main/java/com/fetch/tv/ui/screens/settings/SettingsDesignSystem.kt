@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.fetch.tv.ui.screens.settings

import com.fetch.tv.ui.theme.FetchTheme

import androidx.annotation.RawRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardBorder
import androidx.tv.material3.CardColors
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.ui.components.FocusMarqueeText
import com.fetch.tv.ui.components.BrandWordmark
import com.fetch.tv.ui.util.contentTextDirection
import com.fetch.tv.ui.components.FetchDialog
import com.fetch.tv.ui.screens.detail.requestFocusAfterFrames
import com.fetch.tv.ui.theme.FetchComponents
import com.fetch.tv.ui.theme.FetchRadii
import com.fetch.tv.ui.util.rememberRawSvgPainter

internal val SettingsContainerRadius = FetchComponents.tokens.settings.containerRadius
internal val SettingsPillRadius = FetchRadii.tokens.full
internal val SettingsSecondaryCardRadius = FetchComponents.tokens.settings.secondaryCardRadius
internal val SettingsRailItemHeight = FetchComponents.tokens.settings.railItemHeight

/**
 * Marks a row that holds several options side by side. Without it, moving into the row uses a
 * geometric search and lands on whichever option sits nearest the full width row above, which is
 * the middle one. With it, coming back to the row takes the option last used, and anything else
 * takes [fallbackOption], which callers point at the first option.
 *
 * The fallback has to be named. Left at the default it hands the choice back to the same geometric
 * search, which puts focus in the middle again.
 */
internal fun Modifier.settingsOptionRow(fallbackOption: FocusRequester): Modifier = this
    .focusRestorer(fallbackOption)
    .focusGroup()

internal fun settingsRowShape(): RoundedCornerShape = RoundedCornerShape(SettingsPillRadius)

internal fun settingsTallRowShape(): RoundedCornerShape = RoundedCornerShape(SettingsSecondaryCardRadius)

@Composable
internal fun settingsRowColors(): CardColors = CardDefaults.colors(
    containerColor = FetchTheme.colors.Background,
    focusedContainerColor = FetchTheme.colors.Background
)

@Composable
internal fun settingsRowBorder(
    contentAlpha: Float = 1f,
    shape: RoundedCornerShape = settingsRowShape()
): CardBorder = CardDefaults.border(
    focusedBorder = Border(
        border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs, alpha = contentAlpha),
        shape = shape
    )
)

internal object SettingsTestTags {
    const val ROW_ICON = "settings_row_icon"
}

@Composable
private fun SettingsRowLeadingIcon(
    icon: ImageVector,
    enabled: Boolean,
    focused: Boolean
) {
    val contentAlpha = if (enabled) 1f else 0.4f
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = (if (focused && enabled) FetchTheme.colors.TextPrimary else FetchTheme.colors.TextSecondary)
            .copy(alpha = contentAlpha),
        modifier = Modifier
            .size(FetchTheme.spacing.xl)
            .testTag(SettingsTestTags.ROW_ICON)
    )
}

internal data class SettingsPickerOption<T>(
    val value: T,
    val title: String,
    val description: String? = null,
    val trailing: String? = null,
    val titleFontFamily: FontFamily? = null
)

@Composable
internal fun SettingsStandaloneScaffold(
    title: String,
    subtitle: String,
    classicContainer: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = FetchTheme.spacing.xxl, vertical = FetchTheme.spacing.xl)
    ) {
        SettingsWorkspaceSurface(
            modifier = Modifier
                .fillMaxSize(),
            classicContainer = classicContainer
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.lg)
            ) {
                content()
            }
        }
    }
}

@Composable
internal fun SettingsBrandPanel(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    showBuiltInHeader: Boolean = true
) {
    val titleColor = if (showBuiltInHeader) FetchTheme.colors.TextPrimary else Color.Transparent
    val subtitleColor = if (showBuiltInHeader) FetchTheme.colors.TextSecondary else Color.Transparent

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(SettingsContainerRadius))
            .background(FetchTheme.colors.BackgroundElevated)
            .border(
                width = FetchTheme.spacing.hairline,
                color = FetchTheme.colors.Border,
                shape = RoundedCornerShape(SettingsContainerRadius)
            )
            .padding(26.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(FetchTheme.colors.BackgroundCard),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = titleColor
                )
            }
            Spacer(modifier = Modifier.width(FetchTheme.spacing.md))
            Text(
                text = stringResource(R.string.nav_settings),
                style = MaterialTheme.typography.titleLarge,
                color = titleColor
            )
        }

        Spacer(modifier = Modifier.height(26.dp))

        BrandWordmark(
            contentDescription = stringResource(R.string.cd_fetch_logo),
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .height(72.dp),
            contentScale = ContentScale.Fit
        )

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = titleColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(FetchTheme.spacing.sm))

        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = subtitleColor,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = stringResource(R.string.settings_rounded_ui),
            style = MaterialTheme.typography.labelMedium,
            letterSpacing = 1.2.sp,
            color = subtitleColor
        )
    }
}

@Composable
internal fun SettingsWorkspaceSurface(
    modifier: Modifier = Modifier,
    classicContainer: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    if (!classicContainer) {
        Box(
            modifier = modifier.padding(horizontal = FetchTheme.spacing.md, vertical = FetchTheme.spacing.sm),
            content = content
        )
    } else {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(SettingsContainerRadius))
                .background(FetchTheme.colors.BackgroundElevated)
                .border(
                    width = FetchTheme.spacing.hairline,
                    color = FetchTheme.colors.Border,
                    shape = RoundedCornerShape(SettingsContainerRadius)
                )
                .padding(20.dp),
            content = content
        )
    }
}

@Composable
internal fun SettingsRailButton(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
    icon: ImageVector? = null,
    rawIconRes: Int? = null,
    suppressSelectedVisual: Boolean = false
) {
    var isFocused by remember { mutableStateOf(false) }
    val railShape = RoundedCornerShape(SettingsPillRadius)
    val appliedModifier = if (focusRequester != null) {
        modifier.focusRequester(focusRequester)
    } else {
        modifier
    }
    val showSelected = isSelected && !suppressSelectedVisual

    Card(
        onClick = onClick,
        modifier = appliedModifier
            .padding(top = FetchTheme.spacing.xxs, bottom = FetchTheme.spacing.xxs)
            .fillMaxWidth()
            .heightIn(min = SettingsRailItemHeight)
            .onFocusChanged { state ->
                val nowFocused = state.isFocused
                if (isFocused != nowFocused) {
                    isFocused = nowFocused
                    if (nowFocused) onFocused()
                }
            },
        colors = CardDefaults.colors(
            containerColor = if (showSelected) FetchTheme.colors.BackgroundCard else FetchTheme.colors.Background,
            focusedContainerColor = FetchTheme.colors.BackgroundCard
        ),
        border = CardDefaults.border(
            border = if (showSelected) Border(
                border = FetchTheme.focusRing.border(FetchTheme.spacing.hairline),
                shape = railShape
            ) else Border.None,
            focusedBorder = Border(
                border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs),
                shape = railShape
            )
        ),
        shape = CardDefaults.shape(railShape),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = SettingsRailItemHeight)
                .clearAndSetSemantics {
                    contentDescription = title
                    role = Role.Tab
                    this.selected = isSelected
                },
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FetchTheme.spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (rawIconRes != null) {
                        Image(
                            painter = rememberRawSvgPainter(rawIconRes),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            contentScale = ContentScale.Fit,
                            colorFilter = ColorFilter.tint(
                                if (isSelected || isFocused) FetchTheme.colors.TextPrimary else FetchTheme.colors.TextSecondary
                            )
                        )
                    } else if (icon != null) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = if (isSelected || isFocused) FetchTheme.colors.TextPrimary else FetchTheme.colors.TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    if (rawIconRes != null || icon != null) {
                        Spacer(modifier = Modifier.width(10.dp))
                    }

                    FocusMarqueeText(
                        text = title,
                        focused = isFocused,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = if (isSelected || isFocused) FontWeight.SemiBold else FontWeight.Medium
                        ),
                        color = if (isSelected || isFocused) FetchTheme.colors.TextPrimary else FetchTheme.colors.TextSecondary,
                    )
                }

                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = FetchTheme.colors.TextTertiary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
internal fun SettingsDetailHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    SettingsPageTitleTarget(title)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = FetchTheme.colors.TextPrimary
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = FetchTheme.colors.TextSecondary
        )
    }
}

@Composable
internal fun SettingsGroupCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            // A title names the card's rows as a whole, so landing on it lands on its first row.
            .settingsRowTarget(title)
            .fillMaxWidth()
            .clip(RoundedCornerShape(SettingsSecondaryCardRadius))
            .background(FetchTheme.colors.BackgroundCard)
            .border(
                width = FetchTheme.spacing.hairline,
                color = FetchTheme.colors.Border,
                shape = RoundedCornerShape(SettingsSecondaryCardRadius)
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (!title.isNullOrBlank()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = FetchTheme.colors.TextPrimary
            )
        }
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = FetchTheme.colors.TextSecondary
            )
        }
        content()
    }
}

@Composable
internal fun SettingsToggleRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    enabled: Boolean = true,
    titleTrailingIcon: ImageVector? = null,
    titleTrailingIconTint: Color = FetchTheme.colors.TextPrimary,
    expandSubtitleOnFocus: Boolean = false
) {
    val contentAlpha = if (enabled) 1f else 0.4f
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = {
            if (enabled) onToggle()
        },
        modifier = modifier
            .settingsRowTarget(title)
            .fillMaxWidth()
            .heightIn(min = 62.dp)
            .focusProperties { canFocus = enabled }
            .semantics {
                if (!enabled) disabled()
                role = Role.Switch
                toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
            }
            .onFocusChanged { state ->
                val nowFocused = state.isFocused
                if (isFocused != nowFocused) {
                    isFocused = nowFocused
                    if (nowFocused) onFocused()
                }
            },
        colors = settingsRowColors(),
        border = settingsRowBorder(contentAlpha),
        shape = CardDefaults.shape(settingsRowShape()),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FetchTheme.spacing.lg, vertical = FetchTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = FetchTheme.colors.TextPrimary.copy(alpha = contentAlpha),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (titleTrailingIcon != null) {
                        Spacer(modifier = Modifier.width(FetchTheme.spacing.sm))
                        Icon(
                            imageVector = titleTrailingIcon,
                            contentDescription = null,
                            tint = titleTrailingIconTint.copy(alpha = contentAlpha),
                            modifier = Modifier.size(FetchTheme.spacing.lg)
                        )
                    }
                }
                if (!subtitle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(FetchTheme.spacing.xxs))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = FetchTheme.colors.TextSecondary.copy(alpha = contentAlpha),
                        maxLines = if (expandSubtitleOnFocus && isFocused) 8 else 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.width(FetchTheme.spacing.md))
            SettingsTogglePill(
                checked = checked,
                enabled = enabled
            )
        }
    }
}

@Composable
internal fun SettingsActionRow(
    title: String,
    subtitle: String?,
    value: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    enabled: Boolean = true,
    trailingIcon: ImageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
    titleTrailingIcon: ImageVector? = null,
    titleTrailingIconTint: Color = FetchTheme.colors.TextPrimary,
    leadingIcon: ImageVector? = null,
    @RawRes leadingRawIconRes: Int? = null,
    leadingArtworkSize: Dp = FetchTheme.spacing.xl,
    valueColor: Color = FetchTheme.colors.TextSecondary
) {
    val contentAlpha = if (enabled) 1f else 0.4f
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = { if (enabled) onClick() },
        modifier = modifier
            .settingsRowTarget(title)
            .padding(top = FetchTheme.spacing.xxs, bottom = FetchTheme.spacing.xxs)
            .fillMaxWidth()
            .focusProperties { canFocus = enabled }
            .semantics {
                if (!enabled) disabled()
                role = Role.Button
            }
            .onFocusChanged { state ->
                val nowFocused = state.isFocused
                if (isFocused != nowFocused) {
                    isFocused = nowFocused
                    if (nowFocused) onFocused()
                }
            },
        colors = settingsRowColors(),
        border = settingsRowBorder(contentAlpha),
        shape = CardDefaults.shape(settingsRowShape()),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 62.dp)
                .padding(horizontal = FetchTheme.spacing.lg, vertical = FetchTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingRawIconRes != null) {
                Image(
                    painter = rememberRawSvgPainter(leadingRawIconRes, leadingArtworkSize),
                    contentDescription = null,
                    modifier = Modifier
                        .size(leadingArtworkSize)
                        .alpha(contentAlpha),
                    contentScale = ContentScale.Fit
                )
                Spacer(modifier = Modifier.width(FetchTheme.spacing.lg))
            } else if (leadingIcon != null) {
                SettingsRowLeadingIcon(icon = leadingIcon, enabled = enabled, focused = isFocused)
                Spacer(modifier = Modifier.width(FetchTheme.spacing.lg))
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = FetchTheme.colors.TextPrimary.copy(alpha = contentAlpha),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (titleTrailingIcon != null) {
                        Spacer(modifier = Modifier.width(FetchTheme.spacing.sm))
                        Icon(
                            imageVector = titleTrailingIcon,
                            contentDescription = null,
                            tint = titleTrailingIconTint.copy(alpha = contentAlpha),
                            modifier = Modifier.size(FetchTheme.spacing.lg)
                        )
                    }
                }
                if (!subtitle.isNullOrBlank()) {
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

            if (!value.isNullOrBlank()) {
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelLarge,
                    color = valueColor.copy(alpha = contentAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 260.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))
            Icon(
                imageVector = trailingIcon,
                contentDescription = null,
                tint = FetchTheme.colors.TextTertiary.copy(alpha = contentAlpha),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
internal fun <T> SettingsSingleChoiceDialog(
    title: String,
    options: List<SettingsPickerOption<T>>,
    selectedValue: T,
    onOptionSelected: (T) -> Unit,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    width: Dp = 420.dp,
    maxHeight: Dp = 320.dp
) {
    val focusRequester = remember { FocusRequester() }
    val focusedIndex = options.indexOfFirst { it.value == selectedValue }
        .let { if (it >= 0) it else 0 }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = focusedIndex)

    LaunchedEffect(focusedIndex) {
        focusRequester.requestFocusAfterFrames()
    }

    FetchDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = subtitle,
        width = width,
        suppressFirstKeyUp = false
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
        ) {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm),
                contentPadding = PaddingValues(vertical = FetchTheme.spacing.xs)
            ) {
                itemsIndexed(
                    items = options,
                    key = { index, option -> "$index-${option.value}" }
                ) { index, option ->
                    val isSelected = option.value == selectedValue
                    Card(
                        onClick = { onOptionSelected(option.value) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (index == focusedIndex) Modifier.focusRequester(focusRequester) else Modifier),
                        colors = CardDefaults.colors(
                            containerColor = if (isSelected) FetchTheme.colors.FocusBackground else FetchTheme.colors.BackgroundCard,
                            focusedContainerColor = FetchTheme.colors.FocusBackground
                        ),
                        shape = CardDefaults.shape(RoundedCornerShape(FetchRadii.tokens.sm)),
                        scale = CardDefaults.scale(focusedScale = 1f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(FetchTheme.spacing.lg),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = option.title,
                                    color = if (isSelected) FetchTheme.colors.Primary else FetchTheme.colors.TextPrimary,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontFamily = option.titleFontFamily
                                )
                                if (!option.description.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(FetchTheme.spacing.xs))
                                    Text(
                                        text = option.description,
                                        color = FetchTheme.colors.TextSecondary,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                            if (!option.trailing.isNullOrBlank()) {
                                Spacer(modifier = Modifier.width(FetchTheme.spacing.md))
                                Text(
                                    text = option.trailing,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = FetchTheme.colors.TextSecondary
                                )
                            }
                            if (isSelected) {
                                Spacer(modifier = Modifier.width(FetchTheme.spacing.md))
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = stringResource(R.string.cd_selected),
                                    tint = FetchTheme.colors.Primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun <T> SettingsMultiChoiceDialog(
    title: String,
    options: List<SettingsPickerOption<T>>,
    selectedValues: List<T>,
    onValuesSelected: (List<T>) -> Unit,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    width: Dp = 520.dp,
    maxHeight: Dp = 420.dp,
    maxSelections: Int? = null
) {
    val focusRequester = remember { FocusRequester() }
    val selected = remember(selectedValues) { mutableStateListOf<T>().also { it.addAll(selectedValues) } }
    val firstSelectedIndex = options.indexOfFirst { option -> selectedValues.contains(option.value) }
        .let { if (it >= 0) it else 0 }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = firstSelectedIndex)

    LaunchedEffect(firstSelectedIndex) {
        focusRequester.requestFocusAfterFrames()
    }

    FetchDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = subtitle,
        width = width,
        suppressFirstKeyUp = false
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
            ) {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm),
                    contentPadding = PaddingValues(vertical = FetchTheme.spacing.xs)
                ) {
                    itemsIndexed(
                        items = options,
                        key = { index, option -> "$index-${option.value}" }
                    ) { index, option ->
                        val isSelected = selected.contains(option.value)
                        Card(
                            onClick = {
                                if (isSelected) {
                                    selected.remove(option.value)
                                } else if (maxSelections == null || selected.size < maxSelections) {
                                    selected.add(option.value)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (index == firstSelectedIndex) Modifier.focusRequester(focusRequester) else Modifier),
                            colors = CardDefaults.colors(
                                containerColor = if (isSelected) FetchTheme.colors.FocusBackground else FetchTheme.colors.BackgroundCard,
                                focusedContainerColor = FetchTheme.colors.FocusBackground
                            ),
                            shape = CardDefaults.shape(RoundedCornerShape(FetchRadii.tokens.sm)),
                            scale = CardDefaults.scale(focusedScale = 1f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(FetchTheme.spacing.lg),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = option.title,
                                        color = if (isSelected) FetchTheme.colors.Primary else FetchTheme.colors.TextPrimary,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontFamily = option.titleFontFamily
                                    )
                                    if (!option.description.isNullOrBlank()) {
                                        Spacer(modifier = Modifier.height(FetchTheme.spacing.xs))
                                        Text(
                                            text = option.description,
                                            color = FetchTheme.colors.TextSecondary,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                                if (isSelected) {
                                    Spacer(modifier = Modifier.width(FetchTheme.spacing.md))
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = stringResource(R.string.cd_selected),
                                        tint = FetchTheme.colors.Primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            SettingsDialogActionRow {
                SettingsDialogActionButton(
                    text = stringResource(R.string.action_clear),
                    onClick = { selected.clear() }
                )
                SettingsDialogActionButton(
                    text = stringResource(R.string.action_save),
                    onClick = { onValuesSelected(options.map { it.value }.filter { selected.contains(it) }) },
                    primary = true
                )
            }
        }
    }
}

@Composable
internal fun SettingsDialogActionRow(
    horizontalAlignment: Alignment.Horizontal = Alignment.End,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm, horizontalAlignment),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
internal fun SettingsDialogActionButton(
    text: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.colors(
            containerColor = if (primary) FetchTheme.colors.FocusBackground else FetchTheme.colors.BackgroundCard,
            contentColor = FetchTheme.colors.TextPrimary
        )
    ) {
        Text(
            text = text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun SettingsChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {}
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = modifier.onFocusChanged { state ->
            val nowFocused = state.isFocused
            if (isFocused != nowFocused) {
                isFocused = nowFocused
                if (nowFocused) onFocused()
            }
        },
        colors = CardDefaults.colors(
            containerColor = if (selected) FetchTheme.colors.FocusRing.copy(alpha = 0.2f) else FetchTheme.colors.Background,
            focusedContainerColor = if (selected) FetchTheme.colors.FocusRing.copy(alpha = 0.2f) else FetchTheme.colors.Background
        ),
        border = CardDefaults.border(
            border = if (selected) Border(
                border = FetchTheme.focusRing.border(FetchTheme.spacing.hairline),
                shape = RoundedCornerShape(SettingsPillRadius)
            ) else Border.None,
            focusedBorder = Border(
                border = FetchTheme.focusRing.border(FetchTheme.spacing.hairline),
                shape = RoundedCornerShape(SettingsPillRadius)
            )
        ),
        shape = CardDefaults.shape(RoundedCornerShape(SettingsPillRadius)),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                textDirection = label.contentTextDirection()
            ),
            color = if (selected || isFocused) FetchTheme.colors.TextPrimary else FetchTheme.colors.TextSecondary,
            modifier = Modifier.padding(horizontal = FetchTheme.spacing.lg, vertical = 10.dp)
        )
    }
}

@Composable
private fun SettingsTogglePill(
    checked: Boolean,
    enabled: Boolean
) {
    val alpha = if (enabled) 1f else 0.35f
    val trackColor = if (checked) {
        FetchTheme.colors.Secondary.copy(alpha = 0.35f * alpha)
    } else {
        FetchTheme.colors.Border.copy(alpha = alpha)
    }
    val knobColor = Color.White.copy(alpha = alpha)
    Box(
        modifier = Modifier
            .width(46.dp)
            .height(FetchTheme.spacing.xl)
            .clip(RoundedCornerShape(SettingsPillRadius))
            .background(trackColor)
            .padding(FetchTheme.spacing.xxs),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(knobColor)
        )
    }
}
