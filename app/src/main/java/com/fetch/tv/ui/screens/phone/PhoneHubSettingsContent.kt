package com.fetch.tv.ui.screens.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import com.fetch.tv.R
import com.fetch.tv.ui.screens.settings.SettingsActionRow
import com.fetch.tv.ui.screens.settings.SettingsDetailHeader
import com.fetch.tv.ui.screens.settings.SettingsGroupCard
import com.fetch.tv.ui.theme.FetchTheme

@Composable
fun PhoneHubSettingsContent(
    onOpen: () -> Unit,
    initialFocusRequester: FocusRequester? = null
) {
    val rowModifier = if (initialFocusRequester != null) Modifier.focusRequester(initialFocusRequester) else Modifier
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md),
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.phone_hub_title),
            subtitle = stringResource(R.string.phone_hub_description),
        )
        SettingsGroupCard {
            SettingsActionRow(
                title = stringResource(R.string.phone_hub_open),
                subtitle = stringResource(R.string.phone_hub_open_description),
                onClick = onOpen,
                leadingIcon = Icons.Rounded.PhoneAndroid,
                modifier = rowModifier,
            )
        }
    }
}
