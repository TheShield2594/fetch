@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.fetch.tv.ui.screens.settings


import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.fetch.tv.R
import com.fetch.tv.core.build.AppFeaturePolicy
import com.fetch.tv.domain.model.HomeImdbRatingsVisibility

@Composable
internal fun LayoutHomeLayoutSection(
    uiState: LayoutSettingsUiState,
    onEvent: (LayoutSettingsEvent) -> Unit
) {
    SettingsToggleRow(
        title = stringResource(R.string.layout_fullscreen_hero_backdrop),
        subtitle = stringResource(R.string.layout_fullscreen_hero_backdrop_sub),
        checked = uiState.modernHeroFullScreenBackdropEnabled,
        onToggle = {
            onEvent(LayoutSettingsEvent.SetModernHeroFullScreenBackdropEnabled(!uiState.modernHeroFullScreenBackdropEnabled))
        }
    )
}

/**
 * Essential mode's whole Layout page: the few Home settings most people change, taken from the
 * sections Advanced mode spreads them across.
 */
@Composable
internal fun LayoutEssentialSection(
    uiState: LayoutSettingsUiState,
    onEvent: (LayoutSettingsEvent) -> Unit,
    firstRowFocusRequester: FocusRequester? = null
) {
    SettingsToggleRow(
        title = stringResource(R.string.layout_cw_enabled),
        subtitle = stringResource(R.string.layout_cw_enabled_sub),
        checked = uiState.continueWatchingEnabled,
        onToggle = { onEvent(LayoutSettingsEvent.SetContinueWatchingEnabled(!uiState.continueWatchingEnabled)) },
        modifier = if (firstRowFocusRequester != null) Modifier.focusRequester(firstRowFocusRequester) else Modifier
    )
    if (uiState.continueWatchingEnabled) ContinueWatchingSortModeRow(uiState, onEvent)
    SettingsToggleRow(
        title = stringResource(R.string.layout_landscape_posters),
        subtitle = stringResource(R.string.layout_landscape_posters_sub),
        checked = uiState.modernLandscapePostersEnabled,
        onToggle = {
            onEvent(LayoutSettingsEvent.SetModernLandscapePostersEnabled(!uiState.modernLandscapePostersEnabled))
        }
    )
    if (AppFeaturePolicy.inAppTrailerPlaybackEnabled) {
        SettingsToggleRow(
            title = stringResource(R.string.audio_autoplay_trailers),
            subtitle = stringResource(R.string.audio_autoplay_trailers_sub),
            checked = uiState.detailPageTrailerAutoplayEnabled,
            onToggle = {
                onEvent(LayoutSettingsEvent.SetDetailPageTrailerAutoplayEnabled(!uiState.detailPageTrailerAutoplayEnabled))
            }
        )
    }
    SettingsToggleRow(
        title = stringResource(R.string.layout_hide_unreleased),
        subtitle = stringResource(R.string.layout_hide_unreleased_sub),
        checked = uiState.hideUnreleasedContent,
        onToggle = { onEvent(LayoutSettingsEvent.SetHideUnreleasedContent(!uiState.hideUnreleasedContent)) }
    )
}

@Composable
internal fun LayoutHomeContentSection(
    uiState: LayoutSettingsUiState,
    onEvent: (LayoutSettingsEvent) -> Unit
) {
    // Home cards carry their own titles; these two apply to the poster grids in Search and See all.
    SettingsToggleRow(
        title = stringResource(R.string.layout_poster_labels),
        subtitle = stringResource(R.string.layout_poster_labels_sub),
        checked = uiState.posterLabelsEnabled,
        onToggle = { onEvent(LayoutSettingsEvent.SetPosterLabelsEnabled(!uiState.posterLabelsEnabled)) }
    )
    SettingsToggleRow(
        title = stringResource(R.string.layout_addon_name),
        subtitle = stringResource(R.string.layout_addon_name_sub),
        checked = uiState.catalogAddonNameEnabled,
        onToggle = { onEvent(LayoutSettingsEvent.SetCatalogAddonNameEnabled(!uiState.catalogAddonNameEnabled)) }
    )
    SettingsToggleRow(
        title = stringResource(R.string.layout_catalog_type),
        subtitle = stringResource(R.string.layout_catalog_type_sub),
        checked = uiState.catalogTypeSuffixEnabled,
        onToggle = { onEvent(LayoutSettingsEvent.SetCatalogTypeSuffixEnabled(!uiState.catalogTypeSuffixEnabled)) }
    )
    SettingsToggleRow(
        title = stringResource(R.string.layout_search_system_keyboard),
        subtitle = stringResource(R.string.layout_search_system_keyboard_sub),
        checked = uiState.searchSystemKeyboard,
        onToggle = { onEvent(LayoutSettingsEvent.SetSearchSystemKeyboard(!uiState.searchSystemKeyboard)) }
    )
    SettingsToggleRow(
        title = stringResource(R.string.layout_hide_unreleased),
        subtitle = stringResource(R.string.layout_hide_unreleased_sub),
        checked = uiState.hideUnreleasedContent,
        onToggle = { onEvent(LayoutSettingsEvent.SetHideUnreleasedContent(!uiState.hideUnreleasedContent)) }
    )
    SettingsToggleRow(
        title = stringResource(R.string.layout_overall_ratings),
        subtitle = stringResource(
            if (uiState.homeImdbRatingsVisibility.showRatings) {
                R.string.layout_overall_ratings_sub_on
            } else {
                R.string.layout_overall_ratings_sub_off
            }
        ),
        checked = uiState.homeImdbRatingsVisibility.showRatings,
        onToggle = {
            val visibility = if (uiState.homeImdbRatingsVisibility.showRatings) {
                HomeImdbRatingsVisibility.HIDE_ALL
            } else {
                HomeImdbRatingsVisibility.SHOW_ALL
            }
            onEvent(LayoutSettingsEvent.SetHomeImdbRatingsVisibility(visibility))
        }
    )
}

@Composable
internal fun LayoutSidebarSection(
    uiState: LayoutSettingsUiState,
    onEvent: (LayoutSettingsEvent) -> Unit
) {
    SettingsToggleRow(
        title = stringResource(R.string.layout_collapse_sidebar),
        subtitle = stringResource(R.string.layout_collapse_sidebar_sub),
        checked = uiState.sidebarCollapsedByDefault,
        onToggle = { onEvent(LayoutSettingsEvent.SetSidebarCollapsed(!uiState.sidebarCollapsedByDefault)) }
    )
}
