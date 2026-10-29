@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.fetch.tv.ui.screens.account

import com.fetch.tv.ui.theme.FetchRadii
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.domain.model.ServerConfiguration
import com.fetch.tv.ui.components.FetchDialog
import com.fetch.tv.ui.screens.detail.requestFocusAfterFrames
import com.fetch.tv.ui.theme.FetchTheme
import kotlinx.coroutines.delay

@Composable
internal fun ServerOptionsMenuHost(
    viewModel: ServerConnectionViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    var showConnectionDialog by remember { mutableStateOf(false) }
    var showOfficialConfirmation by remember { mutableStateOf(false) }
    val firstMenuItemFocusRequester = remember { FocusRequester() }

    LaunchedEffect(expanded) {
        if (expanded) {
            var focused = firstMenuItemFocusRequester.requestFocusAfterFrames(frames = 3)
            var attempt = 0
            while (!focused && attempt < 3) {
                delay(32)
                focused = runCatching { firstMenuItemFocusRequester.requestFocus() }.getOrDefault(false)
                attempt++
            }
            if (focused) {
                delay(48)
                runCatching { firstMenuItemFocusRequester.requestFocus() }
            }
        }
    }

    Box(modifier = modifier) {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                contentColor = FetchTheme.colors.TextSecondary,
                focusedContentColor = FetchTheme.colors.FocusRing
            ),
            scale = IconButtonDefaults.scale(focusedScale = 1.12f),
            shape = IconButtonDefaults.shape(shape = CircleShape)
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.server_options_content_description),
                modifier = Modifier.size(24.dp)
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = DpOffset(x = 0.dp, y = 12.dp),
            modifier = Modifier.width(320.dp),
            shape = RoundedCornerShape(FetchRadii.tokens.md),
            containerColor = FetchTheme.colors.BackgroundCard,
            tonalElevation = 0.dp,
            shadowElevation = FetchTheme.spacing.sm,
            border = BorderStroke(FetchTheme.spacing.hairline, FetchTheme.colors.Border)
        ) {
            if (uiState.activeServer.isCustom) {
                ServerMenuItem(
                    text = stringResource(R.string.server_options_use_official),
                    modifier = Modifier.focusRequester(firstMenuItemFocusRequester),
                    onClick = {
                        expanded = false
                        showOfficialConfirmation = true
                    }
                )
            }
            ServerMenuItem(
                text = if (uiState.activeServer.isCustom) {
                    stringResource(R.string.server_options_change_custom)
                } else {
                    stringResource(R.string.server_options_connect_custom)
                },
                modifier = if (uiState.activeServer.isCustom) {
                    Modifier
                } else {
                    Modifier.focusRequester(firstMenuItemFocusRequester)
                },
                onClick = {
                    expanded = false
                    viewModel.resetDiscovery()
                    showConnectionDialog = true
                }
            )
        }
    }

    if (showConnectionDialog) {
        ServerConnectionDialog(
            uiState = uiState,
            onDiscover = viewModel::discover,
            onConnect = viewModel::connectDiscoveredServer,
            onDismiss = {
                viewModel.resetDiscovery()
                showConnectionDialog = false
            }
        )
    }

    if (showOfficialConfirmation) {
        OfficialServerConfirmationDialog(
            isSwitching = uiState.isSwitching,
            error = uiState.error,
            onConfirm = viewModel::useOfficialServer,
            onDismiss = {
                if (!uiState.isSwitching) {
                    viewModel.resetDiscovery()
                    showOfficialConfirmation = false
                }
            }
        )
    }
}

@Composable
private fun ServerMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val itemColor = if (isFocused) FetchTheme.colors.OnSecondary else FetchTheme.colors.TextPrimary

    DropdownMenuItem(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = FetchTheme.spacing.sm, vertical = FetchTheme.spacing.xxs)
            .background(
                color = if (isFocused) FetchTheme.colors.Secondary else Color.Transparent,
                shape = RoundedCornerShape(FetchRadii.tokens.sm)
            )
            .border(
                width = if (isFocused) FetchTheme.spacing.xxs else FetchTheme.spacing.hairline,
                color = if (isFocused) FetchTheme.colors.FocusRing else Color.Transparent,
                shape = RoundedCornerShape(FetchRadii.tokens.sm)
            )
            .onFocusChanged { state -> isFocused = state.isFocused || state.hasFocus },
        text = {
            Text(
                text = text,
                color = itemColor,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        onClick = onClick,
        colors = MenuDefaults.itemColors(textColor = itemColor)
    )
}

@Composable
private fun ServerConnectionDialog(
    uiState: ServerConnectionUiState,
    onDiscover: (String) -> Unit,
    onConnect: () -> Unit,
    onDismiss: () -> Unit
) {
    val discoveredServer = uiState.discoveredServer
    if (discoveredServer != null) {
        ServerTrustConfirmationDialog(
            server = discoveredServer,
            isSwitching = uiState.isSwitching,
            error = uiState.error,
            onConnect = onConnect,
            onDismiss = onDismiss
        )
        return
    }

    var serverUrl by rememberSaveable {
        mutableStateOf(uiState.activeServer.discoveryUrl?.substringBefore("/.well-known/fetch").orEmpty())
    }
    val urlFocusRequester = remember { FocusRequester() }
    val canDiscover = serverUrl.isNotBlank() && !uiState.isDiscovering

    LaunchedEffect(Unit) {
        urlFocusRequester.requestFocus()
    }

    FetchDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.custom_server_title),
        subtitle = stringResource(R.string.custom_server_description),
        suppressFirstKeyUp = false
    ) {
        InputField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            placeholder = stringResource(R.string.custom_server_url_placeholder),
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done,
            onImeAction = { if (canDiscover) onDiscover(serverUrl) },
            modifier = Modifier.focusRequester(urlFocusRequester)
        )
        uiState.error?.let {
            ServerMessage(
                text = it,
                containerColor = FetchTheme.colors.Error.copy(alpha = 0.2f),
                contentColor = FetchTheme.colors.Error
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)
        ) {
            Button(
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.colors(
                    containerColor = FetchTheme.colors.BackgroundCard,
                    contentColor = FetchTheme.colors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.action_cancel))
            }
            Button(
                onClick = { onDiscover(serverUrl) },
                enabled = canDiscover,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.colors(
                    containerColor = FetchTheme.colors.BackgroundCard,
                    contentColor = FetchTheme.colors.TextPrimary
                )
            ) {
                Text(
                    if (uiState.isDiscovering) {
                        stringResource(R.string.custom_server_checking)
                    } else {
                        stringResource(R.string.custom_server_check)
                    }
                )
            }
        }
    }
}

