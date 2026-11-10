package com.fetch.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Reorder
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import com.fetch.tv.R
import com.fetch.tv.ui.theme.FetchTheme

/** Settings › Addons: the addon manager, one press from the rail rather than inside another page. */
@Composable
internal fun AddonsSettingsContent(
    onNavigateToAddons: () -> Unit,
    initialFocusRequester: FocusRequester?
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.addon_title),
            subtitle = stringResource(R.string.settings_addons_page_subtitle)
        )
        SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
            SettingsActionRow(
                title = stringResource(R.string.settings_addons_manage),
                subtitle = stringResource(R.string.settings_addons_manage_sub),
                onClick = onNavigateToAddons,
                leadingIcon = Icons.Default.Extension,
                modifier = if (initialFocusRequester != null) {
                    Modifier.focusRequester(initialFocusRequester)
                } else {
                    Modifier
                }
            )
        }
    }
}

/**
 * Settings › Home › Customise Home: the one place for what Home shows. The rows themselves are
 * reordered, hidden and pinned on the Customise Home screen; new rows come from a catalog (an
 * addon), a filter, or a folder.
 */
@Composable
internal fun CustomiseHomeSettingsContent(
    onOpenCustomiseHome: () -> Unit,
    onAddCatalogRow: () -> Unit,
    onAddFilterRow: () -> Unit,
    onAddFolderRow: () -> Unit,
    onEditFilterRows: () -> Unit,
    onEditFolders: () -> Unit,
    initialFocusRequester: FocusRequester?
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.customise_home_title),
            subtitle = stringResource(R.string.customise_home_settings_subtitle)
        )
        SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
            SettingsActionRow(
                title = stringResource(R.string.customise_home_rows),
                subtitle = stringResource(R.string.customise_home_rows_sub),
                onClick = onOpenCustomiseHome,
                leadingIcon = Icons.Default.Reorder,
                modifier = if (initialFocusRequester != null) {
                    Modifier.focusRequester(initialFocusRequester)
                } else {
                    Modifier
                }
            )
        }
        SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
            SettingsActionRow(
                title = stringResource(R.string.customise_home_add_catalog),
                subtitle = stringResource(R.string.customise_home_add_catalog_sub),
                onClick = onAddCatalogRow,
                leadingIcon = Icons.Default.Add
            )
            SettingsActionRow(
                title = stringResource(R.string.customise_home_add_filter),
                subtitle = stringResource(R.string.customise_home_add_filter_sub),
                onClick = onAddFilterRow,
                leadingIcon = Icons.Default.FilterList
            )
            SettingsActionRow(
                title = stringResource(R.string.customise_home_add_folder),
                subtitle = stringResource(R.string.customise_home_add_folder_sub),
                onClick = onAddFolderRow,
                leadingIcon = Icons.Default.FolderOpen
            )
        }
        SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
            SettingsActionRow(
                title = stringResource(R.string.customise_home_edit_filter_rows),
                subtitle = stringResource(R.string.filter_rows_card_subtitle),
                onClick = onEditFilterRows,
                leadingIcon = Icons.Default.FilterList
            )
            SettingsActionRow(
                title = stringResource(R.string.customise_home_edit_folders),
                subtitle = stringResource(R.string.collections_card_subtitle),
                onClick = onEditFolders,
                leadingIcon = Icons.Default.FolderOpen
            )
        }
    }
}
