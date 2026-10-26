@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.fetch.tv.ui.screens.account

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.core.qr.QrCodeGenerator
import com.fetch.tv.ui.components.FetchDialog
import com.fetch.tv.ui.components.LoadingIndicator
import com.fetch.tv.ui.screens.settings.SettingsActionRow
import com.fetch.tv.ui.screens.settings.SettingsDialogActionButton
import com.fetch.tv.ui.screens.settings.SettingsDialogActionRow
import com.fetch.tv.ui.theme.FetchTheme
import kotlinx.coroutines.delay

/**
 * The "Fetch website" rows of Settings → Account: sign this TV in to a Fetch website by
 * pairing, see which one it is signed in to, and sign it out. Also where the TV says so when
 * the website revoked it.
 */
@Composable
fun FetchWebsiteSection(viewModel: FetchWebsiteViewModel = hiltViewModel()) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val signedOutByWebsite by viewModel.signedOutByWebsite.collectAsStateWithLifecycle()
    var showPairing by rememberSaveable { mutableStateOf(false) }
    var showManage by rememberSaveable { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (signedOutByWebsite && session == null) {
            Text(
                text = stringResource(R.string.website_signed_out_notice),
                style = MaterialTheme.typography.bodySmall,
                color = FetchTheme.colors.Error,
                modifier = Modifier.fillMaxWidth().padding(horizontal = FetchTheme.spacing.md)
            )
        }
        val current = session
        if (current != null) {
            SettingsActionRow(
                title = stringResource(R.string.website_name),
                subtitle = stringResource(R.string.website_signed_in_subtitle, current.deviceName, current.serverUrl),
                onClick = { showManage = true }
            )
        } else {
            SettingsActionRow(
                title = stringResource(R.string.website_sign_in_title),
                subtitle = stringResource(R.string.website_sign_in_subtitle),
                onClick = {
                    viewModel.dismissSignedOutNotice()
                    viewModel.resetPairing()
                    showPairing = true
                }
            )
        }
    }

    if (showPairing) {
        FetchWebsitePairingDialog(viewModel = viewModel, onDismiss = { showPairing = false })
    }
    val current = session
    if (showManage && current != null) {
        FetchDialog(
            onDismiss = { showManage = false },
            title = stringResource(R.string.website_name),
            subtitle = current.serverUrl
        ) {
            Text(
                text = stringResource(R.string.website_manage_body),
                style = MaterialTheme.typography.bodyMedium,
                color = FetchTheme.colors.TextSecondary
            )
            SettingsDialogActionRow {
                SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = { showManage = false })
                SettingsDialogActionButton(
                    text = stringResource(R.string.website_sync_now),
                    onClick = {
                        viewModel.syncNow()
                        showManage = false
                    }
                )
                SettingsDialogActionButton(
                    text = stringResource(R.string.website_sign_out),
                    primary = true,
                    onClick = {
                        viewModel.signOut()
                        showManage = false
                    }
                )
            }
        }
    }
}

@Composable
private fun FetchWebsitePairingDialog(viewModel: FetchWebsiteViewModel, onDismiss: () -> Unit) {
    val state by viewModel.pairing.collectAsStateWithLifecycle()
    var address by remember { mutableStateOf(viewModel.lastServerUrl) }

    DisposableEffect(Unit) { onDispose { if (viewModel.pairing.value !is WebsitePairingState.Paired) viewModel.resetPairing() } }

    FetchDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.website_sign_in_title),
        width = 640.dp
    ) {
        when (val current = state) {
            WebsitePairingState.EnterAddress, is WebsitePairingState.Failed -> {
                Message(stringResource(R.string.website_enter_address))
                InputField(
                    value = address,
                    onValueChange = { address = it },
                    placeholder = stringResource(R.string.website_address_placeholder),
                    keyboardType = KeyboardType.Uri,
                    onImeAction = { viewModel.startPairing(address) },
                    modifier = Modifier.fillMaxWidth()
                )
                if (current is WebsitePairingState.Failed) {
                    Text(
                        text = current.message?.let { stringResource(R.string.website_pairing_failed, it) }
                            ?: stringResource(R.string.website_address_invalid),
                        style = MaterialTheme.typography.bodySmall,
                        color = FetchTheme.colors.Error
                    )
                }
                SettingsDialogActionRow {
                    SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = onDismiss)
                    SettingsDialogActionButton(
                        text = stringResource(R.string.website_continue),
                        primary = true,
                        enabled = address.isNotBlank(),
                        onClick = { viewModel.startPairing(address) }
                    )
                }
            }
            WebsitePairingState.Starting -> Progress(stringResource(R.string.website_pairing_starting))
            is WebsitePairingState.ShowingCode -> {
                CodeContent(current)
                SettingsDialogActionRow {
                    SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = onDismiss)
                }
            }
            WebsitePairingState.Expired -> {
                Message(stringResource(R.string.website_pairing_expired))
                SettingsDialogActionRow {
                    SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = onDismiss)
                    SettingsDialogActionButton(
                        text = stringResource(R.string.website_pairing_retry),
                        primary = true,
                        onClick = { viewModel.startPairing(address) }
                    )
                }
            }
            is WebsitePairingState.Paired -> {
                Message(stringResource(R.string.website_pairing_done, current.deviceName))
                SettingsDialogActionRow {
                    SettingsDialogActionButton(text = stringResource(R.string.website_done), primary = true, onClick = onDismiss)
                }
            }
        }
    }
}

@Composable
private fun CodeContent(state: WebsitePairingState.ShowingCode) {
    val qrBitmap = remember(state.confirmUrl) {
        state.confirmUrl?.let { url -> runCatching { QrCodeGenerator.generate(url, 420, margin = 1) }.getOrNull() }
    }
    val nowMillis by produceState(initialValue = System.currentTimeMillis(), key1 = state) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val secondsLeft = ((state.expiresAtEpochMs - nowMillis) / 1_000L).coerceAtLeast(0L)

    Message(
        if (qrBitmap != null) stringResource(R.string.website_code_instruction_qr)
        else stringResource(R.string.website_code_instruction)
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xl, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (qrBitmap != null) {
            Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.website_code_qr),
                modifier = Modifier.size(144.dp),
                contentScale = ContentScale.Fit
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xs)) {
            Text(
                text = state.code,
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = FetchTheme.colors.TextPrimary
            )
            Text(
                text = stringResource(R.string.website_code_where),
                style = MaterialTheme.typography.bodyMedium,
                color = FetchTheme.colors.TextSecondary
            )
            Text(
                text = stringResource(R.string.website_code_expires, secondsLeft / 60, secondsLeft % 60),
                style = MaterialTheme.typography.bodySmall,
                color = FetchTheme.colors.TextTertiary
            )
        }
    }
    Progress(stringResource(R.string.website_code_waiting))
}

@Composable
private fun Progress(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LoadingIndicator(modifier = Modifier.size(18.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = FetchTheme.colors.TextSecondary)
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = FetchTheme.colors.TextSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}
