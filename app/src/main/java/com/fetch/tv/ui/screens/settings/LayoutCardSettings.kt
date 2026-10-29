@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.fetch.tv.ui.screens.settings

import com.fetch.tv.domain.model.THEME_CARD_CORNER_RADIUS
import com.fetch.tv.ui.theme.FetchTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.core.build.AppFeaturePolicy
import com.fetch.tv.core.poster.CustomPosterScreen
import com.fetch.tv.domain.model.ContinueWatchingCardStyle
import com.fetch.tv.domain.model.ContinueWatchingSortMode
import com.fetch.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.fetch.tv.ui.components.CardCwStylePreview
import com.fetch.tv.ui.components.PosterCwStylePreview
import com.fetch.tv.ui.components.WideCwStylePreview

@Composable
internal fun LayoutContinueWatchingSection(
    uiState: LayoutSettingsUiState,
    onEvent: (LayoutSettingsEvent) -> Unit
) {
    SettingsToggleRow(
        title = stringResource(R.string.layout_cw_enabled),
        subtitle = stringResource(R.string.layout_cw_enabled_sub),
        checked = uiState.continueWatchingEnabled,
        onToggle = { onEvent(LayoutSettingsEvent.SetContinueWatchingEnabled(!uiState.continueWatchingEnabled)) }
    )

    if (uiState.continueWatchingEnabled) {
        val firstStyleFocusRequester = remember { FocusRequester() }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .settingsOptionRow(firstStyleFocusRequester),
            horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)
        ) {
            listOf(
                ContinueWatchingCardStyle.CARD,
                ContinueWatchingCardStyle.WIDE,
                ContinueWatchingCardStyle.POSTER
            ).forEachIndexed { index, style ->
                CwStyleCard(
                    style = style,
                    isSelected = uiState.continueWatchingCardStyle == style,
                    onClick = { onEvent(LayoutSettingsEvent.SetContinueWatchingCardStyle(style)) },
                    modifier = Modifier
                        .weight(1f)
                        .then(if (index == 0) Modifier.focusRequester(firstStyleFocusRequester) else Modifier)
                )
            }
        }

        val episodeThumbnailsApply = uiState.continueWatchingCardStyle != ContinueWatchingCardStyle.POSTER
        if (episodeThumbnailsApply) {
            SettingsToggleRow(
                title = stringResource(R.string.layout_use_episode_thumbnails_cw),
                subtitle = stringResource(R.string.layout_use_episode_thumbnails_cw_sub),
                checked = uiState.useEpisodeThumbnailsInCw,
                onToggle = { onEvent(LayoutSettingsEvent.SetUseEpisodeThumbnailsInCw(!uiState.useEpisodeThumbnailsInCw)) }
            )
        }
        if (episodeThumbnailsApply && uiState.useEpisodeThumbnailsInCw) {
            SettingsToggleRow(
                title = stringResource(R.string.layout_blur_cw_next_up),
                subtitle = stringResource(R.string.layout_blur_cw_next_up_sub),
                checked = uiState.blurContinueWatchingNextUp,
                onToggle = { onEvent(LayoutSettingsEvent.SetBlurContinueWatchingNextUp(!uiState.blurContinueWatchingNextUp)) }
            )
        }
        SettingsToggleRow(
            title = stringResource(R.string.layout_next_up_furthest_episode),
            subtitle = stringResource(R.string.layout_next_up_furthest_episode_sub),
            checked = uiState.nextUpFromFurthestEpisode,
            onToggle = { onEvent(LayoutSettingsEvent.SetNextUpFromFurthestEpisode(!uiState.nextUpFromFurthestEpisode)) }
        )
        SettingsToggleRow(
            title = stringResource(R.string.layout_show_unaired_next_up),
            subtitle = stringResource(R.string.layout_show_unaired_next_up_sub),
            checked = uiState.showUnairedNextUp,
            onToggle = { onEvent(LayoutSettingsEvent.SetShowUnairedNextUp(!uiState.showUnairedNextUp)) }
        )
        ContinueWatchingSortModeRow(uiState, onEvent)
    }
}

