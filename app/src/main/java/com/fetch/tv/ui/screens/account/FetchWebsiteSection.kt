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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.fetch.tv.data.website.DiscoveredFetchServer
import com.fetch.tv.data.website.WebsitePairingState
import com.fetch.tv.ui.components.FetchDialog
import com.fetch.tv.ui.components.LoadingIndicator
import com.fetch.tv.ui.screens.detail.requestFocusAfterFrames
import com.fetch.tv.ui.screens.settings.SettingsActionRow
import com.fetch.tv.ui.screens.settings.SettingsDialogActionButton
import com.fetch.tv.ui.screens.settings.SettingsDialogActionRow
import com.fetch.tv.ui.theme.FetchTheme
import kotlinx.coroutines.delay
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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
    FetchDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.website_sign_in_title),
        width = 640.dp,
        scrollable = true
    ) {
        FetchWebsitePairingContent(viewModel = viewModel, onCancel = onDismiss, onDone = onDismiss)
    }
}

/**
 * Pairing with a Fetch website. It opens on a code from the pairing hub, which any Fetch
 * website confirms under Settings → TVs & Devices, so nothing has to be typed on the TV.
 * Websites found on the LAN and a typed address are the fallbacks: for a website with the hub
 * turned off, or when the hub can't be reached. Used by the Settings dialog and the setup
 * wizard; [onCancel] adds a Cancel button where the surroundings don't offer a way out, and
 * [firstFocus] lands on the first control.
 */
@Composable
internal fun FetchWebsitePairingContent(
    viewModel: FetchWebsiteViewModel,
    onCancel: (() -> Unit)?,
    onDone: () -> Unit,
    firstFocus: FocusRequester? = null
) {
    val state by viewModel.pairing.collectAsStateWithLifecycle()
    var address by remember { mutableStateOf(viewModel.lastServerUrl) }
    // Typing the address was chosen over the hub code.
    var manual by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(Unit) { onDispose { if (viewModel.pairing.value !is WebsitePairingState.Paired) viewModel.resetPairing() } }
    LaunchedEffect(manual) {
        if (!manual && viewModel.pairing.value == WebsitePairingState.EnterAddress) viewModel.startHubPairing()
    }

    val useCode = {
        manual = false
        viewModel.startHubPairing()
    }
    val typeAddress = {
        manual = true
        viewModel.resetPairing()
    }

    Column(verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.lg)) {
        when (val current = state) {
            WebsitePairingState.EnterAddress, is WebsitePairingState.Failed -> {
                val failed = current as? WebsitePairingState.Failed
                if (!manual && failed == null) {
                    // About to ask the hub for a code (the LaunchedEffect above).
                    Progress(stringResource(R.string.website_hub_starting))
                } else {
                    AddressStep(
                        viewModel = viewModel,
                        address = address,
                        onAddressChange = { address = it },
                        failed = failed,
                        firstFocus = firstFocus,
                        onCancel = onCancel,
                        onUseCode = useCode
                    )
                }
            }
            WebsitePairingState.Starting -> Progress(
                stringResource(if (manual) R.string.website_pairing_starting else R.string.website_hub_starting)
            )
            is WebsitePairingState.ShowingCode -> {
                if (current.viaHub) {
                    // The code arrives a moment after the step opens, when its owner (the setup
                    // wizard) has already tried to focus it, so it takes focus once it shows.
                    if (firstFocus != null) {
                        LaunchedEffect(current.code) { firstFocus.requestFocusAfterFrames() }
                    }
                    HubCodeContent(current)
                    val servers by viewModel.discoveredServers.collectAsStateWithLifecycle()
                    if (servers.isNotEmpty()) {
                        DiscoveredServers(
                            servers = servers,
                            title = stringResource(R.string.website_discovered_or),
                            firstFocus = null,
                            onPick = { server ->
                                manual = true
                                address = server.apiUrl
                                viewModel.startPairing(server.apiUrl)
                            }
                        )
                    }
                    SettingsDialogActionRow {
                        if (onCancel != null) {
                            SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = onCancel)
                        }
                        SettingsDialogActionButton(
                            text = stringResource(R.string.website_type_address),
                            onClick = typeAddress,
                            modifier = if (firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier
                        )
                    }
                } else {
                    CodeContent(current)
                    SettingsDialogActionRow {
                        SettingsDialogActionButton(
                            text = stringResource(R.string.action_cancel),
                            onClick = onCancel ?: { viewModel.resetPairing() }
                        )
                    }
                }
            }
            is WebsitePairingState.Connecting -> Progress(
                stringResource(R.string.website_hub_connecting, hostOf(current.serverUrl))
            )
            WebsitePairingState.Expired -> {
                Message(stringResource(R.string.website_pairing_expired))
                SettingsDialogActionRow {
                    SettingsDialogActionButton(
                        text = stringResource(R.string.action_cancel),
                        onClick = onCancel ?: { viewModel.resetPairing() }
                    )
                    SettingsDialogActionButton(
                        text = stringResource(R.string.website_pairing_retry),
                        primary = true,
                        onClick = { viewModel.retryPairing() }
                    )
                }
            }
            is WebsitePairingState.Paired -> {
                Message(stringResource(R.string.website_pairing_done, current.deviceName))
                SettingsDialogActionRow {
                    SettingsDialogActionButton(text = stringResource(R.string.website_done), primary = true, onClick = onDone)
                }
            }
        }
    }
}

