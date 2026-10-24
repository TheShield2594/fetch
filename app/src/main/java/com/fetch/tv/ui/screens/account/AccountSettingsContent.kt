@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.fetch.tv.ui.screens.account

import com.fetch.tv.ui.theme.FetchRadii
import com.fetch.tv.ui.theme.FetchPrimitives
import com.fetch.tv.ui.theme.FetchTheme
import com.fetch.tv.ui.util.contentTextDirection

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.domain.model.AuthState
import androidx.compose.ui.res.stringResource
import com.fetch.tv.R

@Composable
fun AccountSettingsContent(
    uiState: AccountUiState,
    viewModel: AccountViewModel,
    onNavigateToAuthQrSignIn: () -> Unit = {},
    initialFocusRequester: FocusRequester? = null
) {
    if (uiState.authState is AuthState.FullAccount) {
        SignedInAccountSettingsContent(
            uiState = uiState,
            viewModel = viewModel,
            initialFocusRequester = initialFocusRequester
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = FetchTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        when (val authState = uiState.authState) {
            is AuthState.Loading -> {
                item(key = "account_loading") {
                    Text(
                        text = stringResource(R.string.account_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = FetchTheme.colors.TextSecondary
                    )
                }
            }

            is AuthState.SignedOut -> {
                item(key = "account_signed_out_info") {
                    Text(
                        text = stringResource(R.string.account_sync_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = FetchTheme.colors.TextSecondary
                    )
                }
                item(key = "account_sync_note_signed_out") {
                    AccountInlineNote(text = stringResource(R.string.account_sync_restart_note))
                }
                if (!viewModel.isSyncServerConfigured) {
                    item(key = "account_local_only") {
                        AccountInlineNote(text = stringResource(R.string.account_local_only_note))
                    }
                } else item(key = "account_sign_in_qr") {
                    SettingsActionButton(
                        icon = Icons.Default.VpnKey,
                        title = stringResource(
                            if (viewModel.usesEmailPasswordLogin) R.string.account_signin_email_title else R.string.account_signin_qr_title
                        ),
                        subtitle = stringResource(
                            if (viewModel.usesEmailPasswordLogin) R.string.account_signin_email_subtitle else R.string.account_signin_qr_subtitle
                        ),
                        onClick = onNavigateToAuthQrSignIn,
                        modifier = if (initialFocusRequester != null) {
                            Modifier.focusRequester(initialFocusRequester)
                        } else {
                            Modifier
                        }
                    )
                }
            }

            is AuthState.FullAccount -> Unit

        }
    }
}

@Composable
private fun SignedInAccountSettingsContent(
    uiState: AccountUiState,
    viewModel: AccountViewModel,
    initialFocusRequester: FocusRequester?
) {
    val listState = rememberLazyListState()
    var showSignOutConfirmation by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(bottom = FetchTheme.spacing.xs),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val authState = uiState.authState as AuthState.FullAccount
            item(key = "account_status") {
                StatusCard(label = stringResource(R.string.account_signed_in_label), value = authState.email)
            }
            item(key = "account_sync_note_signed_in") {
                AccountInlineNote(text = stringResource(R.string.account_sync_restart_note))
            }

            val overview = uiState.syncOverview
            if (overview != null) {
                item(key = "account_sync_overview") { SyncOverviewCard(overview) }
            } else if (uiState.isSyncOverviewLoading) {
                item(key = "account_sync_overview_loading") { SyncOverviewLoadingCard() }
            }
        }

        SignOutSettingsButton(
            onClick = { showSignOutConfirmation = true },
            modifier = if (initialFocusRequester != null) {
                Modifier.focusRequester(initialFocusRequester)
            } else {
                Modifier
            }
        )
    }

    if (showSignOutConfirmation) {
        AccountSignOutConfirmationDialog(
            onConfirm = {
                viewModel.signOut()
                showSignOutConfirmation = false
            },
            onDismiss = { showSignOutConfirmation = false }
        )
    }
}

@Composable
private fun AccountInlineNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = FetchTheme.colors.TextTertiary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FetchTheme.spacing.md)
    )
}

@Composable
private fun SyncOverviewCard(overview: SyncOverview) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = FetchTheme.colors.BackgroundCard,
                shape = RoundedCornerShape(FetchTheme.radii.sm)
            )
            .padding(10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Totals row — layout matches ProfileSyncRow columns
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = FetchTheme.colors.BackgroundElevated,
                        shape = RoundedCornerShape(FetchRadii.tokens.sm)
                    )
                    .padding(horizontal = FetchTheme.spacing.sm, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.account_total_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = FetchTheme.colors.Secondary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(100.dp)
                )
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    ProfileStatValue(overview.totalAddons, stringResource(R.string.account_stat_addons))
                    ProfileStatValue(overview.totalLibrary, stringResource(R.string.account_stat_library))
                    ProfileStatValue(overview.totalWatchProgress, stringResource(R.string.account_stat_progress))
                    ProfileStatValue(overview.totalWatchedItems, stringResource(R.string.account_stat_watched))
                }
            }

            // Per-profile breakdown
            if (overview.perProfile.isNotEmpty()) {
                overview.perProfile.forEach { profile ->
                    ProfileSyncRow(profile)
                }
            }
        }
    }
}