@Composable
internal fun ContinueWatchingSortModeRow(
    uiState: LayoutSettingsUiState,
    onEvent: (LayoutSettingsEvent) -> Unit
) {
    var showSortModeDialog by rememberSaveable { mutableStateOf(false) }

    SettingsActionRow(
        title = stringResource(R.string.layout_cw_sort_mode),
        subtitle = stringResource(R.string.layout_cw_sort_mode_sub),
        value = when (uiState.continueWatchingSortMode) {
            ContinueWatchingSortMode.DEFAULT -> stringResource(R.string.layout_cw_sort_default)
            ContinueWatchingSortMode.STREAMING_STYLE -> stringResource(R.string.layout_cw_sort_streaming)
            ContinueWatchingSortMode.SPLIT_UPCOMING -> stringResource(R.string.layout_cw_sort_split_upcoming)
        },
        onClick = { showSortModeDialog = true }
    )

    if (showSortModeDialog) {
        ContinueWatchingSortModeDialog(
            currentMode = uiState.continueWatchingSortMode,
            onModeSelected = { mode ->
                onEvent(LayoutSettingsEvent.SetContinueWatchingSortMode(mode))
                showSortModeDialog = false
            },
            onDismiss = { showSortModeDialog = false }
        )
    }
}

internal fun LayoutSettingsUiState.focusedPosterHasOptions(): Boolean =
    !modernLandscapePostersEnabled || AppFeaturePolicy.inAppTrailerPlaybackEnabled

@Composable
internal fun LayoutFocusedPosterSection(
    uiState: LayoutSettingsUiState,
    onEvent: (LayoutSettingsEvent) -> Unit
) {
    val isModernLandscape = uiState.modernLandscapePostersEnabled
    val showAutoplayRow = AppFeaturePolicy.inAppTrailerPlaybackEnabled &&
        (uiState.focusedPosterBackdropExpandEnabled || isModernLandscape)

    if (!isModernLandscape) {
        SettingsToggleRow(
            title = stringResource(R.string.layout_expand_poster),
            subtitle = stringResource(R.string.layout_expand_poster_sub),
            checked = uiState.focusedPosterBackdropExpandEnabled,
            onToggle = {
                onEvent(LayoutSettingsEvent.SetFocusedPosterBackdropExpandEnabled(!uiState.focusedPosterBackdropExpandEnabled))
            }
        )
        if (uiState.focusedPosterBackdropExpandEnabled) {
            SliderSettingsItem(
                title = stringResource(R.string.layout_expand_delay),
                subtitle = stringResource(R.string.layout_expand_delay_sub),
                value = uiState.focusedPosterBackdropExpandDelaySeconds,
                valueText = "${uiState.focusedPosterBackdropExpandDelaySeconds}s",
                minValue = 0,
                maxValue = 10,
                step = 1,
                onValueChange = { seconds ->
                    onEvent(LayoutSettingsEvent.SetFocusedPosterBackdropExpandDelaySeconds(seconds))
                }
            )
        }
    }

    if (showAutoplayRow) {
        SettingsToggleRow(
            title = stringResource(R.string.layout_autoplay_trailer),
            subtitle = stringResource(R.string.layout_autoplay_trailer_sub),
            checked = uiState.focusedPosterBackdropTrailerEnabled,
            onToggle = {
                onEvent(LayoutSettingsEvent.SetFocusedPosterBackdropTrailerEnabled(!uiState.focusedPosterBackdropTrailerEnabled))
            }
        )
        if (uiState.focusedPosterBackdropTrailerEnabled) {
            SettingsToggleRow(
                title = stringResource(R.string.layout_trailer_muted),
                subtitle = stringResource(R.string.layout_trailer_muted_sub_preview),
                checked = uiState.focusedPosterBackdropTrailerMuted,
                onToggle = {
                    onEvent(LayoutSettingsEvent.SetFocusedPosterBackdropTrailerMuted(!uiState.focusedPosterBackdropTrailerMuted))
                }
            )
            ModernTrailerPlaybackTargetRow(
                selectedTarget = uiState.focusedPosterBackdropTrailerPlaybackTarget,
                onTargetSelected = { target ->
                    onEvent(LayoutSettingsEvent.SetFocusedPosterBackdropTrailerPlaybackTarget(target))
                }
            )
        }
    }

    // Also covers the details page's background trailer, so it shows without home autoplay.
    if (AppFeaturePolicy.inAppTrailerPlaybackEnabled) {
        SettingsToggleRow(
            title = stringResource(R.string.layout_trailer_crop_to_fill),
            subtitle = stringResource(R.string.layout_trailer_crop_to_fill_sub),
            checked = uiState.trailerCropToFill,
            onToggle = {
                onEvent(LayoutSettingsEvent.SetTrailerCropToFill(!uiState.trailerCropToFill))
            }
        )
    }
}

