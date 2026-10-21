@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.fetch.tv.ui.screens.settings


import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.fetch.tv.R
import com.fetch.tv.domain.model.DiscoverLocation
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
    DiscoverLocationRow(
        selectedLocation = uiState.discoverLocation,
        rememberedLocation = uiState.lastNonOffDiscoverLocation,
        onLocationSelected = { location -> onEvent(LayoutSettingsEvent.SetDiscoverLocation(location)) }
    )
}

@Composable
private fun DiscoverLocationRow(
    selectedLocation: DiscoverLocation,
    rememberedLocation: DiscoverLocation,
    onLocationSelected: (DiscoverLocation) -> Unit
) {
    val sectionEnabled = selectedLocation != DiscoverLocation.OFF
    var dialogOpen by remember { mutableStateOf(false) }

    SettingsToggleRow(
        title = stringResource(R.string.layout_show_discover),
        subtitle = stringResource(R.string.layout_show_discover_sub),
        checked = sectionEnabled,
        onToggle = {
            onLocationSelected(
                when (selectedLocation) {
                    DiscoverLocation.OFF -> rememberedLocation
                    DiscoverLocation.IN_SEARCH,
                    DiscoverLocation.IN_SIDEBAR -> DiscoverLocation.OFF
                }
            )
        }
    )
    if (sectionEnabled) {
        val currentLabel = when (selectedLocation) {
            DiscoverLocation.IN_SIDEBAR -> stringResource(R.string.layout_discover_location_in_sidebar)
            DiscoverLocation.IN_SEARCH -> stringResource(R.string.layout_discover_location_in_search)
            DiscoverLocation.OFF -> ""
        }
        SettingsActionRow(
            title = stringResource(R.string.layout_discover_location_action),
            subtitle = null,
            value = currentLabel,
            onClick = { dialogOpen = true }
        )
    }

    if (dialogOpen) {
        DiscoverLocationDialog(
            selectedLocation = selectedLocation,
            onLocationSelected = { location ->
                onLocationSelected(location)
                dialogOpen = false
            },
            onDismiss = { dialogOpen = false }
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DiscoverLocationDialog(
    selectedLocation: DiscoverLocation,
    onLocationSelected: (DiscoverLocation) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        SettingsPickerOption(
            DiscoverLocation.IN_SEARCH,
            stringResource(R.string.layout_discover_location_in_search),
            stringResource(R.string.layout_discover_location_in_search_desc)
        ),
        SettingsPickerOption(
            DiscoverLocation.IN_SIDEBAR,
            stringResource(R.string.layout_discover_location_in_sidebar),
            stringResource(R.string.layout_discover_location_in_sidebar_desc)
        )
    )

    val effectiveSelected = if (selectedLocation == DiscoverLocation.OFF) {
        DiscoverLocation.IN_SEARCH
    } else {
        selectedLocation
    }

    SettingsSingleChoiceDialog(
        title = stringResource(R.string.layout_discover_location_dialog_title),
        options = options,
        selectedValue = effectiveSelected,
        onOptionSelected = onLocationSelected,
        onDismiss = onDismiss,
        width = 460.dp,
        maxHeight = 320.dp
    )
}
