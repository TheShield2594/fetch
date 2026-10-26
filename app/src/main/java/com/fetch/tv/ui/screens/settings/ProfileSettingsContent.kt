@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.fetch.tv.ui.screens.settings

import com.fetch.tv.ui.theme.FetchTheme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.ui.components.ProfileAvatarCircle

@Composable
internal fun ProfileSettingsContent(
    onManageProfiles: () -> Unit,
    initialFocusRequester: FocusRequester? = null,
    viewModel: ProfileSettingsViewModel = hiltViewModel()
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val avatarImageUrls by viewModel.avatarImageUrls.collectAsStateWithLifecycle()
    val rememberLastProfileEnabled by viewModel.rememberLastProfileEnabled.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.loadAvatars() }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.profile_title),
            subtitle = stringResource(R.string.profile_subtitle)
        )
        SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
            // Shown, not focused: every change to a profile happens in Manage Profiles.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.lg)
            ) {
                profiles.forEach { profile ->
                    Column(
                        modifier = Modifier.width(88.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xs)
                    ) {
                        ProfileAvatarCircle(
                            name = profile.name,
                            colorHex = profile.avatarColorHex,
                            size = 56.dp,
                            avatarImageUrl = avatarImageUrls[profile.id]
                        )
                        Text(
                            text = profile.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = FetchTheme.colors.TextSecondary,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            SettingsActionRow(
                title = stringResource(R.string.profile_manage_button),
                subtitle = stringResource(R.string.profile_manage_subtitle),
                onClick = onManageProfiles,
                modifier = if (initialFocusRequester != null) {
                    Modifier.focusRequester(initialFocusRequester)
                } else {
                    Modifier
                }
            )
        }
        SettingsGroupCard(
            modifier = Modifier.fillMaxWidth(),
            title = stringResource(R.string.profile_section_startup)
        ) {
            SettingsToggleRow(
                title = stringResource(R.string.profile_remember_last_profile),
                subtitle = stringResource(R.string.profile_remember_last_profile_subtitle),
                checked = rememberLastProfileEnabled,
                onToggle = { viewModel.setRememberLastProfileEnabled(!rememberLastProfileEnabled) }
            )
        }
    }
}