@Composable
internal fun LayoutPosterCardSection(
    uiState: LayoutSettingsUiState,
    onEvent: (LayoutSettingsEvent) -> Unit
) {
    val widthOptions = listOf(
        PresetOption(stringResource(R.string.layout_preset_compact), 104),
        PresetOption(stringResource(R.string.layout_preset_dense), 112),
        PresetOption(stringResource(R.string.layout_preset_standard), 120),
        PresetOption(stringResource(R.string.layout_preset_balanced), 126),
        PresetOption(stringResource(R.string.layout_preset_comfort), 134),
        PresetOption(stringResource(R.string.layout_preset_large), 140)
    )
    val radiusOptions = listOf(
        PresetOption(stringResource(R.string.layout_preset_theme), THEME_CARD_CORNER_RADIUS),
        PresetOption(stringResource(R.string.layout_preset_sharp), 0),
        PresetOption(stringResource(R.string.layout_preset_subtle), 4),
        PresetOption(stringResource(R.string.layout_preset_classic), 8),
        PresetOption(stringResource(R.string.layout_preset_rounded), 12),
        PresetOption(stringResource(R.string.layout_preset_pill), 16)
    )

    OptionRow(
        title = stringResource(R.string.layout_card_width),
        selectedValue = uiState.posterCardWidthDp,
        options = widthOptions,
        onSelected = { width -> onEvent(LayoutSettingsEvent.SetPosterCardWidth(width)) }
    )
    OptionRow(
        title = stringResource(R.string.layout_card_radius),
        selectedValue = uiState.posterCardCornerRadiusDp,
        options = radiusOptions,
        onSelected = { radius -> onEvent(LayoutSettingsEvent.SetPosterCardCornerRadius(radius)) }
    )
    SettingsToggleRow(
        title = stringResource(R.string.layout_landscape_posters),
        subtitle = stringResource(R.string.layout_landscape_posters_sub),
        checked = uiState.modernLandscapePostersEnabled,
        onToggle = {
            onEvent(LayoutSettingsEvent.SetModernLandscapePostersEnabled(!uiState.modernLandscapePostersEnabled))
        }
    )
    SettingsToggleRow(
        title = stringResource(R.string.layout_always_show_landscape_clearlogo),
        subtitle = stringResource(R.string.layout_always_show_landscape_clearlogo_sub),
        checked = uiState.alwaysShowLandscapeClearlogo,
        onToggle = { onEvent(LayoutSettingsEvent.SetAlwaysShowLandscapeClearlogo(!uiState.alwaysShowLandscapeClearlogo)) }
    )
    SettingsResetButton(
        text = stringResource(R.string.layout_reset_default),
        onClick = { onEvent(LayoutSettingsEvent.ResetPosterCardStyle) }
    )
}