@Composable
private fun SyncStatChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = FetchTheme.colors.Secondary,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            fontSize = 12.sp,
            color = FetchTheme.colors.TextTertiary
        )
    }
}

@Composable
private fun ProfileSyncRow(profile: ProfileSyncStats) {
    val color = runCatching { Color(android.graphics.Color.parseColor(profile.avatarColorHex)) }
        .getOrDefault(FetchPrimitives.blue500)
    val rowShape = RoundedCornerShape(FetchRadii.tokens.sm)
    var isFocused by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .background(
                color = if (isFocused) FetchTheme.colors.FocusBackground else FetchTheme.colors.BackgroundElevated,
                shape = rowShape
            )
            .border(
                width = FetchTheme.spacing.xxs,
                color = if (isFocused) FetchTheme.colors.FocusRing else Color.Transparent,
                shape = rowShape
            )
            .padding(horizontal = FetchTheme.spacing.sm, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = profile.profileName.firstOrNull()?.uppercase() ?: "?",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.width(FetchTheme.spacing.sm))

        Text(
            text = profile.profileName,
            style = MaterialTheme.typography.bodySmall.copy(
                textDirection = profile.profileName.contentTextDirection()
            ),
            color = FetchTheme.colors.TextPrimary,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(70.dp)
        )

        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ProfileStatValue(profile.addons, stringResource(R.string.account_stat_addons))
            ProfileStatValue(profile.library, stringResource(R.string.account_stat_library))
            ProfileStatValue(profile.watchProgress, stringResource(R.string.account_stat_progress))
            ProfileStatValue(profile.watchedItems, stringResource(R.string.account_stat_watched))
        }
    }
}

@Composable
private fun ProfileStatValue(count: Int, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = count.toString(),
            fontSize = 12.sp,
            color = if (count > 0) FetchTheme.colors.TextPrimary else FetchTheme.colors.TextTertiary,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = label,
            fontSize = 12.sp,
            color = FetchTheme.colors.TextTertiary
        )
    }
}

@Composable
private fun SyncOverviewLoadingCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = FetchTheme.colors.BackgroundCard,
                shape = RoundedCornerShape(FetchTheme.radii.sm)
            )
            .padding(10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.account_loading_sync),
            style = MaterialTheme.typography.bodySmall,
            color = FetchTheme.colors.TextSecondary
        )
    }
}

@Composable
private fun SettingsActionButton(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
        colors = CardDefaults.colors(
            containerColor = FetchTheme.colors.BackgroundCard,
            focusedContainerColor = FetchTheme.colors.FocusBackground
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs),
                shape = RoundedCornerShape(FetchTheme.radii.sm)
            )
        ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(FetchTheme.radii.sm)),
        scale = CardDefaults.scale(focusedScale = 1.02f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = if (isFocused) FetchTheme.colors.Primary else FetchTheme.colors.TextSecondary
            )
            Spacer(modifier = Modifier.width(FetchTheme.spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = FetchTheme.colors.TextPrimary,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = FetchTheme.colors.TextSecondary
                )
            }
        }
    }
}

@Composable
private fun StatusCard(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = FetchTheme.colors.Secondary.copy(alpha = 0.1f),
                shape = RoundedCornerShape(FetchTheme.radii.sm)
            )
            .padding(horizontal = FetchTheme.spacing.md, vertical = FetchTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(FetchTheme.spacing.lg),
            tint = FetchTheme.colors.Secondary
        )
        Spacer(modifier = Modifier.width(FetchTheme.spacing.sm))
        Text(
            text = "$label  ",
            style = MaterialTheme.typography.labelSmall,
            color = FetchTheme.colors.TextTertiary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = FetchTheme.colors.TextPrimary,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun SignOutSettingsButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.colors(
            containerColor = FetchPrimitives.red600.copy(alpha = 0.12f),
            focusedContainerColor = FetchPrimitives.red600.copy(alpha = 0.25f)
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(FetchTheme.spacing.xxs, FetchTheme.colors.Error.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(FetchTheme.radii.sm)
            )
        ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(FetchTheme.radii.sm)),
        scale = CardDefaults.scale(focusedScale = 1.02f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = FetchTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Logout,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = FetchTheme.colors.Error
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.account_sign_out),
                style = MaterialTheme.typography.bodyMedium,
                color = FetchTheme.colors.Error,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
