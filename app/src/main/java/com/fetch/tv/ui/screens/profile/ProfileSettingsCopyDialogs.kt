@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.fetch.tv.ui.screens.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.domain.model.UserProfile
import com.fetch.tv.ui.components.FetchDialog
import com.fetch.tv.ui.theme.FetchTheme

@Composable
internal fun ProfileSettingsSourceDialog(
    profiles: List<UserProfile>,
    selectedSourceProfileId: Int?,
    copyProviderCredentials: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (sourceProfileId: Int?, copyProviderCredentials: Boolean) -> Unit
) {
    var pendingSourceProfileId by remember(selectedSourceProfileId) {
        mutableStateOf(selectedSourceProfileId)
    }
    var pendingCopyProviderCredentials by remember(copyProviderCredentials) {
        mutableStateOf(copyProviderCredentials)
    }
    val focusRequester = remember { FocusRequester() }
    val selectedIndex = selectedSourceProfileId
        ?.let { selectedId -> profiles.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 } }
        ?.plus(1)
        ?: 0
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)

    LaunchedEffect(Unit) {
        repeat(2) { withFrameNanos { } }
        runCatching { focusRequester.requestFocus() }
    }

    FetchDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.profile_copy_settings_setup_title),
        subtitle = stringResource(R.string.profile_copy_settings_setup_subtitle),
        width = 520.dp,
        scrollable = true
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(
                    max = FetchTheme.sizes.buttons.defaultHeight * 2 +
                        FetchTheme.spacing.sm
                ),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)
        ) {
            item(key = "start_fresh") {
                SourceProfileButton(
                    text = stringResource(R.string.profile_copy_settings_start_fresh),
                    selected = pendingSourceProfileId == null,
                    modifier = if (selectedSourceProfileId == null) {
                        Modifier.focusRequester(focusRequester)
                    } else {
                        Modifier
                    },
                    onClick = {
                        pendingSourceProfileId = null
                        pendingCopyProviderCredentials = false
                    }
                )
            }
            items(profiles, key = UserProfile::id) { profile ->
                SourceProfileButton(
                    text = profile.copySourceLabel(),
                    selected = profile.id == pendingSourceProfileId,
                    modifier = if (profile.id == selectedSourceProfileId) {
                        Modifier.focusRequester(focusRequester)
                    } else {
                        Modifier
                    },
                    onClick = { pendingSourceProfileId = profile.id }
                )
            }
        }
        if (pendingSourceProfileId != null) {
            SourceProfileButton(
                text = if (pendingCopyProviderCredentials) {
                    stringResource(R.string.profile_copy_settings_credentials_on)
                } else {
                    stringResource(R.string.profile_copy_settings_credentials_off)
                },
                selected = pendingCopyProviderCredentials,
                onClick = {
                    pendingCopyProviderCredentials = !pendingCopyProviderCredentials
                }
            )
        }
        Text(
            text = stringResource(R.string.profile_copy_settings_exclusions),
            color = FetchTheme.colors.TextTertiary,
            style = MaterialTheme.typography.bodySmall
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(
                FetchTheme.components.dialog.actionSpacing
            )
        ) {
            SourceProfileButton(
                text = stringResource(R.string.profile_cancel),
                selected = false,
                modifier = Modifier.weight(1f),
                onClick = onDismiss
            )
            SourceProfileButton(
                text = stringResource(R.string.profile_copy_settings_apply),
                selected = true,
                modifier = Modifier.weight(1f),
                onClick = {
                    onConfirm(pendingSourceProfileId, pendingCopyProviderCredentials)
                }
            )
        }
    }
}