@Composable
internal fun LayoutCustomPosterSection(
    uiState: LayoutSettingsUiState,
    onEvent: (LayoutSettingsEvent) -> Unit,
    onConfigureViaPhone: () -> Unit
) {
    val pattern = uiState.customPosterUrlPattern
    val isActive = pattern.isNotBlank()

    SettingsNote(text = stringResource(R.string.layout_custom_poster_description))
    if (isActive) {
        Text(
            text = pattern,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = FetchTheme.colors.TextSecondary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .background(FetchTheme.colors.BackgroundElevated, RoundedCornerShape(FetchTheme.radii.sm))
                .padding(horizontal = FetchTheme.spacing.md, vertical = FetchTheme.spacing.md)
        )
    }
    SettingsActionRow(
        title = stringResource(R.string.layout_custom_poster_qr),
        subtitle = if (isActive) stringResource(R.string.layout_custom_poster_active) else null,
        onClick = onConfigureViaPhone
    )
    if (isActive) {
        SettingsActionRow(
            title = stringResource(R.string.layout_custom_poster_clear),
            subtitle = null,
            onClick = { onEvent(LayoutSettingsEvent.ClearCustomPosterSettings) }
        )
        SettingsSectionLabel(text = stringResource(R.string.layout_custom_poster_apply_to))
        listOf(
            CustomPosterScreen.HOME to R.string.layout_custom_poster_screen_home,
            CustomPosterScreen.CONTINUE_WATCHING to R.string.layout_custom_poster_screen_continue_watching,
            CustomPosterScreen.COLLECTIONS to R.string.layout_custom_poster_screen_collections,
            CustomPosterScreen.LIBRARY to R.string.layout_custom_poster_screen_library,
            CustomPosterScreen.SEARCH to R.string.layout_custom_poster_screen_search,
            CustomPosterScreen.DETAILS to R.string.layout_custom_poster_screen_details,
            CustomPosterScreen.TV_RECOMMENDATIONS to R.string.layout_custom_poster_screen_tv_recommendations
        ).forEach { (screen, label) ->
            val enabled = screen in uiState.customPosterEnabledScreens
            SettingsToggleRow(
                title = stringResource(label),
                subtitle = null,
                checked = enabled,
                onToggle = { onEvent(LayoutSettingsEvent.SetCustomPosterScreenEnabled(screen, !enabled)) }
            )
        }
    }
}

@Composable
private fun CwStyleCard(
    style: ContinueWatchingCardStyle,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = modifier.onFocusChanged { state ->
            val nowFocused = state.isFocused
            if (isFocused != nowFocused) {
                isFocused = nowFocused
            }
        },
        colors = CardDefaults.colors(
            containerColor = FetchTheme.colors.Background,
            focusedContainerColor = FetchTheme.colors.Background
        ),
        border = CardDefaults.border(
            border = if (isSelected) Border(
                border = FetchTheme.focusRing.border(FetchTheme.spacing.hairline),
                shape = RoundedCornerShape(SettingsSecondaryCardRadius)
            ) else Border.None,
            focusedBorder = Border(
                border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs),
                shape = RoundedCornerShape(SettingsSecondaryCardRadius)
            )
        ),
        shape = CardDefaults.shape(RoundedCornerShape(SettingsSecondaryCardRadius)),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(112.dp)
            ) {
                when (style) {
                    ContinueWatchingCardStyle.CARD -> CardCwStylePreview(modifier = Modifier.fillMaxSize())
                    ContinueWatchingCardStyle.WIDE -> WideCwStylePreview(modifier = Modifier.fillMaxSize())
                    ContinueWatchingCardStyle.POSTER -> PosterCwStylePreview(modifier = Modifier.fillMaxSize())
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource(R.string.cd_selected),
                        tint = FetchTheme.colors.FocusRing,
                        modifier = Modifier
                            .size(FetchTheme.spacing.lg)
                            .padding(end = 6.dp)
                    )
                }
                Text(
                    text = when (style) {
                        ContinueWatchingCardStyle.CARD -> stringResource(R.string.layout_cw_card_style_card)
                        ContinueWatchingCardStyle.WIDE -> stringResource(R.string.layout_cw_card_style_wide)
                        ContinueWatchingCardStyle.POSTER -> stringResource(R.string.layout_cw_card_style_poster)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected || isFocused) FetchTheme.colors.TextPrimary else FetchTheme.colors.TextSecondary
                )
            }
        }
    }
}