@Composable
private fun ServerTrustConfirmationDialog(
    server: ServerConfiguration,
    isSwitching: Boolean,
    error: String?,
    onConnect: () -> Unit,
    onDismiss: () -> Unit
) {
    val cancelFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        cancelFocusRequester.requestFocus()
    }

    FetchDialog(
        onDismiss = { if (!isSwitching) onDismiss() },
        title = stringResource(R.string.custom_server_review_title),
        subtitle = stringResource(R.string.custom_server_review_description),
        width = 620.dp,
        suppressFirstKeyUp = false,
        scrollable = true
    ) {
        ServerDetailRow(
            icon = Icons.Default.CheckCircle,
            label = stringResource(R.string.custom_server_verified_label),
            value = server.backendUrl,
            tint = FetchTheme.colors.Success
        )
        ServerDetailRow(
            icon = Icons.Default.Lock,
            label = stringResource(R.string.custom_server_key_label),
            value = stringResource(R.string.custom_server_key_discovered),
            tint = FetchTheme.colors.Secondary
        )
        ServerWarning(server)
        error?.let {
            ServerMessage(
                text = it,
                containerColor = FetchTheme.colors.Error.copy(alpha = 0.2f),
                contentColor = FetchTheme.colors.Error
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)
        ) {
            Button(
                onClick = onDismiss,
                enabled = !isSwitching,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(cancelFocusRequester),
                colors = ButtonDefaults.colors(
                    containerColor = FetchTheme.colors.BackgroundCard,
                    contentColor = FetchTheme.colors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.action_cancel))
            }
            Button(
                onClick = onConnect,
                enabled = !isSwitching,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.colors(
                    containerColor = FetchTheme.colors.BackgroundCard,
                    contentColor = FetchTheme.colors.TextPrimary
                )
            ) {
                Text(
                    if (isSwitching) {
                        stringResource(R.string.custom_server_switching)
                    } else {
                        stringResource(R.string.custom_server_trust_action)
                    }
                )
            }
        }
    }
}

@Composable
private fun ServerWarning(server: ServerConfiguration) {
    val warning = when {
        !server.isSecure && server.isPublicHost -> stringResource(R.string.custom_server_warning_public_http)
        !server.isSecure -> stringResource(R.string.custom_server_warning_http)
        server.isPublicHost -> stringResource(R.string.custom_server_warning_public)
        else -> stringResource(R.string.custom_server_warning_private)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, FetchTheme.colors.Warning.copy(alpha = 0.4f), RoundedCornerShape(FetchRadii.tokens.md))
            .background(FetchTheme.colors.Warning.copy(alpha = 0.12f), RoundedCornerShape(FetchRadii.tokens.md))
            .padding(FetchTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xs)
    ) {
        Text(
            text = stringResource(R.string.custom_server_warning_title),
            style = MaterialTheme.typography.titleSmall,
            color = FetchTheme.colors.Warning,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = warning,
            style = MaterialTheme.typography.bodySmall,
            color = FetchTheme.colors.TextSecondary
        )
        Text(
            text = stringResource(R.string.custom_server_warning_credentials),
            style = MaterialTheme.typography.bodySmall,
            color = FetchTheme.colors.TextSecondary
        )
    }
}

@Composable
private fun OfficialServerConfirmationDialog(
    isSwitching: Boolean,
    error: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val cancelFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        cancelFocusRequester.requestFocus()
    }

    FetchDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.official_server_title),
        subtitle = stringResource(R.string.official_server_description),
        suppressFirstKeyUp = false
    ) {
        error?.let {
            ServerMessage(
                text = it,
                containerColor = FetchTheme.colors.Error.copy(alpha = 0.2f),
                contentColor = FetchTheme.colors.Error
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md)
        ) {
            Button(
                onClick = onDismiss,
                enabled = !isSwitching,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(cancelFocusRequester),
                colors = ButtonDefaults.colors(
                    containerColor = FetchTheme.colors.BackgroundCard,
                    contentColor = FetchTheme.colors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.action_cancel))
            }
            Button(
                onClick = onConfirm,
                enabled = !isSwitching,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.colors(
                    containerColor = FetchTheme.colors.BackgroundCard,
                    contentColor = FetchTheme.colors.TextPrimary
                )
            ) {
                Text(
                    if (isSwitching) {
                        stringResource(R.string.custom_server_switching)
                    } else {
                        stringResource(R.string.official_server_action)
                    }
                )
            }
        }
    }
}

@Composable
private fun ServerDetailRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    tint: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = FetchTheme.colors.TextTertiary
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = FetchTheme.colors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ServerMessage(
    text: String,
    containerColor: Color,
    contentColor: Color
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(containerColor, RoundedCornerShape(FetchTheme.radii.md))
            .padding(horizontal = FetchTheme.spacing.md, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = contentColor
        )
    }
}