/**
 * Finding the website without the hub: those announced on the LAN, a phone page for typing
 * the address, and the address field. Also where the TV lands when the hub couldn't be reached
 * or a hub pairing failed.
 */
@Composable
private fun AddressStep(
    viewModel: FetchWebsiteViewModel,
    address: String,
    onAddressChange: (String) -> Unit,
    failed: WebsitePairingState.Failed?,
    firstFocus: FocusRequester?,
    onCancel: (() -> Unit)?,
    onUseCode: () -> Unit
) {
    val servers by viewModel.discoveredServers.collectAsStateWithLifecycle()
    if (failed?.hubUnavailable == true) Message(stringResource(R.string.website_hub_unavailable))
    DiscoveredServers(
        servers = servers,
        title = stringResource(R.string.website_discovered_title),
        firstFocus = firstFocus,
        onPick = { server ->
            onAddressChange(server.apiUrl)
            viewModel.startPairing(server.apiUrl)
        }
    )
    PhoneEntry(viewModel)
    Message(stringResource(if (servers.isEmpty()) R.string.website_enter_address else R.string.website_enter_address_other))
    InputField(
        value = address,
        onValueChange = onAddressChange,
        placeholder = stringResource(R.string.website_address_placeholder),
        keyboardType = KeyboardType.Uri,
        onImeAction = { viewModel.startPairing(address) },
        modifier = Modifier
            .fillMaxWidth()
            .then(if (servers.isEmpty() && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier)
    )
    val error = failed?.takeUnless { it.hubUnavailable }?.let { failure ->
        val handoffFrom = failure.handoffFrom
        val message = failure.message
        when {
            handoffFrom != null -> stringResource(R.string.website_hub_handoff_failed, hostOf(handoffFrom))
            failure.notFound -> stringResource(R.string.website_not_found)
            message != null -> stringResource(R.string.website_pairing_failed, message)
            else -> stringResource(R.string.website_address_invalid)
        }
    }
    if (error != null) {
        Text(text = error, style = MaterialTheme.typography.bodySmall, color = FetchTheme.colors.Error)
    }
    SettingsDialogActionRow {
        if (onCancel != null) {
            SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = onCancel)
        }
        SettingsDialogActionButton(text = stringResource(R.string.website_use_code), onClick = onUseCode)
        SettingsDialogActionButton(
            text = stringResource(R.string.website_continue),
            primary = true,
            enabled = address.isNotBlank(),
            onClick = { viewModel.startPairing(address) }
        )
    }
}

/** The QR code beside a pairing code, hub or website, so both read the same from the sofa. */
private val CODE_QR_SIZE = 152.dp