@Composable
internal fun ContinueWatchingSortModeDialog(
    currentMode: ContinueWatchingSortMode,
    onModeSelected: (ContinueWatchingSortMode) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        SettingsPickerOption(
            ContinueWatchingSortMode.DEFAULT,
            stringResource(R.string.layout_cw_sort_default),
            stringResource(R.string.layout_cw_sort_default_desc)
        ),
        SettingsPickerOption(
            ContinueWatchingSortMode.STREAMING_STYLE,
            stringResource(R.string.layout_cw_sort_streaming),
            stringResource(R.string.layout_cw_sort_streaming_desc)
        ),
        SettingsPickerOption(
            ContinueWatchingSortMode.SPLIT_UPCOMING,
            stringResource(R.string.layout_cw_sort_split_upcoming),
            stringResource(R.string.layout_cw_sort_split_upcoming_desc)
        )
    )

    SettingsSingleChoiceDialog(
        title = stringResource(R.string.layout_cw_sort_mode),
        options = options,
        selectedValue = currentMode,
        onOptionSelected = onModeSelected,
        onDismiss = onDismiss,
        width = 420.dp,
        maxHeight = 320.dp
    )
}

@Composable
private fun ModernTrailerPlaybackTargetRow(
    selectedTarget: FocusedPosterTrailerPlaybackTarget,
    onTargetSelected: (FocusedPosterTrailerPlaybackTarget) -> Unit
) {
    Text(
        text = stringResource(R.string.layout_trailer_location),
        style = MaterialTheme.typography.labelLarge,
        color = FetchTheme.colors.TextSecondary
    )
    Text(
        text = stringResource(R.string.layout_trailer_location_sub),
        style = MaterialTheme.typography.bodySmall,
        color = FetchTheme.colors.TextTertiary
    )
    val firstTrailerTargetFocusRequester = remember { FocusRequester() }
    LazyRow(
        modifier = Modifier.settingsOptionRow(firstTrailerTargetFocusRequester),
        contentPadding = PaddingValues(end = FetchTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)
    ) {
        item(key = "trailer_target_expanded_card") {
            SettingsChoiceChip(
                modifier = Modifier.focusRequester(firstTrailerTargetFocusRequester),
                label = stringResource(R.string.layout_trailer_expanded_card),
                selected = selectedTarget == FocusedPosterTrailerPlaybackTarget.EXPANDED_CARD,
                onClick = {
                    onTargetSelected(FocusedPosterTrailerPlaybackTarget.EXPANDED_CARD)
                }
            )
        }
        item(key = "trailer_target_hero_media") {
            SettingsChoiceChip(
                label = stringResource(R.string.layout_trailer_hero_media),
                selected = selectedTarget == FocusedPosterTrailerPlaybackTarget.HERO_MEDIA,
                onClick = {
                    onTargetSelected(FocusedPosterTrailerPlaybackTarget.HERO_MEDIA)
                }
            )
        }
    }
}

@Composable
private fun OptionRow(
    title: String,
    selectedValue: Int,
    options: List<PresetOption>,
    onSelected: (Int) -> Unit
) {
    val selectedLabel = options.firstOrNull { it.value == selectedValue }?.label ?: stringResource(R.string.layout_custom)

    Text(
        text = "$title ($selectedLabel)",
        style = MaterialTheme.typography.labelLarge,
        color = FetchTheme.colors.TextSecondary
    )

    val firstOptionFocusRequester = remember { FocusRequester() }
    LazyRow(
        modifier = Modifier
            .settingsRowTarget(title)
            .settingsOptionRow(firstOptionFocusRequester),
        contentPadding = PaddingValues(end = FetchTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)
    ) {
        itemsIndexed(
            items = options,
            key = { _, option -> option.value }
        ) { optionIndex, option ->
            ValueChip(
                label = option.label,
                isSelected = option.value == selectedValue,
                onClick = { onSelected(option.value) },
                modifier = if (optionIndex == 0) {
                    Modifier.focusRequester(firstOptionFocusRequester)
                } else {
                    Modifier
                }
            )
        }
    }
}

@Composable
private fun ValueChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsChoiceChip(
        modifier = modifier,
        label = label,
        selected = isSelected,
        onClick = onClick
    )
}

private data class PresetOption(
    val label: String,
    val value: Int
)
