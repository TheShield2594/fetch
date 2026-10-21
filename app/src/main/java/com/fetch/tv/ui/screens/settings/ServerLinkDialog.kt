@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.fetch.tv.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.fetch.tv.R
import com.fetch.tv.core.qr.QrCodeGenerator
import com.fetch.tv.data.mediaserver.ServerConnection
import com.fetch.tv.data.mediaserver.ServerLinkCode
import com.fetch.tv.data.mediaserver.ServerProvider
import com.fetch.tv.ui.components.FetchDialog
import com.fetch.tv.ui.components.LoadingIndicator
import com.fetch.tv.ui.theme.FetchTheme
import kotlinx.coroutines.delay

/**
 * Sign-in for providers that use a link code (Plex): the TV shows a code and a QR code, the
 * user approves it on their phone, and Fetch adds the server, asking which one when the
 * account can reach several.
 */
@Composable
internal fun ServerLinkDialog(
    provider: ServerProvider,
    viewModel: MediaServersViewModel,
    onConnected: (ServerConnection) -> Unit,
    onDismiss: () -> Unit
) {
    val state by viewModel.link.collectAsStateWithLifecycle()
    val start = { viewModel.startLink(provider, onConnected) }

    LaunchedEffect(provider.id) { start() }
    DisposableEffect(Unit) { onDispose { viewModel.cancelLink() } }

    FetchDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.servers_sign_in_title, provider.displayName),
        width = 640.dp
    ) {
        val code = state.code
        val connectingTo = state.connectingTo
        when {
            connectingTo != null -> LinkProgress(stringResource(R.string.servers_link_connecting_to, connectingTo))
            state.loading -> LinkProgress(stringResource(R.string.servers_link_loading))
            state.servers.isNotEmpty() -> {
                LinkMessage(stringResource(R.string.servers_link_choose_server))
                val firstServer = remember { FocusRequester() }
                state.servers.forEachIndexed { index, signIn ->
                    SettingsActionRow(
                        title = signIn.serverName,
                        subtitle = signIn.address,
                        onClick = { viewModel.chooseLinkedServer(provider, signIn, onConnected) },
                        modifier = if (index == 0) Modifier.focusRequester(firstServer) else Modifier
                    )
                }
                LaunchedEffect(state.servers) { runCatching { firstServer.requestFocus() } }
            }
            code != null -> LinkCodeContent(provider, code)
            state.noServers -> LinkMessage(stringResource(R.string.servers_link_no_servers))
            state.expired -> LinkMessage(stringResource(R.string.servers_link_expired))
            else -> Unit
        }
        state.error?.let { failure ->
            Text(
                text = failure.signInMessage(provider),
                style = MaterialTheme.typography.bodySmall,
                color = FetchTheme.colors.Error
            )
        }
        SettingsDialogActionRow {
            SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = onDismiss)
            if (state.expired || state.error != null) {
                SettingsDialogActionButton(text = stringResource(R.string.servers_link_retry), onClick = start, primary = true)
            }
        }
    }
}

@Composable
private fun LinkCodeContent(provider: ServerProvider, code: ServerLinkCode) {
    val qrBitmap = remember(code.qrUrl) {
        runCatching { QrCodeGenerator.generate(code.qrUrl, 420, margin = 1) }.getOrNull()
    }
    val nowMillis by produceState(initialValue = System.currentTimeMillis(), key1 = code) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    LinkMessage(stringResource(R.string.servers_link_instruction, code.displayUrl))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xl, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (qrBitmap != null) {
            Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.servers_link_qr, provider.displayName),
                modifier = Modifier.size(144.dp),
                contentScale = ContentScale.Fit
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xs)) {
            Text(
                text = code.code,
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = FetchTheme.colors.TextPrimary
            )
            Text(
                text = code.displayUrl,
                style = MaterialTheme.typography.bodyMedium,
                color = FetchTheme.colors.TextSecondary
            )
            Text(
                text = stringResource(
                    R.string.servers_link_expires,
                    formatTrackingDuration((code.expiresAtEpochMs - nowMillis).coerceAtLeast(0L))
                ),
                style = MaterialTheme.typography.bodySmall,
                color = FetchTheme.colors.TextTertiary
            )
        }
    }
    LinkProgress(stringResource(R.string.servers_link_waiting))
}

@Composable
private fun LinkProgress(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LoadingIndicator(modifier = Modifier.size(18.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = FetchTheme.colors.TextSecondary
        )
    }
}

@Composable
private fun LinkMessage(text: String) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = FetchTheme.colors.TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