/** The host of [url] for a message, or [url] itself when it doesn't parse. */
private fun hostOf(url: String): String = url.toHttpUrlOrNull()?.let { parsed ->
    if (parsed.port == HttpUrl.defaultPort(parsed.scheme)) parsed.host else "${parsed.host}:${parsed.port}"
} ?: url

@Composable
private fun DiscoveredServers(
    servers: List<DiscoveredFetchServer>,
    title: String,
    firstFocus: FocusRequester?,
    onPick: (DiscoveredFetchServer) -> Unit
) {
    if (servers.isEmpty()) {
        Progress(stringResource(R.string.website_discovering))
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.sm)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = FetchTheme.colors.TextSecondary
        )
        servers.forEachIndexed { index, server ->
            SettingsActionRow(
                title = server.name,
                subtitle = server.apiUrl,
                onClick = { onPick(server) },
                modifier = if (index == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier
            )
        }
    }
}

/**
 * A QR code for typing the address on a phone instead of with the remote. While it shows,
 * the TV serves its phone page; the phone starts the same pairing this dialog then shows.
 */
@Composable
private fun PhoneEntry(viewModel: FetchWebsiteViewModel) {
    DisposableEffect(viewModel) {
        viewModel.onPhoneEntryShown()
        onDispose { viewModel.onPhoneEntryHidden() }
    }
    val url by viewModel.phoneEntryUrl.collectAsStateWithLifecycle()
    val qrBitmap = remember(url) {
        url?.let { runCatching { QrCodeGenerator.generate(it, 360, margin = 1) }.getOrNull() }
    } ?: return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.lg, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            bitmap = qrBitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.website_phone_qr),
            modifier = Modifier.size(120.dp),
            contentScale = ContentScale.Fit
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xs)
        ) {
            Text(
                text = stringResource(R.string.website_phone_title),
                style = MaterialTheme.typography.titleSmall,
                color = FetchTheme.colors.TextPrimary
            )
            Text(
                text = stringResource(R.string.website_phone_hint),
                style = MaterialTheme.typography.bodySmall,
                color = FetchTheme.colors.TextSecondary
            )
        }
    }
}

/**
 * A code from the pairing hub: the QR code opens watchfetch.tv on a phone, which goes on to
 * the person's own website with the code filled in; or they type the code on their website.
 */
@Composable
private fun HubCodeContent(state: WebsitePairingState.ShowingCode) {
    val qrBitmap = remember(state.confirmUrl) {
        state.confirmUrl?.let { url -> runCatching { QrCodeGenerator.generate(url, 420, margin = 1) }.getOrNull() }
    }
    val secondsLeft = secondsUntil(state)

    Message(stringResource(R.string.website_hub_code_instruction))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xl, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (qrBitmap != null) {
            Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.website_hub_qr),
                modifier = Modifier.size(CODE_QR_SIZE),
                contentScale = ContentScale.Fit
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(FetchTheme.spacing.xs)) {
            Text(
                text = state.code,
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                color = FetchTheme.colors.TextPrimary
            )
            Text(
                text = stringResource(R.string.website_code_where),
                style = MaterialTheme.typography.bodyMedium,
                color = FetchTheme.colors.TextSecondary
            )
            Text(
                text = stringResource(R.string.website_hub_scan),
                style = MaterialTheme.typography.bodySmall,
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

/** Seconds until [state]'s code expires, ticking once a second. */
@Composable
private fun secondsUntil(state: WebsitePairingState.ShowingCode): Long {
    val nowMillis by produceState(initialValue = System.currentTimeMillis(), key1 = state) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    return ((state.expiresAtEpochMs - nowMillis) / 1_000L).coerceAtLeast(0L)
}

@Composable
private fun CodeContent(state: WebsitePairingState.ShowingCode) {
    val qrBitmap = remember(state.confirmUrl) {
        state.confirmUrl?.let { url -> runCatching { QrCodeGenerator.generate(url, 420, margin = 1) }.getOrNull() }
    }
    val secondsLeft = secondsUntil(state)

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
                modifier = Modifier.size(CODE_QR_SIZE),
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
