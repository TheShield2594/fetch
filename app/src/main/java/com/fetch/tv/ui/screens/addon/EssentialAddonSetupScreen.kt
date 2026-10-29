package com.fetch.tv.ui.screens.addon

import com.fetch.tv.ui.theme.FetchMotion
import com.fetch.tv.ui.theme.FetchTheme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.core.server.AddonWebConfigMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.TextStyle

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun EssentialAddonSetupScreen(
    onSkip: () -> Unit,
    viewModel: AddonManagerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val keyboardController = LocalSoftwareKeyboardController.current

    DisposableEffect(Unit) {
        onDispose { viewModel.stopQrMode() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = FetchTheme.spacing.huge, vertical = FetchTheme.spacing.xxxl),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.essential_addon_setup_title),
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold, textDirection = TextDirection.Content),
                color = FetchTheme.colors.TextPrimary,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.essential_addon_setup_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = FetchTheme.colors.TextSecondary
            )
            Spacer(modifier = Modifier.height(28.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .height(220.dp),
                    colors = CardDefaults.cardColors(containerColor = FetchTheme.colors.BackgroundCard),
                    shape = RoundedCornerShape(FetchTheme.radii.md)
                ) {
                    Column(
                        modifier = Modifier.padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.addon_install_title),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, textDirection = TextDirection.Content),
                            color = FetchTheme.colors.TextPrimary,
                        )
                        BasicTextField(
                            value = uiState.installUrl,
                            onValueChange = viewModel::onInstallUrlChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(FetchTheme.spacing.xxxl),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    viewModel.installAddon()
                                    keyboardController?.hide()
                                }
                            ),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = FetchTheme.colors.TextPrimary,
                                            textDirection = TextDirection.Content),
                            cursorBrush = SolidColor(FetchTheme.colors.Primary),
                            decorationBox = { innerTextField ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = FetchTheme.spacing.md, vertical = FetchTheme.spacing.md)
                                ) {
                                    if (uiState.installUrl.isEmpty()) {
                                        Text(
                                            text = stringResource(R.string.addon_install_placeholder),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = FetchTheme.colors.TextTertiary
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                        Button(
                            onClick = {
                                viewModel.installAddon()
                                keyboardController?.hide()
                            },
                            enabled = !uiState.isInstalling,
                            colors = ButtonDefaults.colors(
                                containerColor = FetchTheme.colors.Secondary,
                                contentColor = FetchTheme.colors.OnSecondary,
                                focusedContainerColor = FetchTheme.colors.SecondaryVariant,
                                focusedContentColor = FetchTheme.colors.OnSecondary
                            ),
                            border = ButtonDefaults.border(
                                focusedBorder = Border(
                                    border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs),
                                    shape = RoundedCornerShape(50)
                                )
                            ),
                            shape = ButtonDefaults.shape(RoundedCornerShape(50))
                        ) {
                            Text(
                                text = if (uiState.isInstalling) {
                                    stringResource(R.string.addon_installing)
                                } else {
                                    stringResource(R.string.addon_install_btn)
                                }
                            )
                        }
                        // Keep the last message during the exit, so the text doesn't blank before it collapses.
                        var lastError by remember { mutableStateOf(uiState.error) }
                        if (uiState.error != null && uiState.error != lastError) lastError = uiState.error
                        AnimatedVisibility(
                            visible = uiState.error != null,
                            enter = fadeIn(FetchMotion.enterTween()) + expandVertically(FetchMotion.moveTween()),
                            exit = fadeOut(FetchMotion.exitTween()) + shrinkVertically(FetchMotion.moveTween())
                        ) {
                            Text(
                                text = lastError.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = FetchTheme.colors.Error
                            )
                        }
                    }
                }
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .height(220.dp),
                    colors = CardDefaults.cardColors(containerColor = FetchTheme.colors.BackgroundCard),
                    shape = RoundedCornerShape(FetchTheme.radii.md)
                ) {
                    Column(
                        modifier = Modifier.padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhoneAndroid,
                            contentDescription = null,
                            tint = FetchTheme.colors.Primary
                        )
                        Text(
                            text = stringResource(R.string.addon_manage_from_phone_title),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, textDirection = TextDirection.Content),
                            color = FetchTheme.colors.TextPrimary,
                        )
                        Text(
                            text = stringResource(R.string.addon_manage_addons_only_from_phone_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = FetchTheme.colors.TextSecondary
                        )
                        Button(
                            onClick = { viewModel.startQrMode(AddonWebConfigMode.ADDONS_ONLY) },
                            colors = ButtonDefaults.colors(
                                containerColor = FetchTheme.colors.BackgroundElevated,
                                contentColor = FetchTheme.colors.TextPrimary,
                                focusedContainerColor = FetchTheme.colors.FocusBackground,
                                focusedContentColor = FetchTheme.colors.TextPrimary
                            ),
                            border = ButtonDefaults.border(
                                focusedBorder = Border(
                                    border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs),
                                    shape = RoundedCornerShape(50)
                                )
                            ),
                            shape = ButtonDefaults.shape(RoundedCornerShape(50))
                        ) {
                            Icon(imageVector = Icons.Default.QrCode2, contentDescription = null)
                            Text(text = stringResource(R.string.essential_addon_show_qr))
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(FetchTheme.spacing.xl))
            Button(
                onClick = onSkip,
                colors = ButtonDefaults.colors(
                    containerColor = FetchTheme.colors.BackgroundCard,
                    contentColor = FetchTheme.colors.TextPrimary,
                    focusedContainerColor = FetchTheme.colors.FocusBackground,
                    focusedContentColor = FetchTheme.colors.TextPrimary
                ),
                border = ButtonDefaults.border(
                    focusedBorder = Border(
                        border = FetchTheme.focusRing.border(FetchTheme.spacing.xxs),
                        shape = RoundedCornerShape(50)
                    )
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(50))
            ) {
                Text(
                    text = stringResource(R.string.essential_addon_continue_for_now),
                    modifier = Modifier.padding(horizontal = FetchTheme.spacing.lg, vertical = FetchTheme.spacing.xs)
                )
            }
        }

        if (uiState.isQrModeActive) {
            Popup(properties = PopupProperties(focusable = true)) {
                QrCodeOverlay(
                    qrBitmap = uiState.qrCodeBitmap,
                    serverUrl = uiState.serverUrl,
                    instruction = stringResource(R.string.addon_qr_addons_only_scan_instruction),
                    onClose = viewModel::stopQrMode,
                    hasPendingChange = uiState.pendingChange != null
                )
            }
        }

        uiState.configureTarget?.let { target ->
            Popup(properties = PopupProperties(focusable = true)) {
                AddonConfigureOverlay(
                    target = target,
                    statusMessage = uiState.transientMessage,
                    statusIsError = uiState.transientMessageIsError,
                    onConfirm = viewModel::confirmConfiguredUrl,
                    onReject = viewModel::rejectConfiguredUrl,
                    onClose = viewModel::stopConfigure
                )
            }
        }

        if (uiState.pendingChange != null) {
            Popup(properties = PopupProperties(focusable = true)) {
                uiState.pendingChange?.let { pending ->
                    ConfirmAddonChangesDialog(
                        pendingChange = pending,
                        onConfirm = viewModel::confirmPendingChange,
                        onReject = viewModel::rejectPendingChange
                    )
                }
            }
        }

        AddonMessageOverlay(
            message = uiState.transientMessage,
            isError = uiState.transientMessageIsError
        )
    }
}