@Composable
internal fun CopyProfileSettingsDialog(
    profiles: List<UserProfile>,
    targetProfile: UserProfile,
    isCopying: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onCopy: (sourceProfileId: Int, copyProviderCredentials: Boolean) -> Unit
) {
    val sourceProfiles = remember(profiles, targetProfile.id) {
        profiles.filterNot { it.id == targetProfile.id }
    }
    val defaultSourceId = remember(sourceProfiles, targetProfile.id) {
        sourceProfiles.firstOrNull { it.isPrimary }?.id ?: sourceProfiles.firstOrNull()?.id
    }
    var selectedSourceId by remember(sourceProfiles, targetProfile.id) {
        mutableStateOf(defaultSourceId)
    }
    var copyProviderCredentials by remember(targetProfile.id) { mutableStateOf(false) }
    val focusRequester = remember(targetProfile.id) { FocusRequester() }

    LaunchedEffect(targetProfile.id) {
        repeat(2) { withFrameNanos { } }
        runCatching { focusRequester.requestFocus() }
    }

    FetchDialog(
        onDismiss = { if (!isCopying) onDismiss() },
        title = stringResource(R.string.profile_copy_settings_title, targetProfile.name),
        subtitle = stringResource(R.string.profile_copy_settings_subtitle),
        width = 560.dp,
        scrollable = true
    ) {
        Text(
            text = stringResource(R.string.profile_copy_settings_from),
            color = FetchTheme.colors.TextSecondary,
            fontWeight = FontWeight.SemiBold
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(
                    max = FetchTheme.sizes.buttons.defaultHeight * 2 +
                        FetchTheme.spacing.sm
                ),
            verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)
        ) {
            items(sourceProfiles, key = UserProfile::id) { profile ->
                SourceProfileButton(
                    text = profile.copySourceLabel(),
                    selected = profile.id == selectedSourceId,
                    enabled = !isCopying,
                    modifier = if (profile.id == defaultSourceId) {
                        Modifier.focusRequester(focusRequester)
                    } else {
                        Modifier
                    },
                    onClick = { selectedSourceId = profile.id }
                )
            }
        }
        SourceProfileButton(
            text = if (copyProviderCredentials) {
                stringResource(R.string.profile_copy_settings_credentials_on)
            } else {
                stringResource(R.string.profile_copy_settings_credentials_off)
            },
            selected = copyProviderCredentials,
            enabled = !isCopying,
            onClick = { copyProviderCredentials = !copyProviderCredentials }
        )
        Text(
            text = stringResource(R.string.profile_copy_settings_details),
            color = FetchTheme.colors.TextTertiary,
            style = MaterialTheme.typography.bodySmall
        )
        if (errorMessage != null) {
            Text(
                text = errorMessage,
                color = FetchTheme.colors.Error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(
                FetchTheme.components.dialog.actionSpacing
            )
        ) {
            SourceProfileButton(
                text = stringResource(R.string.profile_cancel),
                onClick = onDismiss,
                enabled = !isCopying,
                modifier = Modifier.weight(1f),
                selected = false
            )
            SourceProfileButton(
                text = if (isCopying) {
                    stringResource(R.string.profile_copy_settings_copying)
                } else {
                    stringResource(R.string.profile_copy_settings_action)
                },
                onClick = {
                    selectedSourceId?.let { sourceProfileId ->
                        onCopy(sourceProfileId, copyProviderCredentials)
                    }
                },
                enabled = selectedSourceId != null && !isCopying,
                modifier = Modifier.weight(1f),
                selected = true
            )
        }
    }
}

@Composable
private fun SourceProfileButton(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        colors = ButtonDefaults.colors(
            containerColor = if (selected) {
                FetchTheme.colors.Secondary
            } else {
                FetchTheme.colors.BackgroundCard
            },
            contentColor = if (selected) {
                FetchTheme.colors.OnSecondary
            } else {
                FetchTheme.colors.TextPrimary
            },
            focusedContainerColor = if (selected) {
                FetchTheme.colors.SecondaryVariant
            } else {
                FetchTheme.colors.FocusBackground
            },
            focusedContentColor = if (selected) {
                FetchTheme.colors.OnSecondaryVariant
            } else {
                FetchTheme.colors.FocusContent
            }
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
private fun UserProfile.copySourceLabel(): String {
    return if (isPrimary) {
        stringResource(R.string.profile_copy_settings_primary_source, name)
    } else {
        name
    }
}
